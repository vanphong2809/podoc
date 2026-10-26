import os
import filetype

from app.services.extractors.base import BaseExtractor, ExtractionResult
from app.services.extractors.docx import DocxExtractor
from app.services.extractors.pdf import PdfExtractor
from app.services.extractors.text_files import MarkdownExtractor, TextExtractor
from app.services.extractors.web import WebExtractor
from app.services.extractors.youtube import YouTubeExtractor


class ExtractorRegistry:
    """Trung tâm quản lý đăng ký và lựa chọn Extractor phù hợp cho từng loại nguồn."""

    def __init__(self):
        self._extractors: list[BaseExtractor] = [
            YouTubeExtractor(),
            WebExtractor(),
            DocxExtractor(),
            PdfExtractor(),
            MarkdownExtractor(),
            TextExtractor(),
        ]

    def register(self, extractor: BaseExtractor) -> None:
        """Đăng ký thêm extractor mới (dễ dàng mở rộng cho các định dạng mới)."""
        self._extractors.insert(0, extractor)

    def detect_real_mime_type(self, raw_bytes: bytes, file_name: str = "") -> str:
        """
        Nhận diện loại MIME thực tế dựa trên Magic Bytes (nội dung thật).
        Không tin tưởng vào phần mở rộng hay mime-type do client gửi lên.
        """
        # 1. Kiểm tra Magic Bytes bằng filetype
        kind = filetype.guess(raw_bytes[:8192])
        if kind is not None:
            # Phát hiện PDF, DOCX (Office Open XML), ZIP...
            if kind.mime == "application/pdf":
                return "application/pdf"
            if kind.mime in (
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip",
            ):
                # Kiểm tra nếu ZIP thực chất là file docx
                if file_name.lower().endswith(".docx") or b"word/document.xml" in raw_bytes[:100000]:
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            return kind.mime

        # 2. Kiểm tra các định dạng văn bản (không có header nhị phân trong filetype)
        header_sample = raw_bytes[:2048].strip()

        # RTF
        if header_sample.startswith(b"{\\rtf"):
            return "text/rtf"

        # HTML / SVG
        lower_header = header_sample.lower()
        if b"<svg" in lower_header:
            return "image/svg+xml"

        if (
            b"<!doctype html" in lower_header
            or b"<html" in lower_header
            or b"<head" in lower_header
            or b"<body" in lower_header
        ):
            return "text/html"

        # Markdown (dựa trên tên file hoặc frontmatter)
        ext = os.path.splitext(file_name)[1].lower()
        if ext in (".md", ".markdown") or header_sample.startswith(b"---"):
            return "text/markdown"

        return "text/plain"

    def get_extractor(self, source_type: str, identifier: str) -> BaseExtractor:
        """Tìm extractor phù hợp nhất cho nguồn đầu vào."""
        for extractor in self._extractors:
            if extractor.can_handle(source_type, identifier):
                return extractor

        # Fallback mặc định
        if source_type == "URL":
            return WebExtractor()
        return TextExtractor()


# Khởi tạo singleton registry toàn cục
extractor_registry = ExtractorRegistry()
