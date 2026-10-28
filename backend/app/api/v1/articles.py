import os
import uuid
from typing import Annotated, List, Optional
from fastapi import APIRouter, Depends, Form, HTTPException, Query, UploadFile, File, status
from fastapi.responses import FileResponse
from sqlalchemy import func, or_, select, text, not_
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
from app.services.extractors.registry import extractor_registry
from app.services.thumbnail import ensure_thumbnail
from app.services.url_utils import normalize_url

router = APIRouter(prefix="/articles", tags=["Articles"])


@router.get("", response_model=List[ArticleListResponse])
async def list_articles(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    q: Optional[str] = Query(None, description="Từ khoá tìm kiếm toàn văn tiếng Việt không dấu"),
    category_id: Optional[int] = Query(None, description="Lọc theo ID danh mục"),
    source_type: Optional[str] = Query(None, description="Lọc theo nguồn (URL hoặc FILE)"),
    content_type: Optional[str] = Query(None, description="Lọc theo loại: all, article, image, video, file"),
    favorite: Optional[bool] = Query(None, description="Lọc bài viết yêu thích"),
    sort: Optional[str] = Query("newest", description="Sắp xếp: newest, oldest, reading_time, title"),
    page: int = Query(1, ge=1, description="Số trang"),
    page_size: int = Query(20, ge=1, le=150, description="Số bài mỗi trang"),
):
    """
    Lấy danh sách bài viết (chỉ trả về excerpt, không trả full content để tối ưu băng thông).
    Hỗ trợ tìm kiếm toàn văn tiếng Việt không dấu (gõ 'suc khoe' tìm ra 'sức khoẻ').
    Hỗ trợ lọc theo loại nội dung (bài viết, hình ảnh, video, tệp tin).
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

    if content_type and content_type.lower() != "all":
        ct = content_type.lower().strip()
        image_exts = [".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"]
        video_exts = [".mp4", ".mkv", ".mov", ".webm", ".avi", ".flv", ".3gp"]

        if ct in ("article", "url", "link", "web"):
            stmt = stmt.where(Article.source_type == "URL")
        elif ct in ("image", "photo", "anh"):
            stmt = stmt.where(
                Article.source_type == "FILE",
                or_(
                    Article.mime_type.ilike("image/%"),
                    or_(*[Article.file_name.ilike(f"%{ext}") for ext in image_exts])
                ),
            )
        elif ct in ("video", "clip"):
            stmt = stmt.where(
                Article.source_type == "FILE",
                or_(
                    Article.mime_type.ilike("video/%"),
                    or_(*[Article.file_name.ilike(f"%{ext}") for ext in video_exts])
                ),
            )
        elif ct in ("file", "document", "doc", "tep"):
            stmt = stmt.where(
                Article.source_type == "FILE",
                not_(
                    or_(
                        Article.mime_type.ilike("image/%"),
                        or_(*[Article.file_name.ilike(f"%{ext}") for ext in image_exts])
                    )
                ),
                not_(
                    or_(
                        Article.mime_type.ilike("video/%"),
                        or_(*[Article.file_name.ilike(f"%{ext}") for ext in video_exts])
                    )
                ),
            )

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
        resolved_img = art.image_url
        lower_name = (art.file_name or "").lower()
        lower_path = (art.file_path or "").lower()
        is_image = (
            (art.mime_type and art.mime_type.startswith("image/"))
            or any(lower_name.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
            or any(lower_path.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
        )
        is_video = (
            (art.mime_type and art.mime_type.startswith("video/"))
            or any(lower_name.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
            or any(lower_path.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
        )
        # Nếu là tệp hình ảnh hoặc video đính kèm, luôn phân giải về endpoint thumbnail tối ưu băng thông
        if art.file_path and (is_image or is_video):
            resolved_img = f"/api/articles/{art.id}/thumbnail"
        elif not resolved_img and art.content_markdown:
            import re
            m = re.search(r'!\[.*?\]\((https?://[^\s\)]+)\)', art.content_markdown)
            if m:
                resolved_img = m.group(1)

        response_items.append(
            ArticleListResponse(
                id=art.id,
                title=art.title,
                source_type=art.source_type,
                source_url=art.source_url,
                file_name=art.file_name,
                file_path=art.file_path,
                mime_type=art.mime_type,
                excerpt=art.excerpt,
                author=art.author,
                site_name=art.site_name,
                image_url=resolved_img,
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


@router.post("/upload", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
@router.post("/upload/", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def upload_article_file(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    file: UploadFile = File(...),
    thumbnail: Optional[UploadFile] = File(None),
    category_id: Optional[int] = Form(None),
    title: Optional[str] = Form(None),
):
    """
    Tải lên và lưu trực tiếp tệp tin (Video, Ảnh, PDF, DOCX, DOC, HTML, TXT, MD...)
    vào thư viện để mở xem sau mà KHÔNG qua bước trích xuất text.
    Giới hạn dung lượng:
    - Ảnh: 5 MB
    - Video: 10 MB
    - Tệp tin khác: 10 MB
    """
    orig_name = file.filename or "uploaded_file"
    orig_ext = os.path.splitext(orig_name)[1].lower()

    # 1. Đọc nội dung file & xác định loại để áp dụng hạn mức dung lượng
    file_bytes = await file.read()

    # Nhận diện MIME type thực tế và phần mở rộng chuẩn xác
    real_mime = extractor_registry.detect_real_mime_type(file_bytes, orig_name)
    mime_type = real_mime or file.content_type or "application/octet-stream"

    # Hạn mức dung lượng theo yêu cầu:
    # - Ảnh: 20 MB
    # - Video: 50 MB
    # - File khác: 50 MB
    is_image = mime_type.startswith("image/") or orig_ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")
    is_video = mime_type.startswith("video/") or orig_ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts")

    if is_image:
        max_bytes = 20 * 1024 * 1024
        type_str = "hình ảnh (tối đa 20 MB)"
    elif is_video:
        max_bytes = 50 * 1024 * 1024
        type_str = "video (tối đa 50 MB)"
    else:
        max_bytes = 50 * 1024 * 1024
        type_str = "tệp tin (tối đa 50 MB)"

    if len(file_bytes) > max_bytes:
        file_mb = len(file_bytes) / (1024 * 1024)
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Dung lượng {type_str} vượt quá giới hạn cho phép. Tệp của bạn: {file_mb:.1f} MB",
        )

    mime_to_ext = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "image/gif": ".gif",
        "image/svg+xml": ".svg",
        "video/mp4": ".mp4",
        "video/webm": ".webm",
        "video/x-matroska": ".mkv",
        "video/quicktime": ".mov",
        "video/x-msvideo": ".avi",
        "video/3gpp": ".3gp",
        "application/pdf": ".pdf",
        "text/plain": ".txt",
        "text/markdown": ".md",
        "text/html": ".html",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
        "application/msword": ".doc",
    }

    if not orig_ext or orig_ext not in mime_to_ext.values():
        deduced_ext = mime_to_ext.get(mime_type, "")
        if deduced_ext:
            orig_ext = deduced_ext
            if not orig_name.lower().endswith(deduced_ext):
                orig_name = f"{orig_name}{deduced_ext}"

    # 3. Xác định Danh mục (nếu không truyền thì lấy danh mục đầu tiên)
    cat = None
    if category_id is not None:
        cat_stmt = select(Category).where(Category.id == category_id)
        cat_res = await db.execute(cat_stmt)
        cat = cat_res.scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")
    
    if not cat:
        first_cat_stmt = select(Category).order_by(Category.sort_order.asc(), Category.id.asc()).limit(1)
        first_cat_res = await db.execute(first_cat_stmt)
        cat = first_cat_res.scalar_one_or_none()
        if not cat:
            cat = Category(name="Tài liệu", icon="📁", color="#6366f1", is_system=True)
            db.add(cat)
            await db.flush()

    # 4. Lưu nguyên vẹn file vật lý vào thư mục storage
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    stored_filename = f"{uuid.uuid4().hex}{orig_ext}"
    disk_path = os.path.join(settings.FILES_STORAGE_DIR, stored_filename)

    with open(disk_path, "wb") as f_out:
        f_out.write(file_bytes)

    final_title = title.strip() if (title and title.strip()) else os.path.splitext(orig_name)[0].strip() or orig_name

    # 5. Đối với văn bản text/markdown, đọc trước nội dung để hỗ trợ tìm kiếm toàn văn
    content_text = ""
    if orig_ext in (".txt", ".md", ".mdf"):
        try:
            content_text = file_bytes.decode("utf-8", errors="replace")
        except Exception:
            content_text = ""
    else:
        file_size_mb = len(file_bytes) / (1024 * 1024)
        if file_size_mb >= 1.0:
            size_str = f"{file_size_mb:.1f} MB"
        else:
            size_str = f"{len(file_bytes) / 1024:.1f} KB"
        if is_video:
            content_text = f"Tệp video đính kèm: {orig_name} ({size_str})"
        elif is_image:
            content_text = f"Tệp hình ảnh đính kèm: {orig_name} ({size_str})"
        else:
            content_text = f"Tệp tin đính kèm: {orig_name} ({size_str})"

    excerpt = generate_excerpt(content_text) if content_text else f"Tệp tin đính kèm: {orig_name}"
    reading_mins = max(1, len(content_text.split()) // 200) if content_text else 1

    new_article = Article(
        title=final_title,
        source_type="FILE",
        file_name=orig_name,
        file_path=stored_filename,
        mime_type=mime_type,
        content_markdown=content_text,
        excerpt=excerpt,
        category_id=cat.id,
        reading_minutes=reading_mins,
    )

    db.add(new_article)
    await db.flush()

    # 6. Nếu là ảnh hoặc video, lưu thumbnail hoặc tự động sinh thumbnail từ frame video / resize ảnh
    if is_image or is_video:
        thumb_filename = f"thumb_{os.path.splitext(stored_filename)[0]}.jpg"
        thumb_disk_path = os.path.join(settings.FILES_STORAGE_DIR, thumb_filename)
        saved_thumb = False
        if thumbnail is not None:
            try:
                thumb_bytes = await thumbnail.read()
                if thumb_bytes and len(thumb_bytes) > 0:
                    with open(thumb_disk_path, "wb") as f_thumb:
                        f_thumb.write(thumb_bytes)
                    saved_thumb = True
            except Exception:
                saved_thumb = False
        if not saved_thumb:
            ensure_thumbnail(stored_filename, settings.FILES_STORAGE_DIR, mime_type, orig_name)

        new_article.image_url = f"/api/articles/{new_article.id}/thumbnail"

    await db.commit()
    await db.refresh(new_article)

    return await get_article(new_article.id, current_user, db)


@router.get("/{id}", response_model=ArticleDetailResponse)
async def get_article(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Lấy chi tiết toàn bộ nội dung bài viết và thông tin file đính kèm."""
    stmt = select(Article).options(selectinload(Article.category)).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    resolved_img = art.image_url
    lower_name = (art.file_name or "").lower()
    lower_path = (art.file_path or "").lower()
    is_image = (
        (art.mime_type and art.mime_type.startswith("image/"))
        or any(lower_name.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
        or any(lower_path.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
    )
    is_video = (
        (art.mime_type and art.mime_type.startswith("video/"))
        or any(lower_name.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
        or any(lower_path.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
    )
    if art.file_path and (is_image or is_video):
        resolved_img = f"/api/articles/{art.id}/thumbnail"
    elif not resolved_img and art.content_markdown:
        import re
        m = re.search(r'!\[.*?\]\((https?://[^\s\)]+)\)', art.content_markdown)
        if m:
            resolved_img = m.group(1)

    return ArticleDetailResponse(
        id=art.id,
        title=art.title,
        source_type=art.source_type,
        source_url=art.source_url,
        normalized_url=art.normalized_url,
        file_name=art.file_name,
        file_path=art.file_path,
        mime_type=art.mime_type,
        content_markdown=art.content_markdown,
        excerpt=art.excerpt,
        author=art.author,
        site_name=art.site_name,
        image_url=resolved_img,
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
        file_path=data.file_path,
        mime_type=data.mime_type,
        content_markdown=data.content_markdown or "",
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

    # Xoá file gốc và thumbnail nếu có
    if art.file_path:
        disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
        if os.path.exists(disk_path):
            try:
                os.remove(disk_path)
            except OSError:
                pass
        thumb_filename = f"thumb_{os.path.splitext(art.file_path)[0]}.jpg"
        thumb_path = os.path.join(settings.FILES_STORAGE_DIR, thumb_filename)
        if os.path.exists(thumb_path):
            try:
                os.remove(thumb_path)
            except OSError:
                pass

    await db.delete(art)
    await db.commit()


@router.get("/{id}/thumbnail")
async def get_article_thumbnail(
    id: int,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Trả về ảnh thumbnail nhỏ gọn cho bài viết (dùng hiển thị lưới và thẻ bài viết).
    Tự động trích xuất frame từ video hoặc resize ảnh nếu chưa có thumbnail sẵn.
    """
    stmt = select(Article).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    if not art.file_path:
        if art.image_url and art.image_url.startswith("http"):
            from fastapi.responses import RedirectResponse
            return RedirectResponse(url=art.image_url)
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Bài viết không có hình ảnh hoặc video")

    # 1. Thử lấy thumbnail có sẵn hoặc tạo mới
    thumb_name = ensure_thumbnail(
        art.file_path,
        settings.FILES_STORAGE_DIR,
        art.mime_type,
        art.file_name,
    )

    if thumb_name:
        thumb_path = os.path.join(settings.FILES_STORAGE_DIR, thumb_name)
        if os.path.exists(thumb_path) and os.path.getsize(thumb_path) > 0:
            return FileResponse(
                path=thumb_path,
                media_type="image/jpeg",
                filename=f"thumb_{art.id}.jpg",
                content_disposition_type="inline",
            )

    # 2. Fallback: Nếu là ảnh và không tạo được thumbnail thì trả về ảnh gốc inline
    raw_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
    if os.path.exists(raw_path):
        lower_name = (art.file_name or "").lower()
        lower_path = (art.file_path or "").lower()
        is_image = (
            (art.mime_type and art.mime_type.startswith("image/"))
            or any(lower_name.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
            or any(lower_path.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
        )
        if is_image:
            return FileResponse(
                path=raw_path,
                media_type=art.mime_type or "image/jpeg",
                filename=art.file_name or f"thumb_{art.id}.jpg",
                content_disposition_type="inline",
            )

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không thể tạo hoặc tải thumbnail")


@router.get("/{id}/file")
async def download_article_file(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    inline: bool = Query(False, description="Hiển thị trực tiếp (inline) hay tải về (attachment)"),
):
    """Tải lại file gốc hoặc xem trực tiếp trên app."""
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
        content_disposition_type="inline" if inline else "attachment",
    )


@router.get("/{id}/preview-html")
async def preview_article_html(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Trả về nội dung HTML để mở và xem trực tiếp trên app:
    - Nếu là DOCX: convert sang HTML bằng mammoth
    - Nếu là HTML: trả về HTML nguyên gốc
    - Nếu là TXT / MD / MDF: trả về HTML bọc sẵn style typography
    """
    from fastapi.responses import HTMLResponse

    stmt = select(Article).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết")

    if not art.file_path:
        styled_html = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>body{{font-family:system-ui,-apple-system,sans-serif;line-height:1.7;padding:16px;color:#1e293b;max-width:800px;margin:auto}}</style></head><body><h1>{art.title}</h1><div>{art.content_markdown}</div></body></html>"""
        return HTMLResponse(content=styled_html)

    disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
    if not os.path.exists(disk_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File vật lý không còn tồn tại trên server")

    ext = os.path.splitext(art.file_name or art.file_path)[1].lower()

    if ext in (".docx", ".doc"):
        try:
            import mammoth
            with open(disk_path, "rb") as docx_file:
                result_html = mammoth.convert_to_html(docx_file)
                html_body = result_html.value
            styled_html = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>body{{font-family:system-ui,-apple-system,sans-serif;line-height:1.7;padding:20px;color:#1e293b;max-width:850px;margin:auto;word-wrap:break-word}}table{{border-collapse:collapse;width:100%;margin:16px 0}}th,td{{border:1px solid #cbd5e1;padding:8px 12px;text-align:left}}img{{max-width:100%;height:auto;border-radius:8px}}h1,h2,h3{{color:#0f172a}}p{{margin-bottom:12px}}</style></head><body>{html_body}</body></html>"""
            return HTMLResponse(content=styled_html)
        except Exception as e:
            fallback = f"<!DOCTYPE html><html><body style='padding:20px;font-family:sans-serif;'><h3>Lỗi hiển thị Word: {str(e)}</h3><p>Vui lòng tải về hoặc mở bằng ứng dụng chuyên dụng.</p></body></html>"
            return HTMLResponse(content=fallback)

    elif ext in (".html", ".htm"):
        with open(disk_path, "r", encoding="utf-8", errors="replace") as f:
            return HTMLResponse(content=f.read())

    elif ext in (".txt", ".md", ".mdf"):
        with open(disk_path, "r", encoding="utf-8", errors="replace") as f:
            text_content = f.read()
        styled_html = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>body{{font-family:system-ui,-apple-system,sans-serif;line-height:1.7;padding:20px;color:#1e293b;max-width:850px;margin:auto;white-space:pre-wrap;word-wrap:break-word}}</style></head><body>{text_content}</body></html>"""
        return HTMLResponse(content=styled_html)

    return FileResponse(path=disk_path, filename=art.file_name, media_type=art.mime_type or "application/octet-stream")

