package com.podoc.app.service.sync

import android.content.Context
import android.util.Log
import com.podoc.app.di.AppContainer
import com.podoc.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed class MediaSyncResult {
    data class Success(
        val syncedImages: Int,
        val syncedVideos: Int,
        val skippedCount: Int,
        val message: String
    ) : MediaSyncResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : MediaSyncResult()
}

object MediaSyncEngine {
    private const val TAG = "MediaSyncEngine"

    /**
     * Thực thi quá trình đồng bộ Ảnh và Video hôm nay lên PoDoc.
     * TUYỆT ĐỐI CHỈ CHẠY QUA WI-FI (chặn hoàn toàn 3G/4G/5G).
     */
    suspend fun performSync(
        context: Context,
        container: AppContainer,
        onProgress: ((current: Int, total: Int, currentItemName: String) -> Unit)? = null
    ): MediaSyncResult = withContext(Dispatchers.IO) {
        // 1. Kiểm tra nghiêm ngặt kết nối mạng: Chỉ đồng bộ khi bắt Wi-Fi
        if (!NetworkUtils.isWifiConnected(context)) {
            val netStatus = NetworkUtils.getNetworkStatusDescription(context)
            val errMsg = "Chỉ cho phép đồng bộ khi kết nối Wi-Fi. ${netStatus.message}"
            Log.w(TAG, errMsg)
            container.dataStoreManager.recordMediaSyncResult(
                timestamp = System.currentTimeMillis(),
                summary = "Bị chặn do không có Wi-Fi",
                status = "Chờ Wi-Fi"
            )
            return@withContext MediaSyncResult.Error(errMsg)
        }

        // 2. Kiểm tra quyền đọc MediaStore
        if (!LocalMediaScanner.hasMediaPermissions(context)) {
            val errMsg = "Ứng dụng chưa được cấp quyền truy cập Ảnh và Video của thiết bị."
            Log.e(TAG, errMsg)
            container.dataStoreManager.recordMediaSyncResult(
                timestamp = System.currentTimeMillis(),
                summary = "Thiếu quyền truy cập thư viện ảnh",
                status = "Lỗi quyền"
            )
            return@withContext MediaSyncResult.Error(errMsg)
        }

        // 3. Kiểm tra máy chủ PoDoc (không bắt buộc đăng nhập vì PoDoc là ứng dụng máy chủ cá nhân tự chủ)
        val serverUrl = container.dataStoreManager.serverUrlFlow.first()
        if (serverUrl.isBlank()) {
            val errMsg = "Chưa cấu hình địa chỉ máy chủ PoDoc. Vui lòng vào Cài đặt để kiểm tra."
            Log.e(TAG, errMsg)
            return@withContext MediaSyncResult.Error(errMsg)
        }

        // 4. Quét danh sách ảnh và video chụp/lưu trong ngày hôm nay
        val allTodayItems = LocalMediaScanner.queryTodayMedia(context)
        val alreadySyncedKeys = container.dataStoreManager.mediaSyncSyncedKeysFlow.first()

        // Lọc các file chưa được đồng bộ
        val pendingItems = allTodayItems.filter { !alreadySyncedKeys.contains(it.syncKey) }
        val skippedCount = allTodayItems.size - pendingItems.size

        if (pendingItems.isEmpty()) {
            val summaryMsg = if (allTodayItems.isEmpty()) {
                "Hôm nay bạn chưa chụp ảnh hoặc quay video mới nào."
            } else {
                "Tất cả ${allTodayItems.size} ảnh/video hôm nay đều đã được đồng bộ trước đó."
            }
            container.dataStoreManager.recordMediaSyncResult(
                timestamp = System.currentTimeMillis(),
                summary = summaryMsg,
                status = "Đã đồng bộ đầy đủ"
            )
            return@withContext MediaSyncResult.Success(
                syncedImages = 0,
                syncedVideos = 0,
                skippedCount = skippedCount,
                message = summaryMsg
            )
        }

        // 5. Xác định hoặc khởi tạo danh mục 'Hình ảnh' và 'Video' trên máy chủ
        var imageCatId: Int? = null
        var videoCatId: Int? = null

        val hasImages = pendingItems.any { !it.isVideo }
        val hasVideos = pendingItems.any { it.isVideo }

        val categories = container.categoryRepository.getCategories().getOrNull() ?: emptyList()

        if (hasImages) {
            val existingImageCat = categories.firstOrNull { it.name.equals("Hình ảnh", ignoreCase = true) }
            imageCatId = existingImageCat?.id ?: container.categoryRepository.createCategory(
                name = "Hình ảnh",
                icon = "image",
                color = "#EC4899",
                sortOrder = 8
            ).getOrNull()?.id
        }

        if (hasVideos) {
            val existingVideoCat = categories.firstOrNull { it.name.equals("Video", ignoreCase = true) }
            videoCatId = existingVideoCat?.id ?: container.categoryRepository.createCategory(
                name = "Video",
                icon = "videocam",
                color = "#EF4444",
                sortOrder = 9
            ).getOrNull()?.id
        }

        // 6. Thực hiện tải lên từng file theo thứ tự
        var syncedImages = 0
        var syncedVideos = 0
        val newlySyncedKeys = mutableSetOf<String>()
        val total = pendingItems.size

        for ((index, item) in pendingItems.withIndex()) {
            // Kiểm tra lại trạng thái Wi-Fi giữa các file (phòng trường hợp người dùng tắt Wi-Fi hoặc ra khỏi vùng phủ sóng giữa chừng)
            if (!NetworkUtils.isWifiConnected(context)) {
                Log.w(TAG, "Mất kết nối Wi-Fi khi đang đồng bộ tệp ${index + 1}/$total. Tạm dừng đồng bộ.")
                break
            }

            onProgress?.invoke(index + 1, total, item.displayName)

            val targetCatId = if (item.isVideo) videoCatId else imageCatId

            val uploadResult = container.articleRepository.uploadFile(
                uri = item.uri,
                categoryId = targetCatId,
                customTitle = item.displayName.substringBeforeLast('.')
            )

            if (uploadResult.isSuccess) {
                newlySyncedKeys.add(item.syncKey)
                if (item.isVideo) syncedVideos++ else syncedImages++
                Log.d(TAG, "Đã đồng bộ thành công: ${item.displayName} (${item.formattedSize})")
            } else {
                Log.e(TAG, "Đồng bộ thất bại tệp ${item.displayName}: ${uploadResult.exceptionOrNull()?.message}")
            }
        }

        // 7. Cập nhật lịch sử và trạng thái đồng bộ vào DataStore
        val successSummary = "Đã đồng bộ thành công $syncedImages ảnh và $syncedVideos video"
        container.dataStoreManager.recordMediaSyncResult(
            timestamp = System.currentTimeMillis(),
            summary = successSummary,
            status = if (syncedImages + syncedVideos == total) "Thành công" else "Hoàn thành một phần ($syncedImages ảnh, $syncedVideos video)",
            newSyncedKeys = newlySyncedKeys
        )

        return@withContext MediaSyncResult.Success(
            syncedImages = syncedImages,
            syncedVideos = syncedVideos,
            skippedCount = skippedCount,
            message = successSummary
        )
    }
}
