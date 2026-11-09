"""Durable background video queue, including previously uploaded videos."""
from alembic import op
import sqlalchemy as sa

revision = "0003_video_transcoding"
down_revision = "0002_add_file_path"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("articles", sa.Column("video_status", sa.String(20), nullable=True))
    op.create_index("ix_articles_video_status", "articles", ["video_status"])
    articles = sa.table(
        "articles",
        sa.column("file_path", sa.String),
        sa.column("file_name", sa.String),
        sa.column("mime_type", sa.String),
        sa.column("video_status", sa.String),
    )
    extensions = ("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts", "flv", "m4v")
    video = sa.or_(
        articles.c.mime_type.ilike("video/%"),
        *[articles.c.file_name.ilike(f"%.{ext}") for ext in extensions],
        *[articles.c.file_path.ilike(f"%.{ext}") for ext in extensions],
    )
    op.execute(articles.update().where(
        articles.c.file_path.is_not(None), video,
    ).values(video_status="pending"))


def downgrade() -> None:
    op.drop_index("ix_articles_video_status", table_name="articles")
    op.drop_column("articles", "video_status")
