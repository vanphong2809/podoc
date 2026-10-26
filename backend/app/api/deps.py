from typing import Annotated
from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_db
from app.core.security import hash_password
from app.models.user import User


async def get_current_user(
    db: Annotated[AsyncSession, Depends(get_db)],
) -> User:
    """Đã vô hiệu hoá xác thực: Tự động gán người dùng mặc định (admin) cho mọi API mà không kiểm tra token."""
    # 1. Tìm user admin theo username cấu hình
    stmt = select(User).where(User.username == settings.ADMIN_USERNAME)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()
    if user:
        return user

    # 2. Fallback nếu có bất kỳ user nào trong hệ thống
    stmt_any = select(User).order_by(User.id.asc()).limit(1)
    res_any = await db.execute(stmt_any)
    any_user = res_any.scalar_one_or_none()
    if any_user:
        return any_user

    # 3. Nếu CSDL hoàn toàn mới (chưa có user nào), tự động tạo tài khoản admin ngay lập tức
    new_admin = User(
        username=settings.ADMIN_USERNAME,
        hashed_password=hash_password(settings.ADMIN_PASSWORD),
    )
    db.add(new_admin)
    await db.commit()
    await db.refresh(new_admin)
    return new_admin
