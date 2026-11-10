package com.podoc.app.ui.screens.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.podoc.app.ui.components.formatArticleDate
import com.podoc.app.ui.components.FileInfoBottomSheet
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.podoc.app.ui.theme.MediaSystemBars
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.podoc.app.R
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.ui.components.PoDocTopAppBar
import com.podoc.app.service.MediaPlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    articleId: Int,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToArticle: (Int) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()

    var showFontMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var fileInfoArticleId by remember { mutableStateOf<Int?>(null) }
    var fileInfoFallbackItem by remember { mutableStateOf<ArticleListItem?>(null) }

    LaunchedEffect(articleId) {
        viewModel.loadArticle(articleId, context)
    }

    val article = uiState.article
    val isFile = article != null && (article.sourceType == "FILE" || !article.fileName.isNullOrBlank())
    val fileExt = remember(article?.fileName, article?.filePath, article?.mimeType) {
        val fileName = article?.fileName ?: article?.filePath ?: ""
        val rawExt = if (fileName.contains('.')) fileName.substringAfterLast('.', "").lowercase() else ""
        if (rawExt.isNotBlank()) rawExt
        else {
            when {
                article?.mimeType == "image/jpeg" -> "jpg"
                article?.mimeType == "image/png" -> "png"
                article?.mimeType == "image/webp" -> "webp"
                article?.mimeType == "image/svg+xml" -> "svg"
                article?.mimeType == "image/gif" -> "gif"
                article?.mimeType == "application/pdf" -> "pdf"
                article?.mimeType == "text/plain" -> "txt"
                article?.mimeType == "text/markdown" -> "md"
                article?.mimeType == "text/html" -> "html"
                article?.mimeType?.contains("wordprocessingml") == true -> "docx"
                article?.mimeType == "application/msword" -> "doc"
                article?.mimeType?.contains("spreadsheetml") == true -> "xlsx"
                article?.mimeType == "application/vnd.ms-excel" -> "xls"
                article?.mimeType?.contains("presentationml") == true -> "pptx"
                article?.mimeType == "application/vnd.ms-powerpoint" -> "ppt"
                article?.mimeType == "application/rtf" || article?.mimeType == "text/rtf" -> "rtf"
                article?.mimeType == "video/mp4" -> "mp4"
                article?.mimeType == "video/webm" -> "webm"
                article?.mimeType == "video/x-matroska" -> "mkv"
                article?.mimeType == "video/quicktime" -> "mov"
                article?.mimeType == "video/x-msvideo" -> "avi"
                article?.mimeType == "video/3gpp" -> "3gp"
                article?.mimeType?.startsWith("video/") == true -> "mp4"
                article?.mimeType?.startsWith("image/") == true -> "jpg"
                article?.mimeType == "audio/mpeg" -> "mp3"
                article?.mimeType == "audio/mp3" -> "mp3"
                article?.mimeType == "audio/wav" -> "wav"
                article?.mimeType == "audio/x-wav" -> "wav"
                article?.mimeType == "audio/x-m4a" -> "m4a"
                article?.mimeType == "audio/aac" -> "aac"
                article?.mimeType == "audio/flac" -> "flac"
                article?.mimeType == "audio/ogg" -> "ogg"
                article?.mimeType?.startsWith("audio/") == true -> "mp3"
                else -> ""
            }
        }
    }

    val isVideo = fileExt in listOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts") || article?.mimeType?.startsWith("video/") == true
    val isImage = fileExt in listOf("png", "svg", "jpg", "jpeg", "webp", "gif") || article?.mimeType?.startsWith("image/") == true
    val isMediaViewer = isVideo || isImage
    val isAudio = fileExt in listOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "wma", "opus") || article?.mimeType?.startsWith("audio/") == true

    if (isMediaViewer) {
        MediaSystemBars()
    }
    val isOfficeDoc = fileExt in listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp", "csv") ||
            article?.mimeType in listOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/rtf",
                "text/rtf"
            )

    // Dừng phát media nền khi người dùng thoát khỏi màn hình xem chi tiết
    DisposableEffect(articleId) {
        onDispose {
            MediaPlaybackManager.stop(context)
        }
    }

    // Làm sạch nội dung Markdown nếu là bài đọc dạng văn bản/web
    val cleanedMarkdown = remember(article?.contentMarkdown, article?.title, uiState.textContent) {
        val raw = uiState.textContent ?: article?.contentMarkdown ?: ""
        var text = raw.trim()
        val lines = text.lines()
        if (lines.isNotEmpty() && article != null) {
            val firstLine = lines.first().trim()
            val cleanFirst = firstLine.replace(Regex("^[#\\s*_-]+"), "").trim()
            if (cleanFirst.equals(article.title.trim(), ignoreCase = true) ||
                (cleanFirst.length > 5 && article.title.contains(cleanFirst, ignoreCase = true))) {
                text = lines.drop(1).joinToString("\n").trim()
            }
        }
        text.replace(Regex("^(\\+\\d+|\\d+)\\s*\n"), "").trim()
    }

    val markdownBlocks = remember(cleanedMarkdown) {
        splitMarkdownIntoBlocks(cleanedMarkdown)
    }

    val currentProgress by remember {
        derivedStateOf {
            val total = lazyListState.layoutInfo.totalItemsCount
            if (total <= 2) 0f
            else {
                val first = lazyListState.firstVisibleItemIndex
                (first.toFloat() / (total - 2).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    // Lưu tiến độ đọc bài viết
    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress && markdownBlocks.isNotEmpty() && !isFile) {
            val total = lazyListState.layoutInfo.totalItemsCount
            if (total > 2) {
                val progress = (lazyListState.firstVisibleItemIndex.toFloat() / (total - 2).toFloat()).coerceIn(0f, 1f)
                viewModel.updateReadProgress(progress)
            }
        }
    }

    // Nếu bài viết được lưu ở chế độ giữ nguyên định dạng web gốc -> Mở toàn màn hình bằng InAppWebViewer
    val isWebFormat = article != null && (
        article.mimeType == "text/html-web" ||
        article.contentMarkdown.startsWith("<!-- WEB_VIEW_MODE -->")
    ) && !article.sourceUrl.isNullOrBlank()

    if (isWebFormat && article != null) {
        InAppWebViewer(
            url = article.sourceUrl!!,
            title = article.title,
            onNavigateBack = onNavigateBack
        )
        return
    }

    Scaffold(
        topBar = {
            if (!isMediaViewer) {
                CompactReaderTopBar(
                    title = article?.title ?: "Tài liệu",
                    isFile = isFile,
                    fileExt = fileExt,
                    isOfficeDoc = isOfficeDoc,
                    isConvertingToPdf = uiState.isConvertingToPdf,
                    isDownloadingFile = uiState.isDownloadingFile,
                    showFontMenu = showFontMenu,
                    onShowFontMenuChange = { showFontMenu = it },
                    isSerif = uiState.isSerif,
                    onIncreaseFontSize = viewModel::increaseFontSize,
                    onDecreaseFontSize = viewModel::decreaseFontSize,
                    onToggleSerif = viewModel::toggleSerif,
                    onConvertToPdf = {
                        viewModel.convertToPdf { newArticle ->
                            onNavigateToArticle(newArticle.id)
                        }
                    },
                    onOpenOriginalFile = { viewModel.openOriginalFile(context) },
                    onShare = {
                        val art = article ?: return@CompactReaderTopBar
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, art.title)
                            putExtra(Intent.EXTRA_TEXT, "${art.title}\n\n${art.sourceUrl ?: ""}")
                        }
                        context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                    },
                    onDelete = { showDeleteDialog = true },
                    onBackClick = onNavigateBack,
                    currentProgress = currentProgress,
                    onShowFileInfo = { fileInfoArticleId = uiState.article?.id }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isMediaViewer) {
                        Modifier.background(Color.Black).windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                        )
                    } else {
                        Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
                    }
                )
        ) {
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(uiState.errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadArticle(articleId, context) }) {
                            Text("Thử lại")
                        }
                    }
                }
                article != null -> {
                    if (isFile) {
                        // CHẾ ĐỘ MỞ FILE TRỰC TIẾP TRÊN APP
                        InAppFileContent(
                            article = article,
                            fileExt = fileExt,
                            isOfficeDoc = isOfficeDoc,
                            uiState = uiState,
                            viewModel = viewModel,
                            lazyListState = lazyListState,
                            cleanedMarkdown = cleanedMarkdown,
                            markdownBlocks = markdownBlocks,
                            onRetryLoad = { viewModel.loadFileForInApp(context, article) },
                            onOpenExternal = { viewModel.openOriginalFile(context) },
                            onConvertToPdf = {
                                viewModel.convertToPdf { newArticle ->
                                    onNavigateToArticle(newArticle.id)
                                }
                            },
                            onNavigateBack = onNavigateBack,
                            onDeleteClick = { showDeleteDialog = true },
                            onShowFileInfo = { item ->
                                fileInfoArticleId = item?.id ?: uiState.article?.id
                                fileInfoFallbackItem = item
                            }
                        )
                    } else {
                        // CHẾ ĐỘ ĐỌC BÀI VIẾT WEB (MARKDOWN 120 FPS)
                        ArticleReaderContent(
                            article = article,
                            uiState = uiState,
                            lazyListState = lazyListState,
                            markdownBlocks = markdownBlocks
                        )
                    }
                }
            }
        }
    }

    // Dialog xác nhận xoá tài liệu / video / âm thanh trong màn hình đọc
    if (showDeleteDialog) {
        val isVideo = fileExt in listOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts") || article?.mimeType?.startsWith("video/") == true
        val isAudio = fileExt in listOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "wma", "opus") || article?.mimeType?.startsWith("audio/") == true
        val itemTypeName = when {
            isVideo -> "video"
            isAudio -> "tệp âm thanh"
            else -> "bài viết"
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Xoá $itemTypeName",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Bạn có chắc chắn muốn xoá \"${article?.title ?: "mục này"}\" không? Thao tác này sẽ xoá hoàn toàn tệp tin khỏi thư viện và máy chủ.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteArticle(context) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xoá", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Dialog hiển thị tiến trình khi chuyển đổi sang PDF
    if (uiState.isConvertingToPdf) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = {
                Text(
                    text = "Đang chuyển đổi sang PDF...",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Text(
                        text = "Hệ thống đang chuyển đổi tài liệu giữ nguyên 100% định dạng ban đầu...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // Dialog thông báo lỗi khi chuyển đổi thất bại
    if (uiState.convertError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearConvertError() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearConvertError() }) {
                    Text("Đóng")
                }
            },
            title = {
                Text(
                    text = "Lỗi chuyển đổi",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(uiState.convertError ?: "Không thể chuyển đổi sang PDF")
            }
        )
    }

    // BottomSheet: Xem thông tin chi tiết file
    if (fileInfoArticleId != null) {
        val currentVideoSize by MediaPlaybackManager.videoSize.collectAsState()
        val currentDuration by MediaPlaybackManager.duration.collectAsState()
        val currentPlayingId by MediaPlaybackManager.currentArticleId.collectAsState()
        val isThisVideo = currentPlayingId == fileInfoArticleId

        FileInfoBottomSheet(
            articleId = fileInfoArticleId!!,
            repository = viewModel.articleRepository,
            onDismiss = {
                fileInfoArticleId = null
                fileInfoFallbackItem = null
            },
            fallbackTitle = fileInfoFallbackItem?.title ?: uiState.article?.title,
            fallbackFileName = fileInfoFallbackItem?.fileName ?: uiState.article?.fileName,
            fallbackCategory = fileInfoFallbackItem?.categoryName ?: uiState.article?.categoryName,
            fallbackCreatedAt = fileInfoFallbackItem?.createdAt ?: uiState.article?.createdAt,
            localWidth = if (isThisVideo) currentVideoSize.first else null,
            localHeight = if (isThisVideo) currentVideoSize.second else null,
            localDurationMs = if (isThisVideo) currentDuration else null
        )
    }
}

/**
 * Hiển thị tệp tin trực tiếp ngay trên App theo từng định dạng:
 * PDF, Hình ảnh (PNG, SVG, JPG, WEBP), HTML, Word (DOCX, DOC), Văn bản (TXT, MD, MDF)
 */
@Composable
private fun InAppFileContent(
    article: ArticleDetail,
    fileExt: String,
    isOfficeDoc: Boolean = false,
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    cleanedMarkdown: String,
    markdownBlocks: List<String>,
    onRetryLoad: () -> Unit,
    onOpenExternal: () -> Unit,
    onConvertToPdf: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onShowFileInfo: (ArticleListItem?) -> Unit = {}
) {
    val isVideo = fileExt in listOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts") || article.mimeType?.startsWith("video/") == true
    val isAudio = fileExt in listOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "wma", "opus") || article.mimeType?.startsWith("audio/") == true
    val isImage = fileExt in listOf("png", "svg", "jpg", "jpeg", "webp", "gif") || article.mimeType?.startsWith("image/") == true
    val isSvg = fileExt == "svg" || article.mimeType == "image/svg+xml"

    if (uiState.isFileLoading && !isVideo && !isImage) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Đang tải tệp tin...", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    if (uiState.fileError != null && uiState.localFile == null && uiState.htmlContent == null && !isVideo && !isImage) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(uiState.fileError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                OutlinedButton(onClick = onOpenExternal) {
                    Text("Mở bằng app ngoài")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onRetryLoad) {
                    Text("Thử lại")
                }
            }
        }
        return
    }

    when {
        // 1. Trình xem Video dạng TikTok Reels/Shorts vuốt lên/xuống mượt mà
        isVideo -> {
            TikTokVideoFeedViewer(
                initialArticle = article,
                uiState = uiState,
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onDeleteClick = onDeleteClick,
                onShowFileInfo = { item -> onShowFileInfo(item) }
            )
        }

        // 2. Nghe tệp Âm thanh (.mp3, .wav, .m4a...) trực tiếp trên app & phát nền khi tắt màn hình
        isAudio -> {
            val file = uiState.localFile
            if (file != null && file.exists()) {
                InAppAudioViewer(
                    file = file,
                    article = article,
                    onOpenExternal = onOpenExternal,
                    onDeleteClick = onDeleteClick,
                    onShowFileInfo = { onShowFileInfo(null) }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // 2. Xem file PDF bằng PdfRenderer tích hợp sẵn
        fileExt == "pdf" || article.mimeType == "application/pdf" -> {
            val file = uiState.localFile
            if (file != null && file.exists()) {
                InAppPdfViewer(file = file)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // 2. Xem ảnh (PNG, SVG, JPG, JPEG, WEBP) dạng Gallery vuốt trái/phải mượt mà
        isImage -> {
            ImageGalleryViewer(
                initialArticle = article,
                uiState = uiState,
                viewModel = viewModel,
                onOpenExternal = onOpenExternal,
                onNavigateBack = onNavigateBack,
                onDeleteClick = onDeleteClick,
                onShowFileInfo = { item -> onShowFileInfo(item) }
            )
        }

        // 3. Xem HTML hoặc Word (DOCX / DOC) qua WebView kèm tuỳ chọn convert sang PDF
        fileExt in listOf("html", "htm", "docx", "doc") || article.mimeType in listOf("text/html", "application/msword") -> {
            Column(modifier = Modifier.fillMaxSize()) {
                if (fileExt in listOf("docx", "doc")) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Định dạng Word gốc",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Chuyển sang PDF để giữ chuẩn 100% bố cục trang & bảng",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = onConvertToPdf,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Chuyển PDF", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    InAppHtmlViewer(
                        htmlContent = uiState.htmlContent,
                        localFile = uiState.localFile,
                        onOpenExternal = onOpenExternal
                    )
                }
            }
        }

        // 4. Xem TXT, Markdown (MD, MDF)
        fileExt in listOf("txt", "md", "mdf") || article.mimeType in listOf("text/plain", "text/markdown") -> {
            ArticleReaderContent(
                article = article,
                uiState = uiState,
                lazyListState = lazyListState,
                markdownBlocks = markdownBlocks
            )
        }

        // 5. Định dạng khác (Excel: XLS, XLSX, PowerPoint: PPT, PPTX, hoặc tệp nhị phân khác)
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isOfficeDoc) Icons.Default.PictureAsPdf else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(article.fileName ?: article.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Định dạng: ${fileExt.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(24.dp))

                if (isOfficeDoc) {
                    Button(
                        onClick = onConvertToPdf,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Chuyển đổi sang PDF & xem ngay")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedButton(
                    onClick = onOpenExternal,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                ) {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mở bằng ứng dụng tương thích")
                }
            }
        }
    }
}

/**
 * Trình đọc PDF native mượt mà bằng PdfRenderer (Android SDK)
 */
@Composable
private fun InAppPdfViewer(file: File) {
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember(file) { mutableStateOf(0) }

    DisposableEffect(file) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(pfd)
        fileDescriptor = pfd
        renderer = pdfRenderer
        pageCount = pdfRenderer.pageCount

        onDispose {
            try {
                pdfRenderer.close()
                pfd.close()
            } catch (e: Exception) {
                // Ignore close error
            }
        }
    }

    if (pageCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(pageCount, key = { it }) { pageIndex ->
                PdfPageItem(renderer = renderer, pageIndex = pageIndex, pageCount = pageCount)
            }
        }
    }
}

@Composable
private fun PdfPageItem(renderer: PdfRenderer?, pageIndex: Int, pageCount: Int) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(renderer, pageIndex) {
        if (renderer != null && pageIndex < renderer.pageCount) {
            withContext(Dispatchers.IO) {
                try {
                    val page = synchronized(renderer) { renderer.openPage(pageIndex) }
                    // Render với độ phân giải gấp đôi (2x) để chữ sắc nét
                    val width = page.width * 2
                    val height = page.height * 2
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(AndroidColor.WHITE)
                    synchronized(renderer) {
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                    }
                    bitmap = bmp
                } catch (e: Exception) {
                    // Ignore page render error
                }
            }
        }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Trang ${pageIndex + 1}/$pageCount",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
            Surface(
                color = Color.LightGray.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${pageIndex + 1} / $pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

/**
 * Trình xem ảnh dạng Gallery lướt ngang (Horizontal Pager):
 * - Vuốt sang trái (Swipe Left) để xem ảnh kế tiếp, vuốt sang phải (Swipe Right) để xem ảnh trước đó.
 * - Hỗ trợ zoom đa điểm (Pinch-to-zoom), chạm 2 lần (Double tap) để phóng to/thu nhỏ nhanh.
 * - Tự động tải trước (prefetch) các ảnh liền kề để lướt mượt mà không có độ trễ.
 * - Thanh công cụ TopBar nổi thanh mảnh kèm bộ đếm trang (ví dụ: 3 / 15), mở app ngoài, chia sẻ và xoá ảnh.
 * - Chạm vào ảnh để ẩn/hiện thanh công cụ giúp xem ảnh toàn màn hình trọn vẹn.
 */
@Composable
private fun ImageGalleryViewer(
    initialArticle: ArticleDetail,
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onOpenExternal: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowFileInfo: (ArticleListItem) -> Unit
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var showHint by remember { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<ArticleListItem?>(null) }
    var feedInitialized by remember(initialArticle.id) { mutableStateOf(false) }

    // Tự động tắt gợi ý vuốt sau 3 giây
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        showHint = false
    }

    // Danh sách ảnh trong feed
    val feedList = remember(uiState.imageFeed, initialArticle) {
        if (uiState.imageFeed.isNotEmpty()) {
            uiState.imageFeed
        } else {
            listOf(
                ArticleListItem(
                    id = initialArticle.id,
                    title = initialArticle.title,
                    sourceType = initialArticle.sourceType,
                    sourceUrl = initialArticle.sourceUrl,
                    fileName = initialArticle.fileName,
                    filePath = initialArticle.filePath,
                    mimeType = initialArticle.mimeType,
                    categoryId = initialArticle.categoryId,
                    categoryName = initialArticle.categoryName,
                    isFavorite = initialArticle.isFavorite,
                    readProgress = initialArticle.readProgress,
                    createdAt = initialArticle.createdAt,
                    updatedAt = initialArticle.updatedAt
                )
            )
        }
    }

    val initialPage = remember(feedList) {
        val found = feedList.indexOfFirst { it.id == initialArticle.id }
        if (found >= 0) found else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { feedList.size }
    )

    // Đồng bộ trang ban đầu khi feed hoàn tất tải về
    LaunchedEffect(uiState.imageFeed) {
        if (!feedInitialized && uiState.imageFeed.isNotEmpty()) {
            val index = feedList.indexOfFirst { it.id == initialArticle.id }.coerceAtLeast(0)
            pagerState.scrollToPage(index)
            feedInitialized = true
        }
    }

    val isFeedReady = uiState.imageFeed.isEmpty() || feedInitialized

    // Tự động tải trước các ảnh lân cận khi trang dừng lại
    LaunchedEffect(pagerState, feedList, isFeedReady) {
        if (!isFeedReady) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .collectLatest { settled ->
                if (settled in feedList.indices) {
                    val neighbours = listOf(settled - 1, settled + 1, settled + 2, settled - 2)
                    neighbours.filter { it in feedList.indices }.forEach { idx ->
                        viewModel.preloadImage(idx, feedList, context)
                    }
                }
            }
    }

    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    BackHandler(enabled = true) {
        onNavigateBack()
    }

    val currentItem = feedList.getOrElse(pagerState.currentPage.coerceIn(0, (feedList.size - 1).coerceAtLeast(0))) {
        ArticleListItem(
            id = initialArticle.id,
            title = initialArticle.title,
            categoryId = initialArticle.categoryId,
            categoryName = initialArticle.categoryName
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            flingBehavior = flingBehavior,
            userScrollEnabled = !isCurrentPageZoomed,
            key = { index -> if (index in feedList.indices) feedList[index].id else index },
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val item = feedList[pageIndex]
            val isCurrentPage = (pageIndex == pagerState.currentPage)
            ImageGalleryPage(
                item = item,
                isCurrentPage = isCurrentPage,
                uiState = uiState,
                viewModel = viewModel,
                onToggleControls = { showControls = !showControls },
                onZoomChanged = { zoomed ->
                    if (isCurrentPage) {
                        isCurrentPageZoomed = zoomed
                    }
                }
            )
        }

        // 1. Thanh công cụ nổi phía trên (TopBar Overlay)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xD9000000),
                                Color(0x66000000),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentItem.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (feedList.size > 1) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${feedList.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Nút Thông tin ảnh
                    IconButton(
                        onClick = { onShowFileInfo(currentItem) },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Thông tin ảnh",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Nút Mở app ngoài
                    IconButton(
                        onClick = {
                            viewModel.openImageItemFile(context, currentItem)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Mở bằng ứng dụng khác",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Nút Chia sẻ
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, currentItem.title)
                                putExtra(Intent.EXTRA_TEXT, "${currentItem.title}\n\n${currentItem.sourceUrl ?: ""}")
                            }
                            context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Chia sẻ",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Nút Xoá
                    IconButton(
                        onClick = {
                            itemToDelete = currentItem
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Xoá",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // 2. Thông tin phía dưới & gợi ý vuốt
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x66000000),
                                Color(0xD9000000)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0xFF7C3AED),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "HÌNH ẢNH",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        val imageCategoryName = currentItem.categoryName?.takeIf { it.isNotBlank() } ?: "Chưa phân loại"
                        Surface(
                            color = Color.White.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = imageCategoryName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (showHint && feedList.size > 1) {
                        Text(
                            text = "Vuốt trái / phải để xem ảnh khác",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    // Dialog xác nhận xoá ảnh
    if (itemToDelete != null) {
        val deletingItem = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xoá hình ảnh", fontWeight = FontWeight.Bold) },
            text = { Text("Bạn có chắc chắn muốn xoá hình ảnh \"${deletingItem.title}\" không?") },
            confirmButton = {
                Button(
                    onClick = {
                        val wasLast = feedList.size <= 1
                        viewModel.deleteFeedItem(deletingItem) {
                            itemToDelete = null
                            if (wasLast) {
                                onNavigateBack()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xoá")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Huỷ")
                }
            }
        )
    }
}

/**
 * Trang ảnh đơn lẻ trong Horizontal Pager:
 * - Hỗ trợ Double-tap để zoom nhanh 2.5x / trả về 1x
 * - Hỗ trợ kéo di chuyển ảnh khi đang phóng to (Zoomed pan)
 * - Tự động tải ảnh từ cache hoặc nạp nền
 */
@Composable
private fun ImageGalleryPage(
    item: ArticleListItem,
    isCurrentPage: Boolean,
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onToggleControls: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Tự động đưa về tỷ lệ 1x khi người dùng chuyển sang trang khác
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    val isSvg = remember(item.fileName, item.mimeType) {
        item.mimeType == "image/svg+xml" || (item.fileName ?: "").endsWith(".svg", ignoreCase = true)
    }

    val cachedFile = remember(item.id, uiState.imageFilesCache) {
        uiState.imageFilesCache[item.id]?.takeIf { it.exists() && it.length() > 0 }
            ?: run {
                val rawExt = (item.fileName ?: item.filePath ?: "").let { path ->
                    if (path.contains('.')) path.substringAfterLast('.', "").lowercase() else ""
                }
                val isSvgExt = item.mimeType == "image/svg+xml" || rawExt == "svg"
                val ext = if (rawExt.isNotBlank()) rawExt
                else when {
                    item.mimeType == "image/png" -> "png"
                    isSvgExt -> "svg"
                    item.mimeType == "image/webp" -> "webp"
                    item.mimeType == "image/gif" -> "gif"
                    else -> "jpg"
                }
                val safeExt = ".$ext"
                val fileName = item.fileName?.let { if (it.contains('.')) it else "$it$safeExt" }
                    ?: "image_${item.id}$safeExt"
                val diskFile = File(context.cacheDir, "podoc_doc_${item.id}_$fileName")
                if (diskFile.exists() && diskFile.length() > 0) diskFile else null
            }
    }
    var loadedFile by remember(item.id, cachedFile) { mutableStateOf(cachedFile) }
    var isDownloading by remember(item.id, cachedFile) { mutableStateOf(cachedFile == null || !cachedFile.exists()) }

    LaunchedEffect(item.id) {
        if (loadedFile == null || !loadedFile!!.exists()) {
            isDownloading = true
            try {
                val f = viewModel.awaitImageFile(context, item)
                loadedFile = f
            } catch (_: Exception) {
            } finally {
                isDownloading = false
            }
        } else {
            isDownloading = false
        }
    }

    val imageLoader = remember(isSvg) {
        ImageLoader.Builder(context)
            .components {
                if (isSvg) add(SvgDecoder.Factory())
            }
            .build()
    }

    val isZoomed = scale > 1.05f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isZoomed) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .then(
                if (isZoomed) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1.05f) {
                                offset += pan
                                onZoomChanged(true)
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomChanged(false)
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 1. Hiển thị thumbnail dự phòng (nếu có) trong lúc file gốc đang được tải về
        if (!item.imageUrl.isNullOrBlank() && (loadedFile == null || !loadedFile!!.exists())) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.imageUrl)
                    .crossfade(true)
                    .size(coil.size.Size.ORIGINAL)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Khi file gốc đã tải xong hoàn tất 100%, hiển thị ảnh gốc sắc nét
        if (loadedFile != null && loadedFile!!.exists()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(loadedFile)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                onError = {
                    // Nếu giải mã thất bại do file lỗi trên máy, xoá file để tải lại sạch sẽ
                    loadedFile?.delete()
                    loadedFile = null
                    isDownloading = true
                    viewModel.getImageFile(context, item) { f ->
                        loadedFile = f
                        isDownloading = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }

        // 3. Vòng quay loading nhỏ gọn nếu chưa có cả thumbnail lẫn file gốc
        if (isDownloading && loadedFile == null && item.imageUrl.isNullOrBlank()) {
            CircularProgressIndicator(
                strokeWidth = 2.5.dp,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Thanh TopBar nhỏ gọn, thanh mảnh (chiều cao 48dp) cho chế độ đọc văn bản và tài liệu
 */
@Composable
private fun CompactReaderTopBar(
    title: String,
    isFile: Boolean,
    fileExt: String,
    isOfficeDoc: Boolean,
    isConvertingToPdf: Boolean,
    isDownloadingFile: Boolean,
    showFontMenu: Boolean,
    onShowFontMenuChange: (Boolean) -> Unit,
    isSerif: Boolean,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onToggleSerif: () -> Unit,
    onConvertToPdf: () -> Unit,
    onOpenOriginalFile: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onBackClick: () -> Unit,
    currentProgress: Float,
    onShowFileInfo: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Quay lại",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Cỡ chữ (cho markdown/text)
            if (!isFile || fileExt in listOf("txt", "md", "mdf")) {
                Box {
                    IconButton(
                        onClick = { onShowFontMenuChange(true) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Cỡ chữ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showFontMenu,
                        onDismissRequest = { onShowFontMenuChange(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tăng cỡ chữ (+)") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = onIncreaseFontSize
                        )
                        DropdownMenuItem(
                            text = { Text("Giảm cỡ chữ (-)") },
                            leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) },
                            onClick = onDecreaseFontSize
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isSerif) stringResource(R.string.font_family_sans)
                                    else stringResource(R.string.font_family_serif)
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.FontDownload, contentDescription = null) },
                            onClick = onToggleSerif
                        )
                    }
                }
            }

            // Chuyển Office sang PDF
            if (isOfficeDoc) {
                IconButton(
                    onClick = onConvertToPdf,
                    enabled = !isConvertingToPdf,
                    modifier = Modifier.size(38.dp)
                ) {
                    if (isConvertingToPdf) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Chuyển sang PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Mở app ngoài
            if (isFile) {
                IconButton(
                    onClick = onOpenOriginalFile,
                    enabled = !isDownloadingFile,
                    modifier = Modifier.size(38.dp)
                ) {
                    if (isDownloadingFile) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Mở bằng ứng dụng khác",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Thông tin file
            if (onShowFileInfo != null) {
                IconButton(
                    onClick = onShowFileInfo,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Thông tin tệp tin",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Chia sẻ
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Chia sẻ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Xoá
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Xoá",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Thanh tiến độ đọc
        if (!isFile) {
            LinearProgressIndicator(
                progress = { currentProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

/**
 * Trình xem HTML / Word bằng WebView nhúng
 */
@Composable
private fun InAppHtmlViewer(
    htmlContent: String?,
    localFile: File?,
    onOpenExternal: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                }
            },
            update = { webView ->
                if (!htmlContent.isNullOrBlank()) {
                    webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                } else if (localFile != null && localFile.exists()) {
                    webView.loadUrl("file://${localFile.absolutePath}")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Trình đọc nội dung Markdown / Text thông thường
 */
@Composable
private fun ArticleReaderContent(
    article: ArticleDetail,
    uiState: ReaderUiState,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    markdownBlocks: List<String>
) {
    val activeFontFamily = if (uiState.isSerif) FontFamily.Serif else FontFamily.Default
    val baseSize = uiState.fontSizeSp

    val mdTypography = markdownTypography(
        h1 = TextStyle(fontSize = (baseSize + 8).sp, fontWeight = FontWeight.Bold, fontFamily = activeFontFamily, lineHeight = ((baseSize + 8) * 1.35).sp),
        h2 = TextStyle(fontSize = (baseSize + 5).sp, fontWeight = FontWeight.Bold, fontFamily = activeFontFamily, lineHeight = ((baseSize + 5) * 1.35).sp),
        h3 = TextStyle(fontSize = (baseSize + 3).sp, fontWeight = FontWeight.SemiBold, fontFamily = activeFontFamily, lineHeight = ((baseSize + 3) * 1.35).sp),
        h4 = TextStyle(fontSize = (baseSize + 1).sp, fontWeight = FontWeight.SemiBold, fontFamily = activeFontFamily),
        h5 = TextStyle(fontSize = baseSize.sp, fontWeight = FontWeight.Medium, fontFamily = activeFontFamily),
        h6 = TextStyle(fontSize = (baseSize - 1).sp, fontWeight = FontWeight.Medium, fontFamily = activeFontFamily),
        paragraph = TextStyle(fontSize = baseSize.sp, fontFamily = activeFontFamily, lineHeight = (baseSize * 1.75).sp),
        quote = TextStyle(fontSize = baseSize.sp, fontStyle = FontStyle.Italic, fontFamily = activeFontFamily, lineHeight = (baseSize * 1.65).sp),
        code = TextStyle(fontSize = (baseSize - 2).sp, fontFamily = FontFamily.Monospace, lineHeight = (baseSize * 1.45).sp),
        bullet = TextStyle(fontSize = baseSize.sp, fontFamily = activeFontFamily, lineHeight = (baseSize * 1.65).sp),
        ordered = TextStyle(fontSize = baseSize.sp, fontFamily = activeFontFamily, lineHeight = (baseSize * 1.65).sp),
        list = TextStyle(fontSize = baseSize.sp, fontFamily = activeFontFamily, lineHeight = (baseSize * 1.65).sp)
    )

    val colorScheme = MaterialTheme.colorScheme
    val mdColors = markdownColor(
        text = colorScheme.onBackground,
        codeText = colorScheme.onTertiaryContainer,
        linkText = colorScheme.primary,
        codeBackground = colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        inlineCodeBackground = colorScheme.surfaceVariant,
        dividerColor = colorScheme.outlineVariant
    )

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // 1. Header
        item(key = "article_header", contentType = "header") {
            ArticleReaderHeader(
                article = article,
                activeFontFamily = activeFontFamily,
                baseSize = baseSize
            )
        }

        // 2. Nội dung
        itemsIndexed(
            items = markdownBlocks,
            key = { index, _ -> "block_$index" },
            contentType = { _, _ -> "markdown_block" }
        ) { _, blockText ->
            MarkdownBlockItem(
                content = blockText,
                typography = mdTypography,
                colors = mdColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }

        // 3. Footer
        item(key = "article_footer", contentType = "footer") {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ArticleReaderHeader(
    article: ArticleDetail,
    activeFontFamily: FontFamily,
    baseSize: Int
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!article.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = article.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = (baseSize + 8).sp,
                fontFamily = activeFontFamily,
                lineHeight = ((baseSize + 8) * 1.3).sp
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!article.author.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = article.author,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = article.siteName ?: if (article.sourceType == "FILE") "Tệp tin" else "Web",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            val publishDate = formatArticleDate(article.createdAt)
            if (!publishDate.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = publishDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.reading_time_format, article.readingMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MarkdownBlockItem(
    content: String,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    modifier: Modifier = Modifier
) {
    Markdown(
        content = content,
        typography = typography,
        colors = colors,
        modifier = modifier
    )
}

private fun splitMarkdownIntoBlocks(markdown: String): List<String> {
    if (markdown.isBlank()) return emptyList()
    val blocks = ArrayList<String>()
    val currentBlock = StringBuilder()
    var inCodeBlock = false

    val lines = markdown.lines()
    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            currentBlock.append(line).append("\n")
            if (!inCodeBlock) {
                val text = currentBlock.toString().trim()
                if (text.isNotEmpty()) blocks.add(text)
                currentBlock.clear()
            }
            continue
        }

        if (inCodeBlock) {
            currentBlock.append(line).append("\n")
            continue
        }

        if (trimmed.startsWith("#")) {
            if (currentBlock.isNotEmpty()) {
                val text = currentBlock.toString().trim()
                if (text.isNotEmpty()) blocks.add(text)
                currentBlock.clear()
            }
            blocks.add(trimmed)
            continue
        }

        if (trimmed.isEmpty()) {
            if (currentBlock.isNotEmpty()) {
                val text = currentBlock.toString().trim()
                if (text.isNotEmpty()) blocks.add(text)
                currentBlock.clear()
            }
        } else {
            currentBlock.append(line).append("\n")
        }
    }

    if (currentBlock.isNotEmpty()) {
        val text = currentBlock.toString().trim()
        if (text.isNotEmpty()) blocks.add(text)
    }

    return blocks
}

/**
 * Trình xem Video dạng Feed lướt đứng (Vertical Pager) chuẩn TikTok / Shorts / Reels:
 * - Vuốt lên (Swipe Up) để xem video kế tiếp, vuốt xuống (Swipe Down) để xem video trước đó.
 * - Tự động phát video ngay khi chuyển trang, tự động preload video tiếp theo vào cache.
 * - Chạm 1 chạm vào màn hình để Tạm dừng / Tiếp tục (Pause / Play) với icon Play mờ lớn ở giữa.
 * - Cột chức năng bên phải: Nút Lặp lại (Loop), Mở app ngoài, Chia sẻ, Xoá video.
 * - Thông tin tiêu đề, tag VIDEO, danh mục và thanh tua tiến độ sát đáy màn hình.
 * - Tiếp tục phát âm thanh liên tục trong nền ngay cả khi tắt màn hình điện thoại.
 */
@Composable
internal fun TikTokVideoFeedViewer(
    initialArticle: ArticleDetail,
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowFileInfo: (ArticleListItem) -> Unit
) {
    val context = LocalContext.current
    val isPlaying by MediaPlaybackManager.isPlaying.collectAsState()
    val isRepeatMode by MediaPlaybackManager.isRepeatMode.collectAsState()
    val playbackGeneration by MediaPlaybackManager.playbackGeneration.collectAsState()
    val currentPlayingArticleId by MediaPlaybackManager.currentArticleId.collectAsState()
    val presentedSurfaces by MediaPlaybackManager.presentedSurfaces.collectAsState()
    val videoRecovery by MediaPlaybackManager.videoRecovery.collectAsState()
    val failedArticleId by MediaPlaybackManager.failedArticleId.collectAsState()

    var itemToDelete by remember { mutableStateOf<ArticleListItem?>(null) }
    var showHint by remember { mutableStateOf(true) }
    var isManuallyPaused by remember { mutableStateOf(false) }
    var feedInitialized by remember(initialArticle.id) { mutableStateOf(false) }
    var videoLoadError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var autoPausedArticleId by remember { mutableStateOf<Int?>(null) }
    var initialWindowPrepared by remember(initialArticle.id) { mutableStateOf(false) }

    // Tự động tắt gợi ý vuốt sau 3 giây
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        showHint = false
    }

    // Khi video bắt đầu phát, tự động tắt trạng thái tạm dừng thủ công
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            isManuallyPaused = false
        }
    }

    LaunchedEffect(currentPlayingArticleId) {
        if (currentPlayingArticleId != autoPausedArticleId) autoPausedArticleId = null
    }

    // Danh sách các video trong feed
    val feedList = remember(uiState.videoFeed, initialArticle) {
        if (uiState.videoFeed.isNotEmpty()) {
            uiState.videoFeed
        } else {
            listOf(
                ArticleListItem(
                    id = initialArticle.id,
                    title = initialArticle.title,
                    sourceType = initialArticle.sourceType,
                    sourceUrl = initialArticle.sourceUrl,
                    fileName = initialArticle.fileName,
                    filePath = initialArticle.filePath,
                    mimeType = initialArticle.mimeType,
                    categoryId = initialArticle.categoryId,
                    categoryName = initialArticle.categoryName,
                    isFavorite = initialArticle.isFavorite,
                    readProgress = initialArticle.readProgress,
                    createdAt = initialArticle.createdAt,
                    updatedAt = initialArticle.updatedAt
                )
            )
        }
    }

    val initialPage = remember(feedList) {
        val found = feedList.indexOfFirst { it.id == initialArticle.id }
        if (found >= 0) found else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { feedList.size }
    )

    // The full feed arrives after the initial one-item page. Keep the opened video selected.
    LaunchedEffect(uiState.videoFeed) {
        if (!feedInitialized && uiState.videoFeed.isNotEmpty()) {
            val index = feedList.indexOfFirst { it.id == initialArticle.id }.coerceAtLeast(0)
            pagerState.scrollToPage(index)
            feedInitialized = true
        }
    }
    val isFeedReady = !uiState.isVideoFeedLoading && (uiState.videoFeed.isEmpty() || feedInitialized)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    LaunchedEffect(videoRecovery) {
        val recovery = videoRecovery ?: return@LaunchedEffect
        kotlinx.coroutines.delay(200L * recovery.attempt)
        if (MediaPlaybackManager.selectedVideoArticleId == recovery.articleId &&
            MediaPlaybackManager.selectionSerial == recovery.selection) retryCount++
    }

    // Keep the outgoing frame moving with the drag. Switch playback only when a page settles.
    // collectLatest cancels a pending selection as soon as another drag starts.
    LaunchedEffect(pagerState, feedList, isFeedReady, retryCount) {
        if (!isFeedReady) return@LaunchedEffect
        snapshotFlow { if (pagerState.isScrollInProgress) null else pagerState.settledPage }
            .collectLatest { pageIndex ->
                if (pageIndex == null || pageIndex !in feedList.indices) return@collectLatest
                val item = feedList[pageIndex]
                videoLoadError = null
                MediaPlaybackManager.selectVideo(item.id)
                if (MediaPlaybackManager.currentArticleId.value == item.id &&
                    MediaPlaybackManager.failedArticleId.value != item.id &&
                    MediaPlaybackManager.videoRecovery.value?.articleId != item.id) {
                    try {
                        viewModel.prepareVideoWindow(context, feedList, pageIndex, !initialWindowPrepared, retryCount)
                        initialWindowPrepared = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { videoLoadError = "Không thể chuẩn bị video. Chạm để thử lại."; return@collectLatest }
                    if (autoPausedArticleId == item.id) {
                        MediaPlaybackManager.resume(context)
                        autoPausedArticleId = null
                    }
                    return@collectLatest
                }
                isManuallyPaused = false
                if (MediaPlaybackManager.isPlaying.value) {
                    autoPausedArticleId = MediaPlaybackManager.currentArticleId.value
                }
                MediaPlaybackManager.pauseDirect()
                try {
                    val mediaSource = viewModel.prepareVideoWindow(context, feedList, pageIndex, !initialWindowPrepared, retryCount)
                    initialWindowPrepared = true
                    MediaPlaybackManager.startPlay(context, mediaSource, item.title, item.id, true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    videoLoadError = "Không thể tải video: ${e.message ?: "Lỗi kết nối"}. Chạm để thử lại."
                }
            }
    }

    // Prioritize the predicted target during the drag; the window is replenished on every settled page.
    LaunchedEffect(pagerState, feedList, isFeedReady, currentPlayingArticleId) {
        if (!isFeedReady) return@LaunchedEffect
        snapshotFlow { Triple(pagerState.settledPage, pagerState.targetPage, pagerState.isScrollInProgress) }
            .collectLatest { (settled, target, scrolling) ->
                val predicted = if (scrolling && target != settled) target else settled
                if (predicted in feedList.indices) viewModel.predictVideo(predicted, scrolling)
            }
    }

    BackHandler(enabled = true) {
        onNavigateBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = initialWindowPrepared,
            flingBehavior = flingBehavior,
            key = { index -> if (index in feedList.indices) feedList[index].id else index },
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val item = feedList[pageIndex]
            val isCurrentPage = (pageIndex == pagerState.currentPage)
            val isThisVideoActive = (currentPlayingArticleId == item.id)
            val hasVideoFrame = initialWindowPrepared && presentedSurfaces[item.id]?.let {
                it === MediaPlaybackManager.surfaceFor(item.id)
            } == true
            var showLoading by remember(item.id) { mutableStateOf(false) }
            LaunchedEffect(isCurrentPage, hasVideoFrame, initialWindowPrepared) {
                if (isCurrentPage && !hasVideoFrame) {
                    // Keep the initial preparation indicator visible until its
                    // first frame; only defer a new indicator between pages.
                    if (initialWindowPrepared && !showLoading) kotlinx.coroutines.delay(250)
                    showLoading = true
                } else showLoading = false
            }
            val pageError = if (failedArticleId == item.id) "Không thể phát video. Chạm để thử lại." else videoLoadError
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        // Nếu đang phát mà click thì người dùng chủ động muốn pause -> hiện nút tam giác
                        // Nếu đang dừng mà click thì người dùng muốn play tiếp -> ẩn nút tam giác
                        if (pageError != null && isCurrentPage) {
                            retryCount++
                        } else if (isCurrentPage && isThisVideoActive) {
                            isManuallyPaused = isPlaying
                            MediaPlaybackManager.togglePlayPause(context)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // Keep the output viewport stable while the next decoder
                    // discovers its dimensions. TextureView fits the content.
                    FeedVideoSurface(
                        articleId = item.id,
                        isActive = isThisVideoActive,
                        playbackGeneration = playbackGeneration,
                        modifier = Modifier.fillMaxSize()
                    )

                    // No thumbnail: cover stale/unpresented buffers with black.
                    if (!hasVideoFrame) {
                        Box(Modifier.fillMaxSize().background(Color.Black).testTag("video_shutter_${item.id}"))
                    }
                }

                if (isCurrentPage && pageError != null) {
                    Text(
                        text = pageError,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .clickable { retryCount++ }
                            .padding(16.dp)
                    )
                // Buffering notifications may lag behind visible playback.
                // Loading ends when this surface presents its first frame.
                } else if (isCurrentPage && !hasVideoFrame && showLoading) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp).testTag("video_loading_${item.id}"),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    }
                }

                if (isCurrentPage && !initialWindowPrepared && pageError == null) {
                    val total = uiState.videoPreparationTotal
                    Text(
                        text = if (total > 0) "Đang chuẩn bị video (${uiState.videoPreparationReady}/$total)" else "Đang chuẩn bị video…",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(top = 96.dp).testTag("video_preparation")
                    )
                }

                // 2. Icon Play to tròn ở trung tâm màn hình CHỈ KHI người dùng chủ động chạm tạm dừng (chuẩn TikTok)
                // Tuyệt đối không hiển thị khi vừa vuốt sang video mới
                AnimatedVisibility(
                    visible = isCurrentPage && isThisVideoActive && isManuallyPaused && !isPlaying,
                    enter = fadeIn(spring()),
                    exit = fadeOut(spring()),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Phát",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(50.dp)
                        )
                    }
                }

                // 3. Gradient mờ ở cạnh đáy để thông tin bài viết và action bar luôn rõ nét
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                )

                // 4. Right Action Bar chuẩn TikTok (Cột dọc các nút tương tác bên phải)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Lặp lại (Repeat Mode)
                    TikTokActionButton(
                        icon = if (isRepeatMode) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        label = if (isRepeatMode) "1 lặp" else "Lặp lại",
                        isActive = isRepeatMode,
                        activeTint = Color(0xFF38BDF8),
                        onClick = { MediaPlaybackManager.toggleRepeatMode() }
                    )

                    // Mở app ngoài
                    TikTokActionButton(
                        icon = Icons.Default.OpenInNew,
                        label = "Mở app",
                        onClick = { viewModel.openItemFile(context, item) }
                    )

                    // Chi tiết file
                    TikTokActionButton(
                        icon = Icons.Outlined.Info,
                        label = "Chi tiết",
                        onClick = { onShowFileInfo(item) }
                    )

                    // Chia sẻ
                    TikTokActionButton(
                        icon = Icons.Default.Share,
                        label = "Chia sẻ",
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, item.title)
                                putExtra(Intent.EXTRA_TEXT, "${item.title}\n\n${item.sourceUrl ?: ""}")
                            }
                            context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                        }
                    )

                    // Xoá video
                    TikTokActionButton(
                        icon = Icons.Default.Delete,
                        label = "Xoá",
                        tint = Color(0xFFEF4444),
                        onClick = { itemToDelete = item }
                    )
                }

                // 5. Thông tin video góc dưới bên trái (Tag VIDEO, Tên danh mục, Tiêu đề)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 88.dp, bottom = 26.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0xFFE11D48),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "VIDEO",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        val videoCategoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Video"
                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = videoCategoryName,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 6. Thanh tua tiến độ đáy (Được tách thành composable riêng biệt để tránh recompose toàn trang)
                TikTokBottomProgressBar(
                    isCurrentPage = isCurrentPage && isThisVideoActive,
                    onSeek = { newPos -> MediaPlaybackManager.seekTo(context, newPos) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // 7. Gợi ý vuốt lên/xuống xuất hiện 3s đầu tiên
        AnimatedVisibility(
            visible = showHint && feedList.size > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Vuốt lên / xuống để xem video khác",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
                    )
                }
            }
        }

        // 8. Top Bar Overlay (Nút Back & Bộ đếm video hiện tại)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (feedList.size > 1) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${feedList.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(38.dp))
            }
        }
    }

    // Dialog xác nhận xoá video trong feed
    itemToDelete?.let { deletingItem ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Xoá video", fontWeight = FontWeight.Bold) },
            text = { Text("Bạn có chắc chắn muốn xoá video \"${deletingItem.title}\" không?") },
            confirmButton = {
                Button(
                    onClick = {
                        val currentDel = deletingItem
                        itemToDelete = null
                        viewModel.deleteFeedItem(currentDel) {
                            if (feedList.size <= 1) {
                                onNavigateBack()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xoá", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * Thanh tua tiến độ đáy (Tách riêng để giảm thiểu recomposition khi thời gian cập nhật liên tục)
 */
@Composable
private fun TikTokBottomProgressBar(
    isCurrentPage: Boolean,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isCurrentPage) return
    val currentPosition by MediaPlaybackManager.currentPosition.collectAsState()
    val duration by MediaPlaybackManager.duration.collectAsState()
    if (duration <= 0) return

    val maxDur = duration.toFloat().coerceAtLeast(1f)
    Slider(
        value = currentPosition.toFloat().coerceIn(0f, maxDur),
        onValueChange = { newPos -> onSeek(newPos.toInt()) },
        valueRange = 0f..maxDur,
        modifier = modifier
            .fillMaxWidth()
            .offset(y = 12.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
        )
    )
}

/**
 * Nút hành động nổi dọc cạnh phải kiểu TikTok
 */
@Composable
private fun TikTokActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    isActive: Boolean = false,
    activeTint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (isActive) activeTint.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.45f),
                    CircleShape
                )
                .then(
                    if (isActive) Modifier.border(1.5.dp, activeTint, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeTint else tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeTint else Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/**
 * Trình phát Âm thanh (.mp3, .wav, .m4a, ...) với giao diện máy hát đĩa hiện đại
 * Duy trì phát nền Foreground Service liên tục khi tắt màn hình điện thoại.
 */
@Composable
private fun InAppAudioViewer(
    file: File,
    article: ArticleDetail,
    onOpenExternal: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowFileInfo: () -> Unit
) {
    val context = LocalContext.current
    val isPlaying by MediaPlaybackManager.isPlaying.collectAsState()
    val isRepeatMode by MediaPlaybackManager.isRepeatMode.collectAsState()
    val isCompleted by MediaPlaybackManager.isCompleted.collectAsState()
    val currentPosition by MediaPlaybackManager.currentPosition.collectAsState()
    val duration by MediaPlaybackManager.duration.collectAsState()

    // Bắt đầu phát khi tải xong tệp âm thanh nếu chưa phát
    LaunchedEffect(file.absolutePath) {
        if (MediaPlaybackManager.currentFilePath.value != file.absolutePath) {
            MediaPlaybackManager.startPlay(
                context = context,
                filePath = file.absolutePath,
                title = article.title,
                articleId = article.id,
                isVideo = false
            )
        }
    }

    // Hiệu ứng đĩa nhạc xoay nhịp nhàng khi đang phát nhạc
    val infiniteTransition = rememberInfiniteTransition(label = "disc_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "disc_angle"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 1. Đĩa nhạc minh hoạ / Vinyl visualizer
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1E293B),
                            Color(0xFF0F172A),
                            Color(0xFF020617)
                        )
                    )
                )
                .graphicsLayer(rotationZ = if (isPlaying && !isCompleted) rotation else 0f)
        ) {
            // Vòng tròn vân đĩa vinyl
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            )
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            // Nhãn tròn tâm đĩa
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        // 2. Thông tin bài nhạc / tệp âm thanh
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Âm thanh • ${file.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 3. Thanh Seekbar & Thời lượng
        Column(modifier = Modifier.fillMaxWidth()) {
            val maxDur = duration.toFloat().coerceAtLeast(1f)
            Slider(
                value = currentPosition.toFloat().coerceIn(0f, maxDur),
                onValueChange = { newPos ->
                    MediaPlaybackManager.seekTo(context, newPos.toInt())
                },
                valueRange = 0f..maxDur,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDurationMs(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDurationMs(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 4. Các nút điều khiển phát nhạc (Lặp lại, Lùi 10s, Phát/Dừng/Phát lại, Tiến 10s, Tua về đầu)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nút Lặp lại (Repeat / Loop bài hát như Zing MP3)
            IconButton(
                onClick = { MediaPlaybackManager.toggleRepeatMode() },
                modifier = Modifier
                    .size(46.dp)
                    .then(
                        if (isRepeatMode) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    imageVector = if (isRepeatMode) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = if (isRepeatMode) "Tắt lặp lại" else "Bật lặp lại bài hát",
                    tint = if (isRepeatMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Tua lùi 10s
            IconButton(
                onClick = {
                    MediaPlaybackManager.seekTo(context, (currentPosition - 10000).coerceAtLeast(0))
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "Lùi 10s",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Nút trung tâm: Phát / Tạm dừng / Phát lại (Replay khi phát hết)
            Surface(
                onClick = {
                    if (isCompleted) {
                        MediaPlaybackManager.replay(context)
                    } else {
                        MediaPlaybackManager.togglePlayPause(context)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            isCompleted -> Icons.Default.Replay
                            isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = when {
                            isCompleted -> "Phát lại từ đầu"
                            isPlaying -> "Tạm dừng"
                            else -> "Phát"
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Tua tiến 10s
            IconButton(
                onClick = {
                    MediaPlaybackManager.seekTo(context, (currentPosition + 10000).coerceAtMost(duration))
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "Tiến 10s",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Nút Replay về 00:00 bất kỳ lúc nào
            IconButton(
                onClick = {
                    MediaPlaybackManager.replay(context)
                },
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = "Phát lại từ đầu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 5. Nút mở ứng dụng ngoài & Nút Xoá
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onOpenExternal,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mở app", maxLines = 1)
            }

            OutlinedButton(
                onClick = onShowFileInfo,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Chi tiết")
            }

            OutlinedButton(
                onClick = onDeleteClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Xoá")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private fun formatDurationMs(ms: Int): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Trình duyệt nhúng mở toàn màn hình (Full Screen In-App Browser)
 * cho các bài viết được lưu ở chế độ giữ nguyên định dạng web gốc.
 */
@Composable
private fun InAppWebViewer(
    url: String,
    title: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var currentTitle by remember { mutableStateOf(title) }
    var pageProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    // Xử lý nút Back của Android (quay lại trang trước trong web hoặc thoát ra ngoài)
    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Thanh TopBar hiện đại, siêu mỏng gọn chuẩn thiết kế
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (webView?.canGoBack() == true) {
                                webView?.goBack()
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val host = try { Uri.parse(url).host ?: url } catch (_: Exception) { url }
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { webView?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Tải lại trang",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Mở bằng trình duyệt ngoài",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isLoading && pageProgress < 100) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // Khung WebView full màn hình
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            pageProgress = newProgress
                            isLoading = newProgress < 100
                        }

                        override fun onReceivedTitle(view: WebView?, webTitle: String?) {
                            if (!webTitle.isNullOrBlank()) {
                                currentTitle = webTitle
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}


