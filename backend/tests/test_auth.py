import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_login_success(client: AsyncClient):
    """Kiểm tra đăng nhập thành công trả về JWT Token hợp lệ hạn 30 ngày."""
    response = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "admin_test_password"},
    )
    assert response.status_code == 200
    data = response.json()
    assert "access_token" in data
    assert data["token_type"] == "bearer"
    assert data["expires_in"] == 2592000  # 30 ngày = 2,592,000s


@pytest.mark.asyncio
async def test_login_wrong_password(client: AsyncClient):
    """Kiểm tra đăng nhập sai mật khẩu trả về 401."""
    response = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "wrong_password_123"},
    )
    assert response.status_code == 401
    assert "Tên đăng nhập hoặc mật khẩu không chính xác" in response.json()["detail"]


@pytest.mark.asyncio
async def test_login_nonexistent_user(client: AsyncClient):
    """Kiểm tra đăng nhập với tài khoản không tồn tại trả về 401."""
    response = await client.post(
        "/api/auth/login",
        json={"username": "ghost_user", "password": "any_password"},
    )
    assert response.status_code == 401
    assert "Tên đăng nhập hoặc mật khẩu không chính xác" in response.json()["detail"]


@pytest.mark.asyncio
async def test_get_me_without_token(client: AsyncClient, monkeypatch):
    """Kiểm tra truy cập endpoint khi tắt đăng nhập (REQUIRE_AUTH=False) trả về 200 admin."""
    from app.core.config import settings
    monkeypatch.setattr(settings, "REQUIRE_AUTH", False)
    response = await client.get("/api/auth/me")
    assert response.status_code == 200
    assert response.json()["username"] == "admin"


@pytest.mark.asyncio
async def test_get_me_without_token_when_auth_required(client: AsyncClient, monkeypatch):
    """Kiểm tra khi bật bắt buộc đăng nhập (REQUIRE_AUTH=True), không có token trả về 401."""
    from app.core.config import settings
    monkeypatch.setattr(settings, "REQUIRE_AUTH", True)
    response = await client.get("/api/auth/me")
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_get_me_with_invalid_token(client: AsyncClient, monkeypatch):
    """Kiểm tra truy cập endpoint bảo vệ với token giả mạo khi REQUIRE_AUTH=True trả về 401."""
    from app.core.config import settings
    monkeypatch.setattr(settings, "REQUIRE_AUTH", True)
    response = await client.get(
        "/api/auth/me",
        headers={"Authorization": "Bearer invalid_fake_jwt_token_here"},
    )
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_get_me_authenticated(client: AsyncClient):
    """Kiểm tra truy cập endpoint bảo vệ với token hợp lệ trả về thông tin user."""
    # 1. Đăng nhập lấy token
    login_res = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "admin_test_password"},
    )
    assert login_res.status_code == 200
    token = login_res.json()["access_token"]

    # 2. Gọi /api/auth/me với token
    me_res = await client.get(
        "/api/auth/me",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert me_res.status_code == 200
    data = me_res.json()
    assert data["username"] == "admin"
    assert "id" in data
