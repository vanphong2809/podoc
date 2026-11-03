# Unit tests for OCR Extraction & Validation
from app.services.ocr_validator import validate_id_card, validate_date_str, validate_vietnam_phone
from app.services.llm_ocr_service import fallback_heuristic_extraction, build_markdown_table
from app.schemas.ocr_extract import DocumentType


def test_validate_date():
    assert validate_date_str("28/09/1998") is True
    assert validate_date_str("32/01/2020") is False
    assert validate_date_str("invalid") is False


def test_validate_phone():
    assert validate_vietnam_phone("0987654321") is True
    assert validate_vietnam_phone("0123456789") is False
    assert validate_vietnam_phone("098.765.4321") is True


def test_validate_id_card_full():
    data = {
        "id_number": "001098012345",  # 001 là Hà Nội, 0 là Nam TK20, 98 là năm sinh 1998
        "full_name": "NGUYỄN VĂN PHONG",
        "date_of_birth": "28/09/1998",
        "place_of_origin": "Hà Nội",
        "place_of_residence": "Hà Nội"
    }
    status = validate_id_card(data)
    assert status.is_valid is True
    assert status.score >= 0.8
    assert status.checks.get("province_name") == "Hà Nội"
    assert status.checks.get("birth_year_match") is True


def test_fallback_heuristic_id_card():
    raw_ocr = """
    CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM
    CĂN CƯỚC CÔNG DÂN
    Số / No: 001098012345
    Họ và tên: NGUYỄN VĂN PH0NG
    Ngày sinh: 28/O9/1998
    Quê quán: Hà Nộj
    Nơi thường trú: Hà Nộj
    """
    extracted, notes = fallback_heuristic_extraction(raw_ocr, DocumentType.ID_CARD.value)
    assert extracted.get("id_number") == "001098012345"
    assert extracted.get("full_name") == "NGUYỄN VĂN PHONG"
    assert extracted.get("date_of_birth") == "28/09/1998"
    assert "place_of_origin" in extracted

    md = build_markdown_table(DocumentType.ID_CARD.value, extracted)
    assert "001098012345" in md
    assert "NGUYỄN VĂN PHONG" in md
