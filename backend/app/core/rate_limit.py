from starlette.requests import Request
from slowapi import Limiter
from slowapi.util import get_remote_address

from app.core.config import settings


def get_real_ip(request: Request) -> str:
    """Lấy IP thực tế của client qua reverse proxy (Caddy) hoặc trực tiếp."""
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded:
        # X-Forwarded-For có thể chứa chuỗi "client, proxy1, proxy2"
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("X-Real-IP")
    if real_ip:
        return real_ip.strip()
    return get_remote_address(request) or "127.0.0.1"


# Khởi tạo limiter lưu trữ trong RAM (in-memory, không cần Redis)
limiter = Limiter(key_func=get_real_ip, default_limits=[])
