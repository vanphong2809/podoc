# Quy định phát triển và xuất bản PoDoc

## Quy tắc Versioning & Build APK (Bắt buộc)
Mỗi lần sửa đổi, nâng cấp mã nguồn của app:
1. **Tăng phiên bản (Version Bump)**:
   - Trong `android/app/build.gradle.kts`: tăng `versionCode` (tăng dần +1) và `versionName` (ví dụ `1.2.2` -> `1.2.3`).
   - Trong `android/app/src/main/res/values/strings.xml`: cập nhật `app_version` thành `Phiên bản: <versionName>`.
   - Trong `backend/app/core/config.py`: cập nhật `VERSION = "<versionName>"`.
2. **Xuất bản APK riêng biệt**:
   - Khi build APK xong, copy ra thư mục gốc dự án theo đúng mẫu:
     - `PoDoc-v<versionName>.apk` (Bản Release)
     - `PoDoc-v<versionName>-debug.apk` (Bản Debug)
   - **Tuyệt đối không ghi đè lên các version cũ** để người dùng có thể tải và cài đặt bất kỳ phiên bản nào họ muốn.
3. **Triển khai Backend**:
   - Đẩy commit lên branch `main` của GitHub để Render tự động build & deploy phiên bản mới.
