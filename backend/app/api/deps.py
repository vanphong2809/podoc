from typing import Annotated, Optional
from fastapi import Depends, HTTPException, Query, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_db
from app.core.security import decode_access_token, hash_password
from app.models.user import User

security_scheme = HTTPBearer(auto_error=False)


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(security_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
    token: Optional[str] = Query(None, description="Access token xác thực tuỳ chọn qua query param"),
) -> User:
    """
    Xác thực người dùng (JWT Token):
    - Hỗ trợ Bearer Token qua Header 'Authorization: Bearer <token>' hoặc query param '?token=<token>'.
    - Nếu có token hợp lệ: Trả về tài khoản người dùng tương ứng.
    - Nếu REQUIRE_AUTH = False (môi trường nội bộ / tin cậy): Cho phép tự động gán admin mà không bắt buộc đăng nhập.
    - Nếu REQUIRE_AUTH = True (môi trường Internet công khai): Chặn toàn bộ request không có token hoặc token sai với mã 401.
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

    # Chế độ tin cậy nội bộ (REQUIRE_AUTH = False)
    if not settings.REQUIRE_AUTH:
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

        # 3. Nếu CSDL hoàn toàn mới, tự động tạo tài khoản admin
        new_admin = User(
            username=settings.ADMIN_USERNAME,
            hashed_password=hash_password(settings.ADMIN_PASSWORD),
        )
        db.add(new_admin)
        await db.commit()
        await db.refresh(new_admin)
        return new_admin

    # Chế độ bảo mật công khai (REQUIRE_AUTH = True): Bắt buộc xác thực
    if not raw_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Yêu cầu xác thực tài khoản (Thiếu Bearer Token). Vui lòng đăng nhập.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Token xác thực không hợp lệ hoặc đã hết hạn",
        headers={"WWW-Authenticate": "Bearer"},
    )
