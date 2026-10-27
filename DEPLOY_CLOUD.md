# Hướng Dẫn Triển Khai Backend PoDoc Lên Cloud (Miễn Phí & Dễ Dàng)

Tài liệu này hướng dẫn bạn đưa Backend PoDoc lên các dịch vụ Cloud miễn phí/dễ sử dụng nhất hiện nay (Render hoặc Railway) để điện thoại OPPO có thể truy cập 24/7 từ bất kỳ đâu qua 4G/Wi-Fi mà **không cần bật máy tính hay cắm dây cáp USB**.

---

## Cách 1: Triển Khai Trên Render.com (Khuyến Nghị - Miễn Phí 100%)

Render cung cấp cả Web Service và PostgreSQL Database miễn phí. File cấu hình tự động [render.yaml](file:///Users/vanphong/Desktop/PoDoc/render.yaml) đã được tạo sẵn trong dự án.

### Bước 1: Đưa Code Lên GitHub
Nếu chưa đưa thư mục PoDoc lên GitHub:
1. Đăng nhập [github.com](https://github.com) và tạo một Repository mới (ví dụ: `podoc`).
2. Mở Terminal tại máy tính và chạy:
   ```bash
   cd /Users/vanphong/Desktop/PoDoc
   git init
   git add .
   git commit -m "feat: initial commit with cloud deployment config"
   git branch -M main
   git remote add origin https://github.com/<tai-khoan-cua-ban>/podoc.git
   git push -u origin main
   ```

### Bước 2: Tạo Dịch Vụ Trên Render Trong 1 Click
1. Truy cập [render.com](https://render.com) và đăng ký/đăng nhập bằng tài khoản GitHub.
2. Tại trang Dashboard, nhấn nút **New +** ở góc trên bên phải -> Chọn **Blueprints**.
3. Chọn Repository `podoc` bạn vừa đẩy lên.
4. Render sẽ tự động đọc file `render.yaml` và liệt kê:
   - Cơ sở dữ liệu: `podoc-db` (PostgreSQL miễn phí tại Singapore).
   - Backend API: `podoc-api` (Chạy Dockerfile tự động migrate database).
5. Nhấn **Apply**.
6. Render sẽ tự động khởi tạo database, chạy Alembic migration và cung cấp cho bạn một đường dẫn URL HTTPS công khai (ví dụ: `https://podoc-api-xxxx.onrender.com`).

### Bước 3: Kiểm Tra Server & Lấy Mật Khẩu Admin
- Truy cập vào đường dẫn: `https://podoc-api-xxxx.onrender.com/health`
- Khi thấy hiển thị: `{"status":"ok","database":"connected","version":"1.2.0"}` là backend đã online thành công!
- Tại Dashboard Render -> click vào service `podoc-api` -> tab **Environment**:
  - Xem giá trị `ADMIN_PASSWORD` (Render tự sinh ngẫu nhiên) hoặc bạn có thể đổi thành mật khẩu bạn muốn (ví dụ: `12345678aA@`) rồi ấn **Save Changes**.

### Bước 4: Giữ Server 24/7 Không Bị "Ngủ Đông" (Keep-Alive Bằng UptimeRobot)
Render Free sẽ tự động tạm dừng container nếu không có truy cập trong 15 phút (gây ra độ trễ 30-50s khi mở app lần đầu). Để server luôn thức 24/7:
1. Đăng ký tài khoản miễn phí tại [uptimerobot.com](https://uptimerobot.com).
2. Nhấn **+ Add New Monitor**:
   - **Monitor Type**: `HTTP(s)`
   - **Friendly Name**: `PoDoc Backend`
   - **URL (or IP)**: `https://podoc-api-xxxx.onrender.com/health`
   - **Monitoring Interval**: Chọn **10 minutes** hoặc **14 minutes**.
3. Nhấn **Create Monitor**. UptimeRobot sẽ tự động ping định kỳ, giúp server luôn sẵn sàng phản hồi ngay lập tức cho ứng dụng di động.

---

## Cách 2: Triển Khai Trên Railway.app (Cực Nhanh & Ổn Định)

Railway là nền tảng cloud cực kỳ mượt mà, hỗ trợ deploy Docker và Database trong vài cú click.

1. Đăng ký tài khoản tại [railway.com](https://railway.com) bằng GitHub.
2. Nhấn **New Project** -> Chọn **Provision PostgreSQL**.
3. Sau khi Database được tạo:
   - Nhấn **+ New** (hoặc nhấn phím `Cmd + K` / `Ctrl + K`) -> Chọn **GitHub Repo** -> Chọn repo `podoc`.
4. Nhấp vào dịch vụ `podoc` vừa tạo:
   - Vào tab **Variables** -> Nhấn **Add Variable Reference**:
     - `DATABASE_URL`: Chọn liên kết tới `${{Postgres.DATABASE_URL}}`.
     - Thêm `ADMIN_USERNAME`: `admin`
     - Thêm `ADMIN_PASSWORD`: `<mật-khẩu-bạn-chọn>`
     - Thêm `JWT_SECRET`: `chuoi_bi_mat_ngau_nhien_dai_it_nhat_32_ky_tu`
   - Vào tab **Settings** -> Mục **Networking** -> Nhấn **Generate Domain**.
5. Railway sẽ cấp cho bạn một domain HTTPS (ví dụ: `https://podoc-production-xxxx.up.railway.app`).

---

## Bước Cuối: Kết Nối Ứng Dụng Trên Điện Thoại OPPO

Sau khi đã có đường dẫn URL Cloud ở trên:
1. Mở ứng dụng **PoDoc** trên điện thoại OPPO.
2. Tại màn hình Đăng nhập (Login):
   - **Địa chỉ Server**: Điền URL vừa tạo (ví dụ: `https://podoc-api-xxxx.onrender.com`).
   - **Tài khoản**: `admin`
   - **Mật khẩu**: Mật khẩu Admin đã thiết lập.
3. Nhấn **Đăng nhập**.

🎉 Kể từ bây giờ, điện thoại của bạn có thể lưu bài viết, trích xuất nội dung và đồng bộ từ mọi nơi qua 4G hoặc Wi-Fi độc lập hoàn toàn với máy tính!
