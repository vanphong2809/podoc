from urllib.parse import urlparse
from bs4 import BeautifulSoup
import trafilatura

from app.services.extractors.base import (
    BaseExtractor,
    ExtractionResult,
    calculate_reading_minutes,
    clean_markdown_text,
    generate_excerpt,
)


class WebExtractor(BaseExtractor):
    """Trích xuất bài viết từ trang web thông thường dùng Trafilatura và BeautifulSoup."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type == "URL":
            parsed = urlparse(identifier)
            # YouTube có extractor riêng
            if "youtube.com" in parsed.netloc or "youtu.be" in parsed.netloc:
                return False
            return True
        elif source_type == "FILE":
            # Xử lý cả file .html / .htm
            return identifier in ("text/html", "application/xhtml+xml", "html", "htm")
        return False

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        url = context.get("url", "")
        # Chuyển bytes sang text HTML (tự nhận charset)
        html_text = ""
        for encoding in ("utf-8", "utf-8-sig", "latin1", "cp1252", "cp1258"):
            try:
                html_text = raw_content.decode(encoding)
                break
            except (UnicodeDecodeError, LookupError):
                continue

        if not html_text:
            html_text = raw_content.decode("utf-8", errors="replace")

        soup = BeautifulSoup(html_text, "html.parser")

        # 1. Trích xuất Metadata qua thẻ OpenGraph / Meta
        title = self._extract_title(soup)
        description = self._extract_meta(soup, ["og:description", "twitter:description", "description"])
        image_url = self._extract_meta(soup, ["og:image", "twitter:image"])
        author = self._extract_meta(soup, ["author", "article:author", "twitter:creator"])
        site_name = self._extract_meta(soup, ["og:site_name"])
        if not site_name and url:
            site_name = urlparse(url).netloc

        lang = None
        html_tag = soup.find("html")
        if html_tag and html_tag.get("lang"):
            lang = str(html_tag.get("lang")).strip()[:10]

        # 2. Bóc tách nội dung chính sang Markdown bằng Trafilatura
        extracted_md = trafilatura.extract(
            html_text,
            url=url,
            output_format="markdown",
            include_links=True,
            include_images=True,
            include_tables=True,
            include_formatting=True,
            favor_recall=True,
        )

        if not extracted_md or len(extracted_md.strip()) < 50:
            # Fallback Bookmark khi trang bị chặn, paywall hoặc không bóc tách được
            is_fallback = True
            content_markdown = (
                f"> ⚠️ **Nội dung không thể tự động bóc tách (trang chặn bot, paywall hoặc cần đăng nhập).**\n>\n"
                f"> [🔗 Nhấn vào đây để xem nội dung gốc trên web]({url})"
            )
            excerpt = description or "Không thể bóc tách nội dung tự động. Đã lưu link để đọc trên web."
            error_msg = "Không thể bóc tách nội dung tự động. Đã lưu dưới dạng Bookmark."
        else:
            is_fallback = False
            content_markdown = clean_markdown_text(extracted_md, title=title)
            excerpt = description or generate_excerpt(content_markdown)
            error_msg = None

        if not title:
            title = "Bài viết không tiêu đề"

        reading_minutes = calculate_reading_minutes(content_markdown)

        return ExtractionResult(
            title=title,
            content_markdown=content_markdown,
            excerpt=excerpt,
            author=author,
            site_name=site_name,
            image_url=image_url,
            language=lang,
            reading_minutes=reading_minutes,
            source_type=context.get("source_type", "URL"),
            source_url=url,
            is_fallback_bookmark=is_fallback,
            error_message=error_msg,
        )

    def _extract_title(self, soup: BeautifulSoup) -> str:
        og_title = self._extract_meta(soup, ["og:title", "twitter:title"])
        if og_title:
            return og_title
        if soup.title and soup.title.string:
            return soup.title.string.strip()
        h1 = soup.find("h1")
        if h1 and h1.text:
            return h1.text.strip()
        return ""

    def _extract_meta(self, soup: BeautifulSoup, names: list[str]) -> str | None:
        for name in names:
            meta = soup.find("meta", property=name) or soup.find("meta", attrs={"name": name})
            if meta and meta.get("content"):
                val = meta.get("content").strip()
                if val:
                    return val
        return None
