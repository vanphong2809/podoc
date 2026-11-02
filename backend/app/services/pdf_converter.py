import os
import shutil
import subprocess
import tempfile
import logging
from typing import Optional

logger = logging.getLogger(__name__)

# Danh sách các định dạng tài liệu hỗ trợ chuyển đổi sang PDF chất lượng cao
CONVERTIBLE_EXTENSIONS = {
    ".doc", ".docx",
    ".xls", ".xlsx",
    ".ppt", ".pptx",
    ".odt", ".ods", ".odp",
    ".rtf", ".txt"
}

CONVERTIBLE_MIME_TYPES = {
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/rtf",
    "text/rtf",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
}


def get_soffice_binary() -> Optional[str]:
    """
    Tìm nhị phân soffice hoặc libreoffice trên hệ thống:
    - Linux / Docker: /usr/bin/soffice hoặc trên $PATH
    - macOS: /Applications/LibreOffice.app/Contents/MacOS/soffice
    - Windows: C:\\Program Files\\LibreOffice\\program\\soffice.exe
    """
    # 1. Tìm trên PATH hệ thống
    for bin_name in ["soffice", "libreoffice"]:
        found = shutil.which(bin_name)
        if found:
            return found

    # 2. Đường dẫn mặc định trên macOS
    mac_paths = [
        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
        os.path.expanduser("~/Applications/LibreOffice.app/Contents/MacOS/soffice"),
    ]
    for p in mac_paths:
        if os.path.exists(p):
            return p

    # 3. Đường dẫn mặc định trên Windows
    win_paths = [
        r"C:\Program Files\LibreOffice\program\soffice.exe",
        r"C:\Program Files (x86)\LibreOffice\program\soffice.exe",
    ]
    for p in win_paths:
        if os.path.exists(p):
            return p

    return None


def is_convertible_to_pdf(filename: str, mime_type: Optional[str] = None) -> bool:
    """Kiểm tra tệp có thuộc định dạng có thể chuyển đổi sang PDF hay không."""
    ext = os.path.splitext(filename)[1].lower()
    if ext in CONVERTIBLE_EXTENSIONS:
        return True
    if mime_type and mime_type.lower() in CONVERTIBLE_MIME_TYPES:
        return True
    return False


def convert_office_to_pdf(input_file_path: str, output_dir: Optional[str] = None) -> str:
    """
    Chuyển đổi tệp Office (Word, Excel, PowerPoint...) sang PDF giữ nguyên 100% định dạng
    bằng LibreOffice headless.
    Trả về đường dẫn tới file PDF được tạo ra.
    """
    if not os.path.exists(input_file_path):
        raise FileNotFoundError(f"Tệp không tồn tại: {input_file_path}")

    soffice_bin = get_soffice_binary()
    if not soffice_bin:
        raise RuntimeError(
            "Không tìm thấy LibreOffice trên hệ thống máy chủ để thực hiện chuyển đổi sang PDF. "
            "Vui lòng cài đặt LibreOffice hoặc kiểm tra cấu hình Docker."
        )

    target_dir = output_dir or os.path.dirname(input_file_path)
    os.makedirs(target_dir, exist_ok=True)

    # Đảm bảo môi trường riêng biệt cho LibreOffice headless chạy an toàn trong server
    env = os.environ.copy()
    # Tạo user profile tạm để tránh xung đột instance
    with tempfile.TemporaryDirectory() as user_data_dir:
        cmd = [
            soffice_bin,
            "--headless",
            "--invisible",
            "--nocrashreport",
            "--nodefault",
            "--nofirststartwizard",
            "--nologo",
            "--norestore",
            f"-env:UserInstallation=file://{user_data_dir}",
            "--convert-to",
            "pdf",
            "--outdir",
            target_dir,
            input_file_path,
        ]

        logger.info(f"Đang chuyển đổi Office sang PDF: {input_file_path} -> {target_dir}")
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120,  # Giới hạn tối đa 2 phút cho file lớn
                env=env,
            )
        except subprocess.TimeoutExpired as e:
            raise RuntimeError("Quá trình chuyển đổi sang PDF bị quá thời gian cho phép (timeout 120s)") from e

        if result.returncode != 0:
            error_msg = result.stderr.strip() or result.stdout.strip() or f"Mã lỗi {result.returncode}"
            logger.error(f"Lỗi khi chạy LibreOffice: {error_msg}")
            raise RuntimeError(f"Chuyển đổi sang PDF thất bại: {error_msg}")

    # File PDF sinh ra có tên cùng tên gốc nhưng đuôi .pdf
    base_name = os.path.splitext(os.path.basename(input_file_path))[0]
    expected_pdf = os.path.join(target_dir, f"{base_name}.pdf")

    if not os.path.exists(expected_pdf):
        # Tìm file pdf mới sinh ra trong target_dir nếu tên bị encode khác
        pdf_candidates = [
            os.path.join(target_dir, f) for f in os.listdir(target_dir)
            if f.endswith(".pdf") and os.path.isfile(os.path.join(target_dir, f))
        ]
        if pdf_candidates:
            expected_pdf = max(pdf_candidates, key=os.path.getmtime)
        else:
            raise RuntimeError("Không tìm thấy tệp PDF được sinh ra sau khi chuyển đổi.")

    logger.info(f"Chuyển đổi thành công: {expected_pdf} (kích thước {os.path.getsize(expected_pdf)} bytes)")
    return expected_pdf

