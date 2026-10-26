from typing import Annotated, Optional
from fastapi import Depends, HTTPException, Query, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_db
from app.core.security import decode_access_token
from app.models.user import User

security_scheme = HTTPBearer(auto_error=False)


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(security_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
    token: Optional[str] = Query(None, description="Access token xác thực tuỳ chọn qua query param"),
) -> User:
    """Xác thực JWT token từ Header Authorization: Bearer <token> hoặc query param ?token=.
    Nếu tắt chức năng đăng nhập (REQUIRE_AUTH = False), tự động gán tài khoản mặc định (Admin).
    """
    raw_token = credentials.credentials if credentials else token
    if raw_token:
        payload = decode_access_token(raw_token)
        if payload:
            username: str | None = payload.get("sub")
            if username:
                stmt = select(User).where(User.username == username)
                result = await db.execute(stmt)
                user = result.scalar_one_or_none()
                if user:
                    return user

    if not settings.REQUIRE_AUTH:
        # Tự động gán tài khoản mặc định (Admin)
        stmt = select(User).where(User.username == settings.ADMIN_USERNAME)
        result = await db.execute(stmt)
        user = result.scalar_one_or_none()
        if user:
            return user

        # Fallback nếu admin chưa được tạo: lấy user đầu tiên trong CSDL
        stmt_any = select(User).order_by(User.id.asc()).limit(1)
        res_any = await db.execute(stmt_any)
        any_user = res_any.scalar_one_or_none()
        if any_user:
            return any_user

    if not raw_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Yêu cầu xác thực tài khoản",
            headers={"WWW-Authenticate": "Bearer"},
        )

    payload = decode_access_token(raw_token)
    if not payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token không hợp lệ hoặc đã hết hạn",
            headers={"WWW-Authenticate": "Bearer"},
        )

    username: str | None = payload.get("sub")
    if not username:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token không chứa thông tin định danh",
            headers={"WWW-Authenticate": "Bearer"},
        )

    stmt = select(User).where(User.username == username)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Tài khoản không tồn tại trên hệ thống",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return user
