from abc import ABC, abstractmethod
from dataclasses import dataclass
import re
from typing import Optional


@dataclass
class ExtractionResult:
    """Kết quả trích xuất chuẩn hoá chung cho mọi loại nguồn (URL, file TXT, MD, DOCX, HTML, PDF...)."""
    title: str
    content_markdown: str
    excerpt: str
    author: Optional[str] = None
    site_name: Optional[str] = None
    image_url: Optional[str] = None
    language: Optional[str] = None
    reading_minutes: int = 1
    source_type: str = "URL"  # "URL" hoặc "FILE"
    source_url: Optional[str] = None
    file_name: Optional[str] = None
    file_path: Optional[str] = None
    mime_type: Optional[str] = None
    is_fallback_bookmark: bool = False
    error_message: Optional[str] = None


def calculate_reading_minutes(text: str) -> int:
    """Tính thời gian đọc ước tính dựa trên số từ (trung bình 200 từ/phút)."""
    if not text:
        return 1
    # Đếm số từ (hỗ trợ cả tiếng Việt và tiếng Anh)
    words = re.findall(r"\w+", text)
    count = len(words)
    minutes = max(1, round(count / 200))
    return minutes


def generate_excerpt(text: str, max_chars: int = 250) -> str:
    """Tạo tóm tắt ngắn từ nội dung Markdown loại bỏ các ký tự định dạng."""
    if not text:
        return ""
    # Loại bỏ heading, markdown link, image, code block
    cleaned = re.sub(r"```[\s\S]*?```", "", text)
    cleaned = re.sub(r"!\[.*?\]\(.*?\)", "", cleaned)
    cleaned = re.sub(r"\[(.*?)\]\(.*?\)", r"\1", cleaned)
    cleaned = re.sub(r"[#*_>`~|]", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()

    if len(cleaned) <= max_chars:
        return cleaned
    # Cắt tại ranh giới từ
    truncated = cleaned[:max_chars]
    last_space = truncated.rfind(" ")
    if last_space > 0:
        truncated = truncated[:last_space]
    return truncated + "..."


def clean_markdown_text(text: str, title: str = "") -> str:
    """Làm sạch, định dạng và làm đẹp nội dung Markdown sau khi bóc tách."""
    if not text:
        return ""

    lines = [line.rstrip() for line in text.splitlines()]

    # 1. Loại bỏ các dòng metadata rác ở đầu bài viết (ví dụ: "+26", "Chia sẻ", "Share", số đếm...)
    start_idx = 0
    noise_pattern = re.compile(
        r"^(\+\d+|\d+|share|chia sẻ|bình luận|comment|like|thích|theo dõi|follow|đăng ký|subscribe)$",
        re.IGNORECASE,
    )
    while start_idx < len(lines):
        line = lines[start_idx].strip()
        if not line or noise_pattern.match(line):
            start_idx += 1
            continue
        break

    lines = lines[start_idx:]

    # 2. Bỏ tiêu đề H1 ở đầu nếu nó trùng hoặc chứa tiêu đề bài viết để tránh lặp tiêu đề 2 lần
    if lines and title:
        first_line = lines[0].strip()
        norm_first = re.sub(r"^[#\s*_-]+", "", first_line).strip().lower()
        norm_title = title.strip().lower()
        if norm_first and norm_title:
            if norm_first == norm_title or norm_first.startswith(norm_title) or norm_title.startswith(norm_first):
                lines = lines[1:]

    # 3. Chuẩn hoá khoảng cách giữa các heading và đoạn văn
    result = []
    prev_blank = False
    for line in lines:
        is_blank = not line.strip()
        if is_blank:
            if not prev_blank:
                result.append("")
                prev_blank = True
        else:
            # Đảm bảo heading luôn có 1 dòng trống phía trước
            if re.match(r"^#{1,6}\s", line) and result and result[-1] != "":
                result.append("")
            # Chuẩn hoá danh sách có khoảng trắng sau dấu gạch đầu dòng
            line = re.sub(r"^([*+-])(\S)", r"\1 \2", line)
            result.append(line)
            prev_blank = False

    return "\n".join(result).strip()


class BaseExtractor(ABC):
    """Interface trừu tượng cho mọi bộ bóc tách tài liệu."""

    @abstractmethod
    def can_handle(self, source_type: str, identifier: str) -> bool:
        """
        Kiểm tra xem Extractor này có xử lý được nguồn đầu vào không.
        - source_type: "URL" hoặc "FILE"
        - identifier: URL (nếu URL) hoặc mime-type / phần mở rộng (nếu FILE)
        """
        pass

    @abstractmethod
    async def extract(
        self,
        raw_content: bytes,
        context: dict,
    ) -> ExtractionResult:
        """Thực hiện bóc tách nội dung thô thành ExtractionResult chuẩn."""
        pass
