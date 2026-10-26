# Hướng Dẫn Cài Đặt Android Studio, Chạy Thử và Build File APK PoDoc

Tài liệu này được viết chi tiết cho người không rành code, giúp bạn từng bước mở dự án, chạy thử trên điện thoại thật hoặc máy ảo, và xuất file APK hoàn chỉnh.

---

## 1. Cài Đặt Android Studio

1. Tải bộ cài Android Studio chính thức từ Google:
   👉 **[https://developer.android.com/studio](https://developer.android.com/studio)**
2. Mở file cài đặt vừa tải về và tiến hành cài đặt:
   - Trên **macOS**: Kéo icon Android Studio vào thư mục `Applications`.
   - Trên **Windows**: Nhấn Next theo mặc định.
3. Mở Android Studio lần đầu:
   - Chọn **Standard Setup** khi được hỏi.
   - Android Studio sẽ tự động tải các gói công cụ cần thiết (Android SDK Platform 35, Build Tools, Emulator). Bạn chỉ cần nhấn **Next** và **Finish** khi hoàn thành.

---

## 2. Mở Dự Án PoDoc Trong Android Studio

1. Mở Android Studio, ở màn hình chào mừng chọn **Open** (hoặc `File -> Open`).
2. Tìm đến thư mục dự án và chọn thư mục con:
   ```text
   /Users/vanphong/Desktop/PoDoc/android
   ```
   *(Lưu ý: Mở đúng thư mục `android`, không mở thư mục cha `PoDoc`)*
3. Nhấn **Open / OK**.
4. Android Studio sẽ tự động quét file cấu hình và bắt đầu tải các thư viện qua Gradle (mục `Syncing Gradle...` ở góc dưới).
   *(Lần đầu tiên mở có thể mất khoảng 2-4 phút để tải thư viện Compose và Retrofit. Khi thấy thanh trạng thái báo `BUILD SUCCESSFUL` là xong!)*

---

## 3. Cách Chạy Thử App

Bạn có 2 cách để chạy thử app:

### Cách A: Chạy trên Điện thoại Android thật (Khuyến nghị để thử Share Sheet)
1. Trên điện thoại của bạn, vào **Cài đặt (Settings)** -> **Thông tin điện thoại (About Phone)**.
2. Tìm dòng **Số bản dựng (Build Number)** và nhấn liên tục **7 lần** vào đó cho đến khi máy báo *"Bạn đã là nhà phát triển"*.
3. Quay lại Cài đặt, vào mục **Tuỳ chọn nhà phát triển (Developer Options)** -> Bật **Gỡ lỗi USB (USB Debugging)**.
4. Cắm cáp kết nối điện thoại với máy tính.
5. Trên điện thoại sẽ hiện hộp thoại hỏi *"Cho phép gỡ lỗi USB từ máy tính này?"* -> Tích chọn *"Luôn cho phép"* và nhấn **OK**.
6. Trong Android Studio, nhìn lên thanh công cụ trên cùng: bạn sẽ thấy tên điện thoại của bạn xuất hiện ở ô chọn thiết bị.
7. Nhấn nút **Run hình tam giác màu xanh (▶️)** (hoặc phím tắt `Shift + F10`).
8. App sẽ tự động được cài đặt và mở lên trên điện thoại của bạn!

### Cách B: Chạy trên Máy ảo Android Emulator (Không cần điện thoại)
1. Trong Android Studio, mở menu **Tools** -> **Device Manager** (hoặc nhấn icon điện thoại ở góc phải trên).
2. Nhấn **Create Device** (hoặc dấu `+`).
3. Chọn thiết bị (ví dụ: `Pixel 8 Pro` hoặc `Pixel 7`) -> Nhấn **Next**.
4. Ở màn hình chọn phiên bản hệ điều hành (System Image), chọn bản mới nhất (ví dụ: `VanillaIceCream / Android 15` hoặc `UpsideDownCake / Android 14`) và nhấn **Download** nếu chưa có.
5. Nhấn **Next** -> **Finish**.
6. Nhấn nút **Play (▶️)** để bật máy ảo lên. Sau đó chọn máy ảo này trên thanh công cụ và nhấn nút Run app.

---

## 4. Cách Xuất File APK Độc Lập Để Cài Lên Mọi Điện Thoại

Nếu bạn muốn có một file `.apk` gửi qua Zalo/Telegram để cài lên điện thoại của người thân hoặc máy khác:

1. Trong Android Studio, vào thanh menu trên cùng:
   👉 **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
2. Chờ khoảng 30 giây đến 1 phút.
3. Khi hoàn tất, một thông báo nhỏ sẽ xuất hiện ở góc dưới bên phải màn hình:
   *"APK(s) generated successfully for 1 module."*
4. Nhấn vào chữ **locate** màu xanh trên thông báo đó.
5. Thư mục chứa file `app-debug.apk` sẽ tự động mở ra. Bạn chỉ cần copy file `.apk` này gửi vào điện thoại và nhấn cài đặt!

---

## 5. Trải Nghiệm Các Tính Năng Đã Tích Hợp

1. **Đăng nhập lần đầu**:
   - Nhập địa chỉ server: `https://podoc.yourdomain.com` (hoặc domain VPS của bạn).
   - Nhập tài khoản: `admin`
   - Mật khẩu: mật khẩu bạn đã đặt trong file `.env`.
   - App sẽ tự động lưu token và nhớ đăng nhập vĩnh viễn (30 ngày).
2. **Chia sẻ liên kết từ Chrome / Facebook**:
   - Đang đọc một bài báo trên Chrome -> Nhấn nút Chia sẻ (Share) của trình duyệt.
   - Chọn biểu tượng **PoDoc ("Lưu vào PoDoc")**.
   - Một BottomSheet trong suốt sẽ hiện lên ngay lập tức, hiển thị bản trích xuất sạch sẽ, cho phép chọn danh mục và nhấn **Lưu** mà không hề đẩy bạn ra khỏi trình duyệt.
3. **Nhập tệp tin từ máy (Phase 2)**:
   - Trong app PoDoc, bấm nút **+** -> Chọn **Nhập tệp tin từ máy**.
   - Bạn có thể chọn 1 hoặc nhiều file cùng lúc (`.pdf`, `.docx`, `.txt`, `.md`, `.html`...).
   - PoDoc sẽ bóc tách và lưu vào thư viện cho bạn.
4. **Đọc bài viết (Reader)**:
   - Mở bất kỳ bài viết nào để đọc: font chữ đẹp, hỗ trợ tăng giảm cỡ chữ, đổi font Serif/Sans, nhớ vị trí đọc dở.
   - Nếu là bài viết từ file, có sẵn nút **"Mở tệp tin gốc"** để mở file PDF/DOCX bằng ứng dụng chuyên dụng trên máy.
