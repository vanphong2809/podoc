package com.podoc.app.service.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object MediaSyncScheduler {
    private const val TAG = "MediaSyncScheduler"
    const val UNIQUE_WORK_NAME = "podoc_daily_media_sync_2359"

    /**
     * Lập lịch cho tác vụ chạy vào đúng 23h59 ngày hôm nay (hoặc 23h59 ngày mai nếu đã qua 23h59).
     * Ràng buộc nghiêm ngặt: Chỉ chạy khi kết nối mạng UNMETERED (Wi-Fi hoặc Ethernet),
     * không chạy trên mạng di động 3G/4G/5G.
     */
    fun scheduleDailySync(context: Context) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val initialDelay = target.timeInMillis - now.timeInMillis
        val hours = initialDelay / (1000 * 60 * 60)
        val minutes = (initialDelay % (1000 * 60 * 60)) / (1000 * 60)
        Log.i(TAG, "Đã lên lịch đồng bộ vào 23:59 tiếp theo (sau $hours giờ $minutes phút). Ràng buộc: Chỉ Wi-Fi.")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // CHỈ CHẠY QUA WI-FI, CHỐNG 3G/4G/5G
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<DailyMediaSyncWorker>()
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    /**
     * Huỷ lịch chạy tự động khi người dùng tắt tính năng trong cài đặt.
     */
    fun cancelDailySync(context: Context) {
        Log.i(TAG, "Huỷ lịch đồng bộ định kỳ 23:59.")
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
