import pytest
from app.services.ssrf import validate_url_safe, SSRFValidationError


def test_ssrf_blocks_loopback_and_private():
    """Kiểm tra chặn các IP loopback, private và link-local."""
    blocked_urls = [
        "http://127.0.0.1",
        "http://127.0.0.1:8080/secret",
        "http://localhost",
        "http://10.0.0.1/admin",
        "http://10.255.255.255",
        "http://172.16.0.1",
        "http://192.168.1.1",
        "http://169.254.169.254/latest/meta-data/",  # AWS / Cloud metadata
        "http://[::1]",                              # IPv6 Loopback
        "http://[fe80::1]",                          # IPv6 Link-local
        "ftp://example.com",                         # Không phải HTTP/HTTPS
        "file:///etc/passwd",                        # File scheme
    ]

    for url in blocked_urls:
        with pytest.raises(SSRFValidationError):
            validate_url_safe(url)


def test_ssrf_allows_public_domains():
    """Kiểm tra cho phép các tên miền công khai hợp lệ."""
    # Các domain công khai phổ biến
    valid_urls = [
        "https://vnexpress.net",
        "https://github.com",
        "https://www.google.com",
    ]
    for url in valid_urls:
        # Không được ném ngoại lệ
        try:
            validate_url_safe(url)
        except SSRFValidationError as e:
            # Nếu chạy trong môi trường không có internet thì bỏ qua lỗi phân giải DNS
            if "Không thể phân giải tên miền" in str(e):
                continue
            raise
