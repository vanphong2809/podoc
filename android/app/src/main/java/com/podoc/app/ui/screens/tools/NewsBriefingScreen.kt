package com.podoc.app.ui.screens.tools

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.podoc.app.data.model.NewsSlideItem
import com.podoc.app.ui.components.BriefingSlideSeriesViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsBriefingScreen(
    viewModel: NewsBriefingViewModel,
    onNavigateBack: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearMessages()
        }
    }

    val latest = uiState.latestBriefing

    if (latest != null && !uiState.isGenerating) {
        val slides: List<NewsSlideItem> = remember(latest) {
            if (latest.slides.isNotEmpty()) {
                latest.slides
            } else {
                listOf(
                    NewsSlideItem(
                        slideIndex = 0,
                        title = latest.title,
                        summary = latest.excerpt ?: "",
                        categoryName = latest.categoryName,
                        imageUrl = latest.imageUrl ?: latest.filePath?.let { "/api/articles/${latest.articleId}/file" }
                    )
                )
            }
        }

        BriefingSlideSeriesViewer(
            title = latest.title,
            slides = slides,
            onNavigateBack = onNavigateBack,
            onRefresh = viewModel::generateBriefingNow,
            isRefreshing = uiState.isGenerating,
            textMarkdown = latest.contentMarkdown
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                text = "Điểm tin sáng AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tự động 07:00 AM • Gemini 3.8 Flash",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = viewModel::loadLatestBriefing,
                            enabled = !uiState.isLoading && !uiState.isGenerating
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isGenerating) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = Color(0xFFEA580C)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Đang tổng hợp Điểm tin sáng...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Đang quét VnExpress, Tuổi Trẻ & vẽ series ảnh TikTok chuẩn điện ảnh",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEA580C).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = null,
                                tint = Color(0xFFEA580C),
                                modifier = Modifier.size(42.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Chưa có bản tin sáng nào",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Hệ thống tự động quét lúc 07:00 AM mỗi ngày. Bạn có thể bấm nút bên dưới để tạo ngay bản tin sáng đầu tiên!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = viewModel::generateBriefingNow,
                            enabled = !uiState.isGenerating,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEA580C),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Điểm tin ngay bây giờ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
