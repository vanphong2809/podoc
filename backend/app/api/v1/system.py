import os
import platform
import shutil
import time
from fastapi import APIRouter
from app.core.config import settings
from app.schemas.system import ServerSystemStatsResponse, MemoryStat, DiskStat, CpuStat

router = APIRouter(prefix="/system", tags=["System"])

SERVER_START_TIME = time.time()


def format_byte_size(size_bytes: int) -> str:
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 ** 2:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 ** 3:
        return f"{size_bytes / (1024 ** 2):.1f} MB"
    else:
        return f"{size_bytes / (1024 ** 3):.2f} GB"


def get_memory_stats() -> MemoryStat:
    total, used, free, percent = 0, 0, 0, 0.0
    try:
        import psutil
        mem = psutil.virtual_memory()
        total = mem.total
        free = mem.available
        used = total - free
        percent = round((used / total * 100), 1) if total > 0 else mem.percent
    except Exception:
        # Fallback reading /proc/meminfo on Linux (Render / Docker)
        if os.path.exists("/proc/meminfo"):
            try:
                mem_dict = {}
                with open("/proc/meminfo", "r") as f:
                    for line in f:
                        parts = line.split(":")
                        if len(parts) == 2:
                            mem_dict[parts[0].strip()] = int(parts[1].split()[0]) * 1024
                total = mem_dict.get("MemTotal", 0)
                free = mem_dict.get("MemAvailable", mem_dict.get("MemFree", 0))
                used = max(0, total - free)
                percent = round((used / total * 100), 1) if total > 0 else 0.0
            except Exception:
                pass

        # Fallback on macOS / BSD
        if total == 0:
            try:
                page_size = os.sysconf("SC_PAGE_SIZE")
                total_pages = os.sysconf("SC_PHYS_PAGES")
                total = page_size * total_pages
                avail_pages = os.sysconf("SC_AVPHYS_PAGES")
                free = page_size * avail_pages
                used = max(0, total - free)
                percent = round((used / total * 100), 1) if total > 0 else 0.0
            except Exception:
                pass

    return MemoryStat(
        total_bytes=total,
        used_bytes=used,
        free_bytes=free,
        percent_used=percent,
        formatted_total=format_byte_size(total),
        formatted_used=format_byte_size(used),
        formatted_free=format_byte_size(free),
    )


def get_disk_stats() -> DiskStat:
    target_path = settings.FILES_STORAGE_DIR if os.path.exists(settings.FILES_STORAGE_DIR) else "/"
    try:
        usage = shutil.disk_usage(target_path)
        total = usage.total
        used = usage.used
        free = usage.free
        percent = round((used / total * 100), 1) if total > 0 else 0.0
    except Exception:
        total, used, free, percent = 0, 0, 0, 0.0

    return DiskStat(
        total_bytes=total,
        used_bytes=used,
        free_bytes=free,
        percent_used=percent,
        formatted_total=format_byte_size(total),
        formatted_used=format_byte_size(used),
        formatted_free=format_byte_size(free),
    )


def get_cpu_stats() -> CpuStat:
    cores = os.cpu_count() or 1
    percent = 0.0
    load_avg = []

    if hasattr(os, "getloadavg"):
        try:
            load_avg = [round(x, 2) for x in os.getloadavg()]
        except Exception:
            load_avg = []

    try:
        import psutil
        percent = round(psutil.cpu_percent(interval=None), 1)
    except Exception:
        if load_avg:
            percent = min(100.0, round((load_avg[0] / cores) * 100, 1))

    # CPU Model / Arch
    arch = platform.machine() or "unknown"
    proc = platform.processor()
    model_name = f"{proc} ({arch})" if proc and proc != arch else arch

    return CpuStat(
        cores=cores,
        percent_used=percent,
        model_name=model_name,
        load_avg=load_avg,
    )


@router.get("/stats", response_model=ServerSystemStatsResponse)
async def get_server_stats():
    """
    Trả về thông số phần cứng hiện thời của máy chủ:
    - RAM: Tổng, đang dùng, còn trống, tỷ lệ %
    - Ổ đĩa (Disk Storage): Tổng, đã dùng, còn trống, tỷ lệ %
    - CPU: Số nhân, mức tải hiện tại (%), kiến trúc, load average
    - Thời gian uptime và hệ điều hành máy chủ
    """
    uptime = int(time.time() - SERVER_START_TIME)
    os_name = f"{platform.system()} {platform.release()}"
    return ServerSystemStatsResponse(
        status="ok",
        version=settings.VERSION,
        uptime_seconds=uptime,
        os_name=os_name,
        ram=get_memory_stats(),
        disk=get_disk_stats(),
        cpu=get_cpu_stats(),
    )

