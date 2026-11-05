# Triển khai PoDoc trên PoRender

## Docker build context

`render.yaml` ở gốc repository dành cho schema hiện tại của PoRender:

```yaml
rootDir: backend
dockerfilePath: Dockerfile
```

Agent PoRender dùng `rootDir` làm Docker build context và tìm Dockerfile tương
đối trong thư mục đó. Cấu hình này tương đương lệnh chạy từ gốc PoDoc:

```sh
docker build -f backend/Dockerfile backend
```

`COPY requirements.txt .` và `COPY . .` trong Dockerfile đều đọc từ context
`backend/`. Đặt `rootDir: ""` khiến Docker tìm `requirements.txt` ở gốc repo,
dẫn tới lỗi `"/requirements.txt": not found`. Chỉ đổi vị trí Dockerfile không
đổi context của các lệnh COPY.

Schema PoRender hiện chưa nhận `dockerContext`, `plan`, `region` của Render.
Không thêm các trường này vào Blueprint PoRender. Khi triển khai trên Render
thật, dùng cấu hình riêng với `dockerContext: backend` và
`dockerfilePath: backend/Dockerfile`, tương đối với gốc repository.

## Áp dụng cho service đã tồn tại

1. Push commit lên `main`, mở Blueprint đang quản lý `podoc-api` / `podoc-db`,
   chọn tạo kế hoạch mới và kiểm tra diff trước khi Apply/Sync.
2. Nếu service được tạo thủ công, sửa Settings: Root directory = `backend`,
   Dockerfile path = `Dockerfile`, rồi lưu và Deploy latest commit.
   Push code không tự thay đổi Settings của service tạo thủ công.
3. Giữ nguyên `DATABASE_URL`, `ADMIN_PASSWORD`, `JWT_SECRET` đang dùng.
   Không tạo lại database hay thay khóa khi chỉ sửa build context.
4. API nghe trên biến `PORT` do PoRender cấp (hiện là `10000`), chạy
   `alembic upgrade head` trước Uvicorn, và kiểm tra `/health`.

## Lưu file qua các lần deploy

Blueprint gắn persistent disk vào `/data/files`, trùng `FILES_STORAGE_DIR`.
Đặt `uid: 0`, `gid: 0` vì Dockerfile Python hiện chạy bằng root và agent
PoRender bỏ toàn bộ Linux capabilities. Disk và PostgreSQL nằm trên máy chủ
PoRender; cần sao lưu cả hai. Disk sử dụng một container ghi tại một thời điểm,
nên rollout có thể gián đoạn ngắn.

Nếu đã có file trong container cũ chưa gắn disk, phải sao lưu và chuyển chúng
vào volume trước khi thay container. Database và file trên Render không tự
chuyển sang PoRender khi deploy code.

## Kết nối và tự động deploy

- `autoDeploy: true` vẫn cần GitHub connection/webhook hoạt động hoặc Blueprint
  auto sync đã cấu hình. Một checkbox bật không chứng minh webhook đã kết nối.
- URL dạng `*.localhost:18088` chỉ dùng trên máy chủ. Điện thoại cần URL ứng
  dụng qua HTTPS/Tailscale hoặc domain đã được cấu hình tới app proxy;
  URL dashboard `/web/<service-id>` không phải API của PoDoc.
- Trong app Android, nhập URL API tại màn hình đăng nhập hoặc phần cài đặt máy
  chủ. Kiểm tra URL đó trả `/health` trước khi đổi máy chủ trên điện thoại.

## Phạm vi truy cập dữ liệu

`backend/app/api/deps.py` hiện tự chọn tài khoản admin mà không kiểm tra JWT
trên các API tài liệu. Chuyển máy chủ không thay đổi hành vi này. Với mã nguồn
hiện tại, chỉ cấp đường truy cập API cho thiết bị tin cậy qua mạng riêng/VPN;
muốn mở API ra Internet cần khôi phục xác thực và kiểm tra ứng dụng Android
trước. Bản sửa build context không thay đổi cơ chế đăng nhập này.
