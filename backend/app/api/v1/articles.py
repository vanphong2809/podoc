import asyncio
import logging
import os
import shutil
import tempfile
import urllib.parse
import uuid
from typing import Annotated, List, Optional
from fastapi import APIRouter, Depends, Form, HTTPException, Query, UploadFile, File, status
from fastapi.responses import FileResponse
from sqlalchemy import func, or_, select, text, not_, update
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
    ArticleFileInfoResponse,
    ArticleListResponse,
    ArticleUpdate,
    BatchDeleteRequest,
    BatchUpdateCategoryRequest,
    CategoryStorageStat,
    StorageItemStat,
    StorageStatsResponse,
)
from app.services.extractors.base import generate_excerpt
from app.services.extractors.registry import extractor_registry
from app.services.thumbnail import ensure_thumbnail
from app.services.url_utils import normalize_url
from app.services.video_transcoder import is_video_file, optimized_path, remove_video_derivatives

logger = logging.getLogger(__name__)

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
    page_size: int = Query(20, ge=1, le=500, description="Số bài mỗi trang"),
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
        video_exts = [".mp4", ".mkv", ".mov", ".webm", ".avi", ".flv", ".3gp", ".ts"]
        audio_exts = [".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus"]

        image_filter = or_(
            Article.mime_type.ilike("image/%"),
            or_(*[Article.file_name.ilike(f"%{ext}") for ext in image_exts]),
            or_(*[Article.file_path.ilike(f"%{ext}") for ext in image_exts]),
        )
        video_filter = or_(
            Article.mime_type.ilike("video/%"),
            or_(*[Article.file_name.ilike(f"%{ext}") for ext in video_exts]),
            or_(*[Article.file_path.ilike(f"%{ext}") for ext in video_exts]),
        )
        audio_filter = or_(
            Article.mime_type.ilike("audio/%"),
            or_(*[Article.file_name.ilike(f"%{ext}") for ext in audio_exts]),
            or_(*[Article.file_path.ilike(f"%{ext}") for ext in audio_exts]),
        )

        if ct in ("article", "url", "link", "web"):
            stmt = stmt.where(
                or_(
                    Article.source_type == "URL",
                    Article.source_type.is_(None),
                    Article.source_type != "FILE"
                )
            )
        elif ct in ("image", "photo", "anh"):
            stmt = stmt.where(image_filter)
        elif ct in ("video", "clip"):
            stmt = stmt.where(video_filter)
        elif ct in ("audio", "amthanh", "music", "sound"):
            stmt = stmt.where(audio_filter)
        elif ct in ("file", "document", "doc", "tep"):
            stmt = stmt.where(
                not_(image_filter),
                not_(video_filter),
                not_(audio_filter)
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
                video_status=art.video_status,
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


def format_byte_size(size_bytes: int) -> str:
    """Định dạng kích thước byte sang đơn vị đọc thân thiện (B, KB, MB, GB)."""
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 * 1024:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.1f} MB"
    else:
        return f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"


@router.get("/storage-stats", response_model=StorageStatsResponse)
async def get_storage_stats(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    category_id: Optional[int] = Query(None, description="Lọc theo id danh mục nếu cần"),
):
    """
    Thống kê tổng kích thước và số lượng tệp tin theo các danh mục nội dung:
    - Hình ảnh (Image)
    - Video
    - Âm thanh (Audio)
    - Tệp tin (File)
    Đồng thời phân tích chi tiết dung lượng của từng danh mục người dùng.
    """
    image_exts = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp")
    video_exts = (".mp4", ".mkv", ".mov", ".webm", ".avi", ".flv", ".3gp", ".ts")
    audio_exts = (".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus")

    stmt = select(Article).options(selectinload(Article.category))
    if category_id is not None and category_id > 0:
        stmt = stmt.where(Article.category_id == category_id)

    result = await db.execute(stmt)
    articles = result.scalars().all()

    type_counts = {"image": 0, "video": 0, "audio": 0, "file": 0}
    type_bytes = {"image": 0, "video": 0, "audio": 0, "file": 0}

    cat_counts: dict[int, int] = {}
    cat_bytes: dict[int, int] = {}
    cat_names: dict[int, str] = {}

    total_bytes = 0
    total_files = 0

    for art in articles:
        # Xác định kích thước tệp vật lý trên đĩa
        art_size = 0
        has_file = False
        if art.file_path:
            disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
            if os.path.isfile(disk_path):
                art_size = os.path.getsize(disk_path)
                has_file = True
                optimized = optimized_path(art.file_path)
                if optimized.is_file():
                    art_size += optimized.stat().st_size

        if not has_file and art.source_type != "FILE":
            # Bỏ qua bài viết web không có file đính kèm
            continue

        lower_name = (art.file_name or "").lower()
        lower_path = (art.file_path or "").lower()
        mime = (art.mime_type or "").lower()

        is_image = mime.startswith("image/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in image_exts)
        is_video = mime.startswith("video/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in video_exts)
        is_audio = mime.startswith("audio/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in audio_exts)

        if is_image:
            ctype = "image"
        elif is_video:
            ctype = "video"
        elif is_audio:
            ctype = "audio"
        else:
            ctype = "file"

        type_counts[ctype] += 1
        type_bytes[ctype] += art_size
        total_bytes += art_size
        total_files += 1

        cid = art.category_id
        cname = art.category.name if art.category else "Chưa phân loại"
        cat_counts[cid] = cat_counts.get(cid, 0) + 1
        cat_bytes[cid] = cat_bytes.get(cid, 0) + art_size
        cat_names[cid] = cname

    type_labels = {
        "image": "Hình ảnh",
        "video": "Video",
        "audio": "Âm thanh",
        "file": "Tệp tin"
    }

    items = [
        StorageItemStat(
            content_type=ct,
            label=type_labels[ct],
            count=type_counts[ct],
            total_bytes=type_bytes[ct],
            formatted_size=format_byte_size(type_bytes[ct])
        )
        for ct in ["image", "video", "audio", "file"]
    ]

    by_category = [
        CategoryStorageStat(
            category_id=cid,
            category_name=cat_names[cid],
            count=cat_counts[cid],
            total_bytes=cat_bytes[cid],
            formatted_size=format_byte_size(cat_bytes[cid])
        )
        for cid in sorted(cat_bytes.keys(), key=lambda x: cat_bytes[x], reverse=True)
    ]

    return StorageStatsResponse(
        total_bytes=total_bytes,
        formatted_total_size=format_byte_size(total_bytes),
        total_files=total_files,
        items=items,
        by_category=by_category
    )


@router.post("/upload", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
@router.post("/upload/", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def upload_article_file(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    file: UploadFile = File(...),
    thumbnail: Optional[UploadFile] = File(None),
    category_id: Optional[int] = Form(None),
    title: Optional[str] = Form(None),
    file_name: Optional[str] = Form(None),
):
    """
    Tải lên và lưu trực tiếp tệp tin (Video, Ảnh, PDF, DOCX, DOC, HTML, TXT, MD...)
    vào thư viện để mở xem sau mà KHÔNG qua bước trích xuất text.
    Không giới hạn dung lượng tệp tin.
    """
    raw_orig_name = (file_name.strip() if file_name and file_name.strip() else None) or file.filename or "uploaded_file"
    try:
        orig_name = urllib.parse.unquote(raw_orig_name)
    except Exception:
        orig_name = raw_orig_name
    orig_ext = os.path.splitext(orig_name)[1].lower()

    # 1. Đọc một phần đầu để nhận diện MIME type thực tế mà không tốn RAM
    first_chunk = await file.read(8192)

    # Nhận diện MIME type thực tế và phần mở rộng chuẩn xác
    real_mime = extractor_registry.detect_real_mime_type(first_chunk, orig_name)
    mime_type = real_mime or file.content_type or "application/octet-stream"

    is_image = mime_type.startswith("image/") or orig_ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")
    is_video = is_video_file(orig_name, mime_type, orig_name)
    is_audio = mime_type.startswith("audio/") or orig_ext in (".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus")

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
        "audio/mpeg": ".mp3",
        "audio/mp3": ".mp3",
        "audio/wav": ".wav",
        "audio/x-wav": ".wav",
        "audio/mp4": ".m4a",
        "audio/x-m4a": ".m4a",
        "audio/aac": ".aac",
        "audio/flac": ".flac",
        "audio/ogg": ".ogg",
        "audio/x-ms-wma": ".wma",
        "audio/opus": ".opus",
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

    # 3. Xác định Danh mục (nếu không truyền thì lấy danh mục mặc định hoặc danh mục đầu tiên)
    cat = None
    if category_id is not None:
        cat_stmt = select(Category).where(Category.id == category_id)
        cat_res = await db.execute(cat_stmt)
        cat = cat_res.scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")

    if not cat:
        def_cat_stmt = select(Category).order_by(Category.is_system.desc(), Category.sort_order.asc(), Category.id.asc()).limit(1)
        def_cat_res = await db.execute(def_cat_stmt)
        cat = def_cat_res.scalar_one_or_none()
        if not cat:
            cat = Category(name="Chưa phân loại", icon="folder", color="#64748B", is_system=True)
            db.add(cat)
            await db.flush()

    # 4. Lưu nguyên vẹn file vật lý vào thư mục storage theo dạng stream chunk để tối ưu RAM
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    stored_filename = f"{uuid.uuid4().hex}{orig_ext}"
    disk_path = os.path.join(settings.FILES_STORAGE_DIR, stored_filename)

    total_size = len(first_chunk)
    try:
        with open(disk_path, "wb") as f_out:
            if first_chunk:
                f_out.write(first_chunk)
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                f_out.write(chunk)
                total_size += len(chunk)
    except OSError as e:
        if os.path.exists(disk_path):
            try:
                os.remove(disk_path)
            except Exception:
                pass
        if e.errno == 28:  # ENOSPC: No space left on device
            raise HTTPException(
                status_code=status.HTTP_507_INSUFFICIENT_STORAGE,
                detail="Ổ cứng máy chủ đã đầy (Hết dung lượng lưu trữ). Vui lòng nâng dung lượng Disk hoặc xóa bớt tệp.",
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lỗi ghi tệp lên máy chủ: {e.strerror}",
        )

    final_title = title.strip() if (title and title.strip()) else os.path.splitext(orig_name)[0].strip() or orig_name

    # 5. Đối với văn bản text/markdown nhỏ, đọc nội dung để hỗ trợ tìm kiếm toàn văn
    content_text = ""
    if orig_ext in (".txt", ".md", ".mdf") and total_size <= 5 * 1024 * 1024:
        try:
            with open(disk_path, "r", encoding="utf-8", errors="replace") as f_txt:
                content_text = f_txt.read()
        except Exception:
            content_text = ""
    else:
        file_size_mb = total_size / (1024 * 1024)
        if file_size_mb >= 1.0:
            size_str = f"{file_size_mb:.1f} MB"
        else:
            size_str = f"{total_size / 1024:.1f} KB"
        if is_video:
            content_text = f"Tệp video đính kèm: {orig_name} ({size_str})"
        elif is_audio:
            content_text = f"Tệp âm thanh đính kèm: {orig_name} ({size_str})"
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
        video_status="pending" if is_video else None,
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
            try:
                await asyncio.to_thread(ensure_thumbnail, stored_filename, settings.FILES_STORAGE_DIR, mime_type, orig_name)
            except Exception as e:
                logger.warning(f"Could not generate thumbnail for {stored_filename}: {e}")

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
        video_status=art.video_status,
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
        video_status="pending" if is_video_file(data.file_path, data.mime_type, data.file_name) else None,
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
        remove_video_derivatives(art.file_path)
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


@router.post("/batch-delete")
async def batch_delete_articles(
    data: BatchDeleteRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Xoá hàng loạt bài viết / hình ảnh theo danh sách ID và dọn dẹp file, thumbnail vật lý."""
    if not data.ids:
        return {"deleted_count": 0}

    stmt = select(Article).where(Article.id.in_(data.ids))
    result = await db.execute(stmt)
    articles = result.scalars().all()

    for art in articles:
        if art.file_path:
            remove_video_derivatives(art.file_path)
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
    return {"deleted_count": len(articles)}


@router.post("/batch-update-category")
async def batch_update_category(
    data: BatchUpdateCategoryRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Cập nhật danh mục hàng loạt cho danh sách ID bài viết / tài liệu / hình ảnh / video."""
    if not data.ids:
        return {"updated_count": 0}

    # Kiểm tra danh mục đích có tồn tại không
    cat_stmt = select(Category).where(Category.id == data.category_id)
    cat_res = await db.execute(cat_stmt)
    cat = cat_res.scalar_one_or_none()
    if not cat:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục đích không tồn tại")

    stmt = update(Article).where(Article.id.in_(data.ids)).values(category_id=data.category_id)
    result = await db.execute(stmt)
    await db.commit()

    return {"updated_count": result.rowcount, "category_id": cat.id, "category_name": cat.name}


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
    original: bool = Query(False, description="Luôn lấy file gốc kể cả khi xem inline"),
):
    """Download the original; inline playback prefers the complete optimized MP4."""
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
    if inline and not original and art.video_status == "ready":
        optimized = optimized_path(art.file_path)
        if optimized.is_file() and optimized.stat().st_size > 0:
            disk_path = str(optimized)
            download_name = f"{os.path.splitext(download_name)[0]}_optimized.mp4"
            media_type = "video/mp4"

    return FileResponse(
        path=disk_path,
        filename=download_name,
        media_type=media_type,
        content_disposition_type="inline" if inline else "attachment",
        # Revalidate the same playback URL when pending switches to ready.
        headers={"Accept-Ranges": "bytes", "Cache-Control": "private, no-cache"},
    )


@router.get("/{id}/file-info", response_model=ArticleFileInfoResponse)
async def get_article_file_info(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Xem thông tin chi tiết của file / tài nguyên:
    - Ngày giờ thêm file (created_at)
    - Tên file (file_name)
    - Độ phân giải (Resolution)
    - Dung lượng (File size)
    - FPS (Tốc độ khung hình, nếu là video)
    - Số giây / Thời lượng (nếu là video hoặc audio)
    - Thư mục lưu trữ (Storage directory / path)
    - Album (Tên danh mục chứa tài nguyên)
    """
    stmt = select(Article).options(selectinload(Article.category)).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy bài viết hoặc tệp tin")

    file_name = art.file_name or art.title
    file_path = art.file_path
    storage_dir = settings.FILES_STORAGE_DIR
    disk_path = os.path.join(storage_dir, art.file_path) if art.file_path else None

    file_size_bytes: Optional[int] = None
    file_size_formatted: Optional[str] = None
    if disk_path and os.path.isfile(disk_path):
        try:
            file_size_bytes = os.path.getsize(disk_path)
            file_size_formatted = format_byte_size(file_size_bytes)
        except Exception:
            pass

    lower_name = (art.file_name or art.title or "").lower()
    lower_path = (art.file_path or "").lower()
    lower_mime = (art.mime_type or "").lower()

    content_type = "file"
    if lower_mime.startswith("image/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp")):
        content_type = "image"
    elif lower_mime.startswith("video/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".flv", ".3gp", ".ts")):
        content_type = "video"
    elif lower_mime.startswith("audio/") or any(lower_name.endswith(ext) or lower_path.endswith(ext) for ext in (".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus")):
        content_type = "audio"
    elif art.source_type == "URL" and not art.file_path:
        content_type = "web"

    album = art.category.name if art.category else "Chưa phân loại"

    width: Optional[int] = None
    height: Optional[int] = None
    resolution: Optional[str] = None
    fps: Optional[float] = None
    duration_seconds: Optional[float] = None
    duration_formatted: Optional[str] = None

    if disk_path and os.path.isfile(disk_path):
        if content_type == "image":
            try:
                from PIL import Image
                with Image.open(disk_path) as img:
                    w, h = img.size
                    width = int(w)
                    height = int(h)
                    resolution = f"{width} × {height}"
            except Exception:
                pass
        elif content_type in ("video", "audio"):
            try:
                import json
                import subprocess
                probe_cmd = [
                    "ffprobe",
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    disk_path,
                ]
                proc = subprocess.run(probe_cmd, capture_output=True, text=True, timeout=4)
                if proc.returncode == 0:
                    probe_data = json.loads(proc.stdout)
                    v_stream = next((s for s in probe_data.get("streams", []) if s.get("codec_type") == "video"), None)
                    if v_stream:
                        w = v_stream.get("width")
                        h = v_stream.get("height")
                        if w and h:
                            width = int(w)
                            height = int(h)
                            resolution = f"{width} × {height}"

                        r_fps = v_stream.get("r_frame_rate") or v_stream.get("avg_frame_rate")
                        if r_fps and "/" in r_fps:
                            num, den = r_fps.split("/")
                            if float(den) > 0:
                                val = round(float(num) / float(den), 1)
                                if val > 0:
                                    fps = val
                        elif r_fps:
                            try:
                                fps = round(float(r_fps), 1)
                            except Exception:
                                pass

                    dur_str = probe_data.get("format", {}).get("duration")
                    if dur_str:
                        dur_sec = float(dur_str)
                        duration_seconds = round(dur_sec, 1)
                        total_secs = int(dur_sec)
                        m = total_secs // 60
                        s = total_secs % 60
                        duration_formatted = f"{m:02d}:{s:02d}"
            except Exception:
                pass

    return ArticleFileInfoResponse(
        id=art.id,
        title=art.title,
        file_name=file_name,
        file_path=file_path,
        storage_dir=storage_dir,
        storage_path=os.path.join(storage_dir, file_path) if file_path else None,
        file_size_bytes=file_size_bytes,
        file_size_formatted=file_size_formatted,
        mime_type=art.mime_type,
        content_type=content_type,
        category_id=art.category_id,
        category_name=album,
        album=album,
        created_at=art.created_at,
        updated_at=art.updated_at,
        width=width,
        height=height,
        resolution=resolution,
        fps=fps,
        duration_seconds=duration_seconds,
        duration_formatted=duration_formatted,
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


@router.post("/convert-to-pdf", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def upload_and_convert_to_pdf(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    file: UploadFile = File(...),
    category_id: Optional[int] = Form(None),
    title: Optional[str] = Form(None),
):
    """
    Tải lên tệp tin Office (DOC, DOCX, XLS, XLSX, PPT, PPTX, RTF...)
    và chuyển đổi trực tiếp sang PDF giữ nguyên 100% định dạng ban đầu.
    Lưu tệp kết quả dưới dạng PDF vào thư viện.
    """
    from app.services.pdf_converter import is_convertible_to_pdf, convert_office_to_pdf
    from app.services.extractors.pdf import PdfExtractor

    orig_name = file.filename or "document"

    if not is_convertible_to_pdf(orig_name, file.content_type):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Định dạng tệp '{orig_name}' không được hỗ trợ chuyển đổi sang PDF. Chỉ hỗ trợ DOC, DOCX, XLS, XLSX, PPT, PPTX, RTF, ODT, ODS, ODP, TXT."
        )

    file_bytes = await file.read()

    # Xác định danh mục
    cat = None
    if category_id is not None:
        cat_stmt = select(Category).where(Category.id == category_id)
        cat_res = await db.execute(cat_stmt)
        cat = cat_res.scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")

    if not cat:
        def_cat_stmt = select(Category).order_by(Category.is_system.desc(), Category.sort_order.asc(), Category.id.asc()).limit(1)
        def_cat_res = await db.execute(def_cat_stmt)
        cat = def_cat_res.scalar_one_or_none()
        if not cat:
            cat = Category(name="Chưa phân loại", icon="folder", color="#64748B", is_system=True)
            db.add(cat)
            await db.flush()

    # Chuyển đổi sang PDF trong thư mục tạm
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_input = os.path.join(temp_dir, orig_name)
        with open(temp_input, "wb") as f_in:
            f_in.write(file_bytes)

        try:
            converted_pdf_path = convert_office_to_pdf(temp_input, output_dir=temp_dir)
            with open(converted_pdf_path, "rb") as f_pdf:
                pdf_bytes = f_pdf.read()
        except Exception as e:
            logger.error(f"Lỗi convert PDF khi upload {orig_name}: {e}")
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=f"Lỗi trong quá trình chuyển đổi sang PDF: {str(e)}"
            )

    # Lưu tệp PDF vào storage chính
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    pdf_stored_filename = f"{uuid.uuid4().hex}.pdf"
    pdf_disk_path = os.path.join(settings.FILES_STORAGE_DIR, pdf_stored_filename)
    with open(pdf_disk_path, "wb") as f_out:
        f_out.write(pdf_bytes)

    base_name = os.path.splitext(orig_name)[0]
    pdf_file_name = f"{base_name}.pdf"
    final_title = title.strip() if title and title.strip() else base_name

    pdf_extractor = PdfExtractor()
    extraction_res = await pdf_extractor.extract(pdf_bytes, {"file_name": pdf_file_name, "file_path": pdf_stored_filename})

    new_article = Article(
        title=final_title,
        source_type="FILE",
        file_name=pdf_file_name,
        file_path=pdf_stored_filename,
        mime_type="application/pdf",
        content_markdown=extraction_res.content_markdown,
        excerpt=extraction_res.excerpt or f"Tài liệu PDF chuyển đổi từ '{orig_name}'",
        category_id=cat.id,
        reading_minutes=extraction_res.reading_minutes or 1,
    )

    db.add(new_article)
    await db.commit()
    await db.refresh(new_article)

    return await get_article(new_article.id, current_user, db)


@router.post("/{id}/convert-to-pdf", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def convert_article_file_to_pdf(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Chuyển đổi file đính kèm của bài viết hiện có (doc, docx, xls, xlsx, ppt, pptx, rtf...)
    sang định dạng PDF chuẩn giữ nguyên 100% định dạng ban đầu bằng LibreOffice headless.
    Tạo một bài viết PDF mới trong cùng danh mục để người dùng xem trực tiếp trên app.
    """
    from app.services.pdf_converter import is_convertible_to_pdf, convert_office_to_pdf
    from app.services.extractors.pdf import PdfExtractor

    stmt = select(Article).options(selectinload(Article.category)).where(Article.id == id)
    result = await db.execute(stmt)
    art = result.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy tài liệu")

    if not art.file_path:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Tài liệu này không có tệp đính kèm để chuyển đổi")

    disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
    if not os.path.exists(disk_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tệp vật lý không tồn tại trên máy chủ")

    orig_name = art.file_name or os.path.basename(art.file_path)
    if not is_convertible_to_pdf(orig_name, art.mime_type):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Định dạng tệp '{orig_name}' không được hỗ trợ chuyển đổi sang PDF. Chỉ hỗ trợ DOC, DOCX, XLS, XLSX, PPT, PPTX, RTF, ODT, ODS, ODP, TXT."
        )

    # Chuyển đổi sang PDF trong thư mục tạm
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_input = os.path.join(temp_dir, orig_name)
        shutil.copy2(disk_path, temp_input)

        try:
            converted_pdf_path = convert_office_to_pdf(temp_input, output_dir=temp_dir)
            with open(converted_pdf_path, "rb") as f_pdf:
                pdf_bytes = f_pdf.read()
        except Exception as e:
            logger.error(f"Lỗi convert PDF cho article {id}: {e}")
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=f"Lỗi trong quá trình chuyển đổi sang PDF: {str(e)}"
            )

    # Lưu tệp PDF mới vào storage chính
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    pdf_stored_filename = f"{uuid.uuid4().hex}.pdf"
    pdf_disk_path = os.path.join(settings.FILES_STORAGE_DIR, pdf_stored_filename)
    with open(pdf_disk_path, "wb") as f_out:
        f_out.write(pdf_bytes)

    base_title = os.path.splitext(orig_name)[0]
    pdf_file_name = f"{base_title}.pdf"
    new_title = f"{art.title} (PDF)" if not art.title.lower().endswith(".pdf") else art.title

    # Trích xuất nội dung text từ PDF để hỗ trợ tìm kiếm
    pdf_extractor = PdfExtractor()
    extraction_res = await pdf_extractor.extract(pdf_bytes, {"file_name": pdf_file_name, "file_path": pdf_stored_filename})

    new_article = Article(
        title=new_title,
        source_type="FILE",
        file_name=pdf_file_name,
        file_path=pdf_stored_filename,
        mime_type="application/pdf",
        content_markdown=extraction_res.content_markdown,
        excerpt=extraction_res.excerpt or f"Bản chuyển đổi PDF của '{orig_name}'",
        category_id=art.category_id,
        reading_minutes=extraction_res.reading_minutes or 1,
    )

    db.add(new_article)
    await db.commit()
    await db.refresh(new_article)

    return await get_article(new_article.id, current_user, db)


