import pytest
from app.api.v1 import system


@pytest.fixture
def cgroup(tmp_path, monkeypatch):
    monkeypatch.setattr(system, "CGROUP_ROOT", tmp_path)
    return tmp_path


def test_container_memory_excludes_reclaimable_page_cache(cgroup):
    (cgroup / "memory.max").write_text(str(1024 * 1024 * 1024))
    (cgroup / "memory.current").write_text(str(900 * 1024 * 1024))
    (cgroup / "memory.stat").write_text(f"anon {400 * 1024 * 1024}\ninactive_file {500 * 1024 * 1024}\n")
    memory = system.get_memory_stats()
    assert memory.scope == "container"
    assert memory.total_bytes == 1024 * 1024 * 1024
    assert memory.used_bytes == 400 * 1024 * 1024
    assert memory.free_bytes == 624 * 1024 * 1024
    assert memory.cache_bytes == 500 * 1024 * 1024
    assert memory.percent_used == 39.1


@pytest.mark.parametrize("limit", ["max", "0", "invalid"])
def test_unlimited_or_invalid_cgroup_falls_back_to_host(cgroup, limit):
    (cgroup / "memory.max").write_text(limit)
    (cgroup / "memory.current").write_text("100")
    (cgroup / "memory.stat").write_text("inactive_file 20\n")
    assert system.get_memory_stats().scope == "host"


def test_cache_cannot_make_used_memory_negative(cgroup):
    (cgroup / "memory.max").write_text("1000")
    (cgroup / "memory.current").write_text("100")
    (cgroup / "memory.stat").write_text("inactive_file 200\n")
    memory = system.get_memory_stats()
    assert memory.used_bytes == 0
    assert memory.percent_used == 0
    assert memory.free_bytes == 1000
