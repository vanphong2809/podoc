import os
from typing import Annotated, List, Optional
from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import FileResponse
from sqlalchemy import func, or_, select, text
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.api.deps import get_current_user
from app.core.config import settings
from app.core.database import get_db
from app.models.article import Article
from app.models.category import Category
from app.models.user import User
from app.schemas.article import (
    ArticleCreate,
    ArticleDetailResponse,
    ArticleListResponse,
    ArticleUpdate,
)
from app.services.extractors.base import generate_excerpt
from app.services.url_utils import normalize_url

router = APIRouter(prefix="/articles", tags=["Articles"])


@router.get("", response_model=List[ArticleListResponse])
async def list_articles(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    q: Optional[str] = Query(None, description="Từ khoá tìm kiếm toàn văn tiếng Việt không dấu"),
    category_id: Optional[int] = Query(None, description="Lọc theo ID danh mục"),
    source_type: Optional[str] = Query(None, description="Lọc theo nguồn (URL hoặc FILE)"),
    favorite: Optional[bool] = Query(None, description="Lọc bài viết yêu thích"),
    sort: Optional[str] = Query("newest", description="Sắp xếp: newest, oldest, reading_time, title"),
    page: int = Query(1, ge=1, description="Số trang"),
    page_size: int = Query(20, ge=1, le=100, description="Số bài mỗi trang"),
):
    """
    Lấy danh sách bài viết (chỉ trả về excerpt, không trả full content để tối ưu băng thông).
    Hỗ trợ tìm kiếm toàn văn tiếng Việt không dấu (gõ 'suc khoe' tìm ra 'sức khoẻ').
    """
    stmt = select(Article).options(selectinload(Article.category))

    # 1. Tìm kiếm toàn văn
    if q and q.strip():
        search_term = q.strip()
        words = search_term.split()

        bind = db.get_bind()
        if bind.dialect.name == "postgresql":
            # PostgreSQL: Dùng Full-text search tsvector với GIN index kết hợp unaccent
            formatted_words = [f"{w}:*" for w in words if w]
            tsquery_str = " & ".join(formatted_words) if formatted_words else search_term
            search_filter = or_(
                text("articles.search_vector @@ to_tsquery('simple', unaccent(:tsquery))").bindparams(tsquery=tsquery_str),
                text("unaccent(articles.title) ILIKE unaccent(:like_term)").bindparams(like_term=f"%{search_term}%"),
            )
            stmt = stmt.where(search_filter)
        else:
            # SQLite fallback trong môi trường test (có hàm unaccent đã đăng ký)
            conds = [
                func.unaccent(Article.title).ilike(f"%{w}%")
                for w in words
            ]
            from sqlalchemy import and_
            stmt = stmt.where(and_(*conds))

    # 2. Bộ lọc
    if category_id is not None:
        stmt = stmt.where(Article.category_id == category_id)

    if source_type:
        stmt = stmt.where(Article.source_type == source_type.upper())

    if favorite is not None:
        stmt = stmt.where(Article.is_favorite == favorite)

    # 3. Sắp xếp
    if sort == "oldest":
        stmt = stmt.order_by(Article.created_at.asc())
    elif sort == "reading_time":
        stmt = stmt.order_by(Article.reading_minutes.asc(), Article.created_at.desc())
    elif sort == "title":
        stmt = stmt.order_by(Article.title.asc())
    else:  # newest
        stmt = stmt.order_by(Article.created_at.desc())

    # 4. Phân trang
    offset = (page - 1) * page_size
    stmt = stmt.offset(offset).limit(page_size)

    result = await db.execute(stmt)
    articles = result.scalars().all()

    response_items = []
    for art in articles:
        response_items.append(
            ArticleListResponse(
                id=art.id,
                title=art.title,
                source_type=art.source_type,
                source_url=art.source_url,
                excerpt=art.excerpt,
                author=art.author,
                site_name=art.site_name,
                image_url=art.image_url,
                reading_minutes=art.reading_minutes,
                category_id=art.category_id,
                category_name=art.category.name if art.category else None,
                is_favorite=art.is_favorite,
                read_progress=art.read_progress,
                created_at=art.created_at,
                updated_at=art.updated_at,
            )
        )

    return response_items


@router.get("/{id}", response_model=ArticleDetailResponse)
async def get_article(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Lấy chi tiết toàn bộ nội dung bài viết và Markdown để đọc."""
    stmt = select(Article).options(selectinload(Article.category)).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    return ArticleDetailResponse(
        id=art.id,
        title=art.title,
        source_type=art.source_type,
        source_url=art.source_url,
        normalized_url=art.normalized_url,
        file_name=art.file_name,
        mime_type=art.mime_type,
        content_markdown=art.content_markdown,
        excerpt=art.excerpt,
        author=art.author,
        site_name=art.site_name,
        image_url=art.image_url,
        language=art.language,
        reading_minutes=art.reading_minutes,
        category_id=art.category_id,
        category_name=art.category.name if art.category else None,
        is_favorite=art.is_favorite,
        read_progress=art.read_progress,
        created_at=art.created_at,
        updated_at=art.updated_at,
    )


@router.post("", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def create_article(
    data: ArticleCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Lưu bài viết vào cơ sở dữ liệu sau khi người dùng xem trước và chọn danh mục."""
    cat_stmt = select(Category).where(Category.id == data.category_id)
    cat_res = await db.execute(cat_stmt)
    cat = cat_res.scalar_one_or_none()
    if not cat:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")

    norm_url = normalize_url(data.source_url) if data.source_url else data.normalized_url
    excerpt = data.excerpt or generate_excerpt(data.content_markdown)

    new_article = Article(
        title=data.title.strip(),
        source_type=data.source_type,
        source_url=data.source_url,
        normalized_url=norm_url,
        file_name=data.file_name,
        mime_type=data.mime_type,
        content_markdown=data.content_markdown,
        excerpt=excerpt,
        author=data.author,
        site_name=data.site_name,
        image_url=data.image_url,
        language=data.language,
        reading_minutes=data.reading_minutes,
        category_id=data.category_id,
        is_favorite=data.is_favorite,
        read_progress=data.read_progress,
    )

    db.add(new_article)
    await db.commit()
    await db.refresh(new_article)

    return await get_article(new_article.id, current_user, db)


@router.patch("/{id}", response_model=ArticleDetailResponse)
async def update_article(
    id: int,
    data: ArticleUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Cập nhật thông tin bài viết: tiêu đề, danh mục, yêu thích, tiến độ đọc read_progress."""
    stmt = select(Article).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    if data.title is not None and data.title.strip():
        art.title = data.title.strip()

    if data.category_id is not None:
        cat_stmt = select(Category).where(Category.id == data.category_id)
        cat_res = await db.execute(cat_stmt)
        if not cat_res.scalar_one_or_none():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")
        art.category_id = data.category_id

    if data.is_favorite is not None:
        art.is_favorite = data.is_favorite

    if data.read_progress is not None:
        art.read_progress = data.read_progress

    await db.commit()
    return await get_article(id, current_user, db)


@router.delete("/{id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_article(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Xoá bài viết và xoá file vật lý đính kèm nếu có."""
    stmt = select(Article).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    # Xoá file gốc nếu có
    if art.file_path:
        disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
        if os.path.exists(disk_path):
            try:
                os.remove(disk_path)
            except OSError:
                pass

    await db.delete(art)
    await db.commit()


@router.get("/{id}/file")
async def download_article_file(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Tải lại file gốc của bài viết (Phase 2)."""
    stmt = select(Article).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    if not art.file_path:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Bài viết này không có file gốc đính kèm")

    disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
    if not os.path.exists(disk_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File vật lý không còn tồn tại trên server")

    download_name = art.file_name or f"document_{art.id}"
    media_type = art.mime_type or "application/octet-stream"

    return FileResponse(
        path=disk_path,
        filename=download_name,
        media_type=media_type,
    )
