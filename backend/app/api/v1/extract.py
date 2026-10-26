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
    Trích xuất bản nháp từ file tải lên (Phase 2):
    - Hỗ trợ các định dạng: .txt, .md, .docx, .html/.htm, .pdf, .rtf.
    - Giới hạn tối đa 30MB/file.
    - Kiểm tra magic bytes nội dung thật, không tin phần mở rộng do người dùng gửi.
    - Lưu file gốc vào volume /data/files với tên định danh duy nhất.
    """
    file_bytes = await file.read()
    file_size_mb = len(file_bytes) / (1024 * 1024)

    if file_size_mb > settings.MAX_UPLOAD_SIZE_MB:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Kích thước file vượt quá giới hạn cho phép ({settings.MAX_UPLOAD_SIZE_MB}MB)",
        )

    # Nhận diện loại mime bằng nội dung thật (magic bytes)
    real_mime = extractor_registry.detect_real_mime_type(file_bytes, file.filename or "")

    # Lưu file vật lý vào /data/files
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    orig_ext = os.path.splitext(file.filename or "")[1]
    unique_filename = f"{uuid.uuid4().hex}{orig_ext}"
    disk_path = os.path.join(settings.FILES_STORAGE_DIR, unique_filename)

    with open(disk_path, "wb") as f:
        f.write(file_bytes)

    # Chọn extractor phù hợp và bóc tách
    extractor = extractor_registry.get_extractor("FILE", real_mime)
    context = {
        "file_name": file.filename or "uploaded_file",
        "file_path": unique_filename,
        "mime_type": real_mime,
        "source_type": "FILE",
    }

    result = await extractor.extract(file_bytes, context)

    return ArticleDraft(
        title=result.title,
        source_url="",
        normalized_url="",
        author=result.author,
        site_name="Tệp tin tải lên",
        image_url=None,
        language=result.language,
        reading_minutes=result.reading_minutes,
        excerpt=result.excerpt,
        content_markdown=result.content_markdown,
        is_duplicate=False,
        existing_article_id=None,
        is_fallback_bookmark=result.is_fallback_bookmark,
        error_message=result.error_message,
    )
