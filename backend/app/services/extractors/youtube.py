import json
import re
from urllib.parse import parse_qs, urlparse
import httpx

from app.services.extractors.base import BaseExtractor, ExtractionResult


class YouTubeExtractor(BaseExtractor):
    """Trích xuất metadata video YouTube (tiêu đề, thumbnail, channel, mô tả) qua oEmbed API."""

    def can_handle(self, source_type: str, identifier: str) -> bool:
        if source_type != "URL":
            return False
        parsed = urlparse(identifier)
        return "youtube.com" in parsed.netloc or "youtu.be" in parsed.netloc

    async def extract(self, raw_content: bytes, context: dict) -> ExtractionResult:
        url = context.get("url", "")
        video_id = self._extract_video_id(url)

        # Lấy thông tin video từ YouTube oEmbed API chính thức (nhẹ và nhanh)
        oembed_url = f"https://www.youtube.com/oembed?url={url}&format=json"
        title = "Video YouTube"
        author = None
        thumbnail_url = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg" if video_id else None

        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                res = await client.get(oembed_url)
                if res.status_code == 200:
                    data = res.json()
                    title = data.get("title", title)
                    author = data.get("author_name")
                    if data.get("thumbnail_url"):
                        thumbnail_url = data.get("thumbnail_url")
        except Exception:
            pass

        # Tạo nội dung Markdown đẹp mắt cho video
        content_markdown = (
            f"# {title}\n\n"
            f"**Kênh**: {author or 'YouTube'}\n\n"
            f"[![Xem trên YouTube]({thumbnail_url})]({url})\n\n"
            f"▶️ [Nhấn vào đây để xem trực tiếp video trên YouTube]({url})\n"
        )

        excerpt = f"Video YouTube từ kênh {author or 'YouTube'}: {title}"

        return ExtractionResult(
            title=title,
            content_markdown=content_markdown,
            excerpt=excerpt,
            author=author,
            site_name="YouTube",
            image_url=thumbnail_url,
            language="vi",
            reading_minutes=1,
            source_type="URL",
            source_url=url,
        )

    def _extract_video_id(self, url: str) -> str | None:
        parsed = urlparse(url)
        if "youtu.be" in parsed.netloc:
            return parsed.path.lstrip("/").split("?")[0]
        if "youtube.com" in parsed.netloc:
            if "/watch" in parsed.path:
                qs = parse_qs(parsed.query)
                return qs.get("v", [None])[0]
            if "/shorts/" in parsed.path:
                return parsed.path.split("/shorts/")[1].split("/")[0]
        return None
