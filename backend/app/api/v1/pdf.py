import asyncio
import json
import logging
import os
import uuid
from datetime import datetime
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
from app.schemas.article import ArticleDetailResponse
from app.schemas.pdf import PdfInfoResponse, SplitPdfResponse
from app.services.extractors.pdf import PdfExtractor
from app.services.pdf_tools import (
    PdfToolError,
    build_fixed_page_groups,
    format_page_group_label,
    get_pdf_info,
    merge_pdfs,
    parse_page_ranges,
    safe_pdf_filename,
    split_pdf,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/pdf", tags=["PDF Tools"])


def _is_pdf_article(art: Article) -> bool:
    mime = (art.mime_type or "").lower()
    name = (art.file_name or art.file_path or "").lower()
    return mime == "application/pdf" or name.endswith(".pdf")


async def _resolve_category(db: AsyncSession, category_id: Optional[int]) -> Category:
    if category_id is not None:
        res = await db.execute(select(Category).where(Category.id == category_id))
        cat = res.scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Danh mục không tồn tại")
        return cat

    res = await db.execute(
        select(Category).order_by(Category.is_system.desc(), Category.sort_order.asc(), Category.id.asc()).limit(1)
    )
    cat = res.scalar_one_or_none()
    if not cat:
        cat = Category(name="Chưa phân loại", icon="folder", color="#64748B", is_system=True)
        db.add(cat)
        await db.flush()
    return cat


def _write_pdf_bytes_to_storage(pdf_bytes: bytes, tracked_paths: list[str]) -> str:
    os.makedirs(settings.FILES_STORAGE_DIR, exist_ok=True)
    stored_filename = f"{uuid.uuid4().hex}.pdf"
    disk_path = os.path.join(settings.FILES_STORAGE_DIR, stored_filename)
    try:
        with open(disk_path, "wb") as f_out:
            f_out.write(pdf_bytes)
        tracked_paths.append(disk_path)
        return stored_filename
    except OSError as exc:
        if os.path.exists(disk_path):
            try:
                os.remove(disk_path)
            except OSError:
                pass
        if exc.errno == 28:
            raise HTTPException(
                status_code=status.HTTP_507_INSUFFICIENT_STORAGE,
                detail="Ổ cứng máy chủ đã đầy (Hết dung lượng lưu trữ). Vui lòng nâng dung lượng Disk hoặc xóa bớt tệp.",
            ) from exc
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lỗi ghi tệp PDF lên máy chủ: {exc.strerror}",
        ) from exc


def _cleanup_paths(paths: list[str]) -> None:
    for p in paths:
        try:
            if os.path.exists(p):
                os.remove(p)
        except OSError:
            pass


async def _load_library_pdf(db: AsyncSession, article_id: int) -> tuple[Article, Path, str]:
    art = (await db.execute(select(Article).where(Article.id == article_id))).scalar_one_or_none()
    if not art:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Không tìm thấy tài liệu ID={article_id}")
    if not art.file_path:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tài liệu '{art.title}' không có tệp đính kèm")
    if not _is_pdf_article(art):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tài liệu '{art.title}' không phải là tệp PDF")

    disk_path = Path(settings.FILES_STORAGE_DIR) / art.file_path
    if not disk_path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Tệp vật lý của '{art.title}' không còn tồn tại trên máy chủ")

    label = art.file_name or art.title or f"document_{art.id}.pdf"
    return art, disk_path, label


@router.get("/articles/{id}/info", response_model=PdfInfoResponse)
async def get_library_pdf_info(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Kiểm tra tổng số trang và trạng thái mật khẩu của một tệp PDF trong Thư viện."""
    art, disk_path, label = await _load_library_pdf(db, id)
    try:
        page_count, is_encrypted = await asyncio.to_thread(get_pdf_info, disk_path, label)
    except PdfToolError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    size_bytes = disk_path.stat().st_size
    return PdfInfoResponse(
        id=art.id,
        title=art.title,
        file_name=art.file_name or label,
        page_count=page_count,
        is_encrypted=is_encrypted,
        file_size_bytes=size_bytes,
        file_size_formatted=format_byte_size(size_bytes),
    )


@router.post("/merge", response_model=ArticleDetailResponse, status_code=status.HTTP_201_CREATED)
async def merge_pdf_files(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    files: list[UploadFile] = File(default=[]),
    sources_json: Optional[str] = Form(None, description="Thứ tự nguồn JSON: [{type: 'article'|'upload', id|index}]"),
    article_ids_json: Optional[str] = Form(None, description="Danh sách ID PDF từ thư viện dạng JSON [1, 2]"),
    title: Optional[str] = Form(None),
    category_id: Optional[int] = Form(None),
):
    """Ghép 2 hoặc nhiều tệp PDF (từ thiết bị và/hoặc từ thư viện PoDoc) thành 1 tệp PDF mới."""
    uploaded_buffers: list[tuple[str, bytes]] = []
    for f in files:
        raw = await f.read()
        name = f.filename or f"upload_{len(uploaded_buffers) + 1}.pdf"
        if not raw:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tệp '{name}' bị trống.")
        uploaded_buffers.append((name, raw))

    merge_inputs: list[tuple[str, bytes | Path]] = []
    first_article_cat_id: Optional[int] = None

    if sources_json and sources_json.strip():
        try:
            source_specs = json.loads(sources_json)
            if not isinstance(source_specs, list):
                raise ValueError("sources_json phải là một mảng JSON")
        except Exception as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Danh sách nguồn ghép không hợp lệ: {exc}") from exc

        for spec in source_specs:
            if not isinstance(spec, dict):
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cấu trúc phần tử nguồn PDF không hợp lệ")
            kind = spec.get("type")
            if kind == "upload":
                idx = spec.get("index")
                if not isinstance(idx, int) or idx < 0 or idx >= len(uploaded_buffers):
                    raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Thiếu tệp PDF tải lên ở vị trí {idx}")
                merge_inputs.append(uploaded_buffers[idx])
            elif kind == "article":
                art_id = spec.get("id")
                if not isinstance(art_id, int):
                    raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="ID tài liệu thư viện không hợp lệ")
                art, disk_path, label = await _load_library_pdf(db, art_id)
                if first_article_cat_id is None:
                    first_article_cat_id = art.category_id
                merge_inputs.append((label, disk_path))
            else:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Loại nguồn PDF không hỗ trợ: {kind}")
    else:
        merge_inputs.extend(uploaded_buffers)
        if article_ids_json and article_ids_json.strip():
            try:
                art_ids = json.loads(article_ids_json)
                if not isinstance(art_ids, list):
                    raise ValueError("article_ids_json phải là một mảng JSON")
            except Exception as exc:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Danh sách ID thư viện không hợp lệ: {exc}") from exc
            for art_id in art_ids:
                if not isinstance(art_id, int):
                    raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="ID tài liệu thư viện không hợp lệ")
                art, disk_path, label = await _load_library_pdf(db, art_id)
                if first_article_cat_id is None:
                    first_article_cat_id = art.category_id
                merge_inputs.append((label, disk_path))

    if len(merge_inputs) < 2:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Vui lòng chọn ít nhất 2 tệp PDF để ghép.")

    try:
        merged_bytes, total_pages = await asyncio.to_thread(merge_pdfs, merge_inputs)
    except PdfToolError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Lỗi không xác định khi ghép PDF")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Lỗi ghép tệp PDF: {exc}") from exc

    cat = await _resolve_category(db, category_id if category_id is not None else first_article_cat_id)

    clean_title = (title or "").strip()
    if clean_title.lower().endswith(".pdf"):
        clean_title = clean_title[:-4].strip()
    if not clean_title:
        clean_title = f"Ghep_PDF_{datetime.now().strftime('%d%m_%H%M')}"
    pdf_file_name = safe_pdf_filename(clean_title, fallback="merged_document")

    tracked_paths: list[str] = []
    try:
        stored_filename = _write_pdf_bytes_to_storage(merged_bytes, tracked_paths)
        extraction_res = await PdfExtractor().extract(
            merged_bytes, {"file_name": pdf_file_name, "file_path": stored_filename}
        )
        source_names = ", ".join(os.path.splitext(name)[0] for name, _ in merge_inputs[:3])
        if len(merge_inputs) > 3:
            source_names += f" (+{len(merge_inputs) - 3} tệp)"
        default_excerpt = f"Ghép từ {len(merge_inputs)} tệp PDF ({total_pages} trang): {source_names}"

        new_article = Article(
            title=clean_title,
            source_type="FILE",
            file_name=pdf_file_name,
            file_path=stored_filename,
            mime_type="application/pdf",
            content_markdown=extraction_res.content_markdown,
            excerpt=extraction_res.excerpt or default_excerpt,
            category_id=cat.id,
            reading_minutes=extraction_res.reading_minutes or 1,
        )
        db.add(new_article)
        await db.commit()
        await db.refresh(new_article)
    except HTTPException:
        _cleanup_paths(tracked_paths)
        raise
    except Exception as exc:
        await db.rollback()
        _cleanup_paths(tracked_paths)
        logger.exception("Lỗi lưu tệp PDF sau khi ghép")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Lỗi lưu tệp PDF: {exc}") from exc

    return await get_article(new_article.id, current_user, db)


@router.post("/split", response_model=SplitPdfResponse, status_code=status.HTTP_201_CREATED)
async def split_pdf_file(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    file: Optional[UploadFile] = File(None),
    article_id: Optional[int] = Form(None),
    mode: str = Form("ranges", description="ranges hoặc every_n"),
    ranges: Optional[str] = Form(None, description="Khoảng trang kiểu 1-3, 5"),
    merge_ranges: bool = Form(False, description="True = gộp các trang được chọn thành 1 PDF; False = mỗi nhóm thành 1 PDF"),
    pages_per_split: int = Form(1, description="Số trang mỗi tệp khi mode=every_n"),
    title_prefix: Optional[str] = Form(None),
    category_id: Optional[int] = Form(None),
):
    """Tách một tệp PDF (từ máy hoặc Thư viện PoDoc) thành nhiều tệp PDF theo khoảng trang hoặc chia đều."""
    if file is not None and article_id is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Chỉ chọn 1 nguồn PDF để tách (tệp tải lên hoặc tài liệu từ Thư viện).",
        )
    if file is None and article_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Vui lòng chọn tệp PDF cần tách.")

    source_data: bytes | Path
    source_name: str
    default_cat_id = category_id

    if file is not None:
        raw = await file.read()
        source_name = file.filename or "document.pdf"
        if not raw:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Tệp '{source_name}' bị trống.")
        source_data = raw
    else:
        art, disk_path, label = await _load_library_pdf(db, article_id)
        source_data = disk_path
        source_name = label
        if default_cat_id is None:
            default_cat_id = art.category_id

    try:
        total_pages, is_encrypted = await asyncio.to_thread(get_pdf_info, source_data, source_name)
        if is_encrypted:
            raise PdfToolError(f"Tệp '{source_name}' đang được bảo vệ bằng mật khẩu, chưa hỗ trợ tách.")

        clean_mode = (mode or "ranges").strip().lower()
        if clean_mode == "ranges":
            page_groups = parse_page_ranges(ranges or "", total_pages, merge_ranges=merge_ranges)
        elif clean_mode == "every_n":
            page_groups = build_fixed_page_groups(total_pages, pages_per_split)
        else:
            raise PdfToolError("Chế độ tách không hợp lệ (chỉ hỗ trợ 'ranges' hoặc 'every_n').")

        split_outputs, src_pages = await asyncio.to_thread(split_pdf, source_data, page_groups, source_name)
    except PdfToolError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Lỗi khi tách PDF")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Lỗi tách tệp PDF: {exc}") from exc

    cat = await _resolve_category(db, default_cat_id)

    base_prefix = (title_prefix or "").strip()
    if base_prefix.lower().endswith(".pdf"):
        base_prefix = base_prefix[:-4].strip()
    if not base_prefix:
        base_prefix = os.path.splitext(source_name)[0].strip() or "Document"

    extractor = PdfExtractor()
    tracked_paths: list[str] = []
    created_ids: list[int] = []

    try:
        for pdf_bytes, pages in split_outputs:
            range_label = format_page_group_label(pages)
            part_title = f"{base_prefix} (Trang {range_label})"
            file_tag = range_label.replace(", ", "_").replace(" ", "")
            part_filename = safe_pdf_filename(f"{base_prefix}_trang_{file_tag}")

            stored_filename = _write_pdf_bytes_to_storage(pdf_bytes, tracked_paths)
            extraction_res = await extractor.extract(
                pdf_bytes, {"file_name": part_filename, "file_path": stored_filename}
            )
            default_excerpt = f"Tách từ '{source_name}' · Trang {range_label} ({len(pages)} trang)"

            art_record = Article(
                title=part_title,
                source_type="FILE",
                file_name=part_filename,
                file_path=stored_filename,
                mime_type="application/pdf",
                content_markdown=extraction_res.content_markdown,
                excerpt=extraction_res.excerpt or default_excerpt,
                category_id=cat.id,
                reading_minutes=extraction_res.reading_minutes or 1,
            )
            db.add(art_record)
            await db.flush()
            created_ids.append(art_record.id)

        await db.commit()
    except HTTPException:
        _cleanup_paths(tracked_paths)
        raise
    except Exception as exc:
        await db.rollback()
        _cleanup_paths(tracked_paths)
        logger.exception("Lỗi lưu các tệp PDF sau khi tách")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Lỗi lưu tệp PDF sau khi tách: {exc}") from exc

    created_articles = [await get_article(aid, current_user, db) for aid in created_ids]
    return SplitPdfResponse(
        created_count=len(created_articles),
        source_page_count=src_pages,
        articles=created_articles,
    )

