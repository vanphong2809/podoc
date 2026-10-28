from typing import Annotated, List
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.database import get_db
from app.models.article import Article
from app.models.category import Category
from app.models.user import User
from app.schemas.category import CategoryCreate, CategoryResponse, CategoryUpdate

router = APIRouter(prefix="/categories", tags=["Categories"])


@router.get("", response_model=List[CategoryResponse])
async def list_categories(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Lấy danh sách toàn bộ danh mục, kèm số lượng bài viết trong mỗi danh mục, sắp xếp theo sort_order."""
    # 1. Tự động khắc phục bài viết mồ côi (category_id null hoặc không hợp lệ) về danh mục mặc định
    default_cat_stmt = select(Category).order_by(Category.is_system.desc(), Category.sort_order.asc(), Category.id.asc()).limit(1)
    default_cat_res = await db.execute(default_cat_stmt)
    default_cat = default_cat_res.scalar_one_or_none()

    if default_cat:
        cat_ids_stmt = select(Category.id)
        from sqlalchemy import or_
        await db.execute(
            update(Article)
            .where(
                or_(
                    Article.category_id.is_(None),
                    Article.category_id.not_in(cat_ids_stmt)
                )
            )
            .values(category_id=default_cat.id)
        )
        await db.commit()

    # 2. Tự động đảm bảo danh mục Âm thanh luôn tồn tại
    audio_cat_stmt = select(Category).where(Category.name == "Âm thanh")
    audio_cat_res = await db.execute(audio_cat_stmt)
    if not audio_cat_res.scalar_one_or_none():
        new_audio_cat = Category(
            name="Âm thanh",
            icon="headphones",
            color="#14B8A6",
            sort_order=7,
            is_system=False,
        )
        db.add(new_audio_cat)
        await db.commit()

    # 3. Truy vấn danh mục kèm số lượng bài viết chuẩn xác
    stmt = (
        select(Category, func.count(Article.id).label("article_count"))
        .outerjoin(Article, Article.category_id == Category.id)
        .group_by(Category.id)
        .order_by(Category.sort_order.asc(), Category.id.asc())
    )
    result = await db.execute(stmt)
    rows = result.all()

    categories_out = []
    for cat, count in rows:
        cat_resp = CategoryResponse(
            id=cat.id,
            name=cat.name,
            icon=cat.icon,
            color=cat.color,
            sort_order=cat.sort_order,
            is_system=cat.is_system,
            article_count=count,
        )
        categories_out.append(cat_resp)

    return categories_out


@router.post("", response_model=CategoryResponse, status_code=status.HTTP_201_CREATED)
async def create_category(
    data: CategoryCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Tạo danh mục mới."""
    existing_stmt = select(Category).where(Category.name == data.name)
    existing_res = await db.execute(existing_stmt)
    if existing_res.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Danh mục '{data.name}' đã tồn tại",
        )

    new_cat = Category(
        name=data.name.strip(),
        icon=data.icon,
        color=data.color,
        sort_order=data.sort_order,
        is_system=False,
    )
    db.add(new_cat)
    await db.commit()
    await db.refresh(new_cat)

    return CategoryResponse(
        id=new_cat.id,
        name=new_cat.name,
        icon=new_cat.icon,
        color=new_cat.color,
        sort_order=new_cat.sort_order,
        is_system=new_cat.is_system,
        article_count=0,
    )


@router.patch("/{id}", response_model=CategoryResponse)
async def update_category(
    id: int,
    data: CategoryUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Cập nhật thông tin danh mục (tên, icon, màu, thứ tự hiển thị)."""
    stmt = select(Category).where(Category.id == id)
    result = await db.execute(stmt)
    cat = result.scalar_one_or_none()

    if not cat:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy danh mục")

    if data.name is not None and data.name.strip() != cat.name:
        check_stmt = select(Category).where(Category.name == data.name.strip(), Category.id != id)
        check_res = await db.execute(check_stmt)
        if check_res.scalar_one_or_none():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Tên danh mục đã tồn tại")
        cat.name = data.name.strip()

    if data.icon is not None:
        cat.icon = data.icon
    if data.color is not None:
        cat.color = data.color
    if data.sort_order is not None:
        cat.sort_order = data.sort_order

    await db.commit()
    await db.refresh(cat)

    count_stmt = select(func.count(Article.id)).where(Article.category_id == cat.id)
    count_res = await db.execute(count_stmt)
    article_count = count_res.scalar_one() or 0

    return CategoryResponse(
        id=cat.id,
        name=cat.name,
        icon=cat.icon,
        color=cat.color,
        sort_order=cat.sort_order,
        is_system=cat.is_system,
        article_count=article_count,
    )


@router.delete("/{id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_category(
    id: int,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """
    Xoá danh mục:
    - Danh mục hệ thống 'Chưa phân loại' (is_system=True) không được phép xoá.
    - Khi xoá một danh mục, toàn bộ bài viết trong đó sẽ tự động chuyển về 'Chưa phân loại'.
    """
    stmt = select(Category).where(Category.id == id)
    result = await db.execute(stmt)
    cat = result.scalar_one_or_none()

    if not cat:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy danh mục")

    if cat.is_system:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Danh mục mặc định của hệ thống không thể bị xoá",
        )

    # Tìm danh mục Chưa phân loại để chuyển bài sang
    uncat_stmt = select(Category).where(Category.is_system == True).limit(1)
    uncat_res = await db.execute(uncat_stmt)
    uncat = uncat_res.scalar_one_or_none()

    if not uncat:
        # Tạo danh mục Chưa phân loại nếu chưa có
        uncat = Category(name="Chưa phân loại", icon="folder", color="#64748B", sort_order=99, is_system=True)
        db.add(uncat)
        await db.flush()

    # Chuyển toàn bộ bài viết sang Chưa phân loại
    await db.execute(
        update(Article).where(Article.category_id == id).values(category_id=uncat.id)
    )

    await db.delete(cat)
    await db.commit()
