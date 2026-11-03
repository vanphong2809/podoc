from typing import Annotated
from fastapi import APIRouter, Depends, HTTPException, status
from app.api.deps import get_current_user
from app.models.user import User
from app.schemas.ocr_extract import OcrExtractRequest, OcrExtractResponse
from app.services.llm_ocr_service import extract_structured_ocr

router = APIRouter(prefix="", tags=["OCR & IDP"])


@router.post("/ocr/extract-structured", response_model=OcrExtractResponse)
async def extract_structured(
    payload: OcrExtractRequest,
    current_user: Annotated[User, Depends(get_current_user)],
):
    """
    Trích xuất thông tin có cấu trúc (KIE) & sửa lỗi chính tả ngữ cảnh từ kết quả OCR:
    - Hỗ trợ các mẫu: CCCD/CMND (id_card), Hóa đơn (invoice), Danh thiếp (business_card), Văn bản chung (general).
    - Sử dụng mô hình Google Gemini AI qua REST API.
    - Tự động Fallback sang bộ bóc tách Heuristic Regex nếu chưa cấu hình API key.
    - Xác thực định dạng CCCD 12 số, mã tỉnh theo Bộ Công An, ngày tháng hợp lệ.
    """
    raw_text = payload.raw_text.strip()
    if not raw_text:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Nội dung văn bản OCR đầu vào không được để trống."
        )

    try:
        response = await extract_structured_ocr(
            raw_text=raw_text,
            doc_type=payload.document_type,
            user_api_key=payload.api_key
        )
        return response
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lỗi khi trích xuất dữ liệu: {str(e)}"
        )
