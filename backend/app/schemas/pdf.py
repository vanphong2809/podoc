from typing import Optional
from pydantic import BaseModel

from app.schemas.article import ArticleDetailResponse


class PdfInfoResponse(BaseModel):
    id: Optional[int] = None
    title: Optional[str] = None
    file_name: Optional[str] = None
    page_count: int
    is_encrypted: bool = False
    file_size_bytes: Optional[int] = None
    file_size_formatted: Optional[str] = None


class SplitPdfResponse(BaseModel):
    created_count: int
    source_page_count: int
    articles: list[ArticleDetailResponse]

