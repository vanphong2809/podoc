from urllib.parse import urlparse, urljoin
import json
import re
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

        # 1. Trích xuất Metadata qua thẻ OpenGraph / Meta / Thumbnail
        title = self._extract_title(soup)
        description = self._extract_meta(soup, ["og:description", "twitter:description", "description"])
        image_url = self._extract_image_url(soup, url)
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

        # Fallback nếu image_url vẫn chưa tìm thấy: tìm ảnh đầu tiên trong content_markdown
        if not image_url and content_markdown:
            m = re.search(r'!\[.*?\]\((https?://[^\s\)]+)\)', content_markdown)
            if m:
                image_url = m.group(1)

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

    def _extract_image_url(self, soup: BeautifulSoup, base_url: str) -> str | None:
        """
        Trích xuất thumbnail ảnh đại diện của trang web theo nhiều nguồn ưu tiên:
        1. OpenGraph / Twitter Cards / Meta tags
        2. Schema JSON-LD
        3. Link rel=image_src / thumbnail
        4. Thẻ img tiêu biểu trong bài viết
        """
        # 1. Thẻ meta phổ biến
        meta_names = [
            "og:image",
            "og:image:url",
            "og:image:secure_url",
            "twitter:image",
            "twitter:image:src",
            "image",
            "thumbnail",
        ]
        raw_meta = self._extract_meta(soup, meta_names)
        if raw_meta:
            resolved = urljoin(base_url, raw_meta) if base_url else raw_meta
            if resolved.startswith("http://") or resolved.startswith("https://"):
                return resolved

        # Thẻ meta itemprop="image"
        itemprop_meta = soup.find("meta", attrs={"itemprop": "image"})
        if itemprop_meta and itemprop_meta.get("content"):
            c = itemprop_meta.get("content").strip()
            if c:
                return urljoin(base_url, c) if base_url else c

        # 2. Thẻ link rel (image_src, apple-touch-icon)
        link_img = soup.find("link", rel=lambda r: r and any(k in r.lower() for k in ("image_src", "thumbnail")))
        if link_img and link_img.get("href"):
            h = link_img.get("href").strip()
            if h:
                return urljoin(base_url, h) if base_url else h

        # 3. JSON-LD schema
        for script in soup.find_all("script", type="application/ld+json"):
            if not script.string:
                continue
            try:
                data = json.loads(script.string.strip())
                img = self._find_image_in_json_ld(data)
                if img:
                    return urljoin(base_url, img) if base_url else img
            except Exception:
                continue

        # 4. Thẻ img đầu tiên trong article hoặc main hoặc class featured/thumb
        container = (
            soup.find("article")
            or soup.find("main")
            or soup.find(class_=re.compile(r"post|entry|content|detail", re.I))
            or soup
        )
        for img in container.find_all("img"):
            src = img.get("src") or img.get("data-src") or img.get("data-original") or img.get("data-lazy-src")
            if not src:
                # Check srcset
                srcset = img.get("srcset")
                if srcset:
                    src = srcset.split(",")[0].strip().split(" ")[0]
            if src:
                src = src.strip()
                # Bỏ qua icon, tracking pixel 1x1, base64 quá ngắn
                if src.startswith("data:") and len(src) < 500:
                    continue
                if any(x in src.lower() for x in ("avatar", "logo", "icon", "pixel", "blank.gif", "spacer")):
                    continue
                resolved = urljoin(base_url, src) if base_url else src
                if resolved.startswith("http://") or resolved.startswith("https://"):
                    return resolved

        return None

    def _find_image_in_json_ld(self, data: any) -> str | None:
        if isinstance(data, dict):
            if "image" in data:
                val = data["image"]
                if isinstance(val, str) and val.startswith("http"):
                    return val
                elif isinstance(val, list) and len(val) > 0 and isinstance(val[0], str):
                    return val[0]
                elif isinstance(val, dict) and "url" in val:
                    return val["url"]
            for v in data.values():
                res = self._find_image_in_json_ld(v)
                if res:
                    return res
        elif isinstance(data, list):
            for item in data:
                res = self._find_image_in_json_ld(item)
                if res:
                    return res
        return None
