from datetime import timedelta
import logging
from typing import Annotated
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.config import settings
from app.core.database import get_db
from app.core.rate_limit import limiter
from app.core.security import create_access_token, verify_password
from app.models.user import User
from app.schemas.auth import LoginRequest, TokenResponse, UserResponse

logger = logging.getLogger("podoc.auth")
router = APIRouter(prefix="/auth", tags=["Authentication"])


@router.post("/login", response_model=TokenResponse)
@limiter.limit(settings.RATE_LIMIT_LOGIN)
async def login(
    request: Request,
    credentials: LoginRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Đăng nhập bằng tài khoản admin duy nhất để nhận JWT hạn 30 ngày."""
    stmt = select(User).where(User.username == credentials.username)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()

    if not user or not verify_password(credentials.password, user.hashed_password):
        # Lưu ý: Tuyệt đối không log mật khẩu
        logger.warning(f"Đăng nhập thất bại cho username '{credentials.username}' từ client.")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Tên đăng nhập hoặc mật khẩu không chính xác",
        )

    # 30 ngày tính bằng giây = 30 * 24 * 3600 = 2,592,000 giây
    expire_delta = timedelta(days=settings.ACCESS_TOKEN_EXPIRE_DAYS)
    expires_in_seconds = int(expire_delta.total_seconds())

    token = create_access_token(
        data={"sub": user.username, "user_id": user.id},
        expires_delta=expire_delta,
    )

    logger.info(f"Đăng nhập thành công: {user.username}")
    return TokenResponse(
        access_token=token,
        token_type="bearer",
        expires_in=expires_in_seconds,
    )


@router.get("/me", response_model=UserResponse)
async def get_me(current_user: Annotated[User, Depends(get_current_user)]):
    """Lấy thông tin tài khoản đang đăng nhập."""
    return current_user
