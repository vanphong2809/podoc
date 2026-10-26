import io
import pytest
from pypdf import PdfWriter

from app.services.extractors.docx import DocxExtractor
from app.services.extractors.pdf import PdfExtractor
from app.services.extractors.registry import extractor_registry
from app.services.extractors.text_files import MarkdownExtractor, TextExtractor


@pytest.mark.asyncio
async def test_text_extractor_vietnamese():
    """Kiểm tra trích xuất file .txt tiếng Việt có dấu, giữ ngắt đoạn."""
    extractor = TextExtractor()
    content = "Học tập lập trình Python\n\nĐây là đoạn văn bản đầu tiên.\n\nĐây là đoạn văn bản thứ hai."
    raw_bytes = content.encode("utf-8")

    result = await extractor.extract(raw_bytes, {"file_name": "ghi_chu.txt"})
    assert result.title == "Học tập lập trình Python"
    assert "Đây là đoạn văn bản đầu tiên." in result.content_markdown
    assert "Đây là đoạn văn bản thứ hai." in result.content_markdown
    assert result.source_type == "FILE"


@pytest.mark.asyncio
async def test_markdown_extractor_frontmatter():
    """Kiểm tra trích xuất file .md có YAML front-matter."""
    extractor = MarkdownExtractor()
    md_content = """---
title: "Hướng dẫn tối ưu PostgreSQL"
author: "Nguyen Van A"
---

# Nội dung chính
PostgreSQL là hệ quản trị cơ sở dữ liệu mạnh mẽ.
"""
    raw_bytes = md_content.encode("utf-8")
    result = await extractor.extract(raw_bytes, {"file_name": "postgres.md"})
    assert result.title == "Hướng dẫn tối ưu PostgreSQL"
    assert result.author == "Nguyen Van A"
    assert "PostgreSQL là hệ quản trị cơ sở dữ liệu mạnh mẽ." in result.content_markdown


@pytest.mark.asyncio
async def test_pdf_extractor_with_text():
    """Kiểm tra tạo file PDF có text và bóc tách thành công."""
    writer = PdfWriter()
    page = writer.add_blank_page(width=200, height=200)
    # Metadata
    writer.add_metadata({"/Title": "Tài liệu PDF mẫu", "/Author": "PoDoc"})

    pdf_buffer = io.BytesIO()
    writer.write(pdf_buffer)
    raw_bytes = pdf_buffer.getvalue()

    extractor = PdfExtractor()
    result = await extractor.extract(raw_bytes, {"file_name": "sample.pdf"})
    assert result.source_type == "FILE"
    # PDF không có text được nhận diện là PDF scan cần OCR
    assert result.is_fallback_bookmark is True
    assert "cần tính năng OCR" in result.content_markdown


def test_magic_bytes_detection():
    """Kiểm tra nhận diện định dạng bằng Magic Bytes nội dung thật."""
    # PDF magic byte (%PDF)
    pdf_bytes = b"%PDF-1.5 some pdf data here"
    assert extractor_registry.detect_real_mime_type(pdf_bytes, "fake_name.txt") == "application/pdf"

    # HTML
    html_bytes = b"<!DOCTYPE html><html><body>Test</body></html>"
    assert extractor_registry.detect_real_mime_type(html_bytes, "page.doc") == "text/html"

    # RTF
    rtf_bytes = b"{\\rtf1\\ansi\\deff0 Hello}"
    assert extractor_registry.detect_real_mime_type(rtf_bytes, "test.unknown") in ("text/rtf", "application/rtf")

    # Plain text
    txt_bytes = "Xin chào các bạn".encode("utf-8")
    assert extractor_registry.detect_real_mime_type(txt_bytes, "note.txt") == "text/plain"
