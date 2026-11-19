package com.podoc.app.service.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Calendar

data class TodayMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val isVideo: Boolean,
    val dateAddedSec: Long,
    val dateTakenMs: Long
) {
    val syncKey: String get() = "${if (isVideo) "video" else "image"}_${id}_$size"

    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val mb = size / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            } else {
                String.format(java.util.Locale.US, "%.1f KB", size / 1024.0)
            }
        }
}

object LocalMediaScanner {

    /**
     * Kiểm tra ứng dụng đã được cấp quyền đọc ảnh và video hay chưa.
     */
    fun hasMediaPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideos = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            hasImages && hasVideos
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Danh sách quyền cần yêu cầu người dùng cấp dựa theo phiên bản Android.
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Quét tất cả ảnh và video từ ứng dụng Ảnh / Máy ảnh (MediaStore)
     * được chụp hoặc thêm vào thiết bị trong ngày hôm nay (từ 00:00:00 đến 23:59:59).
     */
    fun queryTodayMedia(context: Context): List<TodayMediaItem> {
        if (!hasMediaPermissions(context)) {
            return emptyList()
        }

        val items = mutableListOf<TodayMediaItem>()

        // Xác định khoảng thời gian của ngày hôm nay
        val calStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = calStart.timeInMillis
        val startOfDaySec = startOfDayMs / 1000

        val calEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfDayMs = calEnd.timeInMillis
        val endOfDaySec = endOfDayMs / 1000

        // 1. Quét Ảnh (Images)
        try {
            val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN
            )

            val selection = "(${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} <= ?) OR " +
                    "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?)"
            val selectionArgs = arrayOf(
                startOfDaySec.toString(),
                endOfDaySec.toString(),
                startOfDayMs.toString(),
                endOfDayMs.toString()
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(imageUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "IMG_$id.jpg" else "IMG_$id.jpg"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                    val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L

                    if (size <= 0) continue

                    val contentUri = ContentUris.withAppendedId(imageUri, id)
                    items.add(
                        TodayMediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            size = size,
                            isVideo = false,
                            dateAddedSec = dateAdded,
                            dateTakenMs = dateTaken
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Quét Video (Videos)
        try {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATE_TAKEN
            )

            val videoSelection = "(${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.DATE_ADDED} <= ?) OR " +
                    "(${MediaStore.Video.Media.DATE_TAKEN} >= ? AND ${MediaStore.Video.Media.DATE_TAKEN} <= ?)"
            val videoSelectionArgs = arrayOf(
                startOfDaySec.toString(),
                endOfDaySec.toString(),
                startOfDayMs.toString(),
                endOfDayMs.toString()
            )
            val videoSortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(videoUri, videoProjection, videoSelection, videoSelectionArgs, videoSortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val takenCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "VID_$id.mp4" else "VID_$id.mp4"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                    val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L

                    if (size <= 0) continue

                    val contentUri = ContentUris.withAppendedId(videoUri, id)
                    items.add(
                        TodayMediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            size = size,
                            isVideo = true,
                            dateAddedSec = dateAdded,
                            dateTakenMs = dateTaken
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sắp xếp theo thời gian mới nhất lên đầu
        return items.sortedByDescending { if (it.dateTakenMs > 0) it.dateTakenMs else it.dateAddedSec * 1000 }
    }
}
