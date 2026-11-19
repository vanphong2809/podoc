package com.podoc.app.service.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.podoc.app.PoDocApplication
import com.podoc.app.R
import com.podoc.app.util.NetworkUtils
import kotlinx.coroutines.flow.first

class DailyMediaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailyMediaSyncWorker"
        const val CHANNEL_ID = "podoc_media_sync_channel"
        const val NOTIFICATION_ID = 202611
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Bắt đầu kiểm tra và thực hiện đồng bộ ảnh/video định kỳ 23:59...")

        val app = applicationContext as? PoDocApplication
        if (app == null) {
            Log.e(TAG, "Không thể truy cập Application container.")
            return Result.failure()
        }
        val container = app.container

        // 1. Kiểm tra tính năng tự động đồng bộ có được bật hay không
        val isAutoSyncEnabled = container.dataStoreManager.mediaAutoSyncEnabledFlow.first()
        if (!isAutoSyncEnabled) {
            Log.i(TAG, "Tính năng tự động đồng bộ đang tắt. Bỏ qua.")
            MediaSyncScheduler.scheduleDailySync(applicationContext)
            return Result.success()
        }

        // 2. Kiểm tra điều kiện Wi-Fi: Chặn tuyệt đối nếu dùng 3G/4G/5G
        if (!NetworkUtils.isWifiConnected(applicationContext)) {
            Log.w(TAG, "Bỏ qua đồng bộ 23:59: Thiết bị không kết nối Wi-Fi (đang bật 3G/4G/5G hoặc ngoại tuyến).")
            // Vẫn lên lịch tiếp cho 23:59 ngày mai
            MediaSyncScheduler.scheduleDailySync(applicationContext)
            return Result.success()
        }

        // 3. Hiển thị thông báo bắt đầu
        showNotification("Đang đồng bộ ảnh & video hôm nay qua Wi-Fi...")

        // 4. Tiến hành đồng bộ qua MediaSyncEngine
        val result = MediaSyncEngine.performSync(applicationContext, container)

        // 5. Cập nhật thông báo kết quả
        when (result) {
            is MediaSyncResult.Success -> {
                if (result.syncedImages > 0 || result.syncedVideos > 0) {
                    showNotification("✅ ${result.message} vào thư viện PoDoc.")
                } else {
                    dismissNotification()
                }
            }
            is MediaSyncResult.Error -> {
                showNotification("⚠️ Không thể đồng bộ: ${result.message}")
            }
        }

        // 6. Luôn luôn tái lập lịch cho 23:59 ngày tiếp theo
        MediaSyncScheduler.scheduleDailySync(applicationContext)

        return Result.success()
    }

    private fun showNotification(content: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Đồng bộ ảnh & video",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo tiến trình đồng bộ ảnh và video tự động qua Wi-Fi"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PoDoc - Đồng bộ Media")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun dismissNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
    }
}
