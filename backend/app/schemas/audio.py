from typing import Optional
from pydantic import BaseModel, ConfigDict


class AudioProbeResponse(BaseModel):
    """Thông tin luồng âm thanh phát hiện được trong video."""
    has_audio: bool
    codec_name: Optional[str] = None
    codec_display: Optional[str] = None
    channels: Optional[int] = None
    channels_display: Optional[str] = None
    sample_rate_hz: Optional[int] = None
    bit_rate_kbps: Optional[int] = None
    duration_seconds: Optional[float] = None
    duration_formatted: Optional[str] = None
    suggested_ext: str = "m4a"
    suggested_mime: str = "audio/mp4"
    lossless_possible: bool = True

    model_config = ConfigDict(from_attributes=True)


class AudioExtractResultResponse(BaseModel):
    """Kết quả trích xuất âm thanh từ video."""
    article_id: int
    title: str
    file_name: str
    mime_type: str
    duration_seconds: Optional[float] = None
    duration_formatted: Optional[str] = None
    file_size_bytes: int
    file_size_formatted: str
    audio_codec: str
    is_lossless: bool
    source_video_name: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

