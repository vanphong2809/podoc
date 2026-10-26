import pytest
from httpx import AsyncClient
from app.models.category import Category
from sqlalchemy.ext.asyncio import AsyncSession


@pytest.mark.asyncio
async def test_upload_article_file_direct(client: AsyncClient, db_session: AsyncSession):
    """Kiểm tra upload file trực tiếp lưu nguyên vẹn file vào thư viện, không bóc tách."""
    # 1. Đăng nhập lấy token
    login_resp = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "admin_test_password"},
    )
    assert login_resp.status_code == 200
    token = login_resp.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Tạo category mẫu
    cat = Category(name="Tài liệu mẫu", icon="📁", color="#6366f1")
    db_session.add(cat)
    await db_session.commit()

    # 3. Tải file mẫu lên (ví dụ file txt hoặc pdf)
    file_content = b"Day la noi dung file test de luu truc tiep khong trich xuat text."
    files = {
        "file": ("sample_test_doc.txt", file_content, "text/plain")
    }
    data = {
        "category_id": str(cat.id),
        "title": "Tài liệu mẫu đính kèm"
    }

    upload_resp = await client.post(
        "/api/articles/upload",
        files=files,
        data=data,
        headers=headers,
    )

    assert upload_resp.status_code == 201
    art_data = upload_resp.json()
    assert art_data["title"] == "Tài liệu mẫu đính kèm"
    assert art_data["source_type"] == "FILE"
    assert art_data["file_name"] == "sample_test_doc.txt"
    assert art_data["file_path"] is not None
    assert art_data["mime_type"] == "text/plain"

    # 4. Kiểm tra tải lại file gốc / inline preview
    download_resp = await client.get(
        f"/api/articles/{art_data['id']}/file?inline=true",
        headers=headers,
    )
    assert download_resp.status_code == 200
    assert download_resp.content == file_content
