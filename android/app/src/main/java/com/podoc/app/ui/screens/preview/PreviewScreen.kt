package com.podoc.app.ui.screens.preview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.podoc.app.R
import com.podoc.app.ui.components.PoDocTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    viewModel: PreviewViewModel,
    onNavigateBack: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var isFullPreviewExpanded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.reset()
        }
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            val articleId = uiState.savedArticleId
            viewModel.reset()
            if (articleId != null) {
                onOpenArticle(articleId)
            } else {
                onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            PoDocTopAppBar(
                title = stringResource(R.string.preview_title),
                onBackClick = onNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // Trạng thái đang tải trích xuất
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = if (uiState.isBatchMode) {
                                uiState.uploadProgressMessage ?: "Đang xử lý tệp tin (${uiState.batchCurrentIndex}/${uiState.batchTotalCount})..."
                            } else if (uiState.isUploadingFile) {
                                uiState.uploadProgressMessage ?: stringResource(R.string.saving_file_status)
                            } else {
                                stringResource(R.string.extracting_status)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = onNavigateBack,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }

                // Trạng thái lỗi
                !uiState.errorMessage.isNullOrBlank() && uiState.draft == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.errorMessage!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row {
                            OutlinedButton(onClick = onNavigateBack) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Button(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }

                // Trạng thái hiển thị bản nháp
                uiState.draft != null -> {
                    val draft = uiState.draft!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        // Cảnh báo trùng link nếu có
                        if (draft.isDuplicate) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.duplicate_warning),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row {
                                        if (draft.existingArticleId != null) {
                                            FilledTonalButton(
                                                onClick = { onOpenArticle(draft.existingArticleId) },
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(stringResource(R.string.action_open_existing))
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        TextButton(onClick = { viewModel.saveArticle() }) {
                                            Text(stringResource(R.string.action_save_as_new))
                                        }
                                    }
                                }
                            }
                        }

                        // Cảnh báo bookmark fallback nếu có
                        if (draft.isFallbackBookmark) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkBorder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.bookmark_fallback_notice),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        // Ảnh bìa nếu có
                        if (!draft.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = draft.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Tiêu đề (cho phép sửa)
                        Text(
                            text = stringResource(R.string.label_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = uiState.editedTitle,
                            onValueChange = { viewModel.onTitleChange(it) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Metadata tóm tắt: Nguồn + Thời gian đọc + Tác giả dạng Badge đẹp mắt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Badge Nguồn
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (draft.fileName != null) Icons.Default.Description else Icons.Default.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = draft.siteName ?: (draft.fileName ?: "Trang web"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            // Badge Thời gian đọc
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.reading_time_format, draft.readingMinutes),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }

                            // Badge Tác giả nếu có
                            if (!draft.author.isNullOrBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = draft.author!!,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Chọn danh mục bằng Chips
                        Text(
                            text = stringResource(R.string.label_category),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Chip tạo danh mục mới inline
                            AssistChip(
                                onClick = { showNewCategoryDialog = true },
                                label = { Text(stringResource(R.string.action_new_category)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            uiState.categories.forEach { cat ->
                                FilterChip(
                                    selected = uiState.selectedCategoryId == cat.id,
                                    onClick = { viewModel.onCategorySelect(cat.id) },
                                    label = { Text(cat.name) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Làm sạch nội dung Markdown cho khung xem trước
                        val cleanedPreviewContent = remember(draft.contentMarkdown, draft.title) {
                            var text = draft.contentMarkdown.trim()
                            val lines = text.lines()
                            if (lines.isNotEmpty()) {
                                val firstLine = lines.first().trim()
                                val cleanFirst = firstLine.replace(Regex("^[#\\s*_-]+"), "").trim()
                                if (cleanFirst.equals(draft.title.trim(), ignoreCase = true) ||
                                    (cleanFirst.length > 5 && draft.title.contains(cleanFirst, ignoreCase = true))) {
                                    text = lines.drop(1).joinToString("\n").trim()
                                }
                            }
                            text.replace(Regex("^(\\+\\d+|\\d+)\\s*\n"), "").trim()
                        }

                        val previewColors = markdownColor(
                            text = MaterialTheme.colorScheme.onSurface,
                            linkText = MaterialTheme.colorScheme.primary,
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
                            dividerColor = MaterialTheme.colorScheme.outlineVariant
                        )

                        val previewTypography = markdownTypography(
                            h1 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            h2 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            h3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            paragraph = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                            code = TextStyle(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            quote = TextStyle(fontSize = 13.sp, fontStyle = FontStyle.Italic)
                        )

                        // Khung xem trước nội dung hiển thị Markdown thực tế sang trọng
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Article,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Xem trước nội dung trích xuất",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    if (cleanedPreviewContent.length > 400) {
                                        TextButton(
                                            onClick = { isFullPreviewExpanded = !isFullPreviewExpanded },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isFullPreviewExpanded) "Thu gọn" else "Mở rộng",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Icon(
                                                imageVector = if (isFullPreviewExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (!isFullPreviewExpanded) Modifier.heightIn(max = 240.dp)
                                            else Modifier
                                        )
                                ) {
                                    if (cleanedPreviewContent.isNotBlank()) {
                                        Markdown(
                                            content = if (!isFullPreviewExpanded && cleanedPreviewContent.length > 800) {
                                                cleanedPreviewContent.take(800) + "...\n\n*(Bấm \"Mở rộng\" để xem toàn bộ nội dung)*"
                                            } else {
                                                cleanedPreviewContent
                                            },
                                            typography = previewTypography,
                                            colors = previewColors,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = "Tệp tin: ${draft.fileName ?: "Tài liệu"}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "Tệp tin nguyên bản sẽ được mở và xem trực tiếp trên ứng dụng khi đọc.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // Hai nút hành động: "Lưu tài liệu" và "Lưu nhanh"
                        Button(
                            onClick = { viewModel.saveArticle() },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_save),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { viewModel.quickSave() },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                text = "${stringResource(R.string.action_quick_save)} ${stringResource(R.string.quick_save_note)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog tạo danh mục mới inline
    if (showNewCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showNewCategoryDialog = false },
            title = { Text(stringResource(R.string.create_category_title)) },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.category_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCategoryName.isNotBlank()) {
                            viewModel.createCategory(newCategoryName.trim())
                            newCategoryName = ""
                            showNewCategoryDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewCategoryDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
