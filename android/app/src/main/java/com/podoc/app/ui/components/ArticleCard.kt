package com.podoc.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
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

// ─────────────────────────────────────────────────────────────────────
// SHIMMER EFFECT — dùng chung cho skeleton loading
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ShimmerBrush(showShimmer: Boolean = true, targetValue: Float = 1000f): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerTranslate"
        )
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation, y = translateAnimation)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// ARTICLE CARD SKELETON — Shimmer placeholder khi đang tải
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ArticleCardSkeleton() {
    val shimmerBrush = ShimmerBrush()
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge placeholder
            Row {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    // Title lines
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Excerpt lines
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(13.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(13.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 82.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(shimmerBrush)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            // Footer row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// BADGE TOKENS
// ─────────────────────────────────────────────────────────────────────

private val categoryBadgeBg   = Color(0xFFE0EDFF)
private val categoryBadgeText = Color(0xFF2563EB)
private val webBadgeBg        = Color(0xFFE0F2FE)
private val webBadgeText      = Color(0xFF0284C7)
private val videoBadgeBg      = Color(0xFFFFE8EC)
private val videoBadgeText    = Color(0xFFE11D48)
private val audioBadgeBg      = Color(0xFFD1FAE5)
private val audioBadgeText    = Color(0xFF059669)
private val fileBadgeBg       = Color(0xFFF1F5F9)
private val fileBadgeText     = Color(0xFF475569)
private val imageBadgeBg      = Color(0xFFEDE9FE)
private val imageBadgeText    = Color(0xFF7C3AED)

// ─────────────────────────────────────────────────────────────────────
// ARTICLE CARD — Entry point
// ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCard(
    article: ArticleListItem,
    isHeroBanner: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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

    // Xác định badge loại nội dung
    val (typeBadgeText, typeBadgeBg, typeBadgeColor) = remember(article) {
        when {
            article.sourceType == "FILE" -> {
                val lowerName = (article.fileName ?: "").lowercase()
                val isVideo = article.mimeType?.startsWith("video/") == true ||
                        lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                        lowerName.endsWith(".mov") || lowerName.endsWith(".webm")
                val isAudio = article.mimeType?.startsWith("audio/") == true ||
                        lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                        lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") ||
                        lowerName.endsWith(".flac") || lowerName.endsWith(".ogg") ||
                        lowerName.endsWith(".wma") || lowerName.endsWith(".opus")
                val isImage = article.mimeType?.startsWith("image/") == true ||
                        lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                        lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                        lowerName.endsWith(".gif") || lowerName.endsWith(".svg")
                when {
                    isVideo -> Triple("VIDEO", videoBadgeBg, videoBadgeText)
                    isAudio -> Triple("ÂM THANH", audioBadgeBg, audioBadgeText)
                    isImage -> Triple("HÌNH ẢNH", imageBadgeBg, imageBadgeText)
                    else    -> Triple("TỆP TIN", fileBadgeBg, fileBadgeText)
                }
            }
            article.mimeType == "text/html-web" || article.sourceType == "URL" ->
                Triple("TRANG WEB", webBadgeBg, webBadgeText)
            else -> Triple(null, Color.Transparent, Color.Transparent)
        }
    }

    val cardBorderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }

    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isSelected) 4.dp else 2.dp,
            pressedElevation = 5.dp
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Viền mờ tinh tế
        Surface(
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, cardBorderColor),
            color = Color.Transparent
        ) {
            if (isHeroBanner && !article.imageUrl.isNullOrBlank() && isImageValid) {
                HeroBannerContent(
                    article = article,
                    typeBadgeText = typeBadgeText,
                    typeBadgeBg = typeBadgeBg,
                    typeBadgeColor = typeBadgeColor,
                    isImageValid = isImageValid,
                    onImageError = { isImageValid = false },
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    showMenu = showMenu,
                    onShowMenu = { showMenu = true },
                    onDismissMenu = { showMenu = false },
                    onToggleFavorite = onToggleFavorite,
                    onEditTitle = onEditTitle,
                    onChangeCategory = onChangeCategory,
                    onOpenSource = onOpenSource,
                    onShare = onShare,
                    onDelete = onDelete
                )
            } else {
                StandardCardContent(
                    article = article,
                    typeBadgeText = typeBadgeText,
                    typeBadgeBg = typeBadgeBg,
                    typeBadgeColor = typeBadgeColor,
                    isImageValid = isImageValid,
                    onImageError = { isImageValid = false },
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    showMenu = showMenu,
                    onShowMenu = { showMenu = true },
                    onDismissMenu = { showMenu = false },
                    onToggleFavorite = onToggleFavorite,
                    onEditTitle = onEditTitle,
                    onChangeCategory = onChangeCategory,
                    onOpenSource = onOpenSource,
                    onShare = onShare,
                    onDelete = onDelete
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// HERO BANNER CARD CONTENT
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun HeroBannerContent(
    article: ArticleListItem,
    typeBadgeText: String?,
    typeBadgeBg: Color,
    typeBadgeColor: Color,
    isImageValid: Boolean,
    onImageError: () -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    showMenu: Boolean,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditTitle: () -> Unit,
    onChangeCategory: () -> Unit,
    onOpenSource: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(article.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) onImageError()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // Gradient scrim ở bottom để badge + tên dễ đọc
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.0f),
                                Color.Black.copy(alpha = 0.0f),
                                Color.Black.copy(alpha = 0.35f)
                            )
                        )
                    )
            )

            // Top overlay: Badges + Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayCatName = article.categoryName ?: "Chưa phân loại"
                if (displayCatName.isNotBlank()) {
                    ArticleBadge(
                        text = displayCatName,
                        bgColor = Color.White.copy(alpha = 0.92f),
                        textColor = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (typeBadgeText != null) {
                    ArticleBadge(
                        text = typeBadgeText,
                        bgColor = typeBadgeBg.copy(alpha = 0.92f),
                        textColor = typeBadgeColor
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.90f)
                            )
                            .border(
                                1.5.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.White,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Đã chọn",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Box {
                        IconButton(
                            onClick = onShowMenu,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.90f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Thao tác",
                                tint = Color(0xFF374151),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        ArticleActionDropdownMenu(
                            expanded = showMenu,
                            onDismiss = onDismissMenu,
                            onEditTitle = onEditTitle,
                            onChangeCategory = onChangeCategory,
                            onOpenSource = onOpenSource,
                            onShare = onShare,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }

        // Content below image
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 24.sp,
                    letterSpacing = (-0.1).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!article.excerpt.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.excerpt,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            ArticleCardFooter(
                createdAt = article.createdAt,
                readingMinutes = article.readingMinutes,
                readProgress = article.readProgress,
                isFavorite = article.isFavorite,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// STANDARD CARD CONTENT
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun StandardCardContent(
    article: ArticleListItem,
    typeBadgeText: String?,
    typeBadgeBg: Color,
    typeBadgeColor: Color,
    isImageValid: Boolean,
    onImageError: () -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    showMenu: Boolean,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditTitle: () -> Unit,
    onChangeCategory: () -> Unit,
    onOpenSource: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        // Header: Badges + source + menu
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val displayCatName = article.categoryName ?: "Chưa phân loại"
            if (displayCatName.isNotBlank()) {
                ArticleBadge(
                    text = displayCatName,
                    bgColor = categoryBadgeBg,
                    textColor = categoryBadgeText
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (typeBadgeText != null) {
                ArticleBadge(
                    text = typeBadgeText,
                    bgColor = typeBadgeBg,
                    textColor = typeBadgeColor
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            val sourceLabel = article.siteName
                ?: if (article.sourceType == "FILE") "File máy" else "Web"
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .border(
                            1.5.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Đã chọn",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Box {
                    IconButton(
                        onClick = onShowMenu,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Thao tác",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    ArticleActionDropdownMenu(
                        expanded = showMenu,
                        onDismiss = onDismissMenu,
                        onEditTitle = onEditTitle,
                        onChangeCategory = onChangeCategory,
                        onOpenSource = onOpenSource,
                        onShare = onShare,
                        onDelete = onDelete
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Body: Text left + Thumbnail right
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                        letterSpacing = (-0.1).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!article.excerpt.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = article.excerpt,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
                            is AsyncImagePainter.State.Error -> onImageError()
                            is AsyncImagePainter.State.Success -> {
                                val size = state.painter.intrinsicSize
                                if (size.width < 32f || size.height < 32f) onImageError()
                            }
                            else -> {}
                        }
                    },
                    modifier = Modifier
                        .size(width = 104.dp, height = 82.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ArticleCardFooter(
            createdAt = article.createdAt,
            readingMinutes = article.readingMinutes,
            readProgress = article.readProgress,
            isFavorite = article.isFavorite,
            onToggleFavorite = onToggleFavorite
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// BADGE ATOM
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ArticleBadge(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(7.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// CARD FOOTER
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun ArticleCardFooter(
    createdAt: String?,
    readingMinutes: Int,
    readProgress: Float,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    val formattedDate = remember(createdAt) { formatArticleDate(createdAt) }

    // Animated heart color
    val heartColor by animateColorAsState(
        targetValue = if (isFavorite) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 250),
        label = "heartColor"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                tint = heartColor,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (!formattedDate.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = stringResource(R.string.reading_time_format, readingMinutes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1
        )

        if (readProgress > 0f) {
            Spacer(modifier = Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { readProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "${(readProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// DROPDOWN MENU
// ─────────────────────────────────────────────────────────────────────

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
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp)
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit_title)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { onDismiss(); onEditTitle() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_change_category)) },
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = { onDismiss(); onChangeCategory() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open_source)) },
            leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null) },
            onClick = { onDismiss(); onOpenSource() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = { onDismiss(); onShare() }
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// DATE FORMATTER — không thay đổi logic
// ─────────────────────────────────────────────────────────────────────

fun formatArticleDate(isoString: String?): String? {
    if (isoString.isNullOrBlank()) return null
    return try {
        val instant = try {
            java.time.OffsetDateTime.parse(isoString).toInstant()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(isoString)
            } catch (_: Exception) {
                val cleaned = isoString.substringBefore(".").substringBefore("Z").substringBefore("+")
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(cleaned)?.toInstant()
            }
        } ?: return null

        val now = java.time.Instant.now()
        val diffSeconds = java.time.Duration.between(instant, now).seconds

        when {
            diffSeconds < 60              -> "Vừa xong"
            diffSeconds < 3600            -> "${(diffSeconds / 60).coerceAtLeast(1)} phút trước"
            diffSeconds < 86400           -> "${(diffSeconds / 3600).coerceAtLeast(1)} giờ trước"
            diffSeconds < 86400 * 2       -> "Hôm qua"
            diffSeconds < 86400 * 7       -> "${diffSeconds / 86400} ngày trước"
            else -> {
                val zdt = instant.atZone(java.time.ZoneId.systemDefault())
                val currentYear = java.time.LocalDate.now().year
                if (zdt.year == currentYear) {
                    zdt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                } else {
                    zdt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                }
            }
        }
    } catch (_: Exception) { null }
}
