import asyncio
import json
import logging
import os
import re
from pathlib import Path
from typing import Optional, Tuple

from app.core.config import settings

logger = logging.getLogger(__name__)


class AudioExtractionError(Exception):
    """Lỗi xảy ra trong quá trình kiểm tra hoặc trích xuất âm thanh."""
    pass


CODEC_TO_EXT = {
    "aac": "m4a",
    "alac": "m4a",
    "mp3": "mp3",
    "opus": "opus",
    "flac": "flac",
    "vorbis": "ogg",
    "ac3": "ac3",
    "eac3": "eac3",
    "pcm_s16le": "wav",
    "pcm_s24le": "wav",
    "pcm_s32le": "wav",
    "wav": "wav",
}

EXT_TO_MIME = {
    "m4a": "audio/mp4",
    "mp3": "audio/mpeg",
    "opus": "audio/opus",
    "flac": "audio/flac",
    "ogg": "audio/ogg",
    "wav": "audio/wav",
    "ac3": "audio/ac3",
    "eac3": "audio/eac3",
    "aac": "audio/aac",
}


def format_seconds_to_mmss(seconds: Optional[float]) -> Optional[str]:
    """Chuyển số giây sang định dạng mm:ss hoặc hh:mm:ss."""
    if seconds is None or seconds <= 0:
        return None
    total_sec = int(round(seconds))
    h = total_sec // 3600
    m = (total_sec % 3600) // 60
    s = total_sec % 60
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


def get_channels_display(channels: Optional[int]) -> Optional[str]:
    if not channels:
        return None
    if channels == 1:
        return "Mono (1 kênh)"
    if channels == 2:
        return "Stereo (2 kênh)"
    if channels == 6:
        return "5.1 Surround"
    return f"{channels} kênh"


def safe_audio_filename(title: str, ext: str) -> str:
    clean = re.sub(r'[\\/*?:"<>|]', "", title).strip()
    clean = re.sub(r"\s+", "_", clean)
    if not clean:
        clean = "audio"
    if clean.lower().endswith(f".{ext.lower()}"):
        return clean
    return f"{clean}.{ext}"


async def run_process(*args: str, timeout: float = 60.0) -> bytes:
    """Thực thi tiến trình command-line bất đồng bộ an toàn."""
    process = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        try:
            process.kill()
        except OSError:
            pass
        await process.wait()
        raise AudioExtractionError("Quá trình xử lý âm thanh vượt quá thời gian cho phép.")
    except Exception:
        try:
            process.kill()
        except OSError:
            pass
        await process.wait()
        raise

    if process.returncode != 0:
        err_msg = stderr.decode(errors="replace").strip()
        last_err = err_msg.splitlines()[-1] if err_msg else "Lỗi không xác định"
        logger.error(f"Command failed ({process.returncode}): {' '.join(args[:4])}... Error: {last_err}")
        raise AudioExtractionError(f"Lỗi FFmpeg: {last_err}")

    return stdout


async def probe_video_audio(source: Path) -> dict:
    """
    Sử dụng ffprobe để kiểm tra các luồng âm thanh trong tệp video.
    Trả về dict metadata chi tiết của luồng audio đầu tiên.
    """
    if not source.is_file():
        raise AudioExtractionError("Tệp video nguồn không tồn tại trên máy chủ.")

    cmd = [
        settings.FFPROBE_BINARY,
        "-v", "error",
        "-protocol_whitelist", "file,pipe",
        "-show_entries",
        "stream=index,codec_type,codec_name,channels,sample_rate,bit_rate,duration:format=duration,format_name",
        "-of", "json",
        str(source),
    ]

    try:
        raw = await run_process(*cmd, timeout=20.0)
        data = json.loads(raw.decode("utf-8", errors="replace"))
    except AudioExtractionError:
        raise
    except Exception as exc:
        logger.warning(f"ffprobe failed on {source}: {exc}")
        raise AudioExtractionError(f"Không thể phân tích dữ liệu video: {exc}")

    streams = data.get("streams", [])
    audio_streams = [s for s in streams if s.get("codec_type") == "audio"]

    if not audio_streams:
        raise AudioExtractionError("Video này không có luồng âm thanh nào để trích xuất.")

    main_audio = audio_streams[0]
    codec = (main_audio.get("codec_name") or "unknown").lower()
    channels = int(main_audio.get("channels") or 0) or None

    try:
        sample_rate = int(main_audio.get("sample_rate") or 0) or None
    except (ValueError, TypeError):
        sample_rate = None

    try:
        bit_rate = int(main_audio.get("bit_rate") or 0) or None
    except (ValueError, TypeError):
        bit_rate = None

    # Thời lượng ưu tiên từ stream, fallback format
    duration = None
    try:
        dur_str = main_audio.get("duration") or data.get("format", {}).get("duration")
        if dur_str:
            duration = round(float(dur_str), 1)
    except (ValueError, TypeError):
        pass

    suggested_ext = CODEC_TO_EXT.get(codec, "m4a")
    suggested_mime = EXT_TO_MIME.get(suggested_ext, "audio/mp4")

    # Tên hiển thị codec
    codec_display = codec.upper()
    if codec == "aac":
        codec_display = "AAC (Chất lượng cao)"
    elif codec == "mp3":
        codec_display = "MP3"
    elif codec == "opus":
        codec_display = "Opus (Hiện đại)"
    elif codec == "flac":
        codec_display = "FLAC (Không suy hao)"
    elif codec.startswith("pcm"):
        codec_display = "WAV (Không nén PCM)"

    bit_rate_kbps = round(bit_rate / 1000) if bit_rate else None

    return {
        "has_audio": True,
        "codec_name": codec,
        "codec_display": codec_display,
        "channels": channels,
        "channels_display": get_channels_display(channels),
        "sample_rate_hz": sample_rate,
        "bit_rate_kbps": bit_rate_kbps,
        "duration_seconds": duration,
        "duration_formatted": format_seconds_to_mmss(duration),
        "suggested_ext": suggested_ext,
        "suggested_mime": suggested_mime,
        "lossless_possible": True,
    }


async def extract_audio_stream(
    source: Path,
    output_path: Path,
    output_format: str = "original",
) -> Tuple[Path, str, Optional[float], bool]:
    """
    Trích xuất âm thanh từ tệp video:
    - Nếu output_format == 'original': Sử dụng Stream Copy (-vn -c:a copy) để giữ
      nguyên 100% chất lượng ban đầu mà không làm suy hao dù chỉ 1 bit.
    - Nếu output_format == 'mp3': Ép sang chuẩn MP3 320 kbps (nếu nguồn chưa phải mp3).
    - Nếu output_format == 'm4a': Ép sang M4A/AAC 320 kbps (nếu nguồn chưa phải aac).
    - Nếu output_format == 'wav': Ép sang WAV PCM không nén.

    Trả về tuple: (output_path_thực_tế, mime_type, duration_seconds, is_lossless).
    """
    probe_info = await probe_video_audio(source)
    src_codec = probe_info["codec_name"]
    duration = probe_info.get("duration_seconds")

    mode = (output_format or "original").lower().strip()
    is_lossless = False

    # Xác định phần mở rộng đích và câu lệnh FFmpeg
    if mode in ("original", "source", "lossless", "copy"):
        target_ext = probe_info["suggested_ext"]
        final_output = output_path.with_suffix(f".{target_ext}")
        is_lossless = True

        cmd = [
            settings.FFMPEG_BINARY,
            "-y",
            "-v", "error",
            "-i", str(source),
            "-vn",
            "-c:a", "copy",
        ]
        if target_ext == "m4a":
            cmd.extend(["-movflags", "+faststart"])
        cmd.append(str(final_output))

        try:
            await run_process(*cmd, timeout=120.0)
        except AudioExtractionError as err:
            logger.warning(f"Stream copy failed ({err}), falling back to 320k re-encode: {source}")
            # Fallback nếu stream copy không tương thích container
            is_lossless = False
            final_output = output_path.with_suffix(".m4a")
            fallback_cmd = [
                settings.FFMPEG_BINARY,
                "-y",
                "-v", "error",
                "-i", str(source),
                "-vn",
                "-c:a", "aac",
                "-b:a", "320k",
                "-movflags", "+faststart",
                str(final_output),
            ]
            await run_process(*fallback_cmd, timeout=180.0)

    elif mode == "mp3":
        final_output = output_path.with_suffix(".mp3")
        if src_codec == "mp3":
            # Nguồn vốn là MP3 -> copy nguyên gốc!
            is_lossless = True
            cmd = [
                settings.FFMPEG_BINARY,
                "-y", "-v", "error",
                "-i", str(source),
                "-vn",
                "-c:a", "copy",
                str(final_output),
            ]
        else:
            is_lossless = False
            cmd = [
                settings.FFMPEG_BINARY,
                "-y", "-v", "error",
                "-i", str(source),
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "320k",
                "-q:a", "0",
                str(final_output),
            ]
        await run_process(*cmd, timeout=180.0)

    elif mode in ("m4a", "aac"):
        final_output = output_path.with_suffix(".m4a")
        if src_codec in ("aac", "alac"):
            is_lossless = True
            cmd = [
                settings.FFMPEG_BINARY,
                "-y", "-v", "error",
                "-i", str(source),
                "-vn",
                "-c:a", "copy",
                "-movflags", "+faststart",
                str(final_output),
            ]
        else:
            is_lossless = False
            cmd = [
                settings.FFMPEG_BINARY,
                "-y", "-v", "error",
                "-i", str(source),
                "-vn",
                "-c:a", "aac",
                "-b:a", "320k",
                "-movflags", "+faststart",
                str(final_output),
            ]
        await run_process(*cmd, timeout=180.0)

    elif mode == "wav":
        final_output = output_path.with_suffix(".wav")
        is_lossless = True
        cmd = [
            settings.FFMPEG_BINARY,
            "-y", "-v", "error",
            "-i", str(source),
            "-vn",
            "-c:a", "pcm_s16le",
            str(final_output),
        ]
        await run_process(*cmd, timeout=180.0)

    elif mode == "flac":
        final_output = output_path.with_suffix(".flac")
        is_lossless = True
        cmd = [
            settings.FFMPEG_BINARY,
            "-y", "-v", "error",
            "-i", str(source),
            "-vn",
            "-c:a", "flac",
            str(final_output),
        ]
        await run_process(*cmd, timeout=180.0)

    else:
        raise AudioExtractionError(f"Định dạng âm thanh '{output_format}' không được hỗ trợ.")

    if not final_output.is_file() or final_output.stat().st_size == 0:
        raise AudioExtractionError("Không thể tạo tệp âm thanh đầu ra từ video.")

    ext = final_output.suffix.lstrip(".").lower()
    mime = EXT_TO_MIME.get(ext, "audio/mp4")

    return final_output, mime, duration, is_lossless

