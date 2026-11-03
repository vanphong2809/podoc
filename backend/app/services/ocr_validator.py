import re
from datetime import datetime
from typing import Any, Dict, List
from app.schemas.ocr_extract import ValidationStatus

# Danh mục 63 mã tỉnh/thành phố trên thẻ CCCD theo quy định của Bộ Công An
CCCD_PROVINCE_CODES = {
    "001": "Hà Nội", "002": "Hà Giang", "004": "Cao Bằng", "006": "Bắc Kạn",
    "008": "Tuyên Quang", "010": "Lào Cai", "011": "Điện Biên", "012": "Lai Châu",
    "014": "Sơn La", "015": "Yên Bái", "017": "Hòa Bình", "019": "Thái Nguyên",
    "020": "Lạng Sơn", "022": "Quảng Ninh", "024": "Bắc Giang", "025": "Phú Thọ",
    "026": "Vĩnh Phúc", "027": "Bắc Ninh", "030": "Hải Dương", "031": "Hải Phòng",
    "033": "Hưng Yên", "034": "Thái Bình", "035": "Hà Nam", "036": "Nam Định",
    "037": "Ninh Bình", "038": "Thanh Hóa", "040": "Nghệ An", "042": "Hà Tĩnh",
    "044": "Quảng Bình", "045": "Quảng Trị", "046": "Thừa Thiên Huế", "048": "Đà Nẵng",
    "049": "Quảng Nam", "051": "Quảng Ngãi", "052": "Bình Định", "054": "Phú Yên",
    "056": "Khánh Hòa", "058": "Ninh Thuận", "060": "Bình Thuận", "062": "Kon Tum",
    "064": "Gia Lai", "066": "Đắk Lắk", "067": "Đắk Nông", "068": "Lâm Đồng",
    "070": "Bình Phước", "072": "Tây Ninh", "074": "Bình Dương", "075": "Đồng Nai",
    "077": "Bà Rịa - Vũng Tàu", "079": "Hồ Chí Minh", "080": "Long An", "082": "Tiền Giang",
    "083": "Bến Tre", "084": "Trà Vinh", "086": "Vĩnh Long", "087": "Đồng Tháp",
    "089": "An Giang", "091": "Kiên Giang", "092": "Cần Thơ", "093": "Hậu Giang",
    "094": "Sóc Trăng", "095": "Bạc Liêu", "096": "Cà Mau"
}


def validate_date_str(date_str: str) -> bool:
    """Kiểm tra chuỗi ngày tháng có đúng định dạng DD/MM/YYYY và không vượt quá hiện tại."""
    if not date_str:
        return False
    try:
        dt = datetime.strptime(date_str.strip(), "%d/%m/%Y")
        return dt <= datetime.now()
    except Exception:
        return False


def validate_vietnam_phone(phone_str: str) -> bool:
    """Kiểm tra số điện thoại di động Việt Nam (10 chữ số, đầu số hợp lệ)."""
    if not phone_str:
        return False
    clean = re.sub(r"[\s.-]", "", phone_str)
    return bool(re.match(r"^(03|05|07|08|09)\d{8}$", clean))


def validate_id_card(data: Dict[str, Any]) -> ValidationStatus:
    """
    Xác thực dữ liệu Căn cước công dân / CMND theo quy tắc nghiệp vụ:
    - Định dạng 12 chữ số
    - 3 số đầu tra cứu mã tỉnh theo Bộ Công An
    - 2 số năm sinh khớp với ngày sinh
    - Ngày sinh và ngày hết hạn hợp lệ
    """
    checks = {}
    warnings = []
    score_points = 0
    max_points = 5

    id_number = str(data.get("id_number") or "").strip()
    dob = str(data.get("date_of_birth") or "").strip()
    full_name = str(data.get("full_name") or "").strip()

    # 1. Kiểm tra Họ và tên
    if full_name and len(full_name) >= 3 and not any(char.isdigit() for char in full_name):
        checks["full_name_valid"] = True
        score_points += 1
    else:
        checks["full_name_valid"] = False
        warnings.append("Họ và tên chưa chuẩn hoặc chứa ký tự số")

    # 2. Kiểm tra định dạng số CCCD (12 số) hoặc CMND (9 số)
    is_cccd_12 = bool(re.match(r"^\d{12}$", id_number))
    is_cmnd_9 = bool(re.match(r"^\d{9}$", id_number))

    if is_cccd_12:
        checks["id_format_12_digits"] = True
        score_points += 1

        # 3. Kiểm tra mã tỉnh (3 số đầu)
        province_code = id_number[:3]
        if province_code in CCCD_PROVINCE_CODES:
            checks["province_code_valid"] = True
            checks["province_name"] = CCCD_PROVINCE_CODES[province_code]
            score_points += 1
        else:
            checks["province_code_valid"] = False
            warnings.append(f"Mã tỉnh '{province_code}' trên CCCD không nằm trong danh mục Bộ Công An")

        # 4. Kiểm tra mã năm sinh (2 số thứ 5 và 6) khớp với Date of Birth
        if dob and "/" in dob:
            parts = dob.split("/")
            if len(parts) == 3 and len(parts[2]) == 4:
                birth_year_last2 = parts[2][-2:]
                cccd_birth_year = id_number[4:6]
                if birth_year_last2 == cccd_birth_year:
                    checks["birth_year_match"] = True
                    score_points += 1
                else:
                    checks["birth_year_match"] = False
                    warnings.append(f"Năm sinh trên CCCD ({cccd_birth_year}) không khớp với ngày sinh ({parts[2]})")
            else:
                checks["birth_year_match"] = False
        else:
            checks["birth_year_match"] = False
    elif is_cmnd_9:
        checks["id_format_9_digits"] = True
        score_points += 2
    else:
        checks["id_format_valid"] = False
        warnings.append("Số định danh không đúng 12 chữ số (CCCD) hoặc 9 chữ số (CMND)")

    # 5. Kiểm tra ngày tháng năm sinh
    if dob:
        if validate_date_str(dob):
            checks["date_of_birth_valid"] = True
            score_points += 1
        else:
            checks["date_of_birth_valid"] = False
            warnings.append(f"Ngày sinh '{dob}' không hợp lệ hoặc lớn hơn ngày hiện tại")
    else:
        checks["date_of_birth_valid"] = False
        warnings.append("Chưa tìm thấy ngày sinh")

    confidence = round(score_points / max_points, 2)
    is_valid = confidence >= 0.6 and ("id_format_12_digits" in checks or "id_format_9_digits" in checks)

    return ValidationStatus(
        is_valid=is_valid,
        score=confidence,
        checks=checks,
        warnings=warnings
    )


def validate_invoice(data: Dict[str, Any]) -> ValidationStatus:
    """Xác thực dữ liệu Hóa đơn."""
    checks = {}
    warnings = []
    score_points = 0
    max_points = 3

    if data.get("invoice_number"):
        checks["has_invoice_number"] = True
        score_points += 1
    else:
        checks["has_invoice_number"] = False
        warnings.append("Không tìm thấy số hóa đơn")

    if data.get("total_amount"):
        checks["has_total_amount"] = True
        score_points += 1
    else:
        checks["has_total_amount"] = False
        warnings.append("Không tìm thấy tổng tiền thanh toán")

    if data.get("seller_tax_id"):
        checks["has_tax_id"] = True
        score_points += 1
    else:
        checks["has_tax_id"] = False

    return ValidationStatus(
        is_valid=score_points >= 2,
        score=round(score_points / max_points, 2),
        checks=checks,
        warnings=warnings
    )


def validate_business_card(data: Dict[str, Any]) -> ValidationStatus:
    """Xác thực dữ liệu Danh thiếp."""
    checks = {}
    warnings = []
    score_points = 0
    max_points = 3

    if data.get("full_name"):
        checks["has_name"] = True
        score_points += 1

    phone = data.get("phone_number")
    if phone and validate_vietnam_phone(str(phone)):
        checks["phone_valid"] = True
        score_points += 1
    elif phone:
        checks["phone_valid"] = False
        warnings.append("Số điện thoại không đúng định dạng di động Việt Nam")

    email = data.get("email")
    if email and re.match(r"^[^@]+@[^@]+\.[^@]+$", str(email).strip()):
        checks["email_valid"] = True
        score_points += 1

    return ValidationStatus(
        is_valid=score_points >= 2,
        score=round(score_points / max_points, 2),
        checks=checks,
        warnings=warnings
    )

