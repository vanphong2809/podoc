import re
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

# Các tham số query tracking cần loại bỏ
TRACKING_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "utm_name", "fbclid", "gclid", "dclid", "msclkid",
    "mc_eid", "yclid", "ref", "ref_src", "source", "feature", "si",
}

# Regex tìm kiếm URL trong văn bản được chia sẻ (từ Share Sheet)
URL_REGEX = re.compile(
    r"(https?:\/\/(?:www\.)?[-a-zA-Z0-9@:%._\+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b(?:[-a-zA-Z0-9()@:%_\+.~#?&//=]*))",
    re.IGNORECASE,
)


def extract_first_url(text: str) -> str | None:
    """Tìm URL đầu tiên xuất hiện trong đoạn văn bản (hỗ trợ Share Sheet chứa cả tiêu đề + link)."""
    if not text:
        return None
    match = URL_REGEX.search(text)
    return match.group(1) if match else None


def normalize_url(url: str) -> str:
    """
    Chuẩn hoá URL:
    - Chuyển scheme và host thành chữ thường.
    - Bỏ default ports (80, 443).
    - Loại bỏ các tracking params (utm_*, fbclid...).
    - Sắp xếp các query params còn lại theo thứ tự alphabet.
    - Bỏ fragment (#...).
    - Xoá dấu gạch chéo cuối path (trailing slash).
    """
    if not url:
        return ""

    parsed = urlparse(url.strip())
    scheme = parsed.scheme.lower()
    netloc = parsed.netloc.lower()

    # Bỏ port mặc định
    if scheme == "http" and netloc.endswith(":80"):
        netloc = netloc[:-3]
    elif scheme == "https" and netloc.endswith(":443"):
        netloc = netloc[:-4]

    # Chuẩn hoá path: loại bỏ trailing slash nếu path dài hơn 1 ký tự
    path = parsed.path
    if len(path) > 1 and path.endswith("/"):
        path = path.rstrip("/")

    # Lọc và sắp xếp query parameters
    query_items = parse_qsl(parsed.query, keep_blank_values=False)
    filtered_items = [
        (k, v) for k, v in query_items
        if k.lower() not in TRACKING_PARAMS and not k.lower().startswith("utm_")
    ]
    filtered_items.sort(key=lambda x: x[0])
    normalized_query = urlencode(filtered_items)

    # Loại bỏ fragment
    normalized = urlunparse((scheme, netloc, path, parsed.params, normalized_query, ""))
    return normalized
