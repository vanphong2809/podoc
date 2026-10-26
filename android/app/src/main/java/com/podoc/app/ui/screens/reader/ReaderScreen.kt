package com.podoc.app.ui.screens.reader

import android.content.Intent
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.podoc.app.R
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.ui.components.PoDocTopAppBar

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
        viewModel.loadArticle(articleId)
    }

    val article = uiState.article

    // Làm sạch nội dung Markdown (loại bỏ tiêu đề H1 trùng lặp và rác ở đầu bài)
    val cleanedMarkdown = remember(article?.contentMarkdown, article?.title) {
        if (article == null) ""
        else {
            var text = article.contentMarkdown.trim()
            val lines = text.lines()
            if (lines.isNotEmpty()) {
                val firstLine = lines.first().trim()
                val cleanFirst = firstLine.replace(Regex("^[#\\s*_-]+"), "").trim()
                if (cleanFirst.equals(article.title.trim(), ignoreCase = true) ||
                    (cleanFirst.length > 5 && article.title.contains(cleanFirst, ignoreCase = true))) {
                    text = lines.drop(1).joinToString("\n").trim()
                }
            }
            text.replace(Regex("^(\\+\\d+|\\d+)\\s*\n"), "").trim()
        }
    }

    // Tách nhỏ Markdown thành các block để LazyColumn ảo hóa (Virtualize), đạt 120 FPS
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

    // Chỉ đồng bộ tiến độ đọc khi người dùng dừng cuộn (tránh trigger recomposition khi đang lướt)
    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress && markdownBlocks.isNotEmpty()) {
            val total = lazyListState.layoutInfo.totalItemsCount
            if (total > 2) {
                val progress = (lazyListState.firstVisibleItemIndex.toFloat() / (total - 2).toFloat()).coerceIn(0f, 1f)
                viewModel.updateReadProgress(progress)
            }
        }
    }

    // Cuộn tới vị trí đọc trước đó khi mở bài viết
    var initialScrolled by remember(articleId) { mutableStateOf(false) }
    LaunchedEffect(article, markdownBlocks.size) {
        val art = article
        if (!initialScrolled && art != null && art.readProgress > 0f && markdownBlocks.isNotEmpty()) {
            val targetIndex = (art.readProgress * markdownBlocks.size).toInt().coerceIn(0, markdownBlocks.size)
            lazyListState.scrollToItem(targetIndex)
            initialScrolled = true
        }
    }

    Scaffold(
        topBar = {
            Column {
                PoDocTopAppBar(
                    title = article?.title?.take(25)?.let { "$it..." } ?: "Đang đọc",
                    onBackClick = onNavigateBack,
                    actions = {
                        // Nút chỉnh cỡ chữ và font
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

                        // Nút Mở file gốc nếu là bài viết từ File
                        if (article?.sourceType == "FILE") {
                            IconButton(
                                onClick = { viewModel.openOriginalFile(context) },
                                enabled = !uiState.isDownloadingFile
                            ) {
                                if (uiState.isDownloadingFile) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(imageVector = Icons.Default.FileOpen, contentDescription = stringResource(R.string.action_open_original_file))
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

                // Thanh tiến độ đọc mảnh ở trên cùng (chạy trong Draw pass, không gây giật khi cuộn)
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
                article != null -> {
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

                    // LazyColumn ảo hóa các khối nội dung, lướt mượt mà 120 FPS trên bất kỳ bài viết dài nào
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        // 1. Khối Header bài viết (Ảnh bìa, Tiêu đề, Metadata Tags)
                        item(key = "article_header", contentType = "header") {
                            ArticleReaderHeader(
                                article = article,
                                activeFontFamily = activeFontFamily,
                                baseSize = baseSize
                            )
                        }

                        // 2. Các khối nội dung Markdown ảo hóa theo từng đoạn
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

                        // 3. Khoảng trống cuối trang
                        item(key = "article_footer", contentType = "footer") {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
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
        // Ảnh bìa bài viết nếu có
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

        // Tiêu đề bài viết
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

        // Metadata tác giả, nguồn, thời gian đọc
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

/**
 * Tách nội dung Markdown thành danh sách các khối (Blocks) logic:
 * - Khối mã nguồn Code Block (giữ nguyên fence ```...```)
 * - Khối Tiêu đề (# Heading)
 * - Khối Danh sách và Đoạn văn (phân tách bởi dòng trắng)
 * Giúp LazyColumn tái sử dụng và chỉ render những đoạn đang hiển thị trên màn hình.
 */
private fun splitMarkdownIntoBlocks(markdown: String): List<String> {
    if (markdown.isBlank()) return emptyList()
    val blocks = ArrayList<String>()
    val currentBlock = StringBuilder()
    var inCodeBlock = false

    val lines = markdown.lines()
    for (line in lines) {
        val trimmed = line.trim()

        // Xử lý khối code ```
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

        // Tiêu đề Markdown bắt đầu bằng # tách thành 1 khối độc lập
        if (trimmed.startsWith("#")) {
            if (currentBlock.isNotEmpty()) {
                val text = currentBlock.toString().trim()
                if (text.isNotEmpty()) blocks.add(text)
                currentBlock.clear()
            }
            blocks.add(trimmed)
            continue
        }

        // Dòng trắng đánh dấu kết thúc một đoạn văn / khối
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
