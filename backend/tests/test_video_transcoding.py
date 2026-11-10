import asyncio
import importlib.util
import json
import logging
from pathlib import Path
import shutil
import sys
from unittest.mock import Mock

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text, update

from app.core.config import settings
from app.models.article import Article
from app.services import video_transcoder as video
from tests.conftest import TestingSessionLocal

pytestmark = pytest.mark.asyncio


async def test_migration_backfills_existing_videos(tmp_path):
    migration_path = Path(__file__).parents[1] / "alembic/versions/0003_video_transcoding.py"
    spec = importlib.util.spec_from_file_location("video_migration", migration_path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    engine = create_engine(f"sqlite:///{tmp_path / 'migration.db'}")
    with engine.begin() as connection:
        connection.execute(text("CREATE TABLE articles (id INTEGER PRIMARY KEY, file_path TEXT, file_name TEXT, mime_type TEXT)"))
        connection.execute(text("""INSERT INTO articles VALUES
            (1, 'clip.bin', 'phone.bin', 'video/quicktime'),
            (2, 'other.bin', 'phone.M4V', 'application/octet-stream'),
            (3, 'clip.WEBM', NULL, NULL),
            (4, 'note.txt', 'note.txt', 'text/plain'),
            (5, NULL, 'youtube.mp4', 'video/mp4')"""))
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
            statuses = connection.execute(text("SELECT video_status FROM articles ORDER BY id")).scalars().all()
            assert statuses == ["pending", "pending", "pending", None, None]
            migration.downgrade()
            assert "video_status" not in {c["name"] for c in inspect(connection).get_columns("articles")}
    engine.dispose()


async def upload_video(client):
    original = b"\x00\x00\x00\x18ftypmp42" + b"original video" * 100
    response = await client.post("/api/articles/upload", files={
        "file": ("phone.mov", original, "video/quicktime"),
        "thumbnail": ("thumb.jpg", b"thumbnail", "image/jpeg"),
    })
    assert response.status_code == 201
    assert response.json()["video_status"] == "pending"
    return response.json(), original


async def test_queue_playback_ranges_and_original_download(client, monkeypatch):
    article, original = await upload_video(client)
    url = f"/api/articles/{article['id']}/file"
    assert (await client.get(url + "?inline=true")).content == original
    unavailable = await client.get(url + "?variant=optimized")
    assert unavailable.status_code == 409
    assert unavailable.headers["retry-after"] == "5"
    info = (await client.get(f"/api/articles/{article['id']}/file-info")).json()
    assert info["video_queue_position"] == 1
    assert [v["kind"] for v in info["video_versions"]] == ["original", "optimized"]
    assert [v["status"] for v in info["video_versions"]] == ["ready", "pending"]
    assert info["video_versions"][1]["download_url"] is None
    optimized = b"complete optimized mp4" * 10

    async def encode(source, partial):
        assert source.read_bytes() == original
        partial.write_bytes(b"incomplete")
        # In-progress output must never be served to viewers.
        assert (await client.get(url + "?inline=true")).content == original
        partial.write_bytes(optimized)

    monkeypatch.setattr(video, "transcode_video", encode)
    worker = video.VideoTranscoder(TestingSessionLocal)
    assert await worker.process_next()
    assert not await worker.process_next()
    response = await client.get(url + "?inline=true")
    assert response.content == optimized
    assert response.headers["content-type"] == "video/mp4"
    assert response.headers["x-podoc-video-variant"] == "optimized"
    assert "optimized.mp4" in response.headers["content-disposition"]
    assert "no-cache" in response.headers["cache-control"]
    ranged = await client.get(url + "?inline=true", headers={"Range": "bytes=5-14"})
    assert ranged.status_code == 206
    assert ranged.content == optimized[5:15]
    assert ranged.headers["content-range"] == f"bytes 5-14/{len(optimized)}"
    unsatisfiable = await client.get(url + "?inline=true", headers={"Range": "bytes=9999-"})
    assert unsatisfiable.status_code == 416
    assert (await client.get(url)).content == original
    assert (await client.get(url + "?variant=original&inline=true")).content == original
    assert (await client.get(url + "?variant=optimized")).content == optimized
    assert (await client.get(url + "?inline=true&original=true")).content == original
    assert (await client.get(url + "?original=true&variant=optimized")).status_code == 400
    info = (await client.get(f"/api/articles/{article['id']}/file-info")).json()
    versions = info["video_versions"]
    assert [v["status"] for v in versions] == ["ready", "ready"]
    assert [v["file_size_bytes"] for v in versions] == [len(original), len(optimized)]
    assert (await client.get(versions[0]["download_url"])).content == original
    assert (await client.get(versions[1]["download_url"])).content == optimized
    assert info["video_saved_percent"] == round((1 - len(optimized) / len(original)) * 100, 1)
    assert (await client.get(f"/api/articles/{article['id']}")).json()["video_status"] == "ready"
    stats = (await client.get("/api/articles/storage-stats")).json()
    assert stats["total_bytes"] == len(original) + len(optimized)
    # Missing derivative falls back to the intact source.
    video.optimized_path(article["file_path"]).unlink()
    assert (await client.get(url + "?inline=true")).content == original
    assert (await client.get(url + "?variant=optimized")).status_code == 404
    info = (await client.get(f"/api/articles/{article['id']}/file-info")).json()
    assert info["video_versions"][1]["status"] == "missing"
    assert info["video_versions"][1]["download_url"] is None


async def test_new_uploads_take_priority_over_historical_queue(client, monkeypatch):
    old, _ = await upload_video(client)
    new, _ = await upload_video(client)
    old_info = (await client.get(f"/api/articles/{old['id']}/file-info")).json()
    new_info = (await client.get(f"/api/articles/{new['id']}/file-info")).json()
    assert old_info["video_queue_position"] == 2
    assert new_info["video_queue_position"] == 1

    async def encode(source, partial):
        partial.write_bytes(b"optimized")

    monkeypatch.setattr(video, "transcode_video", encode)
    await video.VideoTranscoder(TestingSessionLocal).process_next()
    assert (await client.get(f"/api/articles/{new['id']}")).json()["video_status"] == "ready"
    assert (await client.get(f"/api/articles/{old['id']}")).json()["video_status"] == "pending"


@pytest.mark.parametrize("skip", [False, True])
async def test_never_publishes_a_larger_derivative(client, monkeypatch, caplog, skip):
    article, original = await upload_video(client)
    output = video.optimized_path(article["file_path"])
    output.write_bytes(original * 2)  # An oversized derivative from a previous version.

    async def encode(source, partial):
        if skip:
            return False
        partial.write_bytes(original * 3)
        return True

    monkeypatch.setattr(video, "transcode_video", encode)
    caplog.set_level(logging.INFO, logger=video.__name__)
    await video.VideoTranscoder(TestingSessionLocal).process_next()
    assert not output.exists()
    assert not output.with_suffix(".part.mp4").exists()
    detail = (await client.get(f"/api/articles/{article['id']}/file-info")).json()
    assert detail["video_status"] == "kept_original"
    assert detail["video_versions"][1]["status"] == "kept_original"
    assert detail["video_versions"][1]["download_url"] is None
    assert (await client.get(f"/api/articles/{article['id']}/file?inline=true")).content == original
    assert (await client.get(f"/api/articles/{article['id']}/file?variant=optimized")).status_code == 404
    assert not any("NÉN XONG" in record.message for record in caplog.records)
    assert any("GIỮ BẢN GỐC" in record.message for record in caplog.records)


async def test_historical_oversized_video_is_not_served_and_is_requeued(client, monkeypatch):
    article, original = await upload_video(client)
    output = video.optimized_path(article["file_path"])
    output.write_bytes(original * 2)
    async with TestingSessionLocal() as db:
        await db.execute(update(Article).where(Article.id == article["id"]).values(video_status="ready"))
        await db.commit()
    response = await client.get(f"/api/articles/{article['id']}/file?inline=true")
    assert response.content == original
    assert response.headers["x-podoc-video-variant"] == "original"
    assert (await client.get(f"/api/articles/{article['id']}/file?variant=optimized")).status_code == 404
    worker = video.VideoTranscoder(TestingSessionLocal)
    assert await worker.requeue_oversized() == 1
    assert (await client.get(f"/api/articles/{article['id']}")).json()["video_status"] == "pending"
    assert (Path(settings.FILES_STORAGE_DIR) / article["file_path"]).read_bytes() == original


async def test_completion_log_uses_real_filename_and_committed_result(client, monkeypatch, caplog):
    name = 'Video kỳ nghỉ "mới"\n2026.mp4'
    response = await client.post("/api/articles/upload", files={
        "file": ("upload.mp4", b"\x00\x00\x00\x18ftypmp42" + bytes(1024), "video/mp4"),
        "thumbnail": ("thumb.jpg", b"thumbnail", "image/jpeg"),
    }, data={"file_name": name})
    assert response.status_code == 201
    article = response.json()
    caplog.set_level(logging.INFO, logger=video.__name__)

    async def encode(source, partial):
        assert not any("NÉN XONG" in r.message for r in caplog.records)
        partial.write_bytes(b"optimized")

    monkeypatch.setattr(video, "transcode_video", encode)
    monkeypatch.setattr(video, "monotonic", Mock(side_effect=[100.0, 102.5]))
    await video.VideoTranscoder(TestingSessionLocal).process_next()
    log = next(r.message for r in caplog.records if "NÉN XONG" in r.message)
    assert f"file={json.dumps(name, ensure_ascii=False)}" in log
    assert "\n" not in log
    assert "gốc=" in log and "bản nén=" in log
    assert "thay đổi=-99.1%" in log
    assert "thời gian=2.5s" in log
    assert f"id={article['id']}" in log
    assert (await client.get(f"/api/articles/{article['id']}")).json()["video_status"] == "ready"


async def test_failed_encoding_keeps_original_and_removes_partial(client, monkeypatch, caplog):
    article, original = await upload_video(client)

    async def fail(source, partial):
        partial.write_bytes(b"broken")
        raise RuntimeError("disk full or invalid video")

    monkeypatch.setattr(video, "transcode_video", fail)
    worker = video.VideoTranscoder(TestingSessionLocal)
    await worker.process_next()
    detail = (await client.get(f"/api/articles/{article['id']}")).json()
    assert detail["video_status"] == "failed"
    assert not list(Path(settings.FILES_STORAGE_DIR).glob("*.part.mp4"))
    assert (await client.get(f"/api/articles/{article['id']}/file?inline=true")).content == original
    assert not await worker.process_next()  # Do not endlessly retry corrupt files.
    assert not any("NÉN XONG" in r.message for r in caplog.records)
    assert any('NÉN LỖI | file="phone.mov"' in r.message for r in caplog.records)


async def test_interrupted_encoding_recovers_after_restart(client, monkeypatch):
    article, _ = await upload_video(client)

    async def interrupted(source, partial):
        partial.write_bytes(b"interrupted")
        raise asyncio.CancelledError

    monkeypatch.setattr(video, "transcode_video", interrupted)
    worker = video.VideoTranscoder(TestingSessionLocal)
    with pytest.raises(asyncio.CancelledError):
        await worker.process_next()
    assert not list(Path(settings.FILES_STORAGE_DIR).glob("*.part.mp4"))
    detail = (await client.get(f"/api/articles/{article['id']}")).json()
    assert detail["video_status"] == "processing"
    await worker.reset_interrupted()

    async def success(source, partial):
        partial.write_bytes(b"recovered mp4")

    monkeypatch.setattr(video, "transcode_video", success)
    await worker.process_next()
    assert (await client.get(f"/api/articles/{article['id']}")).json()["video_status"] == "ready"


@pytest.mark.parametrize("batch", [False, True])
async def test_delete_cleans_derivatives_including_pending_output(client, batch):
    article, _ = await upload_video(client)
    output = video.optimized_path(article["file_path"])
    partial = output.with_suffix(".part.mp4")
    output.write_bytes(b"optimized")
    partial.write_bytes(b"partial")
    if batch:
        response = await client.post("/api/articles/batch-delete", json={"ids": [article["id"]]})
        assert response.status_code == 200
    else:
        response = await client.delete(f"/api/articles/{article['id']}")
        assert response.status_code == 204
    assert not output.exists()
    assert not partial.exists()
    assert not (Path(settings.FILES_STORAGE_DIR) / article["file_path"]).exists()


async def test_deleted_during_encoding_does_not_publish_orphan(client, monkeypatch):
    article, _ = await upload_video(client)

    async def encode(source, partial):
        await client.delete(f"/api/articles/{article['id']}")
        partial.write_bytes(b"finished after deletion")

    monkeypatch.setattr(video, "transcode_video", encode)
    await video.VideoTranscoder(TestingSessionLocal).process_next()
    assert not video.optimized_path(article["file_path"]).exists()
    assert not list(Path(settings.FILES_STORAGE_DIR).glob("*.part.mp4"))


async def test_worker_lock_allows_only_one_encoder(client, monkeypatch):
    entered = asyncio.Event()
    process = Mock()

    async def process_next():
        process()
        entered.set()
        await asyncio.Event().wait()

    first = video.VideoTranscoder(TestingSessionLocal)
    second = video.VideoTranscoder(TestingSessionLocal)
    monkeypatch.setattr(first, "process_next", process_next)
    monkeypatch.setattr(second, "process_next", process_next)
    monkeypatch.setattr(settings, "VIDEO_TRANSCODING_POLL_SECONDS", 0.01)
    first.start()
    await asyncio.wait_for(entered.wait(), 1)
    second.start()
    await asyncio.sleep(0.05)
    await first.stop()
    await second.stop()
    assert process.call_count == 1


async def test_subprocess_timeout_and_cancellation():
    with pytest.raises(asyncio.TimeoutError):
        await video.run_command(sys.executable, "-c", "import time; time.sleep(30)", timeout=0.05)
    task = asyncio.create_task(video.run_command(
        sys.executable, "-c", "import time; time.sleep(30)", timeout=60,
    ))
    await asyncio.sleep(0.05)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


@pytest.mark.parametrize("size,fps,audio", [
    ("2160x3840", 60, True), ("3840x2160", 30, False), ("320x180", 24, True),
])
async def test_real_ffmpeg_output(tmp_path, size, fps, audio, monkeypatch):
    monkeypatch.setattr(settings, "VIDEO_TRANSCODING_THREADS", 2)
    if not shutil.which(settings.FFMPEG_BINARY) or not shutil.which(settings.FFPROBE_BINARY):
        pytest.skip("FFmpeg and ffprobe required for real encoding verification")
    source = tmp_path / "source.mp4"
    output = tmp_path / "result.part.mp4"
    args = [settings.FFMPEG_BINARY, "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate={fps}"]
    if audio:
        args += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100"]
    args += ["-t", "0.5", "-threads", "1", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "10"]
    if audio:
        args += ["-c:a", "aac"]
    await video.run_command(*args, str(source), timeout=60)
    await video.transcode_video(source, output)
    metadata = json.loads(await video.run_command(
        settings.FFPROBE_BINARY, "-v", "error", "-show_streams", "-of", "json", str(output), timeout=10,
    ))
    stream = next(s for s in metadata["streams"] if s["codec_type"] == "video")
    w, h = map(int, size.split("x"))
    ratio = min(1, (1080 / w if h > w else 1920 / w), (1920 / h if h > w else 1080 / h))
    assert stream["width"] == int(w * ratio)
    assert stream["height"] == int(h * ratio)
    assert stream["codec_name"] == "h264"
    assert stream["pix_fmt"] == "yuv420p"
    assert float(video.Fraction(stream["avg_frame_rate"])) <= min(fps, 30)
    sound = next((s for s in metadata["streams"] if s["codec_type"] == "audio"), None)
    assert bool(sound) == audio
    if sound:
        assert sound["codec_name"] == "aac"
        # Audio is copied without resampling/upmixing.
        assert sound["sample_rate"] == "44100"
        assert sound["channels"] == 1
    data = output.read_bytes()
    assert data.index(b"moov") < data.index(b"mdat")
    assert output.stat().st_size < source.stat().st_size


@pytest.mark.parametrize("hdr", [False, True])
async def test_real_rotation_and_hdr(tmp_path, hdr):
    if not shutil.which(settings.FFMPEG_BINARY) or not shutil.which(settings.FFPROBE_BINARY):
        pytest.skip("FFmpeg and ffprobe required")
    base = tmp_path / "base.mp4"
    source = tmp_path / "phone.mp4"
    output = tmp_path / "optimized.part.mp4"
    args = [
        settings.FFMPEG_BINARY, "-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=320x180:rate=30", "-t", "0.5", "-threads", "1", "-c:v", "libx264",
    ]
    if hdr:
        args += ["-pix_fmt", "yuv420p10le", "-color_trc", "smpte2084",
                 "-color_primaries", "bt2020", "-colorspace", "bt2020nc"]
    await video.run_command(*args, str(base), timeout=30)
    await video.run_command(
        settings.FFMPEG_BINARY, "-v", "error", "-y", "-display_rotation:v:0", "90",
        "-i", str(base), "-c", "copy", str(source), timeout=30,
    )
    await video.transcode_video(source, output)
    result = await video.probe_video(output)
    stream = result["streams"][0]
    assert (stream["width"], stream["height"]) == (180, 320)
    if hdr:
        assert stream["color_transfer"] == "bt709"


async def test_real_compact_mp4_keeps_original_without_reencoding(tmp_path):
    if not shutil.which(settings.FFMPEG_BINARY) or not shutil.which(settings.FFPROBE_BINARY):
        pytest.skip("FFmpeg and ffprobe required")
    source = tmp_path / "compact.mp4"
    partial = tmp_path / "candidate.part.mp4"
    await video.run_command(
        settings.FFMPEG_BINARY, "-v", "error", "-y", "-f", "lavfi", "-i",
        "color=c=blue:s=640x360:r=20", "-t", "6", "-threads", "1",
        "-c:v", "libx264", "-crf", "30", str(source), timeout=30,
    )
    before = source.read_bytes()
    assert await video.transcode_video(source, partial) is False
    assert not partial.exists()
    assert source.read_bytes() == before


@pytest.mark.parametrize("codec", ["aac", "libmp3lame"])
async def test_real_all_audio_tracks_are_bit_identical(tmp_path, codec):
    if not shutil.which(settings.FFMPEG_BINARY) or not shutil.which(settings.FFPROBE_BINARY):
        pytest.skip("FFmpeg and ffprobe required")
    source = tmp_path / "two_tracks.mp4"
    output = tmp_path / "optimized.part.mp4"
    await video.run_command(
        settings.FFMPEG_BINARY, "-v", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=32000",
        "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "1",
        "-threads", "1", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "10",
        "-c:a", codec, "-b:a:0", "256k", "-ac:a:0", "2",
        "-b:a:1", "160k", "-ac:a:1", "1", str(source), timeout=30,
    )
    before = source.read_bytes()
    assert await video.transcode_video(source, output) is True

    async def audio_fingerprint(path):
        data = json.loads(await video.run_command(
            settings.FFPROBE_BINARY, "-v", "error", "-select_streams", "a",
            "-show_entries", "stream=codec_name,sample_rate,channels:packet=stream_index,data_hash",
            "-show_data_hash", "sha256", "-of", "json", str(path), timeout=10,
        ))
        return data

    original_audio = await audio_fingerprint(source)
    assert len(original_audio["streams"]) == 2
    assert len(original_audio["packets"]) > 10
    # Compare every compressed audio packet, not just nominal codec/bitrate.
    assert await audio_fingerprint(output) == original_audio
    assert source.read_bytes() == before


async def test_real_unsupported_audio_keeps_original_with_clear_log(client, tmp_path, caplog):
    if not shutil.which(settings.FFMPEG_BINARY) or not shutil.which(settings.FFPROBE_BINARY):
        pytest.skip("FFmpeg and ffprobe required")
    source = tmp_path / "wma_audio.mkv"
    await video.run_command(
        settings.FFMPEG_BINARY, "-v", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
        "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.5",
        "-threads", "1", "-c:v", "libx264", "-c:a", "wmav2", str(source), timeout=30,
    )
    original = source.read_bytes()
    response = await client.post("/api/articles/upload", files={
        "file": ("âm thanh gốc.mkv", original, "video/x-matroska"),
        "thumbnail": ("thumb.jpg", b"thumbnail", "image/jpeg"),
    })
    assert response.status_code == 201
    article = response.json()
    with caplog.at_level(logging.INFO, logger=video.__name__):
        await video.VideoTranscoder(TestingSessionLocal).process_next()
    assert (await client.get(f"/api/articles/{article['id']}")).json()["video_status"] == "kept_original"
    result = await client.get(f"/api/articles/{article['id']}/file?inline=true")
    assert result.content == original
    assert result.headers["x-podoc-video-variant"] == "original"
    assert not video.optimized_path(article["file_path"]).exists()
    assert not video.optimized_path(article["file_path"]).with_suffix(".part.mp4").exists()
    assert 'GIỮ BẢN GỐC | file="âm thanh gốc.mkv"' in caplog.text
    assert "codec âm thanh gốc không ghép được vào MP4" in caplog.text
    assert "NÉN LỖI" not in caplog.text
