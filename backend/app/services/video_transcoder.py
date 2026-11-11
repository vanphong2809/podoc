"""Disk-backed video derivatives with a durable, single-encoder DB queue."""
import asyncio
from contextlib import suppress
import fcntl
from fractions import Fraction
import json
import logging
from pathlib import Path
import tempfile
from time import monotonic

from sqlalchemy import select, update

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.article import Article

logger = logging.getLogger(__name__)
VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv", ".m4v"}


class AudioCopyUnsupportedError(RuntimeError):
    """The original audio cannot be muxed into MP4 without re-encoding."""


def is_video_file(file_path: str | None, mime_type: str | None, file_name: str | None = None) -> bool:
    return bool(file_path) and (
        (mime_type or "").lower().startswith("video/")
        or Path(file_name or "").suffix.lower() in VIDEO_EXTENSIONS
        or Path(file_path).suffix.lower() in VIDEO_EXTENSIONS
    )


def optimized_path(file_path: str, storage_dir: str | None = None) -> Path:
    # Include the entire stored basename to avoid collisions between extensions.
    return Path(storage_dir or settings.FILES_STORAGE_DIR) / f"optimized_{Path(file_path).name}.mp4"


def smaller_optimized_path(file_path: str) -> Path | None:
    """Never serve a historical or newly generated derivative larger than its source."""
    output = optimized_path(file_path)
    source = Path(settings.FILES_STORAGE_DIR) / file_path
    try:
        if 0 < output.stat().st_size < source.stat().st_size:
            return output
    except OSError:
        pass
    return None


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
        "-show_entries", "stream=codec_type,codec_name,width,height,avg_frame_rate,color_transfer,bit_rate,channels,sample_rate:format=duration,format_name",
        "-of", "json", str(source), timeout=30,
    )
    data = json.loads(raw)
    if not any(s.get("codec_type") == "video" for s in data.get("streams", [])):
        raise ValueError("No video stream found")
    return data


async def probe_duration_seconds(source: Path) -> float | None:
    """Return rounded media duration in seconds, or None if unavailable."""
    try:
        if not source.is_file():
            return None
        raw = await run_command(
            settings.FFPROBE_BINARY, "-v", "error", "-protocol_whitelist", "file,pipe",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(source), timeout=10,
        )
        duration = float(raw.decode(errors="replace").strip())
        if duration > 0:
            return round(duration, 1)
    except Exception:
        pass
    return None


async def transcode_video(source: Path, partial: Path) -> bool:
    """Return False when an already compact MP4 should be retained losslessly."""
    metadata = await probe_video(source)
    stream = next(s for s in metadata["streams"] if s.get("codec_type") == "video")
    audio_streams = [s for s in metadata["streams"] if s.get("codec_type") == "audio"]
    try:
        duration = float(metadata.get("format", {}).get("duration", "0"))
    except (TypeError, ValueError):
        duration = 0
    total_rate = source.stat().st_size * 8 / duration if duration > 0 else 0
    container = metadata.get("format", {}).get("format_name", "")
    if (duration >= 5 and 0 < total_rate <= 1_200_000 and "mp4" in container
            and stream.get("codec_name") in ("h264", "hevc")
            and all(s.get("codec_name") == "aac" for s in audio_streams)):
        # Re-encoding a compact AVC/HEVC source can enlarge it or lose detail.
        return False

    # Copy every audio track, regardless of codec/bitrate. Never resample,
    # downmix, or fall back to lossy audio encoding to fit the MP4 container.
    audio_rate = 0
    for audio in audio_streams:
        try:
            audio_rate += max(0, int(audio.get("bit_rate", 0))) or 128_000
        except (TypeError, ValueError):
            audio_rate += 128_000
    # Leave headroom for audio/container overhead and cap against source size.
    maxrate = min(3_500_000, max(64_000, int(total_rate * 0.85 - audio_rate - 16_000))) if total_rate else 3_500_000
    long_edge, short_edge = (1280, 720) if maxrate < 1_500_000 else (1920, 1080)
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
        f"scale=w='min(iw,if(gte(iw,ih),{long_edge},{short_edge}))':"
        f"h='min(ih,if(gte(iw,ih),{short_edge},{long_edge}))':"
        "force_original_aspect_ratio=decrease:force_divisible_by=2",
    ]
    # Phone HDR needs tone mapping before conversion to 8-bit SDR H.264.
    if stream.get("color_transfer") in ("smpte2084", "arib-std-b67"):
        filters.extend([
            "zscale=t=linear:npl=100", "format=gbrpf32le",
            "zscale=p=bt709", "tonemap=tonemap=hable:desat=0",
            "zscale=t=bt709:m=bt709:r=tv",
        ])
    try:
        await run_command(
            settings.FFMPEG_BINARY, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-threads", str(settings.VIDEO_TRANSCODING_THREADS), "-filter_threads", "1", "-protocol_whitelist", "file,pipe",
            "-i", str(source), "-map", "0:v:0", "-map", "0:a?",
            "-vf", ",".join(filters), "-c:v", "libx264", "-preset", "fast",
            "-crf", "24", "-maxrate", str(maxrate), "-bufsize", str(maxrate * 2),
            "-threads", str(settings.VIDEO_TRANSCODING_THREADS), "-x264-params", "rc-lookahead=10:ref=2",
            "-pix_fmt", "yuv420p", "-tag:v", "avc1", "-g", "60",
            "-c:a", "copy",
            "-map_metadata", "-1", "-movflags", "+faststart", str(partial),
            timeout=settings.VIDEO_TRANSCODING_TIMEOUT_SECONDS,
        )
    except RuntimeError as exc:
        # With H.264 video fixed above, these muxer errors concern copied audio.
        # Other errors (corrupt media, permissions, etc.) remain real failures.
        if audio_streams and ("Could not find tag for codec" in str(exc)
                              or "not currently supported in container" in str(exc)):
            raise AudioCopyUnsupportedError(
                "codec âm thanh gốc không ghép được vào MP4; giữ nguyên âm thanh, không mã hóa lại"
            ) from exc
        raise
    if not partial.is_file() or partial.stat().st_size == 0:
        raise RuntimeError("Encoder produced an empty video")
    await probe_video(partial)
    return True


class VideoTranscoder:
    def __init__(self, session_factory=None):
        self.session_factory = session_factory or AsyncSessionLocal
        self.task: asyncio.Task | None = None
        self._duration_cursor: int | None = None
        self._duration_backfill_done: bool = False

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

    async def requeue_oversized(self) -> int:
        """Repair derivatives created by older encoders without altering originals."""
        repaired = 0
        async with self.session_factory() as db:
            rows = (await db.execute(select(Article).where(
                Article.video_status == "ready", Article.file_path.is_not(None),
            ))).scalars().all()
            for article in rows:
                source = Path(settings.FILES_STORAGE_DIR) / article.file_path
                output = optimized_path(article.file_path)
                try:
                    oversized = output.is_file() and output.stat().st_size >= source.stat().st_size
                except OSError:
                    continue
                if oversized:
                    await db.execute(update(Article).where(Article.id == article.id).values(
                        video_status="pending", updated_at=Article.updated_at,
                    ))
                    repaired += 1
            await db.commit()
        if repaired:
            logger.info("HÀNG ĐỢI SỬA | số video có bản cũ lớn hơn nguồn=%s", repaired)
        return repaired

    async def process_next(self) -> bool:
        async with self.session_factory() as db:
            result = await db.execute(select(Article).where(
                Article.video_status == "pending",
            # Newly uploaded videos take precedence over the historical backfill.
            ).order_by(Article.created_at.desc(), Article.id.desc()).limit(1))
            article = result.scalar_one_or_none()
            if article is None:
                return False
            article_id, file_path = article.id, article.file_path
            existing_duration = article.duration_seconds
            # Quote the original name so Unicode stays readable and newlines
            # cannot forge extra server log entries.
            log_file_name = json.dumps(article.file_name or Path(file_path or f"video_{article_id}").name, ensure_ascii=False)
            await db.execute(update(Article).where(Article.id == article_id).values(
                video_status="processing", updated_at=Article.updated_at,
            ))
            await db.commit()

        output = optimized_path(file_path) if file_path else None
        partial = output.with_suffix(".part.mp4") if output else None
        previous_output_bytes = output.stat().st_size if output and output.is_file() else 0
        started_at = monotonic()
        try:
            if not file_path:
                raise FileNotFoundError("Original video path is missing")
            source = Path(settings.FILES_STORAGE_DIR) / file_path
            original_bytes = source.stat().st_size
            keep_reason = "file đã gọn, không mã hóa lại"
            try:
                encoded = await transcode_video(source, partial)
            except AudioCopyUnsupportedError as exc:
                encoded = False
                keep_reason = str(exc)
            optimized_bytes = partial.stat().st_size if encoded is not False else original_bytes
            publish = encoded is not False and optimized_bytes < original_bytes
            probed_duration = existing_duration
            if probed_duration is None:
                target_probe = partial if publish and partial is not None and partial.is_file() else source
                probed_duration = await probe_duration_seconds(target_probe)
            # Re-check after encoding: a deleted article must not leave an orphan.
            async with self.session_factory() as db:
                exists = await db.scalar(select(Article.id).where(
                    Article.id == article_id, Article.file_path == file_path,
                ))
                if exists is None or not source.is_file():
                    return True
                if publish:
                    partial.replace(output)  # Atomic publish, never serve partial MP4.
                update_values = {
                    "video_status": "ready" if publish else "kept_original",
                    "updated_at": Article.updated_at,
                }
                if probed_duration is not None and existing_duration is None:
                    update_values["duration_seconds"] = probed_duration
                await db.execute(update(Article).where(Article.id == article_id).values(**update_values))
                await db.commit()
            if not publish:
                output.unlink(missing_ok=True)  # Remove only a generated derivative.
                logger.info(
                    "GIỮ BẢN GỐC | file=%s | gốc=%.2f MB | lý do=%s | bỏ bản lớn hơn=%.2f MB | thời gian=%.1fs | id=%s",
                    log_file_name, original_bytes / (1024 * 1024),
                    keep_reason if encoded is False else "bản mới không nhỏ hơn nguồn",
                    max(previous_output_bytes, optimized_bytes if encoded is not False else 0) / (1024 * 1024),
                    monotonic() - started_at, article_id,
                )
                return True
            size_change = (optimized_bytes / original_bytes - 1) * 100 if original_bytes else 0
            logger.info(
                "NÉN XONG | file=%s | gốc=%.2f MB | bản nén=%.2f MB | thay đổi=%+.1f%% | thời gian=%.1fs | âm thanh=giữ nguyên | id=%s",
                log_file_name, original_bytes / (1024 * 1024), optimized_bytes / (1024 * 1024),
                size_change, monotonic() - started_at, article_id,
            )
        except asyncio.CancelledError:
            # Leave processing durable; the next worker resets it to pending.
            raise
        except Exception:
            logger.exception("NÉN LỖI | file=%s | id=%s | Giữ nguyên file gốc", log_file_name, article_id)
            async with self.session_factory() as db:
                await db.execute(update(Article).where(Article.id == article_id).values(
                    video_status="failed", updated_at=Article.updated_at,
                ))
                await db.commit()
        finally:
            if partial:
                partial.unlink(missing_ok=True)
        return True

    async def backfill_missing_durations(self, limit: int = 25) -> int:
        """Populate cached duration_seconds for previously uploaded videos without re-encoding."""
        updated = 0
        async with self.session_factory() as db:
            stmt = select(Article).where(
                Article.file_path.is_not(None),
                Article.duration_seconds.is_(None),
            )
            if self._duration_cursor is not None:
                stmt = stmt.where(Article.id < self._duration_cursor)
            stmt = stmt.order_by(Article.id.desc()).limit(limit)
            rows = (await db.execute(stmt)).scalars().all()
            if not rows:
                self._duration_backfill_done = True
                return 0
            self._duration_cursor = rows[-1].id
            if len(rows) < limit:
                self._duration_backfill_done = True
            for article in rows:
                if not is_video_file(article.file_path, article.mime_type, article.file_name):
                    continue
                candidate = smaller_optimized_path(article.file_path) or (
                    Path(settings.FILES_STORAGE_DIR) / article.file_path
                )
                duration = await probe_duration_seconds(candidate)
                if duration is not None:
                    await db.execute(update(Article).where(Article.id == article.id).values(
                        duration_seconds=duration, updated_at=Article.updated_at,
                    ))
                    updated += 1
            if updated:
                await db.commit()
        return updated

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
                    await self.requeue_oversized()
                    while True:
                        try:
                            if not await self.process_next():
                                if not self._duration_backfill_done:
                                    await self.backfill_missing_durations(limit=25)
                                    if not self._duration_backfill_done:
                                        continue
                                await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
                        except Exception:
                            logger.exception("Video queue unavailable; retrying")
                            await self.reset_interrupted()
                            await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
            except Exception:
                logger.exception("Could not start video worker; retrying")
                await asyncio.sleep(settings.VIDEO_TRANSCODING_POLL_SECONDS)
