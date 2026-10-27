package com.podoc.app.ui.screens.library

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podoc.app.R
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.ui.components.ArticleCard
import com.podoc.app.ui.components.EmptyStateView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onArticleClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state
    var editingArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var changingCategoryArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var newTitleInput by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

    // Xử lý Snackbar Hoàn tác
    LaunchedEffect(uiState.recentlyDeletedArticle) {
        val deleted = uiState.recentlyDeletedArticle
        if (deleted != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Đã xoá: ${deleted.title.take(30)}...",
                actionLabel = "Hoàn tác",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            }
        }
    }

    // Xử lý Snackbar Thông báo người dùng (lưu file...)
    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearUserMessage()
        }
    }

    // Tải thêm trang tiếp theo khi cuộn gần cuối
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= uiState.articles.size - 3 && uiState.hasMore && !uiState.isLoading) {
                    viewModel.loadArticles(refresh = false)
                }
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Ô tìm kiếm hiện đại bo góc lớn hình viên thuốc
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    placeholder = {
                        Text(
                            text = "Tìm kiếm bài viết, tài liệu...",
                            color = Color(0xFF6B7280)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF374151),
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Xoá tìm kiếm",
                                    tint = Color(0xFF6B7280)
                                )
                            }
                        } else {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Bộ lọc & Sắp xếp",
                                    tint = Color(0xFF374151),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_newest)) },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.onSortChange("newest")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_oldest)) },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.onSortChange("oldest")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_reading_time)) },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.onSortChange("reading_time")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_title)) },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.onSortChange("title")
                                }
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = Color(0xFFD1D5DB),
                        unfocusedBorderColor = Color(0xFFE5E7EB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Dòng Filter Chips (Yêu thích, Bài viết, Hình ảnh, Video, Tệp tin)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipShape = RoundedCornerShape(20.dp)
                    val activeChipColors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2563EB),
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )

                    // Chip Yêu thích
                    FilterChip(
                        selected = uiState.onlyFavorites,
                        onClick = { viewModel.onToggleFavoritesFilter() },
                        label = { Text("Yêu thích", fontWeight = if (uiState.onlyFavorites) FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.onlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Bài viết
                    FilterChip(
                        selected = uiState.selectedContentType == "article",
                        onClick = { viewModel.onContentTypeFilterChange("article") },
                        label = { Text("Bài viết", fontWeight = if (uiState.selectedContentType == "article") FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Article,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Hình ảnh
                    FilterChip(
                        selected = uiState.selectedContentType == "image",
                        onClick = { viewModel.onContentTypeFilterChange("image") },
                        label = { Text("Hình ảnh", fontWeight = if (uiState.selectedContentType == "image") FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Video
                    FilterChip(
                        selected = uiState.selectedContentType == "video",
                        onClick = { viewModel.onContentTypeFilterChange("video") },
                        label = { Text("Video", fontWeight = if (uiState.selectedContentType == "video") FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Tệp tin
                    FilterChip(
                        selected = uiState.selectedContentType == "file",
                        onClick = { viewModel.onContentTypeFilterChange("file") },
                        label = { Text("Tệp tin", fontWeight = if (uiState.selectedContentType == "file") FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Các Chip danh mục
                    uiState.categories.forEach { cat ->
                        FilterChip(
                            selected = uiState.selectedCategoryId == cat.id,
                            onClick = { viewModel.onCategoryFilterChange(cat.id) },
                            label = { Text(cat.name) },
                            colors = activeChipColors,
                            shape = chipShape
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.loadArticles(refresh = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = uiState.isUploading,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = uiState.uploadProgressMessage ?: "Đang lưu tệp tin...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        uiState.isLoading && uiState.articles.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        uiState.articles.isEmpty() -> {
                            if (uiState.searchQuery.isNotBlank()) {
                                EmptyStateView(
                                    icon = Icons.Default.SearchOff,
                                    title = stringResource(R.string.empty_search_title),
                                    description = stringResource(R.string.empty_search_desc)
                                )
                            } else {
                                EmptyStateView(
                                    icon = Icons.Default.LocalLibrary,
                                    title = stringResource(R.string.empty_library_title),
                                    description = stringResource(R.string.empty_library_desc)
                                )
                            }
                        }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(uiState.articles, key = { _, it -> it.id }) { _, article ->
                            ArticleCard(
                                article = article,
                                onClick = { onArticleClick(article.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(article) },
                                onDelete = { viewModel.deleteArticle(article) },
                                onOpenSource = {
                                    val url = article.sourceUrl
                                    if (!url.isNullOrBlank()) {
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(browserIntent)
                                        } catch (e: Exception) {
                                            // Handle no browser
                                        }
                                    }
                                },
                                onShare = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, article.title)
                                        putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.sourceUrl ?: ""}")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ bài viết"))
                                },
                                onChangeCategory = {
                                    changingCategoryArticle = article
                                },
                                onEditTitle = {
                                    editingArticle = article
                                    newTitleInput = article.title
                                }
                            )
                        }

                        if (uiState.isLoading && uiState.articles.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

    // Dialog Sửa Tiêu Đề
    if (editingArticle != null) {
        AlertDialog(
            onDismissRequest = { editingArticle = null },
            title = { Text(stringResource(R.string.action_edit_title)) },
            text = {
                OutlinedTextField(
                    value = newTitleInput,
                    onValueChange = { newTitleInput = it },
                    label = { Text("Tiêu đề mới") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitleInput.isNotBlank()) {
                            viewModel.updateArticleTitle(editingArticle!!.id, newTitleInput.trim())
                            editingArticle = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingArticle = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Tự động tải lại danh sách danh mục mới nhất khi mở hộp thoại Đổi danh mục
    LaunchedEffect(changingCategoryArticle) {
        if (changingCategoryArticle != null) {
            viewModel.loadCategories()
        }
    }

    // Dialog Đổi Danh Mục
    if (changingCategoryArticle != null) {
        AlertDialog(
            onDismissRequest = { changingCategoryArticle = null },
            title = { Text(stringResource(R.string.action_change_category)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    uiState.categories.forEach { cat ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (changingCategoryArticle?.categoryId == cat.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.updateArticleCategory(changingCategoryArticle!!.id, cat.id)
                                    changingCategoryArticle = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(com.podoc.app.ui.theme.parseHexColor(cat.color))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = cat.name,
                                        fontWeight = if (changingCategoryArticle?.categoryId == cat.id) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (changingCategoryArticle?.categoryId == cat.id) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { changingCategoryArticle = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
