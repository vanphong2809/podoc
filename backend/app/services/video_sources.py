"""Cheap, revisioned playback descriptions; never run ffprobe in a feed request."""
import hashlib
from pathlib import Path

from app.core.config import settings
from app.models.article import Article
from app.services.video_transcoder import is_video_file, smaller_optimized_path


def file_revision(path: Path) -> str:
    info = path.stat()
    identity = f"{path.name}:{info.st_size}:{info.st_mtime_ns}"
    return hashlib.sha256(identity.encode()).hexdigest()[:32]


def describe_video(article: Article) -> dict | None:
    if not is_video_file(article.file_path, article.mime_type, article.file_name):
        return None
    source = Path(settings.FILES_STORAGE_DIR) / article.file_path
    if not source.is_file():
        return None
    optimized = smaller_optimized_path(article.file_path) if article.video_status == "ready" else None
    path = optimized or source
    variant = "optimized" if optimized else "original"
    revision = file_revision(path)
    return {
        "id": article.id, "variant": variant, "revision": revision,
        "url": f"/api/articles/{article.id}/file?inline=true&variant={variant}&revision={revision}",
        "file_size_bytes": path.stat().st_size,
        "mime_type": "video/mp4" if optimized else article.mime_type or "video/mp4",
    }
