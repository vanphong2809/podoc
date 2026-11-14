import datetime
import json
import logging
from typing import Any, Dict, List, Optional
import httpx

from app.core.config import settings

logger = logging.getLogger("podoc.news_summarizer")

VIETNAMESE_DAYS = [
    "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"
]


def format_vietnamese_date(dt: Optional[datetime.datetime] = None) -> str:
    """Trả về chuỗi ngày tiếng Việt, ví dụ: 'Chủ Nhật, 15/11/2026'."""
    if dt is None:
        # Giờ Việt Nam UTC+7
        dt = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7)))
    weekday_str = VIETNAMESE_DAYS[dt.weekday()]
    return f"{weekday_str}, {dt.strftime('%d/%m/%Y')}"


async def call_gemini_briefing(
    prompt: str,
    api_key: str,
    model: str = "gemini-3.8-flash"
) -> Dict[str, Any]:
    """Gửi yêu cầu tóm tắt đến Google Gemini API với JSON Structured Output."""
    models_to_try = [model]
    for m in ["gemini-3.8-flash", "gemini-2.0-flash", "gemini-1.5-flash"]:
        if m not in models_to_try:
            models_to_try.append(m)

    last_exception = None
    for target_model in models_to_try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{target_model}:generateContent?key={api_key}"
        payload = {
            "contents": [
                {
                    "parts": [{"text": prompt}]
                }
            ],
            "generationConfig": {
                "response_mime_type": "application/json",
                "temperature": 0.2
            }
        }
        try:
            async with httpx.AsyncClient(timeout=45.0) as client:
                response = await client.post(url, json=payload)
                if response.status_code in (404, 429, 503, 400):
                    logger.warning(f"Gemini {target_model} trả về mã {response.status_code}, thử model tiếp theo...")
                    continue
                response.raise_for_status()
                res_json = response.json()
                candidates = res_json.get("candidates", [])
                if not candidates:
                    continue
                content_parts = candidates[0].get("content", {}).get("parts", [])
                if not content_parts:
                    continue
                raw_text = content_parts[0].get("text", "{}")
                return json.loads(raw_text)
        except Exception as e:
            last_exception = e
            logger.warning(f"Lỗi gọi model {target_model}: {e}")

    if last_exception:
        raise last_exception
    raise RuntimeError("Không thể kết nối đến Google Gemini API.")


def fallback_summarize(
    aggregated_topics: List[Dict[str, Any]],
    date_str: str
) -> Dict[str, Any]:
    """Tạo bản tóm tắt dự phòng khi chưa cấu hình Gemini API Key hoặc mất mạng."""
    categories_result = []
    markdown_lines = [
        f"# ☀️ ĐIỂM TIN SÁNG PODOC",
        f"*{date_str} - Tự động cập nhật lúc 07:00 AM*",
        "",
        "> Chúc bạn một ngày mới tràn đầy năng lượng, làm việc hiệu quả và đón nhận nhiều tin vui!",
        "",
        "---",
        ""
    ]

    for topic in aggregated_topics:
        topic_name = topic["name"]
        color = topic["color"]
        icon = topic["icon"]
        items = topic["items"]

        topic_items = []
        markdown_lines.append(f"## 📌 {topic_name.upper()}")
        markdown_lines.append("")

        for it in items:
            title = it["title"]
            desc = it["description"] or title
            link = it["link"]
            source = it["source"]

            # Lấy 1 câu tóm tắt đầu tiên
            first_sentence = desc.split(".")[0].strip()
            if not first_sentence:
                first_sentence = desc
            if not first_sentence.endswith("."):
                first_sentence += "."

            topic_items.append({
                "title": title,
                "summary": first_sentence,
                "source": source,
                "link": link,
                "image_url": it.get("image_url")
            })

            markdown_lines.append(f"- **{title}**: {first_sentence} — [Đọc trên {source}]({link})")

        markdown_lines.append("")
        categories_result.append({
            "id": topic["id"],
            "name": topic_name,
            "icon": icon,
            "color": color,
            "items": topic_items
        })

    markdown_lines.append("---")
    markdown_lines.append("*Bản tin sáng tự động bởi PoDoc AI - Ứng dụng lưu trữ cá nhân độc lập.*")

    return {
        "title": f"Điểm tin sáng - {date_str}",
        "date_str": date_str,
        "greeting": "Chúc bạn một ngày mới tràn đầy năng lượng, làm việc hiệu quả và đón nhận nhiều tin vui!",
        "categories": categories_result,
        "markdown_content": "\n".join(markdown_lines),
        "is_ai_generated": False
    }


async def summarize_news_briefing(
    aggregated_topics: List[Dict[str, Any]],
    user_api_key: Optional[str] = None,
    user_model: Optional[str] = None
) -> Dict[str, Any]:
    """
    Tóm tắt toàn bộ tin tức đã thu thập thành bản tin sáng có cấu trúc.
    Ưu tiên dùng Gemini 3.8 Flash; tự động fallback dự phòng nếu không có API key.
    """
    # Lấy ngày hiện tại theo múi giờ Việt Nam
    now_vn = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7)))
    date_str = format_vietnamese_date(now_vn)

    api_key = (user_api_key or "").strip() or settings.GEMINI_API_KEY
    target_model = (user_model or "").strip() or settings.GEMINI_MODEL or "gemini-3.8-flash"

    if not api_key:
        logger.info("Chưa có GEMINI_API_KEY, sử dụng bộ tóm tắt trích xuất dự phòng.")
        return fallback_summarize(aggregated_topics, date_str)

    # Chuẩn bị dữ liệu đầu vào cho AI
    input_sections = []
    for topic in aggregated_topics:
        items_text = []
        for idx, it in enumerate(topic["items"], 1):
            items_text.append(
                f"  {idx}. [{it['source']}] Tiêu đề: {it['title']}\n"
                f"     Nội dung: {it['description']}\n"
                f"     Link: {it['link']}"
            )
        input_sections.append(
            f"CHUYÊN MỤC: {topic['name']} (ID: {topic['id']})\n" + "\n".join(items_text)
        )

    raw_news_input = "\n\n".join(input_sections)

    prompt = f"""
Bạn là Trợ lý Biên tập viên Tin tức Thông minh của PoDoc Morning Briefing.
Hôm nay là: {date_str}.

Nhiệm vụ của bạn:
1. Đọc danh sách các bài viết mới nhất từ các báo uy tín Việt Nam dưới đây.
2. Viết một câu chào/châm ngôn buổi sáng truyền cảm hứng, ngắn gọn (1-2 câu).
3. Với mỗi chuyên mục, chọn lọc những tin tức nổi bật nhất:
   - Tóm tắt mỗi tin thành 1 gạch đầu dòng súc tích, chỉ nêu điểm cốt lõi (1-2 câu).
   - Giữ nguyên link bài gốc và tên nguồn báo.
4. Trả về kết quả dưới định dạng JSON theo đúng schema sau:

{{
  "title": "Điểm tin sáng - {date_str}",
  "greeting": "Câu chào buổi sáng truyền cảm hứng",
  "categories": [
    {{
      "id": "thoi_su / cong_nghe / the_thao / giai_tri / giao_duc / doi_song",
      "name": "Tên chuyên mục",
      "items": [
        {{
          "title": "Tiêu đề tin ngắn gọn, hấp dẫn",
          "summary": "Tóm tắt 1 câu cốt lõi những gì vừa xảy ra",
          "source": "VnExpress / Tuổi Trẻ",
          "link": "Link gốc"
        }}
      ]
    }}
  ]
}}

DANH SÁCH BÀI BÁO THU THẬP:
{raw_news_input}
"""

    try:
        ai_result = await call_gemini_briefing(prompt, api_key=api_key, model=target_model)

        # Hợp nhất icon, color từ aggregated_topics ban đầu vào kết quả AI
        topic_meta = {t["id"]: t for t in aggregated_topics}
        formatted_categories = []

        for cat in ai_result.get("categories", []):
            cat_id = cat.get("id")
            meta = topic_meta.get(cat_id, {})
            # Tìm link ảnh từ tin gốc tương ứng
            items_with_images = []
            for it in cat.get("items", []):
                matched_item = next((orig for orig in meta.get("items", []) if it.get("title", "").lower() in orig["title"].lower() or orig["title"].lower() in it.get("title", "").lower()), None)
                it["image_url"] = matched_item.get("image_url") if matched_item else None
                items_with_images.append(it)

            formatted_categories.append({
                "id": cat_id or meta.get("id", "general"),
                "name": cat.get("name") or meta.get("name", "Tin tức"),
                "icon": meta.get("icon", "newspaper"),
                "color": meta.get("color", "#2563EB"),
                "items": items_with_images
            })

        # Tạo Markdown chi tiết hoàn chỉnh
        greeting = ai_result.get("greeting") or "Chúc bạn một ngày mới an lành và tràn đầy năng lượng!"
        markdown_lines = [
            f"# ☀️ {ai_result.get('title') or f'ĐIỂM TIN SÁNG - {date_str}'}",
            f"*{date_str} - Tự động cập nhật lúc 07:00 AM bởi Gemini AI*",
            "",
            f"> {greeting}",
            "",
            "---",
            ""
        ]

        for cat in formatted_categories:
            markdown_lines.append(f"## 📌 {cat['name'].upper()}")
            markdown_lines.append("")
            for it in cat["items"]:
                markdown_lines.append(f"- **{it['title']}**: {it['summary']} — [Đọc trên {it['source']}]({it['link']})")
            markdown_lines.append("")

        markdown_lines.append("---")
        markdown_lines.append("*Bản tin sáng tự động tổng hợp bởi PoDoc AI - Lưu trữ và quản lý độc lập.*")

        return {
            "title": ai_result.get("title") or f"Điểm tin sáng - {date_str}",
            "date_str": date_str,
            "greeting": greeting,
            "categories": formatted_categories,
            "markdown_content": "\n".join(markdown_lines),
            "is_ai_generated": True
        }

    except Exception as e:
        logger.error(f"Lỗi khi tóm tắt tin bằng Gemini: {e}. Tự động fallback sang trích xuất tin gốc.")
        return fallback_summarize(aggregated_topics, date_str)

