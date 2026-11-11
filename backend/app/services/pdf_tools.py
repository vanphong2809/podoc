"""Lossless PDF inspection, merging, and splitting utilities using pypdf."""
import io
import os
from pathlib import Path
from typing import Iterable

from pypdf import PdfReader, PdfWriter


MAX_SPLIT_OUTPUTS = 50


class PdfToolError(ValueError):
    """Domain validation or unreadable-PDF error surfaced cleanly to callers."""


def _open_pdf_reader(source: bytes | Path | str, label: str = "tệp PDF") -> PdfReader:
    """Open a PDF from raw bytes or disk path and verify it is readable without a user password."""
    try:
        reader = PdfReader(io.BytesIO(source)) if isinstance(source, (bytes, bytearray)) else PdfReader(str(source))
    except Exception as exc:
        raise PdfToolError(f"Không thể đọc {label}: tệp không hợp lệ hoặc bị hỏng ({exc}).") from exc

    if reader.is_encrypted:
        try:
            decrypted = reader.decrypt("")
        except Exception:
            decrypted = 0
        if not decrypted:
            raise PdfToolError(f"Tệp '{label}' đang được bảo vệ bằng mật khẩu, chưa hỗ trợ xử lý.")

    try:
        page_count = len(reader.pages)
    except Exception as exc:
        raise PdfToolError(f"Không thể đọc cấu trúc trang của '{label}' ({exc}).") from exc

    if page_count <= 0:
        raise PdfToolError(f"Tệp '{label}' không có trang nào.")

    return reader


def get_pdf_info(source: bytes | Path | str, label: str = "tệp PDF") -> tuple[int, bool]:
    """Return (page_count, is_encrypted). Raises PdfToolError only if corrupt."""
    try:
        reader = PdfReader(io.BytesIO(source)) if isinstance(source, (bytes, bytearray)) else PdfReader(str(source))
    except Exception as exc:
        raise PdfToolError(f"Không thể đọc {label}: tệp không hợp lệ hoặc bị hỏng.") from exc

    if reader.is_encrypted:
        try:
            decrypted = reader.decrypt("")
        except Exception:
            decrypted = 0
        if not decrypted:
            return 0, True

    try:
        return len(reader.pages), False
    except Exception as exc:
        raise PdfToolError(f"Không thể đọc số trang của {label}.") from exc


def parse_page_ranges(ranges_str: str, total_pages: int, merge_ranges: bool = False) -> list[list[int]]:
    """
    Parse 1-based inclusive page ranges like '1-3, 5, 7-9'.
    - merge_ranges=False: each comma-separated part becomes a separate output PDF.
    - merge_ranges=True: all specified pages are collected into one list (preserving order, deduplicated).
    """
    if total_pages <= 0:
        raise PdfToolError("Tài liệu PDF không có trang nào để tách.")

    raw = (ranges_str or "").strip()
    if not raw:
        raise PdfToolError("Vui lòng nhập khoảng trang cần tách (ví dụ: 1-3, 5).")

    tokens = [tok.strip() for tok in raw.split(",")]
    if any(not tok for tok in tokens):
        raise PdfToolError("Định dạng khoảng trang không hợp lệ (có dấu phẩy thừa hoặc phần trống).")

    groups: list[list[int]] = []
    for tok in tokens:
        if "-" in tok:
            parts = [p.strip() for p in tok.split("-")]
            if len(parts) != 2 or not parts[0].isdigit() or not parts[1].isdigit():
                raise PdfToolError(f"Khoảng trang không hợp lệ: '{tok}'. Đúng định dạng là 'từ-đến' (VD: 1-5).")
            start, end = int(parts[0]), int(parts[1])
            if start < 1 or end < 1:
                raise PdfToolError(f"Số trang phải lớn hơn hoặc bằng 1 (lỗi ở '{tok}').")
            if start > end:
                raise PdfToolError(f"Trang bắt đầu lớn hơn trang kết thúc ở '{tok}'.")
            if end > total_pages:
                raise PdfToolError(f"Trang {end} vượt quá tổng số trang của tài liệu ({total_pages} trang).")
            groups.append(list(range(start, end + 1)))
        else:
            if not tok.isdigit():
                raise PdfToolError(f"Số trang không hợp lệ: '{tok}'. Chỉ nhập số và dấu gạch ngang (VD: 1-3, 5).")
            page = int(tok)
            if page < 1:
                raise PdfToolError(f"Số trang phải lớn hơn hoặc bằng 1 (lỗi ở '{tok}').")
            if page > total_pages:
                raise PdfToolError(f"Trang {page} vượt quá tổng số trang của tài liệu ({total_pages} trang).")
            groups.append([page])

    if merge_ranges:
        seen: set[int] = set()
        merged: list[int] = []
        for group in groups:
            for page in group:
                if page not in seen:
                    seen.add(page)
                    merged.append(page)
        return [merged]

    if len(groups) > MAX_SPLIT_OUTPUTS:
        raise PdfToolError(
            f"Số tệp tạo ra ({len(groups)}) vượt quá giới hạn tối đa {MAX_SPLIT_OUTPUTS} tệp mỗi lần tách."
        )

    return groups


def build_fixed_page_groups(total_pages: int, pages_per_split: int) -> list[list[int]]:
    """Group 1..total_pages into consecutive chunks of pages_per_split."""
    if total_pages <= 0:
        raise PdfToolError("Tài liệu PDF không có trang nào để tách.")
    if pages_per_split < 1:
        raise PdfToolError("Số trang mỗi tệp phải lớn hơn hoặc bằng 1.")

    groups = [
        list(range(start, min(start + pages_per_split, total_pages + 1)))
        for start in range(1, total_pages + 1, pages_per_split)
    ]
    if len(groups) > MAX_SPLIT_OUTPUTS:
        raise PdfToolError(
            f"Tách {total_pages} trang thành từng {pages_per_split} trang sẽ tạo {len(groups)} tệp, "
            f"vượt quá giới hạn tối đa {MAX_SPLIT_OUTPUTS} tệp mỗi lần tách."
        )
    return groups


def format_page_group_label(pages: Iterable[int]) -> str:
    """Format a page list cleanly into a compact range label like '1-3' or '1-2, 5'."""
    ordered = list(pages)
    if not ordered:
        return ""
    spans: list[str] = []
    start = prev = ordered[0]
    for page in ordered[1:]:
        if page == prev + 1:
            prev = page
        else:
            spans.append(f"{start}" if start == prev else f"{start}-{prev}")
            start = prev = page
    spans.append(f"{start}" if start == prev else f"{start}-{prev}")
    return ", ".join(spans)


def merge_pdfs(sources: list[tuple[str, bytes | Path | str]]) -> tuple[bytes, int]:
    """Merge 2+ PDFs losslessly in the exact order supplied and return (pdf_bytes, total_pages)."""
    if len(sources) < 2:
        raise PdfToolError("Cần chọn ít nhất 2 tệp PDF để ghép.")

    writer = PdfWriter()
    for label, src in sources:
        reader = _open_pdf_reader(src, label=label)
        try:
            writer.append(reader)
        except Exception:
            for page in reader.pages:
                writer.add_page(page)

    total_pages = len(writer.pages)
    if total_pages <= 0:
        raise PdfToolError("Tệp PDF sau khi ghép không có trang nào.")

    out = io.BytesIO()
    writer.write(out)
    return out.getvalue(), total_pages


def split_pdf(
    source: bytes | Path | str,
    page_groups: list[list[int]],
    source_label: str = "tệp PDF",
) -> tuple[list[tuple[bytes, list[int]]], int]:
    """Split a PDF according to page_groups and return ([(pdf_bytes, pages), ...], source_total_pages)."""
    if not page_groups:
        raise PdfToolError("Không có danh sách trang hợp lệ để tách.")

    reader = _open_pdf_reader(source, label=source_label)
    total_pages = len(reader.pages)
    outputs: list[tuple[bytes, list[int]]] = []

    for group in page_groups:
        if not group:
            continue
        writer = PdfWriter()
        for page_num in group:
            if page_num < 1 or page_num > total_pages:
                raise PdfToolError(f"Trang {page_num} nằm ngoài phạm vi tài liệu (1–{total_pages}).")
            writer.add_page(reader.pages[page_num - 1])
        buf = io.BytesIO()
        writer.write(buf)
        outputs.append((buf.getvalue(), group))

    if not outputs:
        raise PdfToolError("Không thể tạo tệp PDF nào từ vùng trang đã chọn.")

    return outputs, total_pages


def safe_pdf_filename(base_name: str, fallback: str = "document") -> str:
    """Normalize an output PDF basename without path separators."""
    cleaned = os.path.basename((base_name or "").strip()).replace("/", "_").replace("\\", "_")
    if cleaned.lower().endswith(".pdf"):
        cleaned = cleaned[:-4].strip()
    return f"{cleaned or fallback}.pdf"

