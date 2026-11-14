import datetime
import logging
import os
import textwrap
import uuid
from typing import Any, Dict, List, Optional, Tuple
from PIL import Image, ImageDraw, ImageFont

from app.core.config import settings

logger = logging.getLogger("podoc.news_card_generator")

# Các đường dẫn font chữ ứng viên hỗ trợ tiếng Việt trên macOS & Linux/Docker
CANDIDATE_FONTS_REGULAR = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
]

CANDIDATE_FONTS_BOLD = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
]


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    """Tải font TrueType có hỗ trợ tiếng Việt đầy đủ."""
    paths = CANDIDATE_FONTS_BOLD if bold else CANDIDATE_FONTS_REGULAR
    for p in paths:
        if os.path.isfile(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def hex_to_rgb(hex_str: str) -> Tuple[int, int, int]:
    """Chuyển mã màu hex sang tuple RGB."""
    hex_clean = hex_str.lstrip("#")
    if len(hex_clean) == 3:
        hex_clean = "".join([c * 2 for c in hex_clean])
    if len(hex_clean) >= 6:
        return tuple(int(hex_clean[i:i + 2], 16) for i in (0, 2, 4))
    return (79, 70, 229)


def wrap_text(text: str, font: ImageFont.FreeTypeFont, max_width_px: int) -> List[str]:
    """Tự động ngắt dòng theo pixel width để tránh tràn khung."""
    words = text.split()
    if not words:
        return []

    lines = []
    current_line = []

    for word in words:
        test_line = " ".join(current_line + [word])
        # Đo chiều rộng của test_line
        bbox = font.getbbox(test_line)
        line_w = bbox[2] - bbox[0]
        if line_w <= max_width_px:
            current_line.append(word)
        else:
            if current_line:
                lines.append(" ".join(current_line))
                current_line = [word]
            else:
                # Từ quá dài
                lines.append(word)
                current_line = []

    if current_line:
        lines.append(" ".join(current_line))

    return lines


def generate_news_infographic_card(briefing_data: Dict[str, Any]) -> Tuple[str, str]:
    """
    Vẽ ảnh Infographic tổng hợp Điểm tin sáng độ nét cao (1080px).
    Trả về tuple: (stored_filename, full_disk_path).
    """
    width = 1080
    # Tính toán chiều cao linh hoạt dựa theo số lượng chuyên mục và tin tức
    categories = briefing_data.get("categories", [])
    total_items = sum(len(c.get("items", [])) for c in categories)

    # Chiều cao dự kiến (khoảng 320px header + ~280px mỗi chuyên mục + footer)
    estimated_height = max(1800, 360 + len(categories) * 80 + total_items * 130 + 160)

    # 1. Khởi tạo ảnh với nền màu Slate siêu nhẹ
    image = Image.new("RGB", (width, estimated_height), (248, 250, 252))
    draw = ImageDraw.Draw(image)

    # Font sizes
    font_brand = load_font(20, bold=True)
    font_date = load_font(40, bold=True)
    font_greeting = load_font(22, bold=False)
    font_cat_badge = load_font(20, bold=True)
    font_title = load_font(25, bold=True)
    font_summary = load_font(21, bold=False)
    font_source = load_font(18, bold=False)
    font_footer = load_font(18, bold=False)

    # 2. Vẽ Header Gradient (Deep Indigo -> Midnight Blue)
    header_height = 280
    for y in range(header_height):
        # Nội suy gradient từ (15, 23, 42) đến (30, 27, 75)
        ratio = y / header_height
        r = int(15 + (30 - 15) * ratio)
        g = int(23 + (27 - 23) * ratio)
        b = int(42 + (75 - 42) * ratio)
        draw.line([(0, y), (width, y)], fill=(r, g, b))

    # Đốm sáng cam Sunrise trang trí góc trên bên phải
    draw.ellipse([(width - 200, -80), (width + 120, 240)], fill=(234, 88, 12))
    # Phủ gradient mờ lên đốm sáng
    for y in range(header_height):
        ratio = y / header_height
        draw.line([(width - 250, y), (width, y)], fill=(int(20 + 200 * (1 - ratio)), int(25 + 50 * (1 - ratio)), int(60)), width=1)

    # Header Text
    # Pill Brand Tag
    draw.rounded_rectangle([(50, 36), (360, 72)], radius=18, fill=(234, 88, 12))
    draw.text((66, 44), "☀️  PODOC MORNING BRIEFING", font=font_brand, fill=(255, 255, 255))

    # Thứ Ngày Tháng Năm
    date_str = briefing_data.get("date_str") or datetime.datetime.now().strftime("%d/%m/%Y")
    draw.text((50, 92), date_str.upper(), font=font_date, fill=(255, 255, 255))

    # Câu chào / Châm ngôn buổi sáng
    greeting = briefing_data.get("greeting") or "Chúc bạn một ngày mới tràn đầy năng lượng và hiệu quả!"
    greeting_lines = wrap_text(greeting, font_greeting, max_width_px=width - 120)

    # Box câu chào nền bán trong suốt
    draw.rounded_rectangle([(50, 160), (width - 50, 245)], radius=14, fill=(49, 46, 129))
    curr_gy = 175
    for gl in greeting_lines[:2]:
        draw.text((70, curr_gy), gl, font=font_greeting, fill=(224, 231, 255))
        curr_gy += 28

    # 3. Vẽ các Card chuyên mục
    content_y = header_height + 30
    card_margin_x = 45
    card_width = width - (card_margin_x * 2)

    for cat in categories:
        cat_name = cat.get("name", "TIN TỨC")
        cat_color_rgb = hex_to_rgb(cat.get("color", "#2563EB"))
        items = cat.get("items", [])
        if not items:
            continue

        # Tính toán chiều cao cần thiết cho Card này
        card_content_height = 65 # Tiêu đề chuyên mục
        for it in items:
            t_lines = wrap_text(it.get("title", ""), font_title, max_width_px=card_width - 60)
            s_lines = wrap_text(it.get("summary", ""), font_summary, max_width_px=card_width - 60)
            item_h = len(t_lines) * 32 + len(s_lines) * 28 + 36
            card_content_height += item_h

        card_bottom = content_y + card_content_height

        # Nền card trắng bo góc với viền xám nhạt
        draw.rounded_rectangle(
            [(card_margin_x, content_y), (card_margin_x + card_width, card_bottom)],
            radius=16,
            fill=(255, 255, 255),
            outline=(226, 232, 240),
            width=2
        )

        # Thanh màu nhấn bên trái Card
        draw.rounded_rectangle(
            [(card_margin_x, content_y), (card_margin_x + 8, card_bottom)],
            radius=4,
            fill=cat_color_rgb
        )

        # Huy hiệu Chuyên mục
        badge_text = f"  {cat_name.upper()}  "
        badge_bbox = font_cat_badge.getbbox(badge_text)
        badge_w = (badge_bbox[2] - badge_bbox[0]) + 20
        draw.rounded_rectangle(
            [(card_margin_x + 24, content_y + 16), (card_margin_x + 24 + badge_w, content_y + 50)],
            radius=8,
            fill=cat_color_rgb
        )
        draw.text((card_margin_x + 32, content_y + 22), badge_text, font=font_cat_badge, fill=(255, 255, 255))

        # Danh sách bài viết
        item_y = content_y + 70
        for idx, it in enumerate(items):
            title = it.get("title", "")
            summary = it.get("summary", "")
            source = it.get("source", "Báo chí")

            # Chấm tròn bullet point màu chuyên mục
            draw.ellipse(
                [(card_margin_x + 24, item_y + 8), (card_margin_x + 34, item_y + 18)],
                fill=cat_color_rgb
            )

            # Tiêu đề tin
            t_lines = wrap_text(title, font_title, max_width_px=card_width - 70)
            ty = item_y
            for line in t_lines:
                draw.text((card_margin_x + 44, ty), line, font=font_title, fill=(15, 23, 42))
                ty += 32

            # Tóm tắt
            s_lines = wrap_text(summary, font_summary, max_width_px=card_width - 70)
            sy = ty + 4
            for line in s_lines:
                draw.text((card_margin_x + 44, sy), line, font=font_summary, fill=(51, 65, 85))
                sy += 28

            # Tag nguồn báo
            draw.text((card_margin_x + 44, sy + 4), f"Nguồn: {source}", font=font_source, fill=(100, 116, 139))

            item_y = sy + 38

            # Đường gạch phân cách giữa các bài trong cùng chuyên mục
            if idx < len(items) - 1:
                draw.line(
                    [(card_margin_x + 44, item_y - 12), (card_margin_x + card_width - 24, item_y - 12)],
                    fill=(241, 245, 249),
                    width=1
                )

        content_y = card_bottom + 24

    # 4. Footer Infographic
    footer_y = content_y + 20
    draw.line([(50, footer_y), (width - 50, footer_y)], fill=(203, 213, 225), width=1)

    footer_text = "PoDoc AI • Tự động tổng hợp 07:00 AM mỗi ngày • Độc lập & Tự chủ dữ liệu"
    draw.text((width // 2 - 280, footer_y + 24), footer_text, font=font_footer, fill=(100, 116, 139))

    final_height = footer_y + 80
    # Crop ảnh về đúng chiều cao thực tế
    if final_height < estimated_height:
        image = image.crop((0, 0, width, final_height))

    # 5. Lưu ảnh vào FILES_STORAGE_DIR
    storage_dir = settings.FILES_STORAGE_DIR
    os.makedirs(storage_dir, exist_ok=True)

    timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    unique_suffix = uuid.uuid4().hex[:6]
    stored_filename = f"news_briefing_{timestamp_str}_{unique_suffix}.jpg"
    disk_path = os.path.join(storage_dir, stored_filename)

    image.save(disk_path, "JPEG", quality=92, optimize=True)
    logger.info(f"Đã tạo ảnh Infographic tin sáng: {disk_path} ({os.path.getsize(disk_path)} bytes)")

    return stored_filename, disk_path

