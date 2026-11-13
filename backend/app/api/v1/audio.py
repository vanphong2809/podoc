import asyncio
import logging
import os
import tempfile
import uuid
from pathlib import Path
from typing import Annotated, Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.v1.articles import format_byte_size, get_article
from app.core.config import settings
from app.core.database import get_db
from app.models.article import Article
from app.models.category import Category
from app.models.user import User
from app.schemas.audio import AudioExtractResultResponse, AudioProbeResponse
from app.services.audio_extractor import (
    AudioExtractionError,
    extract_audio_stream,
    format_seconds_to_mmss,
    probe_video_audio,
    safe_audio_filename,
)
from app.services.video_transcoder import is_video_file

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/audio", tags=["Audio Tools"])


async def _resolve_target_category(db: AsyncSession, category_id: Optional[int], fallback_cat_id: Optional[int] = None) -> Category:
    target_id = category_id or fallback_cat_id
    if target_id is not None:
        stmt = select(Category).where(Category.id == target_id)
        res = await db.execute(stmt)
        cat = res.scalar_one_or_none()
        if cat:
            return cat

    stmt = select(Category).order_by(Category.is_system.desc(), Category.sort_order.asc(), Category.id.asc()).limit(1)
    res = await db.execute(stmt)
    cat = res.scalar_one_or_none()
    if not cat:
        cat = Category(name="Chưa phân loại", icon="folder", color="#64748B", is_system=True)
        db.add(cat)
        await db.flush()
    return cat


@router.get("/articles/{id}/probe", response_model=AudioProbeResponse)
async def probe_library_video_audio(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Kiểm tra luồng âm thanh của một tệp video có sẵn trong Thư viện PoDoc.
    Trả về định dạng codec, bitrate, số kênh, thời lượng và định dạng đề xuất.
    """
    stmt = select(Article).where(Article.id == id)
    res = await db.execute(stmt)
    art = res.scalar_one_or_none()

    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Không tìm thấy tài liệu ID={id}")

    if not art.file_path:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tài liệu '{art.title}' không có file đính kèm.")

    if not is_video_file(art.file_path, art.mime_type, art.file_name):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tài liệu '{art.title}' không phải là tệp video.")

    disk_path = Path(settings.FILES_STORAGE_DIR) / art.file_path
    if not disk_path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Tệp vật lý của '{art.title}' không còn tồn tại trên máy chủ.")

    try:
        info = await probe_video_audio(disk_path)
        return AudioProbeResponse(**info)
    except AudioExtractionError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc


@router.post("/extract", response_model=AudioExtractResultResponse, status_code=status.HTTP_201_CREATED)
async def extract_audio_from_video(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    file: Optional[UploadFile] = File(None, description="Tệp video tải lên từ máy"),
    article_id: Optional[int] = Form(None, description="ID của video có sẵn trong Thư viện"),
    output_format: str = Form("original", description="original (giữ nguyên gốc), mp3, m4a, wav, flac"),
    title: Optional[str] = Form(None, description="Tiêu đề bài viết âm thanh kết quả"),
    category_id: Optional[int] = Form(None, description="Danh mục lưu trữ"),
):
    """
    Trích xuất âm thanh từ video:
    - Hỗ trợ video tải lên từ thiết bị hoặc video có sẵn trong Thư viện PoDoc.
    - Mặc định 'original' sử dụng Stream Copy (giữ nguyên 100% chất lượng gốc không nén lại).
    - Tạo bài viết mới dạng FILE âm thanh và lưu trực tiếp vào Thư viện PoDoc.
    """
    if file is None and article_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Vui lòng cung cấp tệp video hoặc chọn video từ Thư viện.")

    if file is not None and article_id is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Chỉ chọn một nguồn video duy nhất (từ máy hoặc từ Thư viện).")

    temp_video_path: Optional[Path] = None
    source_path: Path
    source_name: str
    fallback_cat_id: Optional[int] = None

    if article_id is not None:
        stmt = select(Article).where(Article.id == article_id)
        res = await db.execute(stmt)
        art = res.scalar_one_or_none()
        if not art:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Không tìm thấy video ID={article_id}")
        if not art.file_path:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Tài liệu được chọn không có tệp đính kèm.")
        source_path = Path(settings.FILES_STORAGE_DIR) / art.file_path
        if not source_path.is_file():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tệp video vật lý không còn tồn tại trên máy chủ.")
        source_name = art.file_name or art.title
        fallback_cat_id = art.category_id
    else:
        # Tệp upload từ máy
        source_name = file.filename or "video.mp4"
        # Ghi vào file tạm
        suffix = Path(source_name).suffix or ".mp4"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix, dir=settings.FILES_STORAGE_DIR) as tmp:
            temp_video_path = Path(tmp.name)
            while chunk := await file.read(1024 * 1024):
                tmp.write(chunk)
        source_path = temp_video_path

    # Thư mục lưu trữ chính
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    temp_target_name = f"{uuid.uuid4().hex}"
    temp_target_path = Path(settings.FILES_STORAGE_DIR) / temp_target_name
    extracted_file_path: Optional[Path] = None

    try:
        extracted_file_path, mime_type, duration, is_lossless = await extract_audio_stream(
            source=source_path,
            output_path=temp_target_path,
            output_format=output_format,
        )

        size_bytes = extracted_file_path.stat().st_size
        stored_file_path = extracted_file_path.name
        ext = extracted_file_path.suffix.lstrip(".").lower()

        # Tiêu đề kết quả
        base_name = Path(source_name).stem
        final_title = (title or "").strip() or f"{base_name} (Âm thanh)"
        output_file_name = safe_audio_filename(final_title, ext)

        cat = await _resolve_target_category(db, category_id, fallback_cat_id)

        quality_note = "Chất lượng gốc (Stream Copy)" if is_lossless else f"Chuẩn {output_format.upper()}"
        excerpt = f"Tách âm thanh từ video '{source_name}' • {quality_note} • {format_seconds_to_mmss(duration) or ''}".strip()

        new_article = Article(
            title=final_title,
            source_type="FILE",
            file_name=output_file_name,
            file_path=stored_file_path,
            mime_type=mime_type,
            duration_seconds=duration,
            excerpt=excerpt,
            category_id=cat.id,
            reading_minutes=max(1, int(round((duration or 60) / 60))),
        )
        db.add(new_article)
        await db.commit()
        await db.refresh(new_article)

        return AudioExtractResultResponse(
            article_id=new_article.id,
            title=new_article.title,
            file_name=output_file_name,
            mime_type=mime_type,
            duration_seconds=duration,
            duration_formatted=format_seconds_to_mmss(duration),
            file_size_bytes=size_bytes,
            file_size_formatted=format_byte_size(size_bytes),
            audio_codec=ext.upper(),
            is_lossless=is_lossless,
            source_video_name=source_name,
        )

    except AudioExtractionError as exc:
        if extracted_file_path and extracted_file_path.is_file():
            try:
                extracted_file_path.unlink(missing_ok=True)
            except OSError:
                pass
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    except Exception as exc:
        await db.rollback()
        if extracted_file_path and extracted_file_path.is_file():
            try:
                extracted_file_path.unlink(missing_ok=True)
            except OSError:
                pass
        logger.exception("Lỗi xử lý trích xuất âm thanh")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Lỗi trích xuất âm thanh: {exc}") from exc

    finally:
        if temp_video_path and temp_video_path.is_file():
            try:
                temp_video_path.unlink(missing_ok=True)
            except OSError:
                pass

