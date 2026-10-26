import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.article import Article
from app.models.category import Category


@pytest.mark.asyncio
async def test_search_vietnamese_unaccent(client: AsyncClient, db_session: AsyncSession):
    """Kiểm tra tìm kiếm tiếng Việt không dấu: gõ 'suc khoe' tìm ra 'sức khoẻ'."""
    # 1. Tạo danh mục test
    cat = Category(name="Kiểm thử", icon="folder", color="#3B82F6", sort_order=1)
    db_session.add(cat)
    await db_session.flush()

    # 2. Tạo bài viết có dấu
    art = Article(
        title="Bí quyết duy trì sức khoẻ dẻo dai",
        content_markdown="Chăm chỉ rèn luyện thể thao giúp tăng cường sức đề kháng và thể lực.",
        excerpt="Chăm chỉ rèn luyện thể thao giúp tăng cường sức đề kháng.",
        category_id=cat.id,
        source_type="URL",
    )
    db_session.add(art)
    await db_session.commit()

    # 3. Đăng nhập lấy token
    login_res = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "admin_test_password"},
    )
    token = login_res.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # 4. Tìm kiếm với từ khoá không dấu "suc khoe"
    res = await client.get("/api/articles?q=suc khoe", headers=headers)
    assert res.status_code == 200
    articles = res.json()
    assert len(articles) >= 1
    assert any("sức khoẻ" in a["title"].lower() for a in articles)
