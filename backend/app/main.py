from contextlib import asynccontextmanager
import logging
from fastapi import FastAPI, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from sqlalchemy import text, select

from app.api.v1.articles import router as articles_router
from app.api.v1.auth import router as auth_router
from app.api.v1.categories import router as categories_router
from app.api.v1.export import router as export_router
from app.api.v1.extract import router as extract_router
from app.core.config import settings
from app.core.database import AsyncSessionLocal, engine
from app.core.rate_limit import limiter
from app.core.security import hash_password
from app.models.category import Category
from app.models.user import User

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("podoc.main")

# Danh mục mặc định ban đầu theo yêu cầu
DEFAULT_CATEGORIES = [
    {"name": "Công nghệ", "icon": "laptop", "color": "#2563EB", "sort_order": 1, "is_system": False},
    {"name": "Sức khoẻ", "icon": "heart", "color": "#10B981", "sort_order": 2, "is_system": False},
    {"name": "Tài chính", "icon": "dollar", "color": "#F59E0B", "sort_order": 3, "is_system": False},
    {"name": "Học tập", "icon": "book", "color": "#8B5CF6", "sort_order": 4, "is_system": False},
    {"name": "Công việc", "icon": "briefcase", "color": "#EC4899", "sort_order": 5, "is_system": False},
    {"name": "Giải trí", "icon": "film", "color": "#06B6D4", "sort_order": 6, "is_system": False},
    {"name": "Chưa phân loại", "icon": "folder", "color": "#64748B", "sort_order": 99, "is_system": True},
]


async def seed_initial_data():
    """Khởi tạo tài khoản admin và danh mục mặc định nếu chưa có."""
    async with AsyncSessionLocal() as session:
        try:
            # 1. Kiểm tra và tạo tài khoản Admin duy nhất
            stmt = select(User).where(User.username == settings.ADMIN_USERNAME)
            result = await session.execute(stmt)
            admin = result.scalar_one_or_none()

            if not admin:
                logger.info(f"Khởi tạo tài khoản Admin: {settings.ADMIN_USERNAME}")
                new_admin = User(
                    username=settings.ADMIN_USERNAME,
                    hashed_password=hash_password(settings.ADMIN_PASSWORD),
                )
                session.add(new_admin)
            else:
                # Cập nhật mật khẩu nếu thay đổi trong .env
                admin.hashed_password = hash_password(settings.ADMIN_PASSWORD)

            # 2. Kiểm tra và tạo các danh mục mặc định
            for cat_data in DEFAULT_CATEGORIES:
                cat_stmt = select(Category).where(Category.name == cat_data["name"])
                cat_res = await session.execute(cat_stmt)
                existing_cat = cat_res.scalar_one_or_none()
                if not existing_cat:
                    logger.info(f"Khởi tạo danh mục mặc định: {cat_data['name']}")
                    session.add(Category(**cat_data))

            await session.commit()
            logger.info("Hoàn tất kiểm tra dữ liệu khởi tạo ban đầu.")
        except Exception as e:
            await session.rollback()
            logger.error(f"Lỗi khi khởi tạo dữ liệu ban đầu: {e}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Quản lý vòng đời khởi động và tắt ứng dụng FastAPI."""
    logger.info("PoDoc Backend đang khởi động...")
    try:
        await seed_initial_data()
    except Exception as e:
        logger.warning(f"Chưa thể kết nối DB để seed lúc start (có thể DB đang chờ migrate): {e}")
    yield
    logger.info("PoDoc Backend đang tắt kết nối...")
    await engine.dispose()


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend API cho PoDoc - Lưu trữ và quản lý tài liệu cá nhân",
    lifespan=lifespan,
)

# Cấu hình Rate Limiter
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Cấu hình CORS cho Mobile App và các client
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Health Check Endpoint không cần xác thực
@app.get("/health", tags=["System"])
async def health_check():
    """Kiểm tra trạng thái hoạt động của hệ thống và kết nối PostgreSQL."""
    db_status = "connected"
    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
    except Exception as e:
        db_status = f"disconnected: {str(e)}"
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"status": "error", "database": db_status, "version": settings.VERSION},
        )

    return {
        "status": "ok",
        "database": db_status,
        "version": settings.VERSION,
    }


# Đăng ký Routers API v1
app.include_router(auth_router, prefix="/api")
app.include_router(extract_router, prefix="/api")
app.include_router(categories_router, prefix="/api")
app.include_router(articles_router, prefix="/api")
app.include_router(export_router, prefix="/api")
