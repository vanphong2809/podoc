#!/bin/bash
set -e

# ==============================================================================
# Script khôi phục dữ liệu PoDoc từ file sao lưu
# Cách dùng:
#   ./restore.sh <file_db.sql.gz> [file_files.tar.gz]
# ==============================================================================

if [ -z "$1" ]; then
    echo "Lỗi: Vui lòng truyền đường dẫn file sao lưu DB!"
    echo "Ví dụ: ./restore.sh /var/backups/podoc/podoc_db_20261026_120000.sql.gz"
    exit 1
fi

DB_FILE="$1"
FILES_FILE="$2"

if [ ! -f "${DB_FILE}" ]; then
    echo "Lỗi: Không tìm thấy file '${DB_FILE}'"
    exit 1
fi

echo "⚠️ CẢNH BÁO: Thao tác này sẽ ghi đè toàn bộ dữ liệu hiện tại trong Database '${POSTGRES_DB:-podoc}'!"
read -p "Bạn có chắc chắn muốn tiếp tục? (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Đã huỷ thao tác."
    exit 1
fi

echo "1. Đang ngắt các kết nối tới Database..."
docker compose exec -T postgres psql -U "${POSTGRES_USER:-podoc}" -d postgres -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${POSTGRES_DB:-podoc}' AND pid <> pg_backend_pid();" || true

echo "2. Đang tạo lại Database trống..."
docker compose exec -T postgres psql -U "${POSTGRES_USER:-podoc}" -d postgres -c "DROP DATABASE IF EXISTS ${POSTGRES_DB:-podoc};"
docker compose exec -T postgres psql -U "${POSTGRES_USER:-podoc}" -d postgres -c "CREATE DATABASE ${POSTGRES_DB:-podoc} OWNER ${POSTGRES_USER:-podoc};"

echo "3. Đang khôi phục dữ liệu từ ${DB_FILE}..."
gunzip -c "${DB_FILE}" | docker compose exec -T postgres psql -U "${POSTGRES_USER:-podoc}" -d "${POSTGRES_DB:-podoc}"

# Khôi phục file đính kèm nếu có truyền file_files.tar.gz
if [ -n "${FILES_FILE}" ] && [ -f "${FILES_FILE}" ]; then
    echo "4. Đang khôi phục thư mục Files đính kèm từ ${FILES_FILE}..."
    docker run --rm -v podoc_files_data:/data/files -v "$(dirname "${FILES_FILE}"):/backup" alpine \
        sh -c "rm -rf /data/files/* && tar -xzf /backup/$(basename "${FILES_FILE}") -C /data/files"
fi

echo "5. Khởi động lại container API để cập nhật kết nối..."
docker compose restart api

echo "✅ Hoàn tất khôi phục dữ liệu thành công!"
