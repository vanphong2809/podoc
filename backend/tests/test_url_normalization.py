from app.services.url_utils import extract_first_url, normalize_url


def test_normalize_url_strips_tracking():
    """Kiểm tra loại bỏ các tham số tracking utm_*, fbclid, gclid."""
    url = (
        "https://example.com/article/?utm_source=facebook&utm_medium=cpc"
        "&utm_campaign=summer&fbclid=IwAR123&gclid=xyz&category=tech"
    )
    normalized = normalize_url(url)
    assert "utm_source" not in normalized
    assert "fbclid" not in normalized
    assert "gclid" not in normalized
    assert "category=tech" in normalized
    assert normalized == "https://example.com/article?category=tech"


def test_normalize_url_removes_fragment_and_trailing_slash():
    """Kiểm tra loại bỏ fragment và dấu gạch chéo cuối."""
    url = "HTTPS://WWW.EXAMPLE.COM:443/news/post-1/#section-2"
    normalized = normalize_url(url)
    assert normalized == "https://www.example.com/news/post-1"


def test_extract_first_url_from_share_text():
    """Kiểm tra bóc tách URL từ văn bản được chia sẻ qua Android Share Sheet."""
    # Khi chia sẻ từ Chrome hoặc Facebook, nội dung thường kèm tiêu đề
    text_from_share = (
        "Xem bài viết này rất hay: 'Công nghệ AI năm 2026' "
        "https://vnexpress.net/cong-nghe-ai-2026-12345.html hãy cùng đọc nhé!"
    )
    url = extract_first_url(text_from_share)
    assert url == "https://vnexpress.net/cong-nghe-ai-2026-12345.html"

    # Trường hợp chỉ có link
    assert extract_first_url("https://podoc.app") == "https://podoc.app"

    # Trường hợp không có link
    assert extract_first_url("Không có liên kết nào ở đây cả") is None
