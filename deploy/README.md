# Hướng Dẫn Triển Khai Server PoDoc Lên VPS (A-Z Cho Người Mới)

Tài liệu này được viết riêng cho người không chuyên kỹ thuật, giúp bạn tự đưa server PoDoc lên internet với kết nối HTTPS bảo mật và tự động sao lưu dữ liệu.

---

## 1. Thuê VPS và Trỏ Tên Miền

1. **Thuê VPS Ubuntu 22.04 hoặc 24.04 LTS**:
   - Bạn có thể chọn Hetzner (khoảng 4-5 EUR/tháng gói CPX11/CAX11), DigitalOcean ($4-6/tháng), Vultr hoặc bất kỳ nhà cung cấp nào tại Việt Nam (Viettel, VNPT, BKHOST...).
   - Cấu hình khuyến nghị: 1 vCPU, 2GB RAM, 20GB+ SSD.
2. **Trỏ tên miền (Domain)**:
   - Vào trang quản lý DNS của tên miền (Cloudflare, Namecheap, v.v.).
   - Tạo bản ghi **A record**:
     - **Name**: `podoc` (hoặc `@` nếu dùng domain chính).
     - **Type**: `A`
     - **Value / IPv4 Address**: Điền địa chỉ IP của VPS bạn vừa thuê.
     - **Proxy status** (nếu dùng Cloudflare): Chọn **DNS Only** (tắt đám mây màu cam) để Caddy tự xin chứng chỉ SSL trực tiếp.

---

## 2. Bảo Mật VPS: Bật Firewall và SSH Key

Sau khi nhận IP và mật khẩu root từ nhà cung cấp VPS:

1. **Đăng nhập vào VPS qua Terminal / PowerShell**:
   ```bash
   ssh root@<IP_VPS_CUA_BAN>
   ```

2. **Cập nhật hệ thống**:
   ```bash
   apt update && apt upgrade -y
   ```

3. **Cấu hình Firewall (UFW) - Chỉ mở cổng SSH, HTTP và HTTPS**:
   ```bash
   ufw default deny incoming
   ufw default allow outgoing
   ufw allow 22/tcp    # Cổng SSH
   ufw allow 80/tcp    # Cổng HTTP (Let's Encrypt xác thực)
   ufw allow 443/tcp   # Cổng HTTPS an toàn
   ufw enable
   ```
   *Nhấn `y` khi được hỏi để kích hoạt Firewall.*

---

## 3. Cài Đặt Docker & Docker Compose (Chỉ Cần 1 Dòng)

Chạy lệnh cài đặt Docker chính thức từ Docker Hub:
```bash
curl -fsSL https://get.docker.com | sh
```

Kiểm tra Docker đã sẵn sàng:
```bash
docker --version
docker compose version
```

---

## 4. Tải Mã Nguồn PoDoc và Cấu Hình Môi Trường

1. **Tạo thư mục làm việc**:
   ```bash
   mkdir -p /opt/podoc && cd /opt/podoc
   ```

2. **Tải mã nguồn lên VPS**:
   Bạn có thể dùng `git clone` hoặc dùng lệnh `scp` từ máy tính:
   ```bash
   # Từ máy tính của bạn:
   scp -r /Users/vanphong/Desktop/PoDoc root@<IP_VPS>:/opt/podoc
   ```

3. **Tạo file cấu hình bí mật `.env`**:
   ```bash
   cd /opt/podoc
   cp .env.example .env
   nano .env
   ```
   *Chỉnh sửa các thông số quan trọng sau:*
   - `DOMAIN`: Điền tên miền của bạn (ví dụ: `podoc.yourdomain.com`).
   - `POSTGRES_PASSWORD`: Đặt một mật khẩu database ngẫu nhiên, dài ít nhất 16 ký tự.
   - `JWT_SECRET`: Đặt một chuỗi ngẫu nhiên dài (ít nhất 32 ký tự).
   - `ADMIN_USERNAME`: Tên tài khoản bạn muốn đăng nhập app (ví dụ: `admin`).
   - `ADMIN_PASSWORD`: Mật khẩu đăng nhập app của bạn.
   *Nhấn `Ctrl + O` rồi `Enter` để lưu, `Ctrl + X` để thoát nano.*

---

## 5. Khởi Chạy Server với HTTPS Tự Động

Tại thư mục `/opt/podoc/deploy`:
```bash
cd /opt/podoc/deploy
docker compose up -d --build
```

**Điều gì diễn ra phía sau?**
- PostgreSQL 16 khởi chạy bền vững trong mạng nội bộ, **tuyệt đối không lộ ra ngoài internet**.
- API FastAPI tự động migrate cấu trúc database và seed sẵn tài khoản Admin cùng 7 danh mục.
- Caddy tự động liên hệ với Let's Encrypt để xin chứng chỉ SSL/TLS miễn phí và kích hoạt HTTPS tức thì.

Kiểm tra trạng thái các container:
```bash
docker compose ps
```
*(Cả 3 dịch vụ `podoc_postgres`, `podoc_api`, `podoc_caddy` đều hiển thị trạng thái `Up / healthy`)*

Kiểm tra server hoạt động:
Mở trình duyệt truy cập: `https://podoc.yourdomain.com/health`
👉 Thấy hiển thị: `{"status":"ok","database":"connected","version":"1.0.0"}` là bạn đã thành công 100%!

---

## 6. Xem Log Hoạt Động Khi Cần

- **Xem log toàn bộ hệ thống**:
  ```bash
  docker compose logs -f
  ```
- **Chỉ xem log của Backend API**:
  ```bash
  docker compose logs -f api
  ```
- **Chỉ xem log của Caddy (HTTPS)**:
  ```bash
  docker compose logs -f caddy
  ```
*(Hệ thống đã cấu hình bảo vệ quyền riêng tư: Tuyệt đối không lưu URL, mật khẩu hay nội dung bài viết vào log)*

---

## 7. Cài Đặt Tự Động Sao Lưu Hằng Ngày (Backup)

Script `backup.sh` đã được chuẩn bị sẵn: tự động nén Database PostgreSQL + thư mục Files đính kèm, giữ lại 14 bản gần nhất và tự xoá các bản cũ.

1. **Cấp quyền chạy cho script**:
   ```bash
   chmod +x /opt/podoc/deploy/backup.sh
   chmod +x /opt/podoc/deploy/restore.sh
   ```

2. **Chạy thử sao lưu ngay lập tức**:
   ```bash
   /opt/podoc/deploy/backup.sh
   ```
   *Kiểm tra file sao lưu đã xuất hiện trong `/var/backups/podoc`:*
   ```bash
   ls -lh /var/backups/podoc
   ```

3. **Thiết lập tự động chạy mỗi đêm lúc 02:00 sáng bằng Crontab**:
   ```bash
   crontab -e
   ```
   *Thêm dòng sau vào cuối file:*
   ```text
   0 2 * * * /opt/podoc/deploy/backup.sh >> /var/log/podoc_backup.log 2>&1
   ```

---

## 8. Cách Khôi Phục Dữ Liệu Khi Cần (Restore)

Nếu bạn chuyển sang VPS mới hoặc muốn phục hồi lại trạng thái cũ:
```bash
/opt/podoc/deploy/restore.sh /var/backups/podoc/podoc_db_20261026_XXXXXX.sql.gz /var/backups/podoc/podoc_files_20261026_XXXXXX.tar.gz
```
Script sẽ hỏi xác nhận, tự động dọn sạch DB cũ, nạp lại toàn bộ bài viết, file đính kèm và khởi động lại API.

---

## 9. Cập Nhật Phiên Bản Mới

Khi có code mới, chỉ cần chạy 2 lệnh sau trên VPS:
```bash
cd /opt/podoc/deploy
docker compose up -d --build
```
Hệ thống sẽ tự động cập nhật code mới, chạy migration Alembic mà không làm mất bất kỳ bài viết hay dữ liệu nào của bạn.

---

## 10. Mẹo Giữ Server Render 24/7 Không Bị "Ngủ Đông" (Keep-Alive Với UptimeRobot)

Nếu bạn chọn triển khai Backend trên dịch vụ đám mây miễn phí **Render.com** (thay vì tự thuê VPS riêng), bạn sẽ gặp hiện tượng **Cold Start (Khởi động nguội)**:
- **Cơ chế của Render Free**: Nếu không có request nào trong vòng **15 phút**, Render sẽ tự động đưa container vào trạng thái "ngủ đông" (spin-down). Lần đầu mở lại app sẽ mất từ **30 - 50 giây** để server thức giấc.
- **Giải pháp**: Gói Free của Render cung cấp tới **750 giờ hoạt động/tháng** (trong khi 1 tháng 31 ngày chỉ có tối đa 744 giờ). Bạn hoàn toàn có thể dùng công cụ ping tự động miễn phí [UptimeRobot.com](https://uptimerobot.com) để gọi định kỳ vào server, giúp server **luôn thức 24/7 và phản hồi tức thì**.

### Các bước thiết lập UptimeRobot (Hoàn toàn miễn phí - Chỉ mất 2 phút):

1. **Đăng ký tài khoản**:
   - Truy cập [uptimerobot.com](https://uptimerobot.com) và đăng ký tài khoản miễn phí (Gói Free hỗ trợ tới 50 monitors).

2. **Tạo Monitor giám sát**:
   - Sau khi đăng nhập, tại màn hình Dashboard nhấn nút **+ Add New Monitor** (ở góc trái phía trên).
   - Điền các thông tin sau:
     - **Monitor Type**: Chọn `HTTP(s)`
     - **Friendly Name**: Đặt tên gợi nhớ, ví dụ: `PoDoc Backend Render`
     - **URL (or IP)**: Điền đường dẫn API health check của bạn trên Render:
       ```text
       https://<ten-service-cua-ban>.onrender.com/health
       ```
       *(Ví dụ: `https://podoc-api.onrender.com/health`)*
     - **Monitoring Interval**: Chọn **10 minutes** hoặc **14 minutes** *(Quan trọng: Render tự động ngủ sau 15 phút không có hoạt động, chọn 10-14 phút sẽ đảm bảo server không bao giờ bị đưa vào chế độ ngủ)*.
     - **Monitor Timeout**: Để mặc định 30 giây.
   - Nhấn **Create Monitor**.

3. **Kiểm tra kết quả**:
   - UptimeRobot sẽ ngay lập tức gửi một request HTTP GET tới `/health`.
   - Trạng thái monitor chuyển sang **Up (Màu xanh)** nghĩa là đã hoạt động thành công.
   - Từ nay về sau, server Render của bạn sẽ luôn trong trạng thái sẵn sàng, mở app PoDoc trên điện thoại lúc nào cũng tải dữ liệu ngay tức khắc mà không bị độ trễ khởi động lại.

