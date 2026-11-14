import os
import tempfile
import pytest
from app.services.news_aggregator import clean_html, extract_image_url
from app.services.news_summarizer import format_vietnamese_date, fallback_summarize
from app.services.news_card_generator import generate_news_infographic_card


def test_clean_html():
    raw = '<a href="https://example.com"><img src="test.jpg"></a></br>Bản tin thời sự nóng hổi &amp; quan trọng.'
    cleaned = clean_html(raw)
    assert "Bản tin thời sự nóng hổi & quan trọng." in cleaned
    assert "<" not in cleaned
    assert ">" not in cleaned


def test_format_vietnamese_date():
    import datetime
    dt = datetime.datetime(2026, 11, 15, 7, 0, 0) # Chủ Nhật
    date_str = format_vietnamese_date(dt)
    assert "Chủ Nhật" in date_str
    assert "15/11/2026" in date_str


def test_fallback_summarize():
    mock_topics = [
        {
            "id": "thoi_su",
            "name": "Thời sự",
            "icon": "newspaper",
            "color": "#DC2626",
            "items": [
                {
                    "title": "Chính phủ họp phiên thường kỳ",
                    "description": "Thủ tướng chủ trì phiên họp bàn về kinh tế xã hội.",
                    "link": "https://vnexpress.net/test",
                    "source": "VnExpress"
                }
            ]
        }
    ]
    res = fallback_summarize(mock_topics, "Chủ Nhật, 15/11/2026")
    assert "Chủ Nhật, 15/11/2026" in res["title"]
    assert len(res["categories"]) == 1
    assert "Chính phủ họp phiên thường kỳ" in res["markdown_content"]


def test_generate_news_infographic_card():
    mock_briefing = {
        "title": "Điểm tin sáng - 15/11/2026",
        "date_str": "Chủ Nhật, 15/11/2026",
        "greeting": "Chúc bạn một ngày mới tràn đầy năng lượng!",
        "categories": [
            {
                "id": "thoi_su",
                "name": "Thời sự & Xã hội",
                "color": "#DC2626",
                "items": [
                    {
                        "title": "Khai mạc kỳ họp Quốc hội khóa mới",
                        "summary": "Kỳ họp tập trung thảo luận các quyết sách phát triển kinh tế 5 năm tới.",
                        "source": "VnExpress"
                    }
                ]
            },
            {
                "id": "cong_nghe",
                "name": "Khoa học & Công nghệ",
                "color": "#2563EB",
                "items": [
                    {
                        "title": "Mô hình AI mới đạt kỷ lục",
                        "summary": "Mô hình tăng 30% hiệu năng xử lý văn bản tiếng Việt.",
                        "source": "Tuổi Trẻ"
                    }
                ]
            }
        ]
    }
    with tempfile.TemporaryDirectory() as tmpdir:
        from app.core import config
        orig_dir = config.settings.FILES_STORAGE_DIR
        try:
            config.settings.FILES_STORAGE_DIR = tmpdir
            filename, path = generate_news_infographic_card(mock_briefing)
            assert os.path.isfile(path)
            assert filename.endswith(".jpg")
            assert os.path.getsize(path) > 1000
        finally:
            config.settings.FILES_STORAGE_DIR = orig_dir

