"""Disk-backed video derivatives with a durable, single-encoder DB queue."""
import asyncio
from contextlib import suppress
import fcntl
from fractions import Fraction
import json
import logging
from pathlib import Path
import tempfile

from sqlalchemy import select, update

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.article import Article

logger = logging.getLogger(__name__)
VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv", ".m4v"}


def is_video_file(file_path: str | None, mime_type: str | None, file_name: str | None = None) -> bool:
    return bool(file_path) and (
        (mime_type or "").lower().startswith("video/")
        or Path(file_name or "").suffix.lower() in VIDEO_EXTENSIONS
        or Path(file_path).suffix.lower() in VIDEO_EXTENSIONS
    )


def optimized_path(file_path: str, storage_dir: str | None = None) -> Path:
    # Include the entire stored basename to avoid collisions between extensions.
    return Path(storage_dir or settings.FILES_STORAGE_DIR) / f"optimized_{Path(file_path).name}.mp4"


def remove_video_derivatives(file_path: str) -> None:
    output = optimized_path(file_path)
    output.unlink(missing_ok=True)
    output.with_suffix(".part.mp4").unlink(missing_ok=True)


async def run_command(*args: str, timeout: float) -> bytes:
    """Keep encoder logs on disk, cap returned diagnostics, and reap on cancellation."""
    with tempfile.TemporaryFile() as stderr:
        process = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=stderr,
        )
        try:
            stdout, _ = await asyncio.wait_for(process.communicate(), timeout=timeout)
        except BaseException:
            if process.returncode is None:
                with suppress(ProcessLookupError):
                    process.kill()
            await process.wait()
            raise
        if process.returncode:
            stderr.seek(0, 2)
            stderr.seek(max(0, stderr.tell() - 2000))
            raise RuntimeError(stderr.read().decode(errors="replace"))
        return stdout


async def probe_video(source: Path) -> dict:
    raw = await run_command(
        settings.FFPROBE_BINARY, "-v", "error", "-protocol_whitelist", "file,pipe",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height,avg_frame_rate,color_transfer:format=duration",
        "-of", "json", str(source), timeout=30,
    )
    data = json.loads(raw)
    if not data.get("streams"):
        raise ValueError("No video stream found")
    return data


async def transcode_video(source: Path, partial: Path) -> None:
    metadata = await probe_video(source)
    stream = metadata["streams"][0]
    # Respect lower frame rates rather than duplicating e.g. 24 fps to 30 fps.
    try:
        fps = min(Fraction(stream.get("avg_frame_rate", "30/1")), Fraction(30))
        if fps <= 0:
            fps = Fraction(30)
    except (ValueError, ZeroDivisionError):
        fps = Fraction(30)
    # FFmpeg autorotates before filtering. Fit both orientations without cropping
    # or upscaling, keep SAR/aspect ratio, and ensure even dimensions for yuv420p.
    filters = [
        # Drop surplus frames before scaling/tone mapping 4K sources.
        f"fps={fps.numerator}/{fps.denominator}",
        "scale=w='min(iw,if(gte(iw,ih),1920,1080))':"
        "h='min(ih,if(gte(iw,ih),1080,1920))':"
        "force_original_aspect_ratio=decrease:force_divisible_by=2",
    ]
    # Phone HDR needs tone mapping before conversion to 8-bit SDR H.264.
    if stream.get("color_transfer") in ("smpte2084", "arib-std-b67"):
        filters.extend([
            "zscale=t=linear:npl=100", "format=gbrpf32le",
            "zscale=p=bt709", "tonemap=tonemap=hable:desat=0",
            "zscale=t=bt709:m=bt709:r=tv",
        ])
    await run_command(
        settings.FFMPEG_BINARY, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-threads", "1", "-filter_threads", "1", "-protocol_whitelist", "file,pipe",
        "-i", str(source), "-map", "0:v:0", "-map", "0:a:0?",
        "-vf", ",".join(filters), "-c:v", "libx264", "-preset", "fast",
        "-crf", "24", "-maxrate", "3500k", "-bufsize", "7000k",
        "-threads", "1", "-x264-params", "rc-lookahead=10:ref=2",
        "-pix_fmt", "yuv420p", "-tag:v", "avc1", "-g", "60",
        "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
        "-map_metadata", "-1", "-movflags", "+faststart", str(partial),
        timeout=settings.VIDEO_TRANSCODING_TIMEOUT_SECONDS,
    )
    if not partial.is_file() or partial.stat().st_size == 0:
        raise RuntimeError("Encoder produced an empty video")
    await probe_video(partial)


class VideoTranscoder:
    def __init__(self, session_factory=None):
        self.session_factory = session_factory or AsyncSessionLocal
        self.task: asyncio.Task | None = None

    def start(self) -> None:
        if settings.VIDEO_TRANSCODING_ENABLED:
            self.task = asyncio.create_task(self.run(), name="video-transcoder")

    async def stop(self) -> None:
        if self.task:
            self.task.cancel()
            with suppress(asyncio.CancelledError):
                await self.task
            self.task = None

    async def reset_interrupted(self) -> None:
        async with self.session_factory() as db:
            await db.execute(update(Article).where(
                Article.video_status == "processing",
            ).values(video_status="pending", updated_at=Article.updated_at))
            await db.commit()

    async def process_next(self) -> bool:
        async with self.session_factory() as db:
            result = await db.execute(select(Article).where(
                Article.video_status == "pending",
            ).order_by(Article.id).limit(1))
            article = result.scalar_one_or_none()
            if article is None:
                return False
            article_id, file_path = article.id, article.file_path
            await db.execute(update(Article).where(Article.id == article_id).values(
                video_status="processing", updated_at=Article.updated_at,
            ))
            await db.commit()

        output = optimized_path(file_path) if file_path else None
        partial = output.with_suffix(".part.mp4") if output else None
        try:
            if not file_path:
                raise FileNotFoundError("Original video path is missing")
            source = Path(settings.FILES_STORAGE_DIR) / file_path
            await transcode_video(source, partial)
            # Re-check after encoding: a deleted article must not leave an orphan.
            async with self.session_factory() as db:
                exists = await db.scalar(select(Article.id).where(
                    Article.id == article_id, Article.file_path == file_path,
                ))
                if exists is None or not source.is_file():
                    return True
                partial.replace(output)  # Atomic publish, never serve partial MP4.
                await db.execute(update(Article).where(Article.id == article_id).values(
                    video_status="ready", updated_at=Article.updated_at,
                ))
                await db.commit()
            logger.info("Video %s optimized: %s -> %s bytes", article_id, source.stat().st_size, output.stat().st_size)
        except asyncio.CancelledError:
            # Leave processing durable; the next worker resets it to pending.
            raise
        except Exception:
            logger.exception("Video optimization failed for article %s; original remains available", article_id)
            async with self.session_factory() as db:
                await db.execute(update(Article).where(Article.id == article_id).values(
                    video_status="failed", updated_at=Article.updated_at,
                ))
                await db.commit()
        finally:
            if partial:
                partial.unlink(missing_ok=True)
        return True

    async def run(self) -> None:
        # A shared disk lock also prevents two uvicorn workers/restarts from
        # encoding concurrently or resetting each other's active DB jobs.
        while True:
            try:
                storage = Path(settings.FILES_STORAGE_DIR)
                storage.mkdir(parents=True, exist_ok=True)
                with (storage / ".video-transcoder.lock").open("a") as lock:
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
                        continue
                    await self.reset_interrupted()
                    while True:
                        try:
                            if not await self.process_next():
                                await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
                        except Exception:
                            logger.exception("Video queue unavailable; retrying")
                            await self.reset_interrupted()
                            await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
            except Exception:
                logger.exception("Could not start video worker; retrying")
                await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
