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
        text_to_process = (raw_text or "").strip()

        if text_to_process:
            # ƯU TIÊN SỐ 1: Phân tích & Bóc tách trực tiếp từ văn bản OCR thực tế do thiết bị quét
            # Chống hoàn toàn hiện tượng ảo giác (hallucination) / đọc text một đằng ra một nẻo
            is_vision_processed = False
            if doc_type == DocumentType.ID_CARD.value:
                system_prompt = f"""
Bạn là chuyên gia Intelligent Document Processing (IDP) xử lý căn cước công dân Việt Nam (CCCD).
Nhiệm vụ: Phân tích đoạn văn bản OCR sau, SỬA LỖI CHÍNH TẢ do OCR đọc nhầm theo ngữ cảnh (ví dụ 'PH0NG' -> 'PHONG', '28/O9/1998' -> '28/09/1998', 'Hà Nộj' -> 'Hà Nội', số 0 lẫn chữ O).
Chỉ trích xuất các thông tin cần thiết, bỏ qua toàn bộ văn bản rác, quốc huy, hoa văn.

NGUYÊN TẮC BẮT BUỘC:
1. Bạn CHỈ ĐƯỢC trích xuất thông tin có thật trong đoạn văn bản OCR dưới đây.
2. TUYỆT ĐỐI KHÔNG BỊA ĐẶT thông tin, họ tên hoặc số CCCD không xuất hiện trong văn bản.
3. Nếu trường thông tin nào không có trong văn bản, hãy để chuỗi rỗng "" hoặc null.

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
Nhiệm vụ: Sửa lỗi OCR và trích xuất các trường thông tin thành JSON.

NGUYÊN TẮC BẮT BUỘC:
1. CHỈ trích xuất thông tin có trong văn bản OCR dưới đây, TUYỆT ĐỐI KHÔNG BỊA ĐẶT số tiền hoặc tên công ty.
2. Nếu trường thông tin nào không có, để chuỗi rỗng "".

Trả về JSON theo schema:
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
Nhiệm vụ: Sửa lỗi OCR và trích xuất thành JSON.

NGUYÊN TẮC BẮT BUỘC:
1. CHỈ trích xuất thông tin có trong văn bản OCR dưới đây, TUYỆT ĐỐI KHÔNG BỊA ĐẶT.
2. Nếu trường thông tin nào không có, để chuỗi rỗng "".

Trả về JSON theo schema:
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
Nhiệm vụ: Sửa toàn bộ lỗi chính tả, dấu câu, ngắt dòng do OCR, giữ nguyên ý nghĩa nội dung và số liệu có thật.
TUYỆT ĐỐI KHÔNG BỊA ĐẶT thêm thông tin ngoài văn bản.

Trả về định dạng JSON:
{{
  "title": "Tiêu đề ngắn gọn gợi ý cho tài liệu",
  "cleaned_content": "Toàn bộ văn bản đã được sửa lỗi chính tả và chuẩn hóa trình bày",
  "key_points": ["danh sách 3-5 ý chính quan trọng nhất có trong văn bản"],
  "corrections": ["danh sách các lỗi chính tả tiêu biểu đã sửa"]
}}

VĂN BẢN OCR THÔ:
\"\"\"{text_to_process}\"\"\"
"""
            try:
                llm_result = await call_gemini_api(system_prompt, api_key, model=model_name)
                confidence_notes = llm_result.pop("corrections", []) or []
                full_text = llm_result.get("cleaned_content") or text_to_process
                structured_data = llm_result
            except Exception as e:
                logger.warning(f"Lỗi khi gọi Gemini API ({e}), tự động chuyển sang Fallback Heuristic Extractor.")
                structured_data, confidence_notes = fallback_heuristic_extraction(text_to_process, doc_type)
                full_text = text_to_process
                confidence_notes.append(f"Chế độ ngoại tuyến Heuristic (LLM lỗi: {str(e)[:60]})")
        elif image_base64:
            # Chỉ khi không có raw_text thì mới thử đọc ảnh
            is_vision_processed = True
            system_prompt = f"""
Bạn là chuyên gia thị giác máy tính đọc văn bản từ hình ảnh.
Hãy đọc trung thực và chính xác từng chữ viết trong ảnh. TUYỆT ĐỐI KHÔNG BỊA ĐẶT NỘI DUNG.
Trả về JSON:
{{
  "full_text": "Toàn bộ văn bản có trong ảnh",
  "title": "Tiêu đề tài liệu",
  "cleaned_content": "Văn bản đã đọc",
  "key_points": [],
  "corrections": []
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
            except Exception as e:
                logger.warning(f"Lỗi khi gọi Gemini Vision ({e}), chuyển sang chế độ Heuristic.")
                structured_data, confidence_notes = fallback_heuristic_extraction("", doc_type)
                full_text = ""
                confidence_notes.append(f"Vision lỗi: {str(e)[:60]}")
    else:
        # Không có API key -> dùng Fallback Heuristic Extractor ngay lập tức
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


async def read_image_with_gemini(
    image_base64: str,
    mime_type: str = "image/jpeg",
    user_api_key: Optional[str] = None,
    custom_prompt: Optional[str] = None
) -> Dict[str, Any]:
    """
    Trích xuất toàn bộ văn bản từ hình ảnh bằng Google Gemini 3.8 Flash (Multimodal Vision):
    - Đọc trung thực 100% từng chữ viết, tiếng Việt có dấu, số và bố cục tài liệu giống hệt như khi chat với model.
    - Không ép khuôn khổ JSON Schema cố định để tránh hallucination (ảo giác).
    - Hỗ trợ fallback tự động sang gemini-2.0-flash / gemini-1.5-flash nếu cần.
    """
    api_key = (user_api_key or "").strip() or settings.GEMINI_API_KEY or os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        raise ValueError("Chưa cấu hình Google Gemini API Key. Bạn hãy bấm biểu tượng chìa khóa ở góc trên để nhập API Key từ Google AI Studio.")

    targets_to_try = [
        ("gemini-2.0-flash", "v1beta"),
        ("gemini-2.0-flash-exp", "v1beta"),
        ("gemini-1.5-flash", "v1"),
        ("gemini-1.5-flash", "v1beta"),
        ("gemini-1.5-flash-latest", "v1beta"),
        ("gemini-1.5-flash-002", "v1"),
        ("gemini-1.5-pro", "v1"),
        ("gemini-1.5-pro", "v1beta"),
        ("gemini-3.8-flash", "v1beta"),
    ]

    clean_b64 = image_base64
    if "," in clean_b64:
        clean_b64 = clean_b64.split(",", 1)[1]
    clean_b64 = clean_b64.strip()

    prompt = custom_prompt or (
        "Hãy đọc và trích xuất TOÀN BỘ văn bản chữ viết có trong hình ảnh này một cách chính xác và trung thực nhất.\n"
        "- Giữ nguyên toàn bộ từ ngữ tiếng Việt có dấu, chữ số, ký hiệu và dấu câu.\n"
        "- Giữ nguyên cấu trúc các dòng, đoạn văn, danh sách hoặc bảng biểu giống như bố cục trong tài liệu gốc.\n"
        "- KHÔNG giải thích, KHÔNG tóm tắt, KHÔNG thêm lời chào hay kết luận.\n"
        "- Chỉ trả về văn bản đọc được từ ảnh."
    )

    payload = {
        "contents": [
            {
                "parts": [
                    {
                        "inline_data": {
                            "mime_type": mime_type,
                            "data": clean_b64
                        }
                    },
                    {
                        "text": prompt
                    }
                ]
            }
        ],
        "generationConfig": {
            "temperature": 0.0
        }
    }

    last_error = None
    for target_model, api_version in targets_to_try:
        url = f"https://generativelanguage.googleapis.com/{api_version}/models/{target_model}:generateContent?key={api_key}"
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                res = await client.post(url, json=payload)
                if res.status_code == 404:
                    logger.warning(f"Mô hình {target_model} ({api_version}) không hỗ trợ (404), thử mô hình tiếp theo...")
                    continue
                res.raise_for_status()
                res_json = res.json()
                candidates = res_json.get("candidates", [])
                if not candidates:
                    continue
                parts = candidates[0].get("content", {}).get("parts", [])
                if not parts:
                    continue
                extracted_text = "".join([p.get("text", "") for p in parts]).strip()
                if not extracted_text:
                    continue
                words = len(extracted_text.split())
                lines = len([line for line in extracted_text.splitlines() if line.strip()])
                return {
                    "text": extracted_text,
                    "model_used": target_model,
                    "word_count": words,
                    "line_count": lines
                }
        except Exception as e:
            last_error = e
            logger.warning(f"Lỗi khi gọi {target_model} ({api_version}): {e}")

    if last_error:
        raise last_error
    raise RuntimeError("Không thể trích xuất văn bản từ ảnh qua Gemini API.")
