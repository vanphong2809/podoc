import os
import uuid
from typing import Annotated
from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, File, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.config import settings
from app.core.database import get_db
from app.models.article import Article
from app.models.user import User
from app.schemas.article import ArticleDraft
from app.services.extractors.registry import extractor_registry
from app.services.ssrf import safe_fetch_url, SSRFValidationError
from app.services.url_utils import normalize_url

router = APIRouter(prefix="", tags=["Extraction"])


class ExtractUrlRequest(BaseModel):
    url: str = Field(..., min_length=1, description="URL cần trích xuất")


@router.post("/extract", response_model=ArticleDraft)
async def extract_url(
    payload: ExtractUrlRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Trích xuất bản nháp từ URL (chưa lưu vào DB):
    - Bảo vệ chống SSRF (chặn IP loopback, private, link-local, cloud metadata).
    - Chuẩn hoá URL để kiểm tra trùng lặp với bài viết đã có.
    - Tự động nhận diện link trỏ thẳng tới file (PDF, DOCX...) để tải về và bóc tách.
    """
    raw_url = payload.url.strip()
    norm_url = normalize_url(raw_url)

    # 1. Kiểm tra trùng lặp URL
    existing_stmt = select(Article.id).where(Article.normalized_url == norm_url).limit(1)
    existing_res = await db.execute(existing_stmt)
    existing_id = existing_res.scalar_one_or_none()

    # 2. Tải nội dung qua bộ chống SSRF
    try:
        content_bytes, final_url, content_type = await safe_fetch_url(
            raw_url,
            max_size_bytes=settings.MAX_DOWNLOAD_SIZE_MB * 1024 * 1024,
        )
    except SSRFValidationError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Chặn bảo mật SSRF: {str(e)}")
    except Exception as e:
        # Nếu lỗi mạng hoặc không truy cập được, trả về bản nháp bookmark fallback
        return ArticleDraft(
            title=raw_url,
            source_url=raw_url,
            normalized_url=norm_url,
            content_markdown=f"> ⚠️ **Không thể kết nối đến trang web:** {str(e)}\n>\n> [🔗 Nhấn để mở link gốc]({raw_url})",
            excerpt="Không thể kết nối đến trang web. Lưu dạng bookmark để xem sau.",
            reading_minutes=1,
            is_duplicate=bool(existing_id),
            existing_article_id=existing_id,
            is_fallback_bookmark=True,
            error_message=f"Lỗi kết nối: {str(e)}",
        )

    # 3. Kiểm tra xem link có trỏ thẳng tới file (PDF, Word, TXT...) không
    magic_mime = extractor_registry.detect_real_mime_type(content_bytes, raw_url)
    is_direct_file = magic_mime in (
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown",
    )

    if is_direct_file:
        # Lưu file vào thư mục lưu trữ
        os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
        file_ext = os.path.splitext(raw_url.split("?")[0])[1] or ".bin"
        unique_name = f"{uuid.uuid4().hex}{file_ext}"
        save_path = os.path.join(settings.FILES_STORAGE_DIR, unique_name)
        with open(save_path, "wb") as f:
            f.write(content_bytes)

        file_name = os.path.basename(raw_url.split("?")[0]) or unique_name
        extractor = extractor_registry.get_extractor("FILE", magic_mime)
        context = {
            "file_name": file_name,
            "file_path": unique_name,
            "url": final_url,
            "source_type": "URL",
        }
    else:
        extractor = extractor_registry.get_extractor("URL", final_url)
        context = {"url": final_url, "source_type": "URL"}

    # 4. Thực hiện bóc tách
    result = await extractor.extract(content_bytes, context)

    return ArticleDraft(
        title=result.title,
        source_url=final_url,
        normalized_url=norm_url,
        author=result.author,
        site_name=result.site_name,
        image_url=result.image_url,
        language=result.language,
        reading_minutes=result.reading_minutes,
        excerpt=result.excerpt,
        content_markdown=result.content_markdown,
        is_duplicate=bool(existing_id),
        existing_article_id=existing_id,
        is_fallback_bookmark=result.is_fallback_bookmark,
        error_message=result.error_message,
    )


@router.post("/extract-file", response_model=ArticleDraft)
async def extract_file(
    file: UploadFile = File(...),
    current_user: Annotated[User, Depends(get_current_user)] = None,
):
    """
    Lưu nguyên dạng file tải lên (không bắt buộc bóc tách text):
    - Hỗ trợ tất cả các định dạng: txt, md, mdf, html, pdf, doc, docx, png, svg, v.v.
    - Không giới hạn dung lượng tệp tin.
    - Lưu file gốc vào volume /data/files với tên định danh duy nhất.
    - Trả về bản nháp kèm file_path, file_name, mime_type để hiển thị trực tiếp trên app.
    """
    file_bytes = await file.read()

    orig_name = file.filename or "uploaded_file"
    orig_ext = os.path.splitext(orig_name)[1].lower()

    # Nhận diện MIME type
    mime_map = {
        ".txt": "text/plain",
        ".md": "text/markdown",
        ".mdf": "text/markdown",
        ".html": "text/html",
        ".htm": "text/html",
        ".pdf": "application/pdf",
        ".doc": "application/msword",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".png": "image/png",
        ".svg": "image/svg+xml",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".webp": "image/webp",
    }

    real_mime = extractor_registry.detect_real_mime_type(file_bytes, orig_name)
    mime_type = mime_map.get(orig_ext, real_mime or file.content_type or "application/octet-stream")

    # Lưu nguyên vẹn file vật lý vào /data/files
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    unique_filename = f"{uuid.uuid4().hex}{orig_ext}"
    disk_path = os.path.join(settings.FILES_STORAGE_DIR, unique_filename)

    try:
        with open(disk_path, "wb") as f:
            f.write(file_bytes)
    except OSError as e:
        if os.path.exists(disk_path):
            try:
                os.remove(disk_path)
            except Exception:
                pass
        if e.errno == 28:
            raise HTTPException(
                status_code=status.HTTP_507_INSUFFICIENT_STORAGE,
                detail="Ổ cứng máy chủ đã đầy (Hết dung lượng lưu trữ). Vui lòng nâng dung lượng Disk hoặc xóa bớt tệp.",
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lỗi ghi tệp lên máy chủ: {e.strerror}",
        )

    # Tiêu đề mặc định lấy tên file bỏ đuôi
    title = os.path.splitext(orig_name)[0].strip() or orig_name
    if file_size_mb >= 1.0:
        size_str = f"{file_size_mb:.1f} MB"
    else:
        size_str = f"{file_size_bytes / 1024:.1f} KB"

    # Với file text/markdown, đọc sẵn nội dung để hiển thị/tìm kiếm nhanh
    content_text = ""
    if orig_ext in (".txt", ".md", ".mdf"):
        try:
            content_text = file_bytes.decode("utf-8", errors="replace")
        except Exception:
            content_text = ""
    else:
        content_text = f"Tệp tin đính kèm: {orig_name} ({size_str})"

    return ArticleDraft(
        title=title,
        source_url="",
        normalized_url="",
        author="Tệp tin",
        site_name="Tệp tin tải lên",
        image_url=None,
        language="vi",
        reading_minutes=1,
        excerpt=f"Tệp tin {orig_ext.upper().replace('.', '')}: {orig_name} ({size_str})",
        content_markdown=content_text,
        file_name=orig_name,
        file_path=unique_filename,
        mime_type=mime_type,
        is_duplicate=False,
        existing_article_id=None,
        is_fallback_bookmark=False,
        error_message=None,
    )
