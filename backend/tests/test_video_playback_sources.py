from unittest.mock import Mock

import pytest
from sqlalchemy import update

from app.models.article import Article
from app.services import video_transcoder as video
from tests.conftest import TestingSessionLocal
from tests.test_video_transcoding import upload_video

pytestmark = pytest.mark.asyncio


async def test_batch_sources_are_cheap_ordered_and_bounded(client, monkeypatch):
    first, _ = await upload_video(client)
    second, _ = await upload_video(client)
    probe = Mock(side_effect=AssertionError("Feed metadata must not run ffprobe"))
    monkeypatch.setattr("subprocess.run", probe)
    response = await client.post("/api/articles/playback-sources", json={
        "ids": [second["id"], first["id"], second["id"], 999999],
    })
    assert response.status_code == 200
    assert [item["id"] for item in response.json()["items"]] == [second["id"], first["id"]]
    assert response.json()["unavailable_ids"] == [999999]
    assert not probe.called
    for ids in [[], [first["id"]] * 22, [0], [-1]]:
        assert (await client.post("/api/articles/playback-sources", json={"ids": ids})).status_code == 422


async def test_batch_accepts_all_twenty_one_positions(client):
    article, _ = await upload_video(client)
    ids = [article["id"], *range(10000, 10020)]
    response = await client.post("/api/articles/playback-sources", json={"ids": ids})
    assert response.status_code == 200
    assert [item["id"] for item in response.json()["items"]] == [article["id"]]
    assert response.json()["unavailable_ids"] == ids[1:]
    assert (await client.post("/api/articles/playback-sources", json={"ids": ids + [20000]})).status_code == 422


async def test_source_pins_variant_and_revision_across_ranges(client):
    article, original = await upload_video(client)
    endpoint = "/api/articles/playback-sources"
    before = (await client.post(endpoint, json={"ids": [article["id"]]})).json()["items"][0]
    assert before["variant"] == "original"
    output = video.optimized_path(article["file_path"])
    optimized = b"optimized video" * 10
    output.write_bytes(optimized)
    async with TestingSessionLocal() as db:
        await db.execute(update(Article).where(Article.id == article["id"]).values(video_status="ready"))
        await db.commit()
    after = (await client.post(endpoint, json={"ids": [article["id"]]})).json()["items"][0]
    assert after["variant"] == "optimized"
    assert after["revision"] != before["revision"]
    assert after["file_size_bytes"] == len(optimized)
    assert (await client.get(before["url"])).content == original
    for descriptor, data in [(before, original), (after, optimized)]:
        response = await client.get(descriptor["url"], headers={"Range": "bytes=3-9"})
        assert response.status_code == 206
        assert response.content == data[3:10]
        assert response.headers["etag"] == f'"{descriptor["revision"]}"'
        assert response.headers["x-podoc-video-revision"] == descriptor["revision"]
    output.write_bytes(b"a new revision of the optimized file")
    stale = await client.get(after["url"], headers={"Range": "bytes=10-20"})
    assert stale.status_code == 412
    # A fresh description still works; no source is modified or deleted.
    fresh = (await client.post(endpoint, json={"ids": [article["id"]]})).json()["items"][0]
    assert fresh["revision"] != after["revision"]
    assert (await client.get(fresh["url"])).content == output.read_bytes()
    assert (await client.get(before["url"])).content == original


async def test_missing_video_cannot_be_cached_as_playable(client):
    article, _ = await upload_video(client)
    from app.core.config import settings
    from pathlib import Path
    (Path(settings.FILES_STORAGE_DIR) / article["file_path"]).unlink()
    response = await client.post("/api/articles/playback-sources", json={"ids": [article["id"]]})
    assert response.json() == {"items": [], "unavailable_ids": [article["id"]]}


async def test_playback_sources_and_range_reads_require_auth_when_enabled(client, monkeypatch):
    article, original = await upload_video(client)
    from app.core.config import settings
    monkeypatch.setattr(settings, "REQUIRE_AUTH", True)
    request = {"ids": [article["id"]]}
    assert (await client.post("/api/articles/playback-sources", json=request)).status_code == 401
    login = await client.post("/api/auth/login", json={"username": "admin", "password": "admin_test_password"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    descriptor = (await client.post("/api/articles/playback-sources", json=request, headers=headers)).json()["items"][0]
    assert (await client.get(descriptor["url"])).status_code == 401
    response = await client.get(descriptor["url"], headers={**headers, "Range": "bytes=0-15"})
    assert response.status_code == 206
    assert response.content == original[:16]
