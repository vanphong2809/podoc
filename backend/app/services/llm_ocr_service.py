import json
import logging
import os
import re
from typing import Any, Dict, List, Optional
import httpx

from app.core.config import settings
from app.schemas.ocr_extract import (
    DocumentType,
    OcrExtractResponse,
    ValidationStatus
)
from app.services.ocr_validator import (
    validate_business_card,
    validate_id_card,
    validate_invoice
)

logger = logging.getLogger(__name__)


def build_markdown_table(doc_type: str, data: Dict[str, Any]) -> str:
    """Tạo bảng Markdown trực quan từ dữ liệu trích xuất để người dùng có thể lưu vào Thư viện."""
    if doc_type == DocumentType.ID_CARD.value:
        lines = [
            "### 🪪 THÔNG TIN CĂN CƯỚC CÔNG DÂN",
            "",
            "| Trường thông tin | Giá trị bóc tách |",
            "|---|---|",
            f"| **Số định danh (CCCD)** | `{data.get('id_number') or '---'}` |",
            f"| **Họ và tên** | **{data.get('full_name') or '---'}** |",
            f"| **Ngày sinh** | {data.get('date_of_birth') or '---'} |",
            f"| **Giới tính** | {data.get('gender') or '---'} |",
            f"| **Quốc tịch** | {data.get('nationality') or 'Việt Nam'} |",
            f"| **Quê quán** | {data.get('place_of_origin') or '---'} |",
            f"| **Nơi thường trú** | {data.get('place_of_residence') or '---'} |",
            f"| **Có giá trị đến** | {data.get('expiry_date') or '---'} |"
        ]
        return "\n".join(lines)
    elif doc_type == DocumentType.INVOICE.value:
        lines = [
            "### 🧾 THÔNG TIN HÓA ĐƠN",
            "",
            "| Thông tin | Giá trị |",
            "|---|---|",
            f"| **Số hóa đơn** | `{data.get('invoice_number') or '---'}` |",
            f"| **Đơn vị bán** | {data.get('seller_name') or '---'} |",
            f"| **Mã số thuế** | `{data.get('seller_tax_id') or '---'}` |",
            f"| **Ngày lập** | {data.get('invoice_date') or '---'} |",
            f"| **Khách hàng** | {data.get('buyer_name') or '---'} |",
            f"| **Tổng thanh toán** | **{data.get('total_amount') or '---'}** |",
            f"| **Tóm tắt mặt hàng** | {data.get('items_summary') or '---'} |"
        ]
        return "\n".join(lines)
    elif doc_type == DocumentType.BUSINESS_CARD.value:
        lines = [
            "### 📇 THÔNG TIN DANH THIẾP",
            "",
            "| Mục | Chi tiết |",
            "|---|---|",
            f"| **Họ và tên** | **{data.get('full_name') or '---'}** |",
            f"| **Chức danh** | {data.get('job_title') or '---'} |",
            f"| **Công ty** | {data.get('company') or '---'} |",
            f"| **Số điện thoại** | `{data.get('phone_number') or '---'}` |",
            f"| **Email** | `{data.get('email') or '---'}` |",
            f"| **Địa chỉ** | {data.get('address') or '---'} |",
            f"| **Website** | {data.get('website') or '---'} |"
        ]
        return "\n".join(lines)
    else:
        title = data.get("title") or "Tài liệu trích xuất"
        content = data.get("cleaned_content") or ""
        key_points = data.get("key_points") or []
        lines = [f"### 📄 {title}", "", content, ""]
        if key_points:
            lines.append("#### Các điểm chính:")
            for kp in key_points:
                lines.append(f"- {kp}")
        return "\n".join(lines)


def fallback_heuristic_extraction(raw_text: str, doc_type: str) -> Dict[str, Any]:
    """Bộ bóc tách Heuristic / Regex dự phòng khi không có Google Gemini API Key."""
    notes = []
    data: Dict[str, Any] = {}

    if doc_type == DocumentType.ID_CARD.value:
        # Số CCCD 12 số
        id_match = re.search(r"(?:Số|No\.?|CCCD)\s*[:：]?\s*([0-9OolI!B\s]{9,16})", raw_text, re.IGNORECASE)
        if id_match:
            raw_id = id_match.group(1).strip().replace(" ", "").replace("O", "0").replace("o", "0").replace("l", "1").replace("I", "1").replace("B", "8")
            if re.match(r"^\d{9,12}$", raw_id):
                data["id_number"] = raw_id
                notes.append("Đã trích xuất số định danh bằng Heuristic Regex")

        # Họ tên
        name_match = re.search(r"(?:Họ và tên|Họ tên|Full name)\s*[:：]\s*([^\n\r]+)", raw_text, re.IGNORECASE)
        if name_match:
            clean_name = name_match.group(1).strip().replace("0", "O").replace("1", "I").replace("8", "B")
            data["full_name"] = clean_name.upper()
            notes.append("Đã trích xuất và chuẩn hóa Họ và tên in hoa")

        # Ngày sinh
        dob_match = re.search(r"(?:Ngày sinh|Date of birth|Sinh ngày)\s*[:：]?\s*([0-3]?[0-9][/\-.][O0o1-9][0-9][/\-.]\d{4})", raw_text, re.IGNORECASE)
        if dob_match:
            raw_dob = dob_match.group(1).replace("O", "0").replace("o", "0").replace(".", "/").replace("-", "/")
            data["date_of_birth"] = raw_dob
            notes.append(f"Đã trích xuất ngày sinh: {raw_dob}")

        # Giới tính
        if re.search(r"\bNam\b", raw_text, re.IGNORECASE):
            data["gender"] = "Nam"
        elif re.search(r"\bNữ\b", raw_text, re.IGNORECASE):
            data["gender"] = "Nữ"

        data["nationality"] = "Việt Nam"

        # Quê quán
        origin_match = re.search(r"(?:Quê quán|Place of origin)\s*[:：]\s*([^\n\r]+)", raw_text, re.IGNORECASE)
        if origin_match:
            data["place_of_origin"] = origin_match.group(1).strip()

        # Nơi thường trú
        residence_match = re.search(r"(?:Nơi thường trú|Place of residence|Thường trú)\s*[:：]\s*([^\n\r]+)", raw_text, re.IGNORECASE)
        if residence_match:
            data["place_of_residence"] = residence_match.group(1).strip()

    elif doc_type == DocumentType.INVOICE.value:
        # Số hóa đơn
        inv_match = re.search(r"(?:Số HĐ|Hóa đơn số|Số|Invoice No)\s*[:：]?\s*([A-Za-z0-9\-]+)", raw_text, re.IGNORECASE)
        if inv_match:
            data["invoice_number"] = inv_match.group(1).strip()

        # Mã số thuế
        tax_match = re.search(r"(?:Mã số thuế|MST|Tax code)\s*[:：]?\s*([0-9\-]{10,14})", raw_text, re.IGNORECASE)
        if tax_match:
            data["seller_tax_id"] = tax_match.group(1).strip()

        # Tổng tiền
        total_match = re.search(r"(?:Tổng tiền|Tổng cộng|Total|Thanh toán)\s*[:：]?\s*([0-9.,]+\s*(?:VND|VNĐ|đ)?)", raw_text, re.IGNORECASE)
        if total_match:
            data["total_amount"] = total_match.group(1).strip()

    elif doc_type == DocumentType.BUSINESS_CARD.value:
        # Số điện thoại
        phone_match = re.search(r"(?:Tel|Điện thoại|Mobile|Phone|SĐT)\s*[:：]?\s*([0-9+\s.()-]{10,15})", raw_text, re.IGNORECASE)
        if phone_match:
            data["phone_number"] = phone_match.group(1).strip()

        # Email
        email_match = re.search(r"[\w.+-]+@[\w-]+\.[\w.-]+", raw_text)
        if email_match:
            data["email"] = email_match.group(0).strip()

    else:
        data["title"] = "Tài liệu bóc tách Heuristic"
        data["cleaned_content"] = raw_text

    return data, notes


async def call_gemini_api(
    prompt: str,
    api_key: str,
    model: str = "gemini-3.8-flash",
    image_base64: Optional[str] = None,
    mime_type: str = "image/jpeg"
) -> Dict[str, Any]:
    """Gửi yêu cầu đến Google Gemini API với chế độ JSON Structured Output, ưu tiên Gemini 3.8 Flash, hỗ trợ Multimodal Vision."""
    models_to_try = [model]
    if "gemini-3.8-flash" not in models_to_try:
        models_to_try.insert(0, "gemini-3.8-flash")
    if "gemini-2.0-flash" not in models_to_try:
        models_to_try.append("gemini-2.0-flash")
    if "gemini-1.5-flash" not in models_to_try:
        models_to_try.append("gemini-1.5-flash")

    last_exception = None
    for target_model in models_to_try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{target_model}:generateContent?key={api_key}"

        parts: List[Dict[str, Any]] = [{"text": prompt}]
        if image_base64:
            clean_b64 = image_base64
            if "," in clean_b64:
                clean_b64 = clean_b64.split(",", 1)[1]
            parts.append({
                "inline_data": {
                    "mime_type": mime_type,
                    "data": clean_b64.strip()
                }
            })

        payload = {
            "contents": [
                {
                    "parts": parts
                }
            ],
            "generationConfig": {
                "response_mime_type": "application/json",
                "temperature": 0.1
            }
        }

        try:
            async with httpx.AsyncClient(timeout=45.0) as client:
                response = await client.post(url, json=payload)
                if response.status_code == 404:
                    logger.warning(f"Mô hình {target_model} chưa kích hoạt cho API key này (404), chuyển sang mô hình tiếp theo...")
                    continue
                response.raise_for_status()
                res_json = response.json()

                candidates = res_json.get("candidates", [])
                if not candidates:
                    raise ValueError(f"{target_model} không trả về kết quả hợp lệ.")

                content_parts = candidates[0].get("content", {}).get("parts", [])
                if not content_parts:
                    raise ValueError(f"Phản hồi {target_model} rỗng.")

                raw_content = content_parts[0].get("text", "{}")
                return json.loads(raw_content)
        except Exception as e:
            last_exception = e
            logger.warning(f"Lỗi khi gọi {target_model}: {e}")

    if last_exception:
        raise last_exception
    raise RuntimeError("Không thể gọi API Gemini.")


async def extract_structured_ocr(
    raw_text: Optional[str] = "",
    doc_type: str = DocumentType.ID_CARD.value,
    user_api_key: Optional[str] = None,
    image_base64: Optional[str] = None,
    mime_type: str = "image/jpeg"
) -> OcrExtractResponse:
    """
    Quy trình xử lý hoàn chỉnh (AI Vision trực tiếp là chính, OCR là dự phòng):
    1. Nếu có ảnh (image_base64), dùng Gemini 3.8 Flash Vision đọc trực tiếp ảnh.
    2. Bóc tách Full text và các trường có cấu trúc JSON.
    3. Nếu không có key hoặc lỗi mạng, tự động Fallback sang Heuristic Regex Extractor.
    4. Chạy bộ Validation kiểm tra tính hợp lệ nghiệp vụ (CCCD 12 số, mã tỉnh, năm sinh, v.v.).
    5. Đóng gói kết quả kèm bảng Markdown trực quan.
    """
    api_key = (user_api_key or "").strip() or settings.GEMINI_API_KEY or os.environ.get("GEMINI_API_KEY", "")
    model_name = settings.GEMINI_MODEL or "gemini-3.8-flash"

    structured_data: Dict[str, Any] = {}
    confidence_notes: List[str] = []
    full_text: str = ""
    is_vision_processed = False

    if api_key:
        if image_base64:
            is_vision_processed = True
            # Prompt chuyên sâu cho AI Vision đọc trực tiếp từ file ảnh
            if doc_type == DocumentType.ID_CARD.value:
                system_prompt = f"""
Bạn là chuyên gia thị giác máy tính và AI trích xuất Căn cước công dân (CCCD) / CMND Việt Nam.
Hình ảnh đính kèm là một thẻ CCCD hoặc giấy tờ tùy thân.
Nhiệm vụ:
1. Đọc TOÀN BỘ chữ viết trong hình ảnh vào trường 'full_text' (tiếng Việt có dấu, in hoa, số chính xác 100%, không nhầm số 0 với chữ O).
2. Bóc tách các trường thông tin cần thiết vào JSON:
{{
  "full_text": "Toàn bộ văn bản có trong ảnh",
  "id_number": "12 chữ số CCCD (chỉ số)",
  "full_name": "Họ và tên in hoa tiếng Việt",
  "date_of_birth": "DD/MM/YYYY",
  "gender": "Nam hoặc Nữ",
  "nationality": "Việt Nam",
  "place_of_origin": "Quê quán",
  "place_of_residence": "Nơi thường trú",
  "expiry_date": "DD/MM/YYYY hoặc Không thời hạn",
  "corrections": ["ghi chú chất lượng ảnh hoặc điểm đã tối ưu hóa"]
}}
"""
            elif doc_type == DocumentType.INVOICE.value:
                system_prompt = f"""
Bạn là chuyên gia thị giác máy tính trích xuất Hóa đơn thanh toán / Hóa đơn điện tử VAT Việt Nam từ ảnh.
Nhiệm vụ: Đọc toàn bộ chữ viết trong ảnh và bóc tách các trường theo JSON schema:
{{
  "full_text": "Toàn bộ nội dung chữ trong hóa đơn",
  "invoice_number": "Số hóa đơn",
  "seller_name": "Tên công ty / đơn vị bán hàng",
  "seller_tax_id": "Mã số thuế bên bán",
  "invoice_date": "Ngày lập hóa đơn DD/MM/YYYY",
  "buyer_name": "Tên người mua / đơn vị mua",
  "total_amount": "Tổng tiền thanh toán (kèm đơn vị nếu có)",
  "items_summary": "Tóm tắt ngắn gọn các mặt hàng",
  "corrections": ["ghi chú AI Vision"]
}}
"""
            elif doc_type == DocumentType.BUSINESS_CARD.value:
                system_prompt = f"""
Bạn là chuyên gia thị giác máy tính đọc Danh thiếp (Business Card) từ ảnh.
Nhiệm vụ: Đọc toàn bộ nội dung và bóc tách theo JSON:
{{
  "full_text": "Toàn bộ chữ trên danh thiếp",
  "full_name": "Họ và tên",
  "job_title": "Chức danh / Vị trí",
  "company": "Tên công ty",
  "phone_number": "Số điện thoại di động",
  "email": "Địa chỉ email",
  "address": "Địa chỉ văn phòng",
  "website": "Địa chỉ website",
  "corrections": ["ghi chú AI Vision"]
}}
"""
            else:
                system_prompt = f"""
Bạn là chuyên gia thị giác máy tính đọc văn bản từ hình ảnh tài liệu.
Nhiệm vụ: Đọc toàn bộ chữ viết tiếng Việt và tiếng Anh trong ảnh giữ nguyên bố cục đoạn, sửa các chỗ mờ nhòe.
Trả về JSON:
{{
  "full_text": "Toàn bộ văn bản đã đọc được từ ảnh một cách hoàn chỉnh và mạch lạc",
  "title": "Tiêu đề ngắn gọn gợi ý cho tài liệu",
  "cleaned_content": "Văn bản đã được chuẩn hóa",
  "key_points": ["danh sách 3-5 ý chính quan trọng nhất"],
  "corrections": ["ghi chú AI"]
}}
"""
            try:
                llm_result = await call_gemini_api(
                    prompt=system_prompt,
                    api_key=api_key,
                    model=model_name,
                    image_base64=image_base64,
                    mime_type=mime_type
                )
                confidence_notes = llm_result.pop("corrections", []) or []
                full_text = llm_result.pop("full_text", "") or ""
                structured_data = llm_result
                confidence_notes.insert(0, f"Đã đọc trực tiếp từ ảnh bằng AI Vision ({model_name})")
            except Exception as e:
                logger.warning(f"Lỗi khi gọi Gemini Vision ({e}), chuyển sang chế độ Heuristic / OCR Fallback.")
                structured_data, confidence_notes = fallback_heuristic_extraction(raw_text or "", doc_type)
                full_text = raw_text or ""
                confidence_notes.append(f"Chế độ ngoại tuyến Heuristic (Vision lỗi: {str(e)[:60]})")
        else:
            # Xử lý từ văn bản thô (khi không truyền ảnh)
            text_to_process = raw_text or ""
            if doc_type == DocumentType.ID_CARD.value:
                system_prompt = f"""
Bạn là chuyên gia Intelligent Document Processing (IDP) xử lý căn cước công dân Việt Nam (CCCD).
Nhiệm vụ: Phân tích đoạn văn bản OCR sau, SỬA LỖI CHÍNH TẢ do OCR đọc nhầm theo ngữ cảnh (ví dụ 'PH0NG' -> 'PHONG', '28/O9/1998' -> '28/09/1998', 'Hà Nộj' -> 'Hà Nội', số 0 lẫn chữ O).
Chỉ trích xuất các thông tin cần thiết, bỏ qua toàn bộ văn bản rác, quốc huy, hoa văn.
Trả về định dạng JSON thuần túy theo schema sau:
{{
  "id_number": "12 chữ số CCCD (chỉ số)",
  "full_name": "Họ và tên in hoa tiếng Việt",
  "date_of_birth": "DD/MM/YYYY",
  "gender": "Nam hoặc Nữ",
  "nationality": "Việt Nam",
  "place_of_origin": "Quê quán",
  "place_of_residence": "Nơi thường trú",
  "expiry_date": "DD/MM/YYYY hoặc Không thời hạn",
  "corrections": ["danh sách các lỗi chính tả cụ thể đã được sửa, ví dụ: Sửa 'PH0NG' -> 'PHONG'"]
}}

VĂN BẢN OCR THÔ CẦN XỬ LÝ:
\"\"\"{text_to_process}\"\"\"
"""
            elif doc_type == DocumentType.INVOICE.value:
                system_prompt = f"""
Bạn là chuyên gia bóc tách Hóa đơn mua hàng / Hóa đơn điện tử VAT Việt Nam.
Nhiệm vụ: Sửa lỗi OCR và trích xuất các trường thông tin thành JSON theo schema sau:
{{
  "invoice_number": "Số hóa đơn",
  "seller_name": "Tên công ty / đơn vị bán hàng",
  "seller_tax_id": "Mã số thuế bên bán",
  "invoice_date": "Ngày lập hóa đơn DD/MM/YYYY",
  "buyer_name": "Tên người mua / đơn vị mua",
  "total_amount": "Tổng tiền thanh toán (kèm đơn vị nếu có)",
  "items_summary": "Tóm tắt ngắn gọn các mặt hàng",
  "corrections": ["danh sách các lỗi chính tả đã sửa"]
}}

VĂN BẢN OCR THÔ:
\"\"\"{text_to_process}\"\"\"
"""
            elif doc_type == DocumentType.BUSINESS_CARD.value:
                system_prompt = f"""
Bạn là chuyên gia trích xuất Danh thiếp (Business Card).
Nhiệm vụ: Sửa lỗi OCR và trích xuất thành JSON theo schema sau:
{{
  "full_name": "Họ và tên",
  "job_title": "Chức danh / Vị trí công việc",
  "company": "Tên công ty",
  "phone_number": "Số điện thoại di động",
  "email": "Địa chỉ email",
  "address": "Địa chỉ văn phòng",
  "website": "Địa chỉ website",
  "corrections": ["danh sách các lỗi chính tả đã sửa"]
}}

VĂN BẢN OCR THÔ:
\"\"\"{text_to_process}\"\"\"
"""
            else:
                system_prompt = f"""
Bạn là trợ lý AI sửa lỗi chính tả và chuẩn hóa văn bản tiếng Việt từ kết quả OCR.
Nhiệm vụ: Sửa toàn bộ lỗi chính tả, dấu câu, ngắt dòng do OCR, giữ nguyên ý nghĩa nội dung.
Trả về định dạng JSON:
{{
  "title": "Tiêu đề ngắn gọn gợi ý cho tài liệu",
  "cleaned_content": "Toàn bộ văn bản đã được sửa lỗi chính tả và chuẩn hóa trình bày",
  "key_points": ["danh sách 3-5 ý chính quan trọng nhất"],
  "corrections": ["danh sách các lỗi chính tả tiêu biểu đã sửa"]
}}

VĂN BẢN OCR THÔ:
\"\"\"{text_to_process}\"\"\"
"""
            try:
                llm_result = await call_gemini_api(system_prompt, api_key, model=model_name)
                confidence_notes = llm_result.pop("corrections", []) or []
                full_text = text_to_process
                structured_data = llm_result
            except Exception as e:
                logger.warning(f"Lỗi khi gọi Gemini API ({e}), tự động chuyển sang Fallback Heuristic Extractor.")
                structured_data, confidence_notes = fallback_heuristic_extraction(text_to_process, doc_type)
                full_text = text_to_process
                confidence_notes.append(f"Chế độ ngoại tuyến Heuristic (LLM lỗi: {str(e)[:60]})")
    else:
        # Không có API key -> dùng Fallback Heuristic Extractor ngay lập tức
        text_to_process = raw_text or ""
        structured_data, confidence_notes = fallback_heuristic_extraction(text_to_process, doc_type)
        full_text = text_to_process
        confidence_notes.append("Trích xuất bằng bộ quy tắc Heuristic Regex (Không có Gemini API Key)")

    # Nếu full_text vẫn rỗng, xây dựng lại từ các trường
    if not full_text:
        full_text = "\n".join(f"{k}: {v}" for k, v in structured_data.items() if v)

    # Kiểm tra tính hợp lệ bằng Validation Layer
    if doc_type == DocumentType.ID_CARD.value:
        validation = validate_id_card(structured_data)
    elif doc_type == DocumentType.INVOICE.value:
        validation = validate_invoice(structured_data)
    elif doc_type == DocumentType.BUSINESS_CARD.value:
        validation = validate_business_card(structured_data)
    else:
        validation = ValidationStatus(is_valid=True, score=1.0)

    # Sinh bảng markdown định dạng
    markdown_table = build_markdown_table(doc_type, structured_data)

    return OcrExtractResponse(
        document_type=doc_type,
        full_text=full_text,
        is_vision=is_vision_processed,
        structured_data=structured_data,
        confidence_notes=confidence_notes,
        validation=validation,
        markdown_table=markdown_table
    )
