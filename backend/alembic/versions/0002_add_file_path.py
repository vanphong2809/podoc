"""Add file_path column to articles table

Revision ID: 0002_add_file_path
Revises: 0001_initial_schema
Create Date: 2026-10-26 11:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "0002_add_file_path"
down_revision: Union[str, None] = "0001_initial_schema"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("articles", sa.Column("file_path", sa.String(length=255), nullable=True))


def downgrade() -> None:
    op.drop_column("articles", "file_path")
