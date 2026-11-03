package com.podoc.app.ui.screens.tools.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImagePreprocessor {

    /**
     * Đọc ảnh từ Uri an toàn, tự động giảm tỉ lệ nếu quá lớn để tránh OutOfMemory,
     * và tự động xoay đúng chiều theo EXIF orientation.
     */
    fun loadAndOrientBitmap(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
        return try {
            val resolver = context.contentResolver

            // Bước 1: Đọc kích thước ảnh trước
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // Tính hệ số thu nhỏ inSampleSize
            var sampleSize = 1
            var w = options.outWidth
            var h = options.outHeight
            while (w > maxDimension || h > maxDimension) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            // Bước 2: Đọc bitmap thực tế với sampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Bước 3: Đọc góc xoay EXIF
            val rotationDegrees = getExifRotationDegrees(context, uri)
            if (rotationDegrees != 0) {
                rotateBitmap(rawBitmap, rotationDegrees.toFloat())
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Đọc góc xoay từ EXIF của ảnh
     */
    private fun getExifRotationDegrees(context: Context, uri: Uri): Int {
        return try {
            var inputStream: InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val exif = ExifInterface(inputStream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } else 0
            } finally {
                inputStream?.close()
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Xoay bitmap theo góc độ cho trước
     */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source && !source.isRecycled) {
            source.recycle()
        }
        return rotated
    }

    /**
     * Tiền xử lý ảnh: Chuyển sang ảnh thang xám (Grayscale) và tăng độ tương phản (Contrast)
     * để làm nổi bật nét chữ trên nền hoa văn giấy tờ (CCCD, hóa đơn).
     */
    fun enhanceForOcr(source: Bitmap, contrast: Float = 1.35f, brightness: Float = 5f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. Ma trận Grayscale
        val grayMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }

        // 2. Ma trận Contrast & Brightness
        // formula: c * (color - 128) + 128 + b = c * color + (128*(1-c) + b)
        val translate = 128f * (1f - contrast) + brightness
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // Kết hợp 2 ma trận
        grayMatrix.postConcat(contrastMatrix)

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(grayMatrix)
            isAntiAlias = true
            isFilterBitmap = true
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Cắt ảnh theo tọa độ chuẩn hóa (Normalized 0.0 -> 1.0)
     */
    fun cropNormalized(source: Bitmap, normRect: RectF): Bitmap {
        val left = max(0, (normRect.left * source.width).toInt())
        val top = max(0, (normRect.top * source.height).toInt())
        val right = min(source.width, (normRect.right * source.width).toInt())
        val bottom = min(source.height, (normRect.bottom * source.height).toInt())

        val cropWidth = max(1, right - left)
        val cropHeight = max(1, bottom - top)

        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    /**
     * Chuyển đổi Bitmap sang chuỗi Base64 JPEG để gửi trực tiếp cho AI Vision
     */
    fun bitmapToBase64(bitmap: Bitmap, maxDimension: Int = 1600, quality: Int = 85): String {
        var scaled = bitmap
        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (newW, newH) = if (aspect > 1f) {
                maxDimension to (maxDimension / aspect).toInt()
            } else {
                (maxDimension * aspect).toInt() to maxDimension
            }
            scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        }

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

