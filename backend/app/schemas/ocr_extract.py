from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class DocumentType(str, Enum):
    ID_CARD = "id_card"  # Căn cước công dân / CMND
    INVOICE = "invoice"  # Hóa đơn thanh toán / VAT
    BUSINESS_CARD = "business_card"  # Danh thiếp
    GENERAL = "general"  # Văn bản tài liệu chung


class OcrExtractRequest(BaseModel):
    raw_text: str = Field(..., min_length=1, description="Văn bản thô trích xuất từ OCR")
    document_type: str = Field(default="id_card", description="Loại tài liệu: id_card, invoice, business_card, general")
    api_key: Optional[str] = Field(default=None, description="Google Gemini API key cá nhân (nếu có)")


class ValidationStatus(BaseModel):
    is_valid: bool = Field(default=True, description="Tính hợp lệ tổng quan sau khi kiểm tra quy tắc nghiệp vụ")
    score: float = Field(default=1.0, description="Độ tin cậy từ 0.0 đến 1.0")
    checks: Dict[str, Any] = Field(default_factory=dict, description="Kết quả từng bước kiểm tra cụ thể")
    warnings: List[str] = Field(default_factory=list, description="Cảnh báo nếu có dữ liệu nghi ngờ")


class OcrExtractResponse(BaseModel):
    document_type: str = Field(..., description="Loại tài liệu đã xử lý")
    structured_data: Dict[str, Any] = Field(..., description="Dữ liệu bóc tách có cấu trúc JSON")
    confidence_notes: List[str] = Field(default_factory=list, description="Ghi chú các lỗi chính tả và chuyển đổi AI đã thực hiện")
    validation: ValidationStatus = Field(default_factory=ValidationStatus, description="Kết quả kiểm tra tính hợp lệ nghiệp vụ")
    markdown_table: str = Field(default="", description="Bảng Markdown định dạng trực quan để lưu trữ hoặc xem")
