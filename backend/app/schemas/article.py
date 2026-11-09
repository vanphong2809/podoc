from datetime import datetime
from typing import Optional
from pydantic import BaseModel, ConfigDict, Field


class ArticleBase(BaseModel):
    title: str = Field(..., min_length=1, max_length=500)
    source_type: str = Field(default="URL")
    source_url: Optional[str] = None
    file_name: Optional[str] = None
    file_path: Optional[str] = None
    mime_type: Optional[str] = None
    author: Optional[str] = None
    site_name: Optional[str] = None
    image_url: Optional[str] = None
    language: Optional[str] = None
    reading_minutes: int = 0
    category_id: int
    is_favorite: bool = False
    read_progress: float = 0.0


class ArticleCreate(ArticleBase):
    content_markdown: str = ""
    excerpt: Optional[str] = None
    normalized_url: Optional[str] = None


class ArticleUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=500)
    category_id: Optional[int] = None
    is_favorite: Optional[bool] = None
    read_progress: Optional[float] = Field(None, ge=0.0, le=1.0)


class ArticleListResponse(BaseModel):
    """Schema trả về cho danh sách: chỉ chứa excerpt, không chứa full content_markdown."""
    id: int
    title: str
    source_type: str
    source_url: Optional[str] = None
    file_name: Optional[str] = None
    file_path: Optional[str] = None
    mime_type: Optional[str] = None
    excerpt: Optional[str] = None
    author: Optional[str] = None
    site_name: Optional[str] = None
    image_url: Optional[str] = None
    reading_minutes: int
    category_id: int
    category_name: Optional[str] = None
    is_favorite: bool
    read_progress: float
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ArticleDetailResponse(ArticleListResponse):
    """Schema chi tiết bài viết: bao gồm toàn bộ nội dung Markdown và thông tin file."""
    content_markdown: str
    normalized_url: Optional[str] = None
    language: Optional[str] = None


class ArticleDraft(BaseModel):
    """Bản nháp trả về từ POST /api/extract hoặc POST /api/extract-file."""
    title: str
    source_url: str = ""
    normalized_url: str = ""
    author: Optional[str] = None
    site_name: Optional[str] = None
    image_url: Optional[str] = None
    language: Optional[str] = None
    reading_minutes: int = 1
    excerpt: str = ""
    content_markdown: str = ""
    file_name: Optional[str] = None
    file_path: Optional[str] = None
    mime_type: Optional[str] = None
    is_duplicate: bool = False
    existing_article_id: Optional[int] = None
    is_fallback_bookmark: bool = False
    error_message: Optional[str] = None


class BatchDeleteRequest(BaseModel):
    ids: list[int]


class BatchUpdateCategoryRequest(BaseModel):
    ids: list[int]
    category_id: int


class StorageItemStat(BaseModel):
    content_type: str        # "image", "video", "audio", "file"
    label: str               # "Hình ảnh", "Video", "Âm thanh", "Tệp tin"
    count: int
    total_bytes: int
    formatted_size: str


class CategoryStorageStat(BaseModel):
    category_id: int
    category_name: str
    count: int
    total_bytes: int
    formatted_size: str


class StorageStatsResponse(BaseModel):
    total_bytes: int
    formatted_total_size: str
    total_files: int
    items: list[StorageItemStat]
    by_category: list[CategoryStorageStat] = []


class ArticleFileInfoResponse(BaseModel):
    id: int
    title: str
    file_name: Optional[str] = None
    file_path: Optional[str] = None
    storage_dir: Optional[str] = None
    storage_path: Optional[str] = None
    file_size_bytes: Optional[int] = None
    file_size_formatted: Optional[str] = None
    mime_type: Optional[str] = None
    content_type: str = "file"  # "image", "video", "audio", "file", "web"
    category_id: int
    category_name: Optional[str] = None
    album: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    width: Optional[int] = None
    height: Optional[int] = None
    resolution: Optional[str] = None
    fps: Optional[float] = None
    duration_seconds: Optional[float] = None
    duration_formatted: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)



