import os
import subprocess
from typing import Optional


def generate_image_thumbnail(input_path: str, output_path: str, max_size: int = 1280) -> bool:
    """Tạo thumbnail độ nét cao (HD) cho ảnh bằng Pillow."""
    try:
        from PIL import Image, ImageOps

        with Image.open(input_path) as img:
            # Sửa góc xoay theo EXIF nếu có
            try:
                img = ImageOps.exif_transpose(img)
            except Exception:
                pass

            # Chuyển đổi mode phù hợp lưu JPEG
            if img.mode in ("RGBA", "LA", "P"):
                # Tạo nền trắng cho ảnh có kênh alpha
                bg = Image.new("RGB", img.size, (255, 255, 255))
                if img.mode == "P":
                    img = img.convert("RGBA")
                bg.paste(img, mask=img.split()[-1] if "A" in img.mode else None)
                img = bg
            elif img.mode != "RGB":
                img = img.convert("RGB")

            # Chỉ resize nếu kích thước thực tế lớn hơn max_size
            if img.width > max_size or img.height > max_size:
                img.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
            img.save(output_path, "JPEG", quality=85, optimize=True)
            return True
    except Exception:
        return False


def generate_video_thumbnail(input_path: str, output_path: str) -> bool:
    """Trích xuất 1 khung hình trong video bằng ffmpeg để làm thumbnail sắc nét."""
    # Thử lấy frame ở giây thứ 1, nếu video ngắn thì lấy frame đầu tiên (giây 0)
    for seek_time in ("00:00:01", "00:00:00.1", "00:00:00"):
        try:
            cmd = [
                "ffmpeg",
                "-ss", seek_time,
                "-i", input_path,
                "-vframes", "1",
                "-vf", "scale='min(960,iw)':-2",
                "-q:v", "2",
                "-y",
                output_path,
            ]
            res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=12)
            if res.returncode == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                return True
        except Exception:
            continue
    return False


def ensure_thumbnail(file_path: str, storage_dir: str, mime_type: Optional[str] = None, file_name: Optional[str] = None) -> Optional[str]:
    """
    Đảm bảo luôn có file thumbnail cho tệp media (ảnh/video).
    Trả về tên file thumbnail (ví dụ thumb_abc123.jpg) hoặc None nếu không tạo được.
    """
    if not file_path:
        return None

    thumb_filename = f"thumb_{os.path.splitext(file_path)[0]}.jpg"
    thumb_disk_path = os.path.join(storage_dir, thumb_filename)

    if os.path.exists(thumb_disk_path) and os.path.getsize(thumb_disk_path) > 0:
        return thumb_filename

    raw_disk_path = os.path.join(storage_dir, file_path)
    if not os.path.exists(raw_disk_path):
        return None

    lower_name = (file_name or "").lower()
    lower_path = file_path.lower()
    is_image = (
        (mime_type and mime_type.startswith("image/"))
        or any(lower_name.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
        or any(lower_path.endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp"))
    )
    is_video = (
        (mime_type and mime_type.startswith("video/"))
        or any(lower_name.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
        or any(lower_path.endswith(ext) for ext in (".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp", ".ts", ".flv"))
    )

    if is_image:
        if generate_image_thumbnail(raw_disk_path, thumb_disk_path):
            return thumb_filename
    elif is_video:
        if generate_video_thumbnail(raw_disk_path, thumb_disk_path):
            return thumb_filename

    return None
