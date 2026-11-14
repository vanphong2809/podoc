import logging
from typing import Annotated, Any, Dict, Optional
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.config import settings
from app.core.database import get_db
from app.models.article import Article
from app.models.category import Category
from app.models.user import User
from app.schemas.news import (
    GenerateBriefingRequest,
    NewsBriefingResponse,
    NewsSettings,
)
from app.services.news_briefing_service import (
    BRIEFING_CATEGORY_NAME,
    generate_and_save_daily_briefing,
)

logger = logging.getLogger("podoc.api.news")

router = APIRouter(prefix="/news", tags=["News Briefing"])


@router.post("/briefing/generate", response_model=NewsBriefingResponse)
async def generate_briefing(
    body: Optional[GenerateBriefingRequest] = None,
    current_user: Annotated[User, Depends(get_current_user)] = None,
):
    """
    Kích hoạt tạo Bản tin sáng ngay lập tức:
    Thu thập tin tức từ VnExpress, Tuổi Trẻ -> Gemini tóm tắt -> Vẽ Infographic Card -> Lưu vào Thư viện.
    """
    user_api_key = body.gemini_api_key if body else None
    user_model = body.gemini_model if body else None
    max_items = body.max_items_per_topic if body else 3

    try:
        result = await generate_and_save_daily_briefing(
            user_api_key=user_api_key,
            user_model=user_model,
            max_items_per_topic=max_items,
        )
        return NewsBriefingResponse(**result)
    except Exception as e:
        logger.error(f"Lỗi khi tạo bản tin sáng: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Không thể tạo bản tin sáng: {str(e)}",
        )


@router.get("/briefing/latest", response_model=Optional[Dict[str, Any]])
async def get_latest_briefing(
    current_user: Annotated[User, Depends(get_current_user)] = None,
    db: Annotated[AsyncSession, Depends(get_db)] = None,
):
    """Lấy bài viết Bản tin sáng mới nhất đã được lưu trong thư viện."""
    stmt = (
        select(Article)
        .join(Category)
        .where(Category.name == BRIEFING_CATEGORY_NAME)
        .order_by(Article.created_at.desc())
        .limit(1)
    )
    res = await db.execute(stmt)
    article = res.scalar_one_or_none()

    if not article:
        return None

    return {
        "article_id": article.id,
        "title": article.title,
        "category_id": article.category_id,
        "category_name": BRIEFING_CATEGORY_NAME,
        "file_path": article.file_path,
        "image_url": article.image_url or f"/api/articles/{article.id}/file",
        "content_markdown": article.content_markdown,
        "excerpt": article.excerpt,
        "created_at": article.created_at.isoformat() if article.created_at else None,
    }


@router.get("/settings", response_model=NewsSettings)
async def get_news_settings(
    current_user: Annotated[User, Depends(get_current_user)] = None,
):
    """Lấy cấu hình tính năng Điểm tin sáng."""
    has_key = bool(settings.GEMINI_API_KEY and settings.GEMINI_API_KEY.strip())
    return NewsSettings(
        enabled=True,
        scheduled_time="07:00",
        gemini_model=settings.GEMINI_MODEL or "gemini-3.8-flash",
        has_custom_api_key=has_key,
    )

