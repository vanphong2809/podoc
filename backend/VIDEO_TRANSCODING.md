# Nén video nền (PoDoc 1.6.33)

Video tải lên được giữ nguyên trong `FILES_STORAGE_DIR`; `file_path`, MIME và
thông tin file luôn mô tả bản gốc. API trả 201 sau khi lưu file và metadata,
không chờ nén. Worker dùng FFmpeg subprocess bất đồng bộ, mỗi volume chỉ có
một encoder nhờ khóa file. Không cần Redis hay dịch vụ ngoài.

## Phát và tải về

- `GET /api/articles/{id}/file?inline=true`: bản MP4 tối ưu khi trạng thái
  `ready`, tự dùng bản gốc nếu đang chờ, nén lỗi hoặc mất bản tối ưu.
- `GET /api/articles/{id}/file`: luôn tải bản gốc, tên và nội dung nguyên vẹn.
- `GET /api/articles/{id}/file?inline=true&original=true`: xem trực tiếp bản gốc.
- `GET /api/articles/{id}/file?variant=original`: tải chính xác bản gốc.
- `GET /api/articles/{id}/file?variant=optimized`: tải chính xác bản nhẹ; trả
  409 kèm `Retry-After` khi đang chờ/xử lý, 404 khi không có bản nhẹ.

`GET /api/articles/{id}/file-info` trả `video_versions`: hai mục Bản gốc/Bản
nhẹ cùng trạng thái, số byte, dung lượng định dạng và URL tải riêng;
`video_saved_percent` là phần trăm dung lượng giảm. `video_queue_position`
cho biết vị trí chờ. App hiển thị các mục trong Thông tin tệp tin, tự cập
nhật mỗi 5 giây khi đang chờ và cho lưu từng bản qua trình chọn tệp Android.
Lựa chọn Bản nhẹ không âm thầm tải bản gốc khi nén chưa hoàn tất.

Cả hai bản hỗ trợ HTTP Range (206/416) để tua video. MP4 tối ưu có `moov`
ở đầu (`+faststart`); chỉ công bố sau khi FFmpeg hoàn tất và ffprobe xác minh.
Trường `video_status` trong API danh sách/chi tiết nhận `pending`, `processing`,
`ready`, `failed`, hoặc `null` với tài liệu khác. App hiện tại đã dùng
`inline=true` khi stream nên tự nhận bản tối ưu cho các lượt phát mới. App
không dùng cache bản gốc để thay thế lượt stream video này.

## Cấu hình mã hóa

- H.264 (`libx264`, preset `fast`, CRF 24), yuv420p, VBR giới hạn
  `maxrate=3500k`, `bufsize=7000k`, GOP 60.
- Video dọc nằm trong 1080×1920; ngang trong 1920×1080, giữ tỷ lệ,
  không phóng lớn video nhỏ, kích thước chẵn và tự xoay theo metadata.
- Tối đa 30fps, giữ fps thấp hơn của nguồn.
- AAC 128kbps, 48kHz, stereo; hỗ trợ video không có âm thanh.
- Nguồn HDR PQ/HLG được tone-map sang SDR BT.709 qua `zscale`/`tonemap`.
- Decoder/encoder dùng số luồng cấu hình, filter một luồng; giảm lookahead
  và reference frames. Blueprint cấp 2 CPU và 2 luồng, vẫn giữ RAM 1 GiB.

Dung lượng phụ thuộc thời lượng và độ phức tạp; CRF/VBR không đảm bảo mọi
file giảm 85–95% hoặc đạt 15–35 MB. Tại 3,5Mbps video + 128kbps audio,
một phút vào khoảng 27MB trước overhead; cảnh đơn giản thường nhỏ hơn.
Backend vẫn nhận toàn bộ file gốc nên không rút ngắn bước upload. Disk phải
chứa cả bản gốc và bản tối ưu; thống kê dung lượng tính cả hai (thumbnail và
file tạm không tính). Với 4K, nên cấp ít nhất 1GB RAM cho container; một luồng
không phải giới hạn RAM cứng, đặc biệt khi giải mã HEVC/HDR.
Blueprint `render.yaml` đặt `x-porender.memoryMiB: 1024` để dành bộ nhớ này;
service tạo thủ công cần áp dụng giới hạn tương ứng trong cấu hình máy chủ.

## Triển khai và khôi phục

Dockerfile đã cài `ffmpeg`/`ffprobe`. Khởi động hiện có tự chạy
`alembic upgrade head`; migration `0003_video_transcoding` đưa video cũ vào
hàng đợi. Các video mới được ưu tiên theo thời điểm tải lên mới nhất, xử lý
trước các video cũ đang chờ backfill. Worker lưu trạng thái trong DB;
khi nhận khóa volume sau restart, đổi `processing` về `pending` rồi nén lại.
Shutdown hủy và thu hồi subprocess, xóa file tạm. Lỗi mã hóa ghi log theo ID,
đánh dấu `failed` và tiếp tục video kế tiếp; không thử lại vô hạn file hỏng.
Để thử lại sau khi khắc phục lỗi, quản trị viên có thể đặt `video_status` của
video cần xử lý về `pending` trong DB.

Các biến môi trường tùy chọn:

| Biến | Mặc định |
| --- | --- |
| `VIDEO_TRANSCODING_ENABLED` | `true` |
| `VIDEO_TRANSCODING_TIMEOUT_SECONDS` | `21600` (6 giờ/video) |
| `VIDEO_TRANSCODING_POLL_SECONDS` | `5` |
| `VIDEO_TRANSCODING_THREADS` | `2` |
| `FFMPEG_BINARY` | `ffmpeg` |
| `FFPROBE_BINARY` | `ffprobe` |

Persistent disk phải trùng `FILES_STORAGE_DIR` và được sao lưu cùng DB.
Khi xóa riêng lẻ hoặc hàng loạt, hệ thống xóa file gốc, thumbnail, bản nén
và file nén tạm. Worker không công bố kết quả nếu video đã bị xóa.

Kiểm thử: `cd backend && python -m pytest tests/test_video_transcoding.py`.
Các bài kiểm thử FFmpeg thực tế cần hai binary trong PATH; nếu không có,
pytest chỉ bỏ qua các ca mã hóa thực tế, vẫn kiểm tra queue và API.

## Log sau khi nén

Sau khi công bố file và commit trạng thái `ready`, server ghi INFO `NÉN XONG`
với tên file gốc, dung lượng trước/sau (MB), phần trăm thay đổi và thời gian
xử lý; ID nội bộ nằm cuối dòng để tra cứu. Phần trăm âm nghĩa là nhỏ hơn,
phần trăm dương nghĩa là bản mã hóa lớn hơn nguồn. Tên Unicode được giữ,
ký tự điều khiển được escape để mỗi sự kiện chỉ chiếm một dòng log.
Lỗi ghi `NÉN LỖI` kèm tên file và stack trace, không ghi hoàn tất giả.

PoRender hiện dùng mọi giá trị environment làm mẫu che bí mật, kể cả số
ngắn. Blueprint không khai báo hai giá trị thường `2`/`30` cho số luồng và
số ngày token; backend dùng mặc định tương đương (2 luồng, 30 ngày), tránh
che nhầm các chữ số trong log. Cơ chế che mật khẩu/JWT vẫn giữ nguyên.
Log đã bị che và lưu trước thay đổi vẫn giữ `[REDACTED]`; kiểm tra log của
instance mới sau triển khai. Log nguồn Docker chưa qua lớp che của PoRender.
