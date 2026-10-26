package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.podoc.app.ui.components.PoDocTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    articleId: Int,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()

    var showFontMenu by remember { mutableStateOf(false) }

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
                article?.mimeType?.contains("msword") == true -> "doc"
                article?.mimeType?.startsWith("image/") == true -> "jpg"
                else -> ""
            }
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

    Scaffold(
        topBar = {
            Column {
                PoDocTopAppBar(
                    title = article?.title?.take(25)?.let { "$it..." } ?: "Tài liệu",
                    onBackClick = onNavigateBack,
                    actions = {
                        // Nếu là dạng text/markdown thì hiện tuỳ chỉnh font
                        if (!isFile || fileExt in listOf("txt", "md", "mdf")) {
                            IconButton(onClick = { showFontMenu = true }) {
                                Icon(imageVector = Icons.Default.FormatSize, contentDescription = "Cỡ chữ")
                            }

                            DropdownMenu(
                                expanded = showFontMenu,
                                onDismissRequest = { showFontMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Tăng cỡ chữ (+)") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = { viewModel.increaseFontSize() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Giảm cỡ chữ (-)") },
                                    leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) },
                                    onClick = { viewModel.decreaseFontSize() }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.isSerif) stringResource(R.string.font_family_sans)
                                            else stringResource(R.string.font_family_serif)
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.FontDownload, contentDescription = null) },
                                    onClick = { viewModel.toggleSerif() }
                                )
                            }
                        }

                        // Nút Mở tệp tin bằng app ngoài (Word, WPS, v.v.)
                        if (isFile) {
                            IconButton(
                                onClick = { viewModel.openOriginalFile(context) },
                                enabled = !uiState.isDownloadingFile
                            ) {
                                if (uiState.isDownloadingFile) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = "Mở bằng ứng dụng khác")
                                }
                            }
                        }

                        // Nút Chia sẻ
                        IconButton(
                            onClick = {
                                val art = article ?: return@IconButton
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, art.title)
                                    putExtra(Intent.EXTRA_TEXT, "${art.title}\n\n${art.sourceUrl ?: ""}")
                                }
                                context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Chia sẻ")
                        }
                    }
                )

                // Thanh tiến độ đọc mảnh
                if (!isFile) {
                    LinearProgressIndicator(
                        progress = { currentProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                            uiState = uiState,
                            lazyListState = lazyListState,
                            cleanedMarkdown = cleanedMarkdown,
                            markdownBlocks = markdownBlocks,
                            onRetryLoad = { viewModel.loadFileForInApp(context, article) },
                            onOpenExternal = { viewModel.openOriginalFile(context) }
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
}

/**
 * Hiển thị tệp tin trực tiếp ngay trên App theo từng định dạng:
 * PDF, Hình ảnh (PNG, SVG, JPG, WEBP), HTML, Word (DOCX, DOC), Văn bản (TXT, MD, MDF)
 */
@Composable
private fun InAppFileContent(
    article: ArticleDetail,
    fileExt: String,
    uiState: ReaderUiState,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    cleanedMarkdown: String,
    markdownBlocks: List<String>,
    onRetryLoad: () -> Unit,
    onOpenExternal: () -> Unit
) {
    if (uiState.isFileLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Đang tải tệp tin...", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    if (uiState.fileError != null && uiState.localFile == null && uiState.htmlContent == null) {
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

    val isImage = fileExt in listOf("png", "svg", "jpg", "jpeg", "webp", "gif") || article.mimeType?.startsWith("image/") == true
    val isSvg = fileExt == "svg" || article.mimeType == "image/svg+xml"

    when {
        // 1. Xem file PDF bằng PdfRenderer tích hợp sẵn
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

        // 2. Xem ảnh (PNG, SVG, JPG, JPEG, WEBP) với phóng to/thu nhỏ
        isImage -> {
            val file = uiState.localFile
            if (file != null && file.exists()) {
                InAppImageViewer(file = file, isSvg = isSvg)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // 3. Xem HTML hoặc Word (DOCX / DOC) qua WebView
        fileExt in listOf("html", "htm", "docx", "doc") || article.mimeType in listOf("text/html", "application/msword") -> {
            InAppHtmlViewer(
                htmlContent = uiState.htmlContent,
                localFile = uiState.localFile,
                onOpenExternal = onOpenExternal
            )
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

        // 5. Định dạng khác: Hiển thị thông tin file và nút mở ngoài
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
                        Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(article.fileName ?: article.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Định dạng: ${fileExt.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onOpenExternal, shape = RoundedCornerShape(12.dp)) {
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
 * Trình xem ảnh (PNG, SVG, JPG, WEBP) hỗ trợ zoom đa điểm và kéo
 */
@Composable
private fun InAppImageViewer(file: File, isSvg: Boolean) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current
    val imageLoader = remember(isSvg) {
        ImageLoader.Builder(context)
            .components {
                if (isSvg) add(SvgDecoder.Factory())
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.8f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Fit,
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
