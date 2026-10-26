#!/bin/bash
set -e

# ==============================================================================
# Script tự động sao lưu Cơ sở dữ liệu PostgreSQL & Files lưu trữ cho PoDoc
# - Nén gzip an toàn
# - Tự động giữ lại 14 bản sao lưu gần nhất (xoay vòng)
# - Tuỳ chọn đồng bộ lên Google Drive / Backblaze B2 qua rclone
# ==============================================================================

BACKUP_DIR="${BACKUP_DIR:-/var/backups/podoc}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/podoc_db_${TIMESTAMP}.sql.gz"
FILES_BACKUP="${BACKUP_DIR}/podoc_files_${TIMESTAMP}.tar.gz"

mkdir -p "${BACKUP_DIR}"

echo "[$(date)] Bắt đầu sao lưu PoDoc..."

# 1. Dump database PostgreSQL
echo "Đang sao lưu Database PostgreSQL..."
docker compose exec -T postgres pg_dump -U "${POSTGRES_USER:-podoc}" -d "${POSTGRES_DB:-podoc}" | gzip > "${BACKUP_FILE}"
echo "Đã lưu DB: ${BACKUP_FILE} ($(du -h "${BACKUP_FILE}" | cut -f1))"

# 2. Sao lưu thư mục files đính kèm (/data/files) nếu có volume
if docker volume inspect podoc_files_data >/dev/null 2>&1; then
    echo "Đang sao lưu thư mục Files..."
    docker run --rm -v podoc_files_data:/data/files -v "${BACKUP_DIR}:/backup" alpine \
        tar -czf "/backup/podoc_files_${TIMESTAMP}.tar.gz" -C /data/files .
    echo "Đã lưu Files: ${FILES_BACKUP} ($(du -h "${FILES_BACKUP}" | cut -f1))"
fi

# 3. Dọn dẹp bản sao lưu cũ (chỉ giữ 14 bản gần nhất)
echo "Đang dọn dẹp các bản sao lưu cũ quá 14 ngày..."
find "${BACKUP_DIR}" -name "podoc_db_*.sql.gz" -type f -mtime +14 -delete
find "${BACKUP_DIR}" -name "podoc_files_*.tar.gz" -type f -mtime +14 -delete

# 4. Tuỳ chọn đẩy lên cloud qua rclone (nếu đã cấu hình rclone remote "podoc-backup")
if command -v rclone &> /dev/null && rclone listremotes | grep -q "podoc-backup:"; then
    echo "Đang tải bản sao lưu lên Cloud qua rclone..."
    rclone copy "${BACKUP_FILE}" "podoc-backup:podoc-backups/"
    if [ -f "${FILES_BACKUP}" ]; then
        rclone copy "${FILES_BACKUP}" "podoc-backup:podoc-backups/"
    fi
    echo "Đã đồng bộ lên Cloud thành công."
fi

echo "[$(date)] Hoàn tất sao lưu thành công!"
