from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class DocumentType(str, Enum):
    ID_CARD = "id_card"  # Căn cước công dân / CMND
    INVOICE = "invoice"  # Hóa đơn thanh toán / VAT
    BUSINESS_CARD = "business_card"  # Danh thiếp
    GENERAL = "general"  # Văn bản tài liệu chung


class OcrExtractRequest(BaseModel):
    raw_text: Optional[str] = Field(default="", description="Văn bản thô trích xuất từ OCR (nếu có)")
    image_base64: Optional[str] = Field(default=None, description="Dữ liệu ảnh dạng base64 để AI Vision đọc trực tiếp")
    mime_type: str = Field(default="image/jpeg", description="MIME type của ảnh")
    document_type: str = Field(default="id_card", description="Loại tài liệu: id_card, invoice, business_card, general")
    api_key: Optional[str] = Field(default=None, description="Google Gemini API key cá nhân (nếu có)")


class ValidationStatus(BaseModel):
    is_valid: bool = Field(default=True, description="Tính hợp lệ tổng quan sau khi kiểm tra quy tắc nghiệp vụ")
    score: float = Field(default=1.0, description="Độ tin cậy từ 0.0 đến 1.0")
    checks: Dict[str, Any] = Field(default_factory=dict, description="Kết quả từng bước kiểm tra cụ thể")
    warnings: List[str] = Field(default_factory=list, description="Cảnh báo nếu có dữ liệu nghi ngờ")


class OcrExtractResponse(BaseModel):
    document_type: str = Field(..., description="Loại tài liệu đã xử lý")
    full_text: str = Field(default="", description="Toàn bộ văn bản đã đọc được từ ảnh")
    is_vision: bool = Field(default=False, description="True nếu xử lý trực tiếp qua AI Vision từ ảnh")
    structured_data: Dict[str, Any] = Field(..., description="Dữ liệu bóc tách có cấu trúc JSON")
    confidence_notes: List[str] = Field(default_factory=list, description="Ghi chú các lỗi chính tả và chuyển đổi AI đã thực hiện")
    validation: ValidationStatus = Field(default_factory=ValidationStatus, description="Kết quả kiểm tra tính hợp lệ nghiệp vụ")
    markdown_table: str = Field(default="", description="Bảng Markdown định dạng trực quan để lưu trữ hoặc xem")


class ReadImageRequest(BaseModel):
    image_base64: str = Field(..., description="Dữ liệu ảnh dạng base64")
    mime_type: str = Field(default="image/jpeg", description="MIME type của ảnh")
    api_key: Optional[str] = Field(default=None, description="Google Gemini API key cá nhân (nếu có)")
    prompt: Optional[str] = Field(default=None, description="Tùy chọn câu lệnh trích xuất văn bản")


class ReadImageResponse(BaseModel):
    text: str = Field(..., description="Toàn bộ văn bản trích xuất được từ ảnh")
    model_used: str = Field(default="gemini-3.8-flash", description="Mô hình AI đã thực thi")
    word_count: int = Field(default=0, description="Số từ trích xuất được")
    line_count: int = Field(default=0, description="Số dòng văn bản")
