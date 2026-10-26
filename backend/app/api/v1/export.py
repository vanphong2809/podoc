from datetime import datetime, timezone
from typing import Annotated
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.database import get_db
from app.models.user import User
from app.services.export_service import generate_export_zip

router = APIRouter(prefix="", tags=["Export"])


@router.get("/export")
async def export_data(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Xuất toàn bộ dữ liệu người dùng ra file .ZIP:
    - Chứa tất cả bài viết định dạng Markdown (.md) có kèm metadata front-matter.
    - File JSON metadata chi tiết cho từng bài và metadata.json tổng hợp.
    - Toàn bộ file gốc đính kèm trong thư mục files/.
    """
    zip_buffer = await generate_export_zip(db)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    filename = f"podoc_backup_{timestamp}.zip"

    return StreamingResponse(
        zip_buffer,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
