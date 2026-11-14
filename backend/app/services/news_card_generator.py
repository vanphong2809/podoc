import datetime
import io
import logging
import os
import textwrap
import uuid
from typing import Any, Dict, List, Optional, Tuple
import httpx
from PIL import Image, ImageDraw, ImageFont, ImageFilter

from app.core.config import settings

logger = logging.getLogger("podoc.news_card_generator")


def get_effective_storage_dir() -> str:
    """Trả về thư mục lưu trữ file, tự động fallback nếu /data/files không thể ghi (ví dụ khi test cục bộ trên macOS)."""
    dir_path = settings.FILES_STORAGE_DIR
    try:
        os.makedirs(dir_path, exist_ok=True)
        # Test write permission
        test_file = os.path.join(dir_path, ".write_test")
        with open(test_file, "w") as f:
            f.write("ok")
        os.remove(test_file)
        return dir_path
    except (OSError, PermissionError):
        fallback = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../storage"))
        os.makedirs(fallback, exist_ok=True)
        return fallback

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
    storage_dir = get_effective_storage_dir()
    os.makedirs(storage_dir, exist_ok=True)

    timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    unique_suffix = uuid.uuid4().hex[:6]
    stored_filename = f"news_briefing_{timestamp_str}_{unique_suffix}.jpg"
    disk_path = os.path.join(storage_dir, stored_filename)

    image.save(disk_path, "JPEG", quality=92, optimize=True)
    logger.info(f"Đã tạo ảnh Infographic tin sáng: {disk_path} ({os.path.getsize(disk_path)} bytes)")

    return stored_filename, disk_path


def download_thumbnail_image(url: str, target_size: Tuple[int, int]) -> Optional[Image.Image]:
    """Tải và crop ảnh thumbnail tin tức theo tỷ lệ mong muốn."""
    if not url or not (url.startswith("http://") or url.startswith("https://")):
        return None
    try:
        with httpx.Client(timeout=3.5, follow_redirects=True) as client:
            resp = client.get(url)
            if resp.status_code == 200 and resp.content:
                raw_img = Image.open(io.BytesIO(resp.content)).convert("RGB")
                target_w, target_h = target_size
                src_w, src_h = raw_img.size
                src_ratio = src_w / max(1, src_h)
                target_ratio = target_w / max(1, target_h)

                if src_ratio > target_ratio:
                    crop_w = int(src_h * target_ratio)
                    offset_x = (src_w - crop_w) // 2
                    cropped = raw_img.crop((offset_x, 0, offset_x + crop_w, src_h))
                else:
                    crop_h = int(src_w / target_ratio)
                    offset_y = (src_h - crop_h) // 2
                    cropped = raw_img.crop((0, offset_y, src_w, offset_y + crop_h))

                return cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)
    except Exception as e:
        logger.debug(f"Không thể tải ảnh tin tức từ {url}: {e}")
    return None


def generate_cover_slide(date_str: str, greeting: str, categories: List[Dict[str, Any]]) -> Tuple[str, str]:
    """Vẽ slide bìa tổng quan (Slide 0) cho bộ series ảnh TikTok style."""
    w, h = 1080, 1440
    img = Image.new("RGB", (w, h), color=(15, 23, 42))
    draw = ImageDraw.Draw(img)

    for y in range(h):
        ratio = y / h
        r = int(124 * (1 - ratio) + 15 * ratio)
        g = int(45 * (1 - ratio) + 23 * ratio)
        b = int(18 * (1 - ratio) + 42 * ratio)
        draw.line([(0, y), (w, y)], fill=(r, g, b))

    draw.ellipse([(w // 2 - 260, 60), (w // 2 + 260, 580)], fill=(234, 88, 12))
    draw.ellipse([(w // 2 - 180, 140), (w // 2 + 180, 500)], fill=(251, 146, 60))

    font_brand = load_font(24, bold=True)
    font_main_title = load_font(52, bold=True)
    font_date = load_font(34, bold=True)
    font_greeting = load_font(28, bold=False)
    font_chip = load_font(24, bold=True)
    font_prompt = load_font(28, bold=True)

    draw.rounded_rectangle([(w // 2 - 180, 80), (w // 2 + 180, 128)], radius=24, fill=(15, 23, 42))
    draw.text((w // 2 - 140, 92), "☀️  PODOC BRIEFING", font=font_brand, fill=(254, 215, 170))

    draw.text((w // 2 - 200, 220), "ĐIỂM TIN SÁNG", font=font_main_title, fill=(255, 255, 255))
    draw.text((w // 2 - 180, 300), date_str.upper(), font=font_date, fill=(254, 215, 170))

    greeting_lines = wrap_text(greeting, font_greeting, max_width_px=w - 160)
    box_top = 440
    box_h = len(greeting_lines) * 40 + 50
    draw.rounded_rectangle([(60, box_top), (w - 60, box_top + box_h)], radius=22, fill=(30, 41, 59))
    draw.rounded_rectangle([(60, box_top), (w - 60, box_top + box_h)], radius=22, outline=(249, 115, 22), width=2)
    gy = box_top + 26
    for line in greeting_lines:
        draw.text((90, gy), line, font=font_greeting, fill=(241, 245, 249))
        gy += 40

    chips_y = box_top + box_h + 40
    draw.text((70, chips_y), "CÁC CHUYÊN MỤC TRONG BẢN TIN HÔM NAY:", font=load_font(22, bold=True), fill=(148, 163, 184))
    chips_y += 45

    cat_chips = [
        ("🔴 Thời sự & Xã hội", "#DC2626"),
        ("🔵 Khoa học & Công nghệ", "#2563EB"),
        ("🟢 Bóng đá & Thể thao", "#059669"),
        ("🟣 Giải trí & Văn hóa", "#9333EA"),
        ("🟡 Giáo dục & Tri thức", "#D97706"),
        ("🟠 Đời sống & Sức khỏe", "#EA580C"),
    ]

    cx, cy = 70, chips_y
    for label, col_hex in cat_chips:
        col_rgb = hex_to_rgb(col_hex)
        bbox = font_chip.getbbox(label)
        chip_w = (bbox[2] - bbox[0]) + 36
        if cx + chip_w > w - 70:
            cx = 70
            cy += 60
        draw.rounded_rectangle([(cx, cy), (cx + chip_w, cy + 50)], radius=14, fill=col_rgb)
        draw.text((cx + 18, cy + 10), label, font=font_chip, fill=(255, 255, 255))
        cx += chip_w + 16

    draw.rounded_rectangle([(60, h - 140), (w - 60, h - 50)], radius=20, fill=(234, 88, 12))
    draw.text((w // 2 - 260, h - 108), "Vuốt sang trái để xem từng tin tức ➔", font=font_prompt, fill=(255, 255, 255))

    storage_dir = get_effective_storage_dir()
    os.makedirs(storage_dir, exist_ok=True)
    timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    unique_suffix = uuid.uuid4().hex[:6]
    stored_filename = f"slide_cover_{timestamp_str}_{unique_suffix}.jpg"
    disk_path = os.path.join(storage_dir, stored_filename)
    img.save(disk_path, "JPEG", quality=90, optimize=True)
    return stored_filename, disk_path


def generate_news_slide_card(
    item: Dict[str, Any],
    category: Dict[str, Any],
    slide_index: int,
    total_slides: int,
    date_str: str
) -> Tuple[str, str]:
    """
    Vẽ 1 ảnh slide tin tức riêng biệt (TikTok/Story Style, 1080x1440).
    Mỗi ảnh là 1 tin tức trọn vẹn gồm ảnh bài báo, tiêu đề to rõ, và phần tóm tắt nổi bật.
    """
    w, h = 1080, 1440
    card = Image.new("RGB", (w, h), color=(15, 23, 42))

    cat_name = category.get("name", "TIN TỨC")
    cat_color_hex = category.get("color", "#2563EB")
    cat_color_rgb = hex_to_rgb(cat_color_hex)
    raw_img_url = item.get("image_url")

    bg_img = download_thumbnail_image(raw_img_url, target_size=(w, 800)) if raw_img_url else None

    if bg_img:
        card.paste(bg_img, (0, 0))
    else:
        draw_temp = ImageDraw.Draw(card)
        for y in range(800):
            ratio = y / 800
            r = int(cat_color_rgb[0] * 0.7 * (1 - ratio) + 15 * ratio)
            g = int(cat_color_rgb[1] * 0.7 * (1 - ratio) + 23 * ratio)
            b = int(cat_color_rgb[2] * 0.7 * (1 - ratio) + 42 * ratio)
            draw_temp.line([(0, y), (w, y)], fill=(r, g, b))

    draw = ImageDraw.Draw(card)

    scrim_start = 380
    scrim_height = h - scrim_start
    for y in range(scrim_start, h):
        draw.line([(0, y), (w, y)], fill=(11, 15, 25))

    font_badge = load_font(26, bold=True)
    badge_label = f"  {cat_name.upper()}  "
    bbox_b = font_badge.getbbox(badge_label)
    bw = (bbox_b[2] - bbox_b[0]) + 16
    draw.rounded_rectangle([(48, 44), (48 + bw, 98)], radius=14, fill=cat_color_rgb)
    draw.text((56, 56), badge_label, font=font_badge, fill=(255, 255, 255))

    font_counter = load_font(24, bold=True)
    counter_label = f"Tin {slide_index}/{total_slides}"
    draw.rounded_rectangle([(w - 220, 44), (w - 48, 98)], radius=14, fill=(30, 41, 59))
    draw.text((w - 192, 58), counter_label, font=font_counter, fill=(226, 232, 240))

    font_title = load_font(44, bold=True)
    title = item.get("title", "")
    title_lines = wrap_text(title, font_title, max_width_px=w - 96)

    y_content = 660
    for line in title_lines[:4]:
        draw.text((50, y_content + 2), line, font=font_title, fill=(0, 0, 0))
        draw.text((48, y_content), line, font=font_title, fill=(255, 255, 255))
        y_content += 58

    y_content += 20

    summary = item.get("summary", "")
    font_summary = load_font(30, bold=False)
    summary_lines = wrap_text(summary, font_summary, max_width_px=w - 140)

    box_pad_v = 24
    box_h = len(summary_lines) * 44 + box_pad_v * 2
    box_top = y_content
    box_bottom = min(h - 130, box_top + box_h)

    draw.rounded_rectangle([(48, box_top), (w - 48, box_bottom)], radius=18, fill=(30, 41, 59))
    draw.rounded_rectangle([(48, box_top), (58, box_bottom)], radius=6, fill=cat_color_rgb)

    sy = box_top + box_pad_v
    for line in summary_lines[:5]:
        if sy + 38 > box_bottom - 10:
            break
        draw.text((76, sy), line, font=font_summary, fill=(241, 245, 249))
        sy += 44

    footer_y = h - 90
    draw.line([(48, footer_y), (w - 48, footer_y)], fill=(51, 65, 85), width=1)

    source = item.get("source", "Báo chí")
    font_footer = load_font(22, bold=False)
    draw.text((48, footer_y + 16), f"Nguồn: {source} • {date_str}", font=font_footer, fill=(148, 163, 184))

    swipe_prompt = "Vuốt tiếp ➔" if slide_index < total_slides else "Tin cuối cùng ✓"
    draw.text((w - 220, footer_y + 16), swipe_prompt, font=font_footer, fill=(203, 213, 225))

    storage_dir = get_effective_storage_dir()
    os.makedirs(storage_dir, exist_ok=True)
    timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    unique_suffix = uuid.uuid4().hex[:6]
    stored_filename = f"slide_{timestamp_str}_{slide_index}_{unique_suffix}.jpg"
    disk_path = os.path.join(storage_dir, stored_filename)
    card.save(disk_path, "JPEG", quality=90, optimize=True)
    return stored_filename, disk_path


def generate_all_news_slides(briefing_data: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], str, str]:
    """
    Sinh toàn bộ series ảnh slide tin tức (TikTok style):
    - Slide 0: Slide bìa chào buổi sáng.
    - Slide 1..N: Từng slide tin tức với ảnh và tóm tắt AI.
    Trả về: (slides_list, cover_filename, cover_disk_path).
    """
    date_str = briefing_data.get("date_str") or datetime.datetime.now().strftime("%d/%m/%Y")
    greeting = briefing_data.get("greeting") or "Chúc bạn một ngày mới tràn đầy năng lượng!"
    categories = briefing_data.get("categories", [])

    all_items: List[Tuple[Dict[str, Any], Dict[str, Any]]] = []
    for cat in categories:
        for it in cat.get("items", []):
            all_items.append((it, cat))

    total_news = len(all_items)

    cover_filename, cover_disk_path = generate_cover_slide(date_str, greeting, categories)

    slides: List[Dict[str, Any]] = [
        {
            "slide_index": 0,
            "title": briefing_data.get("title") or f"Điểm tin sáng - {date_str}",
            "summary": greeting,
            "category_name": "Tổng quan",
            "category_color": "#EA580C",
            "source": "PoDoc AI",
            "link": "",
            "image_file": cover_filename,
            "image_url": f"/api/news/slides/{cover_filename}"
        }
    ]

    for idx, (it, cat) in enumerate(all_items, start=1):
        try:
            s_filename, s_path = generate_news_slide_card(
                item=it,
                category=cat,
                slide_index=idx,
                total_slides=total_news,
                date_str=date_str
            )
            slides.append({
                "slide_index": idx,
                "title": it.get("title", ""),
                "summary": it.get("summary", ""),
                "category_name": cat.get("name", ""),
                "category_color": cat.get("color", "#2563EB"),
                "source": it.get("source", "Báo chí"),
                "link": it.get("link", ""),
                "image_file": s_filename,
                "image_url": f"/api/news/slides/{s_filename}",
                "original_image_url": it.get("image_url")
            })
        except Exception as e:
            logger.error(f"Lỗi khi vẽ slide tin tức #{idx}: {e}")

    logger.info(f"Đã tạo thành công bộ series gồm {len(slides)} slides tin tức.")
    return slides, cover_filename, cover_disk_path

