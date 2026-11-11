import io
import json
from pypdf import PdfReader, PdfWriter
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.services.pdf_tools import (
    MAX_SPLIT_OUTPUTS,
    PdfToolError,
    build_fixed_page_groups,
    format_page_group_label,
    merge_pdfs,
    parse_page_ranges,
    split_pdf,
)


def _make_sample_pdf(num_pages: int, prefix: str = "Page") -> bytes:
    """Create a valid in-memory PDF with num_pages blank pages labeled sequentially."""
    writer = PdfWriter()
    for i in range(num_pages):
        page = writer.add_blank_page(width=200, height=200)
        page.mediabox.upper_right = (200 + i, 200)
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def test_parse_page_ranges_and_validation():
    assert parse_page_ranges("1-3, 5", total_pages=6, merge_ranges=False) == [[1, 2, 3], [5]]
    assert parse_page_ranges("1-3, 2-4, 6", total_pages=6, merge_ranges=True) == [[1, 2, 3, 4, 6]]
    assert format_page_group_label([1, 2, 3, 5, 7, 8]) == "1-3, 5, 7-8"

    with pytest.raises(PdfToolError):
        parse_page_ranges("", total_pages=5)
    with pytest.raises(PdfToolError):
        parse_page_ranges("4-2", total_pages=5)
    with pytest.raises(PdfToolError):
        parse_page_ranges("7", total_pages=5)
    with pytest.raises(PdfToolError):
        parse_page_ranges("0-2", total_pages=5)


def test_build_fixed_page_groups_and_max_limit():
    assert build_fixed_page_groups(7, 3) == [[1, 2, 3], [4, 5, 6], [7]]
    with pytest.raises(PdfToolError):
        build_fixed_page_groups(MAX_SPLIT_OUTPUTS + 1, 1)


def test_merge_and_split_pdf_roundtrip():
    pdf_a = _make_sample_pdf(2)
    pdf_b = _make_sample_pdf(3)

    merged_bytes, total_pages = merge_pdfs([("a.pdf", pdf_a), ("b.pdf", pdf_b)])
    assert total_pages == 5
    assert len(PdfReader(io.BytesIO(merged_bytes)).pages) == 5

    groups = parse_page_ranges("1-2, 4-5", total_pages=total_pages, merge_ranges=False)
    outputs, src_pages = split_pdf(merged_bytes, groups)
    assert src_pages == 5
    assert len(outputs) == 2
    assert len(PdfReader(io.BytesIO(outputs[0][0])).pages) == 2
    assert len(PdfReader(io.BytesIO(outputs[1][0])).pages) == 2


@pytest.mark.asyncio
async def test_merge_and_split_endpoints(client: AsyncClient, db_session: AsyncSession):
    cat = Category(name="Tài liệu PDF", icon="folder", color="#0284C7")
    db_session.add(cat)
    await db_session.commit()

    pdf1 = _make_sample_pdf(2)
    pdf2 = _make_sample_pdf(3)

    # 1. Merge 2 uploaded PDFs -> saves new 5-page Article
    merge_resp = await client.post(
        "/api/pdf/merge",
        files=[
            ("files", ("part1.pdf", pdf1, "application/pdf")),
            ("files", ("part2.pdf", pdf2, "application/pdf")),
        ],
        data={
            "sources_json": json.dumps([{"type": "upload", "index": 1}, {"type": "upload", "index": 0}]),
            "title": "Tai lieu da ghep",
            "category_id": str(cat.id),
        },
    )
    assert merge_resp.status_code == 201
    merged_art = merge_resp.json()
    assert merged_art["title"] == "Tai lieu da ghep"
    assert merged_art["file_name"] == "Tai lieu da ghep.pdf"
    assert merged_art["mime_type"] == "application/pdf"
    assert merged_art["category_id"] == cat.id

    # 2. Check /api/pdf/articles/{id}/info
    info_resp = await client.get(f"/api/pdf/articles/{merged_art['id']}/info")
    assert info_resp.status_code == 200
    assert info_resp.json()["page_count"] == 5

    # 3. Split library PDF by ranges
    split_resp = await client.post(
        "/api/pdf/split",
        data={
            "article_id": str(merged_art["id"]),
            "mode": "ranges",
            "ranges": "1-2, 4-5",
            "merge_ranges": "false",
            "title_prefix": "Phan_tach",
        },
    )
    assert split_resp.status_code == 201
    split_data = split_resp.json()
    assert split_data["created_count"] == 2
    assert split_data["source_page_count"] == 5
    assert [a["title"] for a in split_data["articles"]] == [
        "Phan_tach (Trang 1-2)",
        "Phan_tach (Trang 4-5)",
    ]

    # 4. Split uploaded PDF with every_n
    split_fixed_resp = await client.post(
        "/api/pdf/split",
        files={"file": ("five_pages.pdf", _make_sample_pdf(5), "application/pdf")},
        data={
            "mode": "every_n",
            "pages_per_split": "2",
            "category_id": str(cat.id),
        },
    )
    assert split_fixed_resp.status_code == 201
    assert split_fixed_resp.json()["created_count"] == 3
