"""Initial database schema with unaccent, full-text search trigger, and seed data

Revision ID: 0001_initial_schema
Revises: 
Create Date: 2026-10-26 10:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0001_initial_schema"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Bật extension unaccent để tìm kiếm tiếng Việt không dấu
    op.execute("CREATE EXTENSION IF NOT EXISTS unaccent;")

    # 2. Tạo bảng users
    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("username", sa.String(length=50), nullable=False),
        sa.Column("hashed_password", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_users_username", "users", ["username"], unique=True)

    # 3. Tạo bảng categories
    categories_table = op.create_table(
        "categories",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("icon", sa.String(length=50), nullable=False, server_default="folder"),
        sa.Column("color", sa.String(length=20), nullable=False, server_default="#3B82F6"),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("is_system", sa.Boolean(), nullable=False, server_default="false"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_categories_name", "categories", ["name"], unique=True)

    # 4. Tạo bảng articles
    op.create_table(
        "articles",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("title", sa.String(length=500), nullable=False),
        sa.Column("source_type", sa.String(length=20), nullable=False, server_default="URL"),
        sa.Column("source_url", sa.Text(), nullable=True),
        sa.Column("normalized_url", sa.Text(), nullable=True),
        sa.Column("file_name", sa.String(length=255), nullable=True),
        sa.Column("mime_type", sa.String(length=100), nullable=True),
        sa.Column("content_markdown", sa.Text(), nullable=False, server_default=""),
        sa.Column("excerpt", sa.Text(), nullable=True),
        sa.Column("author", sa.String(length=255), nullable=True),
        sa.Column("site_name", sa.String(length=255), nullable=True),
        sa.Column("image_url", sa.Text(), nullable=True),
        sa.Column("language", sa.String(length=20), nullable=True),
        sa.Column("reading_minutes", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("category_id", sa.Integer(), nullable=False),
        sa.Column("is_favorite", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("read_progress", sa.Float(), nullable=False, server_default="0.0"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("search_vector", postgresql.TSVECTOR(), nullable=True),
        sa.ForeignKeyConstraint(["category_id"], ["categories.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_articles_created_at", "articles", ["created_at"])
    op.create_index("ix_articles_category_id", "articles", ["category_id"])
    op.create_index("ix_articles_is_favorite", "articles", ["is_favorite"])
    op.create_index("ix_articles_normalized_url", "articles", ["normalized_url"])

    # GIN index trên cột search_vector
    op.execute("CREATE INDEX ix_articles_search_vector ON articles USING gin(search_vector);")

    # 5. Trigger tự động tính toán search_vector khi INSERT / UPDATE bài viết
    # Kết hợp 'simple' dictionary + unaccent để tìm kiếm không dấu chuẩn xác
    op.execute("""
    CREATE OR REPLACE FUNCTION articles_search_vector_trigger() RETURNS trigger AS $$
    BEGIN
      NEW.search_vector :=
        setweight(to_tsvector('simple', unaccent(coalesce(NEW.title, ''))), 'A') ||
        setweight(to_tsvector('simple', unaccent(coalesce(NEW.excerpt, ''))), 'B') ||
        setweight(to_tsvector('simple', unaccent(coalesce(NEW.content_markdown, ''))), 'C');
      RETURN NEW;
    END
    $$ LANGUAGE plpgsql;
    """)

    op.execute("DROP TRIGGER IF EXISTS tsvectorupdate ON articles;")
    op.execute("""
    CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE
    ON articles FOR EACH ROW EXECUTE FUNCTION articles_search_vector_trigger();
    """)

    # 6. Seed 7 danh mục mặc định ban đầu
    op.bulk_insert(
        categories_table,
        [
            {"name": "Công nghệ", "icon": "laptop", "color": "#2563EB", "sort_order": 1, "is_system": False},
            {"name": "Sức khoẻ", "icon": "heart", "color": "#10B981", "sort_order": 2, "is_system": False},
            {"name": "Tài chính", "icon": "dollar", "color": "#F59E0B", "sort_order": 3, "is_system": False},
            {"name": "Học tập", "icon": "book", "color": "#8B5CF6", "sort_order": 4, "is_system": False},
            {"name": "Công việc", "icon": "briefcase", "color": "#EC4899", "sort_order": 5, "is_system": False},
            {"name": "Giải trí", "icon": "film", "color": "#06B6D4", "sort_order": 6, "is_system": False},
            {"name": "Chưa phân loại", "icon": "folder", "color": "#64748B", "sort_order": 99, "is_system": True},
        ],
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS tsvectorupdate ON articles;")
    op.execute("DROP FUNCTION IF EXISTS articles_search_vector_trigger();")
    op.drop_index("ix_articles_search_vector", table_name="articles")
    op.drop_table("articles")
    op.drop_table("categories")
    op.drop_table("users")
