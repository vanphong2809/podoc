import asyncio
import html
import logging
import re
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional
import httpx

logger = logging.getLogger("podoc.news_aggregator")

TOPIC_CONFIGS = [
    {
        "id": "thoi_su",
        "name": "Thời sự & Xã hội",
        "icon": "newspaper",
        "color": "#DC2626", # Đỏ
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/thoi-su.rss"},
            {"source": "Tuổi Trẻ", "url": "https://tuoitre.vn/rss/thoi-su.rss"},
        ]
    },
    {
        "id": "cong_nghe",
        "name": "Khoa học & Công nghệ",
        "icon": "cpu",
        "color": "#2563EB", # Xanh dương
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/so-hoa.rss"},
            {"source": "Tuổi Trẻ", "url": "https://tuoitre.vn/rss/nhip-song-so.rss"},
        ]
    },
    {
        "id": "the_thao",
        "name": "Bóng đá & Thể thao",
        "icon": "trophy",
        "color": "#059669", # Xanh lá
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/the-thao.rss"},
            {"source": "Tuổi Trẻ", "url": "https://tuoitre.vn/rss/the-thao.rss"},
        ]
    },
    {
        "id": "giai_tri",
        "name": "Giải trí & Văn hóa",
        "icon": "film",
        "color": "#9333EA", # Tím
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/giai-tri.rss"},
            {"source": "Tuổi Trẻ", "url": "https://tuoitre.vn/rss/giai-tri.rss"},
        ]
    },
    {
        "id": "giao_duc",
        "name": "Giáo dục & Tri thức",
        "icon": "graduation-cap",
        "color": "#D97706", # Vàng hổ phách
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/giao-duc.rss"},
            {"source": "Tuổi Trẻ", "url": "https://tuoitre.vn/rss/giao-duc.rss"},
        ]
    },
    {
        "id": "doi_song",
        "name": "Đời sống & Sức khỏe",
        "icon": "heart-pulse",
        "color": "#E11D48", # Hồng đỏ
        "feeds": [
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/doi-song.rss"},
            {"source": "VnExpress", "url": "https://vnexpress.net/rss/suc-khoe.rss"},
        ]
    },
]


def clean_html(raw_html: str) -> str:
    """Loại bỏ các thẻ HTML để lấy văn bản thuần."""
    if not raw_html:
        return ""
    # Nếu có thẻ đóng </br> hay </a>, lấy phần text sau ảnh
    text = re.sub(r"<[^>]+>", " ", raw_html)
    text = html.unescape(text)
    # Rút gọn khoảng trắng
    return re.sub(r"\s+", " ", text).strip()


def extract_image_url(item_elem: ET.Element, desc_html: str) -> Optional[str]:
    """Tìm link ảnh từ enclosure hoặc từ thẻ img trong description."""
    # 1. Thẻ enclosure
    enclosure = item_elem.find("enclosure")
    if enclosure is not None:
        url = enclosure.get("url")
        if url and ("http://" in url or "https://" in url):
            return url.strip()

    # 2. Thẻ img trong description
    if desc_html:
        m = re.search(r'<img[^>]+src=["\'](https?://[^"\']+)["\']', desc_html, re.IGNORECASE)
        if m:
            return m.group(1).strip()

    return None


async def fetch_single_feed(
    client: httpx.AsyncClient,
    feed_info: Dict[str, str],
    limit_per_feed: int = 5
) -> List[Dict[str, Any]]:
    """Tải và phân tích cú pháp 1 RSS Feed."""
    source_name = feed_info["source"]
    url = feed_info["url"]
    results = []
    try:
        response = await client.get(url, timeout=10.0, follow_redirects=True)
        if response.status_code != 200:
            logger.warning(f"Lỗi tải RSS {url}: HTTP {response.status_code}")
            return []

        xml_content = response.content
        root = ET.fromstring(xml_content)
        channel = root.find("channel")
        if channel is None:
            return []

        for item in channel.findall("item"):
            title_el = item.find("title")
            link_el = item.find("link")
            desc_el = item.find("description")
            pub_date_el = item.find("pubDate")

            raw_title = title_el.text if title_el is not None and title_el.text else ""
            clean_title = clean_html(raw_title)
            link = link_el.text.strip() if link_el is not None and link_el.text else ""

            raw_desc = desc_el.text if desc_el is not None and desc_el.text else ""
            clean_desc = clean_html(raw_desc)
            image_url = extract_image_url(item, raw_desc)
            pub_date = pub_date_el.text.strip() if pub_date_el is not None and pub_date_el.text else ""

            if clean_title and link:
                results.append({
                    "title": clean_title,
                    "description": clean_desc,
                    "link": link,
                    "image_url": image_url,
                    "source": source_name,
                    "pub_date": pub_date
                })
                if len(results) >= limit_per_feed:
                    break

    except Exception as e:
        logger.warning(f"Không thể đọc feed {source_name} ({url}): {e}")

    return results


async def aggregate_daily_news(
    max_items_per_topic: int = 3,
    selected_topic_ids: Optional[List[str]] = None
) -> List[Dict[str, Any]]:
    """
    Thu thập tin tức từ tất cả các chuyên mục được cấu hình.
    Trả về danh sách các Topic kèm các bài viết mới nhất.
    """
    topics_to_fetch = TOPIC_CONFIGS
    if selected_topic_ids:
        topics_to_fetch = [t for t in TOPIC_CONFIGS if t["id"] in selected_topic_ids]

    aggregated_topics = []

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 PoDocNews/1.0"
    }

    async with httpx.AsyncClient(headers=headers) as client:
        for topic in topics_to_fetch:
            feed_tasks = [
                fetch_single_feed(client, feed, limit_per_feed=max_items_per_topic)
                for feed in topic["feeds"]
            ]
            feed_results = await asyncio.gather(*feed_tasks, return_exceptions=True)

            all_items = []
            seen_titles = set()

            for res in feed_results:
                if isinstance(res, list):
                    for item in res:
                        # Tránh trùng lặp tiêu đề
                        norm_title = re.sub(r"[^\w\s]", "", item["title"].lower()).strip()
                        if norm_title not in seen_titles:
                            seen_titles.add(norm_title)
                            all_items.append(item)

            # Lấy top bài viết quan trọng nhất
            selected_items = all_items[:max_items_per_topic]

            if selected_items:
                aggregated_topics.append({
                    "id": topic["id"],
                    "name": topic["name"],
                    "icon": topic["icon"],
                    "color": topic["color"],
                    "items": selected_items
                })

    return aggregated_topics

