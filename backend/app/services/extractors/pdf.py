import io
import os
import re
from pypdf import PdfReader

from app.services.extractors.base import (
    BaseExtractor,
    ExtractionResult,
    calculate_reading_minutes,
    generate_excerpt,
)


class PdfExtractor(BaseExtractor):
    """Trích xuất file PDF bằng pypdf, phát hiện PDF scan không có text và thông báo rõ ràng."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type != "FILE":
            return False
        ident = identifier.lower()
        return (
            ident in ("application/pdf", "pdf")
            or ident.endswith(".pdf")
        )

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        file_name = context.get("file_name", "document.pdf")
        pdf_file = io.BytesIO(raw_content)

        try:
            reader = PdfReader(pdf_file)
            num_pages = len(reader.pages)
        except Exception as e:
            return ExtractionResult(
                title=os.path.splitext(file_name)[0],
                content_markdown=f"> ⚠️ Không thể đọc file PDF: {e}",
                excerpt="File PDF bị lỗi hoặc được mã hoá bảo vệ.",
                file_name=file_name,
                file_path=context.get("file_path"),
                mime_type="application/pdf",
                source_type="FILE",
                is_fallback_bookmark=True,
                error_message=f"Lỗi đọc file PDF: {str(e)}",
            )

        title = None
        author = None
        if reader.metadata:
            if reader.metadata.title:
                title = str(reader.metadata.title).strip()
            if reader.metadata.author:
                author = str(reader.metadata.author).strip()

        pages_text = []
        for i, page in enumerate(reader.pages):
            page_content = page.extract_text() or ""
            page_clean = page_content.strip()
            if page_clean:
                pages_text.append(f"### Trang {i + 1}\n\n{page_clean}")

        total_text = "\n\n".join(pages_text)

        # Kiểm tra PDF scan không có text
        if not total_text or len(total_text.strip()) < 30:
            is_fallback = True
            safe_title = title or os.path.splitext(file_name)[0]
            content_markdown = (
                f"# {safe_title}\n\n"
                f"> ⚠️ **PDF dạng scan không chứa lớp text (cần tính năng OCR để bóc tách ở phiên bản sau).**\n>\n"
                f"> *File gốc đã được lưu trữ an toàn trên server ({num_pages} trang). Bạn có thể bấm 'Mở file gốc' để xem trực tiếp.*"
            )
            excerpt = f"Tài liệu PDF scan ({num_pages} trang) không có text. Đã lưu file gốc."
            error_msg = "PDF dạng scan không chứa text (cần OCR). Đã lưu file gốc."
        else:
            is_fallback = False
            content_markdown = total_text
            excerpt = generate_excerpt(content_markdown)
            error_msg = None

        if not title:
            # Lấy dòng đầu tiên có nghĩa
            lines = [l.strip() for l in content_markdown.split("\n") if l.strip() and not l.startswith("###")]
            title = lines[0][:150] if lines else os.path.splitext(file_name)[0]

        reading_minutes = calculate_reading_minutes(content_markdown)

        return ExtractionResult(
            title=title,
            content_markdown=content_markdown,
            excerpt=excerpt,
            author=author,
            file_name=file_name,
            file_path=context.get("file_path"),
            mime_type="application/pdf",
            source_type="FILE",
            reading_minutes=reading_minutes,
            is_fallback_bookmark=is_fallback,
            error_message=error_msg,
        )
