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


@pytest.mark.asyncio
async def test_upload_video_and_size_limits(client: AsyncClient, db_session: AsyncSession):
    """Kiểm tra upload video và giới hạn dung lượng: ảnh <= 5MB, video <= 10MB, file <= 10MB."""
    # 1. Upload video hợp lệ (< 10MB)
    video_content = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 1024
    files = {"file": ("demo_video.mp4", video_content, "video/mp4")}
    data = {"title": "Video giới thiệu"}

    upload_resp = await client.post("/api/articles/upload", files=files, data=data)
    assert upload_resp.status_code == 201
    video_data = upload_resp.json()
    assert video_data["title"] == "Video giới thiệu"
    assert video_data["mime_type"] == "video/mp4"
    assert "Tệp video đính kèm" in video_data["excerpt"]

    # 2. Upload ảnh > 5MB bị từ chối 413
    big_image_content = b"\x89PNG\r\n\x1a\n" + b"\x00" * (6 * 1024 * 1024)
    resp_img_too_big = await client.post(
        "/api/articles/upload",
        files={"file": ("too_big.png", big_image_content, "image/png")}
    )
    assert resp_img_too_big.status_code == 413

    # 3. Upload video > 10MB bị từ chối 413
    big_video_content = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * (11 * 1024 * 1024)
    resp_video_too_big = await client.post(
        "/api/articles/upload",
        files={"file": ("too_big.mp4", big_video_content, "video/mp4")}
    )
    assert resp_video_too_big.status_code == 413


@pytest.mark.asyncio
async def test_filter_articles_by_content_type(client: AsyncClient, db_session: AsyncSession):
    """Kiểm tra lọc bài viết theo content_type (article, image, video, file)."""
    # Upload 1 ảnh
    await client.post(
        "/api/articles/upload",
        files={"file": ("test_pic.png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 100, "image/png")},
        data={"title": "Ảnh Test Filter"}
    )
    # Upload 1 video
    await client.post(
        "/api/articles/upload",
        files={"file": ("test_clip.mp4", b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 100, "video/mp4")},
        data={"title": "Clip Test Filter"}
    )
    # Upload 1 doc
    await client.post(
        "/api/articles/upload",
        files={"file": ("test_doc.pdf", b"%PDF-1.4" + b"\x00" * 100, "application/pdf")},
        data={"title": "PDF Test Filter"}
    )

    # Test lọc image
    img_resp = await client.get("/api/articles?content_type=image")
    assert img_resp.status_code == 200
    img_items = img_resp.json()
    assert all(it["mime_type"] and it["mime_type"].startswith("image/") for it in img_items)

    # Test lọc video
    vid_resp = await client.get("/api/articles?content_type=video")
    assert vid_resp.status_code == 200
    vid_items = vid_resp.json()
    assert all(it["mime_type"] and it["mime_type"].startswith("video/") for it in vid_items)

    # Test lọc file
    file_resp = await client.get("/api/articles?content_type=file")
    assert file_resp.status_code == 200
    file_items = file_resp.json()
    assert all(
        it["source_type"] == "FILE" and 
        not (it["mime_type"] or "").startswith("image/") and 
        not (it["mime_type"] or "").startswith("video/") 
        for it in file_items
    )

