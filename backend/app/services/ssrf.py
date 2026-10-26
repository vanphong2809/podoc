import ipaddress
import socket
from urllib.parse import urlparse
import httpx

CHROME_MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)

# Các dải IP bị cấm (Private, Loopback, Link-local, Reserved IPv4 & IPv6)
BLOCKED_NETWORKS = [
    # IPv4
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("169.254.0.0/16"),  # Link-local / Cloud metadata (169.254.169.254)
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("224.0.0.0/4"),    # Multicast
    ipaddress.ip_network("240.0.0.0/4"),    # Reserved
    # IPv6
    ipaddress.ip_network("::/128"),
    ipaddress.ip_network("::1/128"),        # Loopback
    ipaddress.ip_network("fc00::/7"),       # Unique local
    ipaddress.ip_network("fe80::/10"),      # Link-local
    ipaddress.ip_network("ff00::/8"),       # Multicast
]


class SSRFValidationError(ValueError):
    """Lỗi phát hiện hành vi tấn công SSRF."""
    pass


def validate_url_safe(url: str) -> None:
    """
    Kiểm tra bảo mật URL:
    - Bắt buộc protocol là http hoặc https.
    - Phân giải DNS và kiểm tra toàn bộ IP (IPv4 lẫn IPv6).
    - Chặn IP nội bộ, loopback, metadata cloud (169.254.169.254).
    """
    if not url or not isinstance(url, str):
        raise SSRFValidationError("URL không hợp lệ")

    parsed = urlparse(url)
    if parsed.scheme.lower() not in ("http", "https"):
        raise SSRFValidationError(f"Giao thức '{parsed.scheme}' không được hỗ trợ (chỉ cho phép http/https)")

    hostname = parsed.hostname
    if not hostname:
        raise SSRFValidationError("URL thiếu hostname")

    # Kiểm tra nếu hostname là IP trực tiếp
    try:
        ip = ipaddress.ip_address(hostname)
        _check_ip_allowed(ip)
        return
    except ValueError:
        pass  # Không phải IP trực tiếp, tiếp tục phân giải DNS

    # Phân giải DNS
    try:
        addr_infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror as e:
        raise SSRFValidationError(f"Không thể phân giải tên miền '{hostname}': {e}")

    for addr in addr_infos:
        ip_str = addr[4][0]
        try:
            ip = ipaddress.ip_address(ip_str)
            _check_ip_allowed(ip)
        except ValueError:
            raise SSRFValidationError(f"Địa chỉ IP không hợp lệ từ DNS: {ip_str}")


def _check_ip_allowed(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> None:
    """Kiểm tra một địa chỉ IP có nằm trong các dải cấm không."""
    if ip.is_loopback:
        raise SSRFValidationError(f"Chặn truy cập Loopback IP: {ip}")
    if ip.is_private:
        raise SSRFValidationError(f"Chặn truy cập mạng nội bộ Private IP: {ip}")
    if ip.is_link_local:
        raise SSRFValidationError(f"Chặn truy cập Link-local IP: {ip}")
    if ip.is_reserved:
        raise SSRFValidationError(f"Chặn truy cập IP bảo lưu: {ip}")
    if ip.is_multicast:
        raise SSRFValidationError(f"Chặn truy cập IP multicast: {ip}")

    for network in BLOCKED_NETWORKS:
        if ip in network:
            raise SSRFValidationError(f"IP {ip} thuộc dải bị chặn ({network})")


async def safe_fetch_url(
    url: str,
    max_size_bytes: int = 10 * 1024 * 1024,
    timeout_seconds: float = 20.0,
    max_redirects: int = 5,
) -> tuple[bytes, str, str]:
    """
    Tải nội dung từ URL một cách an toàn:
    - Kiểm tra SSRF trước mỗi lần gửi request và ở TỪNG lần redirect.
    - Giới hạn dung lượng tải tối đa (stream để ngắt sớm).
    - Tự động nhận diện charset, trả về (content_bytes, final_url, content_type).
    """
    current_url = url
    redirect_count = 0

    headers = {
        "User-Agent": CHROME_MOBILE_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/pdf,*/*;q=0.8",
        "Accept-Language": "vi,en-US;q=0.9,en;q=0.8",
    }

    async with httpx.AsyncClient(
        verify=True,
        timeout=httpx.Timeout(timeout_seconds),
        follow_redirects=False,  # Tự xử lý redirect để kiểm tra SSRF mỗi chặng
    ) as client:
        while True:
            validate_url_safe(current_url)

            response = await client.get(current_url, headers=headers)

            # Xử lý redirect thủ công
            if response.is_redirect or response.status_code in (301, 302, 303, 307, 308):
                redirect_count += 1
                if redirect_count > max_redirects:
                    raise SSRFValidationError(f"Vượt quá giới hạn {max_redirects} lần chuyển hướng (redirect)")

                location = response.headers.get("Location")
                if not location:
                    raise SSRFValidationError("Chuyển hướng thiếu header Location")

                from urllib.parse import urljoin
                current_url = urljoin(current_url, location)
                continue

            response.raise_for_status()

            # Kiểm tra kích thước Content-Length nếu có
            content_length = response.headers.get("Content-Length")
            if content_length and int(content_length) > max_size_bytes:
                raise ValueError(f"Dung lượng vượt quá giới hạn cho phép ({max_size_bytes // (1024*1024)}MB)")

            # Đọc nội dung giới hạn
            body = b""
            async for chunk in response.aiter_bytes():
                body += chunk
                if len(body) > max_size_bytes:
                    raise ValueError(f"Dung lượng nội dung vượt quá {max_size_bytes // (1024*1024)}MB")

            content_type = response.headers.get("Content-Type", "").lower()
            return body, str(response.url), content_type
