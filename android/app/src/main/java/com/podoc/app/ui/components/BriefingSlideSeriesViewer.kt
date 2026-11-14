package com.podoc.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.podoc.app.data.model.NewsSlideItem
import kotlinx.coroutines.launch

/**
 * Trình xem series slide Điểm tin sáng chuẩn TikTok Style:
 * - Vuốt ngang trái/phải mượt mà giữa các slide.
 * - Ảnh tối đa hóa không gian hiển thị (chiếm trọn màn hình, không bị che bởi text).
 * - Nút "🌐 Đọc bài viết gốc" mở ngay bằng Chrome Custom Tabs trong app.
 * - Hỗ trợ xem toàn màn hình (Fullscreen) và chuyển đổi sang đọc văn bản nếu muốn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingSlideSeriesViewer(
    title: String,
    slides: List<NewsSlideItem>,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteClick: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    textMarkdown: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showFullscreenDialog by remember { mutableStateOf(false) }
    var isTextMode by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val currentSlide = slides.getOrNull(pagerState.currentPage)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isTextMode) "Chế độ đọc văn bản"
                            else "Tin ${pagerState.currentPage + 1}/${slides.size} • Vuốt ngang để lướt tin",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Nút chuyển đổi giữa xem Slide ảnh và đọc Văn bản tóm tắt
                    if (!textMarkdown.isNullOrBlank()) {
                        IconButton(onClick = { isTextMode = !isTextMode }) {
                            Icon(
                                imageVector = if (isTextMode) Icons.Default.ViewCarousel else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = if (isTextMode) "Xem dạng Slide ảnh" else "Đọc dạng văn bản"
                            )
                        }
                    }

                    // Nút làm mới / cập nhật lại
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
                        }
                    }

                    // Nút chia sẻ
                    IconButton(
                        onClick = {
                            val shareText = if (currentSlide != null && currentSlide.slideIndex > 0) {
                                "${currentSlide.title}\n\n${currentSlide.summary}\n\nNguồn: ${currentSlide.source ?: ""} ${currentSlide.link ?: ""}\n\nĐược tổng hợp bởi PoDoc AI."
                            } else {
                                "$title\n\nĐược tổng hợp bởi PoDoc AI."
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, currentSlide?.title ?: title)
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Chia sẻ tin tức"))
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Chia sẻ")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        if (isTextMode && !textMarkdown.isNullOrBlank()) {
            // Chế độ đọc văn bản tóm tắt thuần
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Markdown(content = textMarkdown)
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else {
            // Chế độ lướt Series ảnh slide TikTok (Ảnh chiếm tối đa không gian màn hình)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Khung hiển thị Slide ảnh chiếm toàn bộ phần trên
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val slide = slides[page]
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { showFullscreenDialog = true }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F172A))
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(slide.imageUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = slide.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Overlay phía trên: Badge chuyên mục và bộ đếm trang
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .align(Alignment.TopStart),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Category badge
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.Black.copy(alpha = 0.65f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val catColor = slide.categoryColor?.let {
                                                try {
                                                    Color(android.graphics.Color.parseColor(it))
                                                } catch (_: Exception) {
                                                    Color(0xFFEA580C)
                                                }
                                            } ?: Color(0xFFEA580C)
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(catColor)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (slide.slideIndex == 0) "Tổng quan" else slide.categoryName.ifBlank { "Tin tức" },
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Bộ đếm slide (1 / 8)
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.65f)
                                    ) {
                                        Text(
                                            text = "${page + 1} / ${slides.size}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                // Hint phóng to ở góc dưới
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Black.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ZoomIn,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Chạm để xem toàn màn hình",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Thanh điều hướng: Nút Prev / Next và Dots Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (pagerState.currentPage > 0) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        },
                        enabled = pagerState.currentPage > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Tin trước",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Dots indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(slides.size) { index ->
                            val isSelected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(if (isSelected) 18.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) Color(0xFFEA580C)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                    )
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (pagerState.currentPage < slides.size - 1) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        enabled = pagerState.currentPage < slides.size - 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Tin tiếp theo",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // NÚT MỞ BÀI VIẾT GỐC (Trực tiếp trong PoDoc bằng Chrome Custom Tabs)
                if (!currentSlide?.link.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            openCustomTab(context, currentSlide?.link)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🌐 Đọc bài viết gốc trên ${currentSlide?.source ?: "Báo"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Fullscreen Dialog xem series ảnh dạng toàn màn hình
            if (showFullscreenDialog) {
                Dialog(
                    onDismissRequest = { showFullscreenDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    val fsPagerState = rememberPagerState(
                        initialPage = pagerState.currentPage,
                        pageCount = { slides.size }
                    )

                    LaunchedEffect(fsPagerState.currentPage) {
                        pagerState.scrollToPage(fsPagerState.currentPage)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        HorizontalPager(
                            state = fsPagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val fsSlide = slides[page]
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(fsSlide.imageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = fsSlide.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Top bar trong fullscreen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.65f)
                            ) {
                                Text(
                                    text = "${fsPagerState.currentPage + 1} / ${slides.size}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            IconButton(
                                onClick = { showFullscreenDialog = false },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Đóng",
                                    tint = Color.White
                                )
                            }
                        }

                        // Nút đọc bài viết gốc ở góc dưới fullscreen
                        val fsCurrentSlide = slides.getOrNull(fsPagerState.currentPage)
                        if (!fsCurrentSlide?.link.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    openCustomTab(context, fsCurrentSlide?.link)
                                },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 20.dp)
                                    .height(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Đọc bài viết gốc trên ${fsCurrentSlide?.source ?: "Báo"}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mở bài viết gốc bằng Android Chrome Custom Tabs ngay bên trong ứng dụng PoDoc.
 */
private fun openCustomTab(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        val uri = Uri.parse(url)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, uri)
    } catch (e: Exception) {
        try {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(fallback)
        } catch (_: Exception) {}
    }
}

