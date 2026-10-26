import io
import json
import os
import re
import zipfile
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.config import settings
from app.models.article import Article
from app.models.category import Category


def sanitize_filename(name: str) -> str:
    """Loại bỏ các ký tự đặc biệt để tạo tên file an toàn trong file zip."""
    clean = re.sub(r'[\\/*?:"<>|]', "", name)
    clean = clean.replace(" ", "_").strip()
    return clean[:50] or "article"


async def generate_export_zip(db: AsyncSession) -> io.BytesIO:
    """
    Tạo file zip chứa:
    - Toàn bộ bài viết dưới dạng file .md (kèm frontmatter)
    - Metadata chi tiết dưới dạng JSON
    - Thư mục files/ chứa toàn bộ file gốc đã tải lên (/data/files)
    """
    stmt = (
        select(Article)
        .options(selectinload(Article.category))
        .order_by(Article.created_at.desc())
    )
    result = await db.execute(stmt)
    articles = result.scalars().all()

    cat_stmt = select(Category).order_by(Category.sort_order)
    cat_result = await db.execute(cat_stmt)
    categories = cat_result.scalars().all()

    zip_buffer = io.BytesIO()

    with zipfile.ZipFile(zip_buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        # 1. File tổng hợp index metadata.json
        manifest = {
            "version": "1.0",
            "total_articles": len(articles),
            "categories": [
                {
                    "id": c.id,
                    "name": c.name,
                    "icon": c.icon,
                    "color": c.color,
                    "sort_order": c.sort_order,
                }
                for c in categories
            ],
            "articles": [],
        }

        # 2. Ghi từng bài viết vào zip
        for art in articles:
            safe_title = sanitize_filename(art.title)
            base_name = f"articles/{art.id}_{safe_title}"

            cat_name = art.category.name if art.category else "Chưa phân loại"

            # Metadata JSON riêng từng bài
            art_meta = {
                "id": art.id,
                "title": art.title,
                "source_type": art.source_type,
                "source_url": art.source_url,
                "file_name": art.file_name,
                "mime_type": art.mime_type,
                "author": art.author,
                "site_name": art.site_name,
                "category": cat_name,
                "is_favorite": art.is_favorite,
                "read_progress": art.read_progress,
                "reading_minutes": art.reading_minutes,
                "created_at": art.created_at.isoformat() if art.created_at else None,
            }
            manifest["articles"].append(art_meta)

            zf.writestr(f"{base_name}.json", json.dumps(art_meta, ensure_ascii=False, indent=2))

            # File Markdown (.md) có kèm YAML front-matter
            frontmatter = (
                f"---\n"
                f"title: \"{art.title}\"\n"
                f"category: \"{cat_name}\"\n"
                f"source_type: \"{art.source_type}\"\n"
                f"source_url: \"{art.source_url or ''}\"\n"
                f"author: \"{art.author or ''}\"\n"
                f"created_at: \"{art.created_at.isoformat() if art.created_at else ''}\"\n"
                f"---\n\n"
            )
            md_content = frontmatter + (art.content_markdown or "")
            zf.writestr(f"{base_name}.md", md_content)

            # Đính kèm file gốc trong thư mục files/ nếu có
            if art.file_path:
                disk_path = os.path.join(settings.FILES_STORAGE_DIR, art.file_path)
                if os.path.exists(disk_path):
                    orig_file_name = art.file_name or art.file_path
                    zf.write(disk_path, f"files/{art.id}_{orig_file_name}")

        # Ghi file manifest tổng
        zf.writestr("metadata.json", json.dumps(manifest, ensure_ascii=False, indent=2))

    zip_buffer.seek(0)
    return zip_buffer
