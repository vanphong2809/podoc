import os
import re
import charset_normalizer
from striprtf.striprtf import rtf_to_text

from app.services.extractors.base import (
    BaseExtractor,
    ExtractionResult,
    calculate_reading_minutes,
    generate_excerpt,
)


class TextExtractor(BaseExtractor):
    """Trích xuất file văn bản thuần .txt và .rtf, tự động phát hiện encoding (hỗ trợ tiếng Việt Windows-1258, UTF-8)."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type != "FILE":
            return False
        ident = identifier.lower()
        return (
            ident in ("text/plain", "text/rtf", "application/rtf")
            or ident.endswith(".txt")
            or ident.endswith(".rtf")
            or ident in ("txt", "rtf")
        )

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        file_name = context.get("file_name", "document.txt")
        file_ext = os.path.splitext(file_name)[1].lower()

        # Xử lý RTF nếu là file .rtf
        if file_ext == ".rtf" or raw_content.startswith(b"{\\rtf"):
            try:
                raw_text = raw_content.decode("utf-8", errors="ignore")
                text = rtf_to_text(raw_text)
            except Exception:
                text = raw_content.decode("latin1", errors="replace")
        else:
            # Tự động phát hiện bảng mã qua charset_normalizer
            detected = charset_normalizer.from_bytes(raw_content).best()
            if detected:
                text = str(detected)
            else:
                text = raw_content.decode("utf-8", errors="replace")

        # Chuẩn hoá ngắt dòng
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        # Tìm tiêu đề từ dòng đầu tiên không rỗng hoặc tên file
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        if lines:
            first_line = lines[0]
            title = first_line[:150] if len(first_line) > 3 else os.path.splitext(file_name)[0]
        else:
            title = os.path.splitext(file_name)[0]

        # Giữ ngắt đoạn văn bản
        paragraphs = text.split("\n\n")
        formatted_paragraphs = []
        for p in paragraphs:
            p_clean = p.strip()
            if p_clean:
                formatted_paragraphs.append(p_clean)

        content_markdown = "\n\n".join(formatted_paragraphs)
        excerpt = generate_excerpt(content_markdown)
        reading_minutes = calculate_reading_minutes(content_markdown)

        return ExtractionResult(
            title=title,
            content_markdown=content_markdown,
            excerpt=excerpt,
            file_name=file_name,
            file_path=context.get("file_path"),
            mime_type="text/plain",
            source_type="FILE",
            reading_minutes=reading_minutes,
        )


class MarkdownExtractor(BaseExtractor):
    """Trích xuất file .md, trích xuất tiêu đề từ YAML front-matter hoặc H1 (# Title)."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type != "FILE":
            return False
        ident = identifier.lower()
        return (
            ident in ("text/markdown", "text/x-markdown")
            or ident.endswith(".md")
            or ident.endswith(".markdown")
            or ident in ("md", "markdown")
        )

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        file_name = context.get("file_name", "document.md")

        # Decode Markdown text
        try:
            text = raw_content.decode("utf-8")
        except UnicodeDecodeError:
            detected = charset_normalizer.from_bytes(raw_content).best()
            text = str(detected) if detected else raw_content.decode("utf-8", errors="replace")

        title = None
        author = None

        # 1. Kiểm tra YAML front-matter (---\ntitle: ...\n---)
        frontmatter_match = re.match(r"^---\s*\n(.*?)\n---\s*\n", text, re.DOTALL)
        if frontmatter_match:
            frontmatter_text = frontmatter_match.group(1)
            title_match = re.search(r"^title:\s*['\"]?(.*?)['\"]?\s*$", frontmatter_text, re.MULTILINE)
            if title_match:
                title = title_match.group(1).strip()
            author_match = re.search(r"^author:\s*['\"]?(.*?)['\"]?\s*$", frontmatter_text, re.MULTILINE)
            if author_match:
                author = author_match.group(1).strip()
            # Bỏ front-matter khỏi nội dung chính
            text = text[frontmatter_match.end():]

        # 2. Tìm H1 (# Heading)
        if not title:
            h1_match = re.search(r"^#\s+(.+)$", text, re.MULTILINE)
            if h1_match:
                title = h1_match.group(1).strip()

        # 3. Fallback tên file
        if not title:
            title = os.path.splitext(file_name)[0]

        excerpt = generate_excerpt(text)
        reading_minutes = calculate_reading_minutes(text)

        return ExtractionResult(
            title=title,
            content_markdown=text.strip(),
            excerpt=excerpt,
            author=author,
            file_name=file_name,
            file_path=context.get("file_path"),
            mime_type="text/markdown",
            source_type="FILE",
            reading_minutes=reading_minutes,
        )
