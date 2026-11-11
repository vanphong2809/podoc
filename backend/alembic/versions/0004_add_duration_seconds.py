"""Add duration_seconds column to articles table

Revision ID: 0004_add_duration_seconds
Revises: 0003_video_transcoding
Create Date: 2026-11-11 11:05:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "0004_add_duration_seconds"
down_revision: Union[str, None] = "0003_video_transcoding"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("articles", sa.Column("duration_seconds", sa.Float(), nullable=True))


def downgrade() -> None:
    op.drop_column("articles", "duration_seconds")

