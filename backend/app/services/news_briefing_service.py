import asyncio
import logging
import os
from typing import Any, Dict, Optional, Tuple
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.article import Article
from app.models.category import Category
from app.services.news_aggregator import aggregate_daily_news
from app.services.news_card_generator import generate_news_infographic_card
from app.services.news_summarizer import summarize_news_briefing
from app.services.thumbnail import ensure_thumbnail

logger = logging.getLogger("podoc.news_briefing_service")

BRIEFING_CATEGORY_NAME = "Điểm tin sáng"
BRIEFING_CATEGORY_COLOR = "#EA580C"
BRIEFING_CATEGORY_ICON = "newspaper"


async def get_or_create_briefing_category(db: AsyncSession) -> Category:
    """Đảm bảo danh mục 'Điểm tin sáng' luôn tồn tại trong hệ thống."""
    stmt = select(Category).where(Category.name == BRIEFING_CATEGORY_NAME)
    res = await db.execute(stmt)
    cat = res.scalar_one_or_none()
    if not cat:
        logger.info(f"Tạo danh mục mới: {BRIEFING_CATEGORY_NAME}")
        cat = Category(
            name=BRIEFING_CATEGORY_NAME,
            icon=BRIEFING_CATEGORY_ICON,
            color=BRIEFING_CATEGORY_COLOR,
            sort_order=8,
            is_system=False
        )
        db.add(cat)
        await db.commit()
        await db.refresh(cat)
    return cat


async def generate_and_save_daily_briefing(
    user_api_key: Optional[str] = None,
    user_model: Optional[str] = None,
    max_items_per_topic: int = 3
) -> Dict[str, Any]:
    """
    Quy trình hoàn chỉnh:
    1. Thu thập tin tức từ các báo uy tín qua RSS.
    2. Tóm tắt AI (Gemini 3.8 Flash, có fallback).
    3. Vẽ ảnh Infographic độ nét cao (1080px).
    4. Lưu vào Database thành 1 Article trong danh mục 'Điểm tin sáng'.
    5. Tạo thumbnail để hiển thị trong Thư viện và danh sách.
    """
    logger.info("Bắt đầu quy trình tạo Bản tin sáng PoDoc...")

    # 1. Thu thập tin tức
    topics = await aggregate_daily_news(max_items_per_topic=max_items_per_topic)
    if not topics:
        raise RuntimeError("Không thể thu thập tin tức từ các nguồn RSS.")

    # 2. Tóm tắt AI
    briefing = await summarize_news_briefing(
        aggregated_topics=topics,
        user_api_key=user_api_key,
        user_model=user_model
    )

    # 3. Vẽ ảnh Infographic
    stored_filename, disk_path = generate_news_infographic_card(briefing)

    # 4. Lưu vào cơ sở dữ liệu
    async with AsyncSessionLocal() as db:
        category = await get_or_create_briefing_category(db)

        # Tạo article mới
        title = briefing.get("title") or "Điểm tin sáng PoDoc"
        greeting = briefing.get("greeting") or "Bản tin tổng hợp sáng tự động."
        markdown_body = briefing.get("markdown_content") or ""

        new_article = Article(
            title=title,
            source_type="FILE",
            file_name=f"{title}.jpg",
            file_path=stored_filename,
            mime_type="image/jpeg",
            content_markdown=markdown_body,
            excerpt=greeting,
            category_id=category.id,
            reading_minutes=2,
            is_favorite=False,
            read_progress=0.0
        )
        db.add(new_article)
        await db.commit()
        await db.refresh(new_article)

        # Cập nhật markdown chèn ảnh Infographic ở đầu bài viết
        final_markdown = f"![{title}](/api/articles/{new_article.id}/file)\n\n{markdown_body}"
        new_article.content_markdown = final_markdown
        new_article.image_url = f"/api/articles/{new_article.id}/thumbnail"
        await db.commit()
        await db.refresh(new_article)

        # Sinh thumbnail nhanh
        try:
            await asyncio.to_thread(
                ensure_thumbnail,
                stored_filename,
                settings.FILES_STORAGE_DIR,
                "image/jpeg",
                f"{title}.jpg"
            )
        except Exception as e:
            logger.warning(f"Lỗi tạo thumbnail cho bản tin: {e}")

        logger.info(f"Đã xuất bản thành công bản tin sáng ID={new_article.id} vào Danh mục '{category.name}'")

        return {
            "article_id": new_article.id,
            "title": new_article.title,
            "category_id": category.id,
            "category_name": category.name,
            "file_path": new_article.file_path,
            "image_url": new_article.image_url,
            "created_at": new_article.created_at.isoformat() if new_article.created_at else None,
            "briefing": briefing
        }

