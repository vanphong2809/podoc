from pydantic import BaseModel, Field


class MemoryStat(BaseModel):
    total_bytes: int = Field(0, description="Tổng dung lượng RAM (byte)")
    used_bytes: int = Field(0, description="Dung lượng RAM đang dùng (byte)")
    free_bytes: int = Field(0, description="Dung lượng RAM còn trống (byte)")
    percent_used: float = Field(0.0, description="Phần trăm RAM đã dùng")
    formatted_total: str = Field("0 B", description="Tổng RAM có định dạng (GB/MB)")
    formatted_used: str = Field("0 B", description="RAM đang dùng có định dạng")
    formatted_free: str = Field("0 B", description="RAM còn trống có định dạng")


class DiskStat(BaseModel):
    total_bytes: int = Field(0, description="Tổng dung lượng ổ đĩa (byte)")
    used_bytes: int = Field(0, description="Dung lượng ổ đĩa đang dùng (byte)")
    free_bytes: int = Field(0, description="Dung lượng ổ đĩa còn trống (byte)")
    percent_used: float = Field(0.0, description="Phần trăm ổ đĩa đã dùng")
    formatted_total: str = Field("0 B", description="Tổng dung lượng có định dạng")
    formatted_used: str = Field("0 B", description="Dung lượng đã dùng có định dạng")
    formatted_free: str = Field("0 B", description="Dung lượng còn trống có định dạng")


class CpuStat(BaseModel):
    cores: int = Field(1, description="Số nhân CPU")
    percent_used: float = Field(0.0, description="Phần trăm sử dụng CPU hiện tại")
    model_name: str = Field("", description="Tên / Kiến trúc CPU")
    load_avg: list[float] = Field(default_factory=list, description="Chỉ số tải 1m, 5m, 15m")


class ServerSystemStatsResponse(BaseModel):
    status: str = Field("ok", description="Trạng thái máy chủ")
    version: str = Field("", description="Phiên bản phần mềm PoDoc Backend")
    uptime_seconds: int = Field(0, description="Thời gian hoạt động liên tục (giây)")
    os_name: str = Field("", description="Hệ điều hành máy chủ")
    ram: MemoryStat = Field(default_factory=MemoryStat)
    disk: DiskStat = Field(default_factory=DiskStat)
    cpu: CpuStat = Field(default_factory=CpuStat)

