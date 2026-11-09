package com.podoc.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podoc.app.data.model.ArticleFileInfo
import com.podoc.app.data.repository.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInfoBottomSheet(
    articleId: Int,
    repository: ArticleRepository,
    onDismiss: () -> Unit,
    fallbackTitle: String? = null,
    fallbackFileName: String? = null,
    fallbackCategory: String? = null,
    fallbackCreatedAt: String? = null,
    localWidth: Int? = null,
    localHeight: Int? = null,
    localDurationMs: Int? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fileInfo by remember { mutableStateOf<ArticleFileInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(articleId) {
        isLoading = true
        errorMessage = null
        val result = repository.getArticleFileInfo(articleId)
        result.fold(
            onSuccess = { info ->
                fileInfo = info
                isLoading = false
            },
            onFailure = { err ->
                errorMessage = err.message ?: "Không thể lấy thông tin chi tiết từ máy chủ"
                isLoading = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thông tin tệp tin",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = (fileInfo?.contentType ?: "Tài nguyên").uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Đóng",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (isLoading && fileInfo == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            } else {
                val info = fileInfo
                val fileName = info?.fileName ?: fallbackFileName ?: info?.title ?: fallbackTitle ?: "Không xác định"
                val album = info?.album ?: info?.categoryName ?: fallbackCategory ?: "Chưa phân loại"
                val createdAtRaw = info?.createdAt ?: fallbackCreatedAt
                val createdAtFormatted = formatIsoDateTime(createdAtRaw)

                // Độ phân giải
                val effectiveWidth = info?.width ?: localWidth?.takeIf { it > 0 }
                val effectiveHeight = info?.height ?: localHeight?.takeIf { it > 0 }
                val resolution = info?.resolution
                    ?: if (effectiveWidth != null && effectiveHeight != null) "$effectiveWidth × $effectiveHeight" else null

                // Dung lượng
                val sizeStr = info?.fileSizeFormatted

                // FPS (Nếu là video)
                val fpsVal = info?.fps

                // Thời lượng / Số giây (Nếu là video hoặc audio)
                val durationSec = info?.durationSeconds
                    ?: (localDurationMs?.takeIf { it > 0 }?.let { it / 1000f })
                val durationFormatted = info?.durationFormatted
                    ?: durationSec?.let { formatDurationSec(it) }

                // Thư mục lưu trữ
                val storagePath = info?.storagePath ?: info?.storageDir ?: "/data/files"

                // 1. Tên file
                FileInfoRow(
                    icon = Icons.Outlined.Description,
                    label = "Tên tệp tin",
                    value = fileName,
                    canCopy = true,
                    onCopy = { copyToClipboard(context, "Tên file", fileName) }
                )

                // 2. Album / Danh mục
                FileInfoRow(
                    icon = Icons.Outlined.Folder,
                    label = "Album / Danh mục",
                    value = album
                )

                // 3. Ngày giờ thêm
                FileInfoRow(
                    icon = Icons.Outlined.CalendarToday,
                    label = "Ngày giờ thêm",
                    value = createdAtFormatted ?: "Không rõ"
                )

                // 4. Dung lượng (nếu có)
                if (!sizeStr.isNullOrBlank()) {
                    FileInfoRow(
                        icon = Icons.Outlined.SdCard,
                        label = "Dung lượng",
                        value = sizeStr
                    )
                }

                // 5. Độ phân giải (nếu là ảnh hoặc video)
                if (!resolution.isNullOrBlank()) {
                    FileInfoRow(
                        icon = Icons.Outlined.AspectRatio,
                        label = "Độ phân giải",
                        value = resolution
                    )
                }

                // 6. FPS (nếu là video)
                if (fpsVal != null && fpsVal > 0f) {
                    FileInfoRow(
                        icon = Icons.Outlined.SlowMotionVideo,
                        label = "Tốc độ khung hình (FPS)",
                        value = "$fpsVal fps"
                    )
                }

                // 7. Số giây / Thời lượng (nếu là video hoặc audio)
                if (durationSec != null && durationSec > 0f) {
                    val secText = "%.1f giây".format(Locale.US, durationSec)
                    val valueText = if (durationFormatted != null) "$secText ($durationFormatted)" else secText
                    FileInfoRow(
                        icon = Icons.Outlined.Timer,
                        label = "Thời lượng (Số giây)",
                        value = valueText
                    )
                }

                // 8. Thư mục lưu trữ
                FileInfoRow(
                    icon = Icons.Outlined.Storage,
                    label = "Thư mục lưu trữ",
                    value = storagePath,
                    canCopy = true,
                    onCopy = { copyToClipboard(context, "Đường dẫn lưu trữ", storagePath) }
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Lưu ý: $errorMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Đóng", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FileInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    canCopy: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (canCopy && onCopy != null) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Sao chép",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

private fun formatIsoDateTime(isoString: String?): String? {
    if (isoString.isNullOrBlank()) return null
    return try {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cleanIso = isoString.substringBefore(".").substringBefore("Z")
        val date = isoFormat.parse(cleanIso) ?: return isoString
        val displayFormat = SimpleDateFormat("HH:mm:ss - dd/MM/yyyy", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        displayFormat.format(date)
    } catch (_: Exception) {
        isoString
    }
}

private fun formatDurationSec(seconds: Float): String {
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(Locale.US, m, s)
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, "Đã sao chép $label", Toast.LENGTH_SHORT).show()
}

