package com.podoc.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.podoc.app.R
import com.podoc.app.data.model.ArticleListItem

@Composable
fun ArticleCard(
    article: ArticleListItem,
    isHeroBanner: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onOpenSource: () -> Unit,
    onShare: () -> Unit,
    onChangeCategory: () -> Unit,
    onEditTitle: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var isImageValid by remember(article.imageUrl) { mutableStateOf(!article.imageUrl.isNullOrBlank()) }

    // Màu sắc badge chuẩn thiết kế hiện đại
    val categoryBadgeBg = Color(0xFFE0EDFF)
    val categoryBadgeText = Color(0xFF1D4ED8)
    val webBadgeBg = Color(0xFFF3E8FF)
    val webBadgeText = Color(0xFF7E22CE)
    val imageBadgeBg = Color(0xFFF3E8FF)
    val imageBadgeText = Color(0xFF7E22CE)
    val videoBadgeBg = Color(0xFFFEE2E2)
    val videoBadgeText = Color(0xFFDC2626)
    val fileBadgeBg = Color(0xFFE2E8F0)
    val fileBadgeText = Color(0xFF334155)

    // Xác định badge loại nội dung
    val (typeBadgeText, typeBadgeBg, typeBadgeColor) = when {
        article.sourceType == "FILE" -> {
            val lowerName = (article.fileName ?: "").lowercase()
            val isVideo = article.mimeType?.startsWith("video/") == true ||
                    lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                    lowerName.endsWith(".mov") || lowerName.endsWith(".webm")
            val isImage = article.mimeType?.startsWith("image/") == true ||
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                    lowerName.endsWith(".gif") || lowerName.endsWith(".svg")
            when {
                isVideo -> Triple("VIDEO", videoBadgeBg, videoBadgeText)
                isImage -> Triple("HÌNH ẢNH", imageBadgeBg, imageBadgeText)
                else -> Triple("TỆP TIN", fileBadgeBg, fileBadgeText)
            }
        }
        article.mimeType == "text/html-web" -> Triple("TRANG WEB", webBadgeBg, webBadgeText)
        article.sourceType == "URL" -> Triple("TRANG WEB", webBadgeBg, webBadgeText)
        else -> Triple(null, Color.Transparent, Color.Transparent)
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Badges + Tên nguồn + Nút 3 chấm
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!article.categoryName.isNullOrBlank()) {
                    Surface(
                        color = categoryBadgeBg,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = article.categoryName,
                            fontSize = 11.5.sp,
                            color = categoryBadgeText,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                if (typeBadgeText != null) {
                    Surface(
                        color = typeBadgeBg,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = typeBadgeText,
                            fontSize = 11.5.sp,
                            color = typeBadgeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                val sourceLabel = article.siteName ?: if (article.sourceType == "FILE") "File máy" else "Web"
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = Color(0xFF6B7280),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Thao tác",
                            tint = Color(0xFF6B7280),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    ArticleActionDropdownMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onEditTitle = onEditTitle,
                        onChangeCategory = onChangeCategory,
                        onOpenSource = onOpenSource,
                        onShare = onShare,
                        onDelete = onDelete
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body: Bên trái là Tiêu đề + Trích dẫn, Bên phải là Thumbnail chuẩn thiết kế
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!article.excerpt.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = article.excerpt,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.5.sp
                            ),
                            color = Color(0xFF6B7280),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Thumbnail bên phải sắc nét (104dp x 82dp, bo góc 14dp, tỉ lệ Crop)
                if (!article.imageUrl.isNullOrBlank() && isImageValid) {
                    Spacer(modifier = Modifier.width(12.dp))
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(article.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = article.title,
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            when (state) {
                                is AsyncImagePainter.State.Error -> {
                                    isImageValid = false
                                }
                                is AsyncImagePainter.State.Success -> {
                                    val size = state.painter.intrinsicSize
                                    // Loại bỏ tracking pixel 1x1 hoặc ảnh rác siêu nhỏ (< 32px)
                                    if (size.width < 32f || size.height < 32f) {
                                        isImageValid = false
                                    }
                                }
                                else -> {}
                            }
                        },
                        modifier = Modifier
                            .size(width = 104.dp, height = 82.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF3F4F6))
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            ArticleCardFooter(
                readingMinutes = article.readingMinutes,
                readProgress = article.readProgress,
                isFavorite = article.isFavorite,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun ArticleCardFooter(
    readingMinutes: Int,
    readProgress: Float,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            tint = Color(0xFF6B7280),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.reading_time_format, readingMinutes),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.5.sp,
                color = Color(0xFF6B7280)
            )
        )

        if (readProgress > 0f) {
            Spacer(modifier = Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { readProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${(readProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = Color(0xFF6B7280)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                tint = if (isFavorite) Color(0xFFEF4444) else Color(0xFF6B7280),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ArticleActionDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEditTitle: () -> Unit,
    onChangeCategory: () -> Unit,
    onOpenSource: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit_title)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                onDismiss()
                onEditTitle()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_change_category)) },
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = {
                onDismiss()
                onChangeCategory()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open_source)) },
            leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenSource()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                onShare()
            }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}
