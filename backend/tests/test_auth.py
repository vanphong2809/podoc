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
async def test_get_me_without_token(client: AsyncClient):
    """Kiểm tra truy cập endpoint mà không có token luôn trả về 200 admin (đã bỏ authen)."""
    response = await client.get("/api/auth/me")
    assert response.status_code == 200
    assert response.json()["username"] == "admin"


@pytest.mark.asyncio
async def test_get_me_with_invalid_token(client: AsyncClient):
    """Kiểm tra truy cập endpoint với token bất kỳ vẫn trả về 200 admin mà không chặn 401."""
    response = await client.get(
        "/api/auth/me",
        headers={"Authorization": "Bearer invalid_fake_jwt_token_here"},
    )
    assert response.status_code == 200
    assert response.json()["username"] == "admin"


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
