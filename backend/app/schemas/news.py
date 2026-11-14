from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class GenerateBriefingRequest(BaseModel):
    gemini_api_key: Optional[str] = None
    gemini_model: Optional[str] = Field(default="gemini-3.8-flash")
    max_items_per_topic: int = Field(default=3, ge=1, le=10)


class NewsBriefingResponse(BaseModel):
    article_id: int
    title: str
    category_id: int
    category_name: str
    file_path: Optional[str] = None
    image_url: Optional[str] = None
    created_at: Optional[str] = None
    briefing: Optional[Dict[str, Any]] = None


class NewsSettings(BaseModel):
    enabled: bool = True
    scheduled_time: str = "07:00"
    gemini_model: str = "gemini-3.8-flash"
    has_custom_api_key: bool = False

