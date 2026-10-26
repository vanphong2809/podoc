import io
import os
import re
import mammoth

from app.services.extractors.base import (
    BaseExtractor,
    ExtractionResult,
    calculate_reading_minutes,
    generate_excerpt,
)


class DocxExtractor(BaseExtractor):
    """Trích xuất file Word .docx sang Markdown bằng mammoth, giữ heading, bảng, danh sách, in đậm/nghiêng."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type != "FILE":
            return False
        ident = identifier.lower()
        return (
            ident in (
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword",
                "docx",
            )
            or ident.endswith(".docx")
        )

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        file_name = context.get("file_name", "document.docx")

        docx_file = io.BytesIO(raw_content)
        # Sử dụng mammoth chuyển đổi trực tiếp sang Markdown
        result = mammoth.convert_to_markdown(docx_file)
        markdown_text = result.value

        # Tìm tiêu đề từ heading đầu tiên
        title = None
        h1_match = re.search(r"^#+\s+(.+)$", markdown_text, re.MULTILINE)
        if h1_match:
            title = h1_match.group(1).strip()

        if not title:
            lines = [line.strip() for line in markdown_text.split("\n") if line.strip()]
            if lines:
                title = lines[0][:150]
            else:
                title = os.path.splitext(file_name)[0]

        excerpt = generate_excerpt(markdown_text)
        reading_minutes = calculate_reading_minutes(markdown_text)

        return ExtractionResult(
            title=title,
            content_markdown=markdown_text.strip(),
            excerpt=excerpt,
            file_name=file_name,
            file_path=context.get("file_path"),
            mime_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            source_type="FILE",
            reading_minutes=reading_minutes,
        )
