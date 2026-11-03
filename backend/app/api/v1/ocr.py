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
    raw_text = (payload.raw_text or "").strip()
    image_b64 = payload.image_base64
    if not raw_text and not image_b64:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Vui lòng cung cấp văn bản OCR thô hoặc dữ liệu ảnh (image_base64) để AI xử lý."
        )

    try:
        response = await extract_structured_ocr(
            raw_text=raw_text,
            doc_type=payload.document_type,
            user_api_key=payload.api_key,
            image_base64=image_b64,
            mime_type=payload.mime_type
        )
        return response
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lỗi khi trích xuất dữ liệu: {str(e)}"
        )
