from typing import Optional
from pydantic import BaseModel, ConfigDict, Field


class CategoryBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)
    icon: str = Field(default="folder", max_length=50)
    color: str = Field(default="#3B82F6", max_length=20)
    sort_order: int = Field(default=0)


class CategoryCreate(CategoryBase):
    pass


class CategoryUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=100)
    icon: Optional[str] = Field(None, max_length=50)
    color: Optional[str] = Field(None, max_length=20)
    sort_order: Optional[int] = None


class CategoryResponse(CategoryBase):
    id: int
    is_system: bool
    article_count: int = 0

    model_config = ConfigDict(from_attributes=True)
