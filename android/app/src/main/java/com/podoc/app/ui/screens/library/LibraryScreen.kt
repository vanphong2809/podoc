package com.podoc.app.ui.screens.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.podoc.app.R
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.service.MediaPlaybackManager
import com.podoc.app.ui.components.ArticleCard
import com.podoc.app.ui.components.ArticleCardSkeleton
import com.podoc.app.ui.components.EmptyStateView
import com.podoc.app.ui.components.ShimmerBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onArticleClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val isMediaGrid = uiState.selectedContentType in listOf("image", "video")
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog states
    var editingArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var changingCategoryArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var newTitleInput by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteMultipleConfirmDialog by remember { mutableStateOf(false) }
    var articleToDelete by remember { mutableStateOf<ArticleListItem?>(null) }

    // Nút Back điện thoại khi đang ở chế độ chọn nhiều
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    // Snackbar Hoàn tác
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

    // Snackbar thông báo người dùng
    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearUserMessage()
        }
    }

    // Cuộn về đầu khi đổi bộ lọc
    LaunchedEffect(uiState.selectedContentType, uiState.selectedCategoryId, uiState.onlyFavorites) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    // Load thêm trang — danh sách
    LaunchedEffect(listState, isMediaGrid, uiState.isLoading, uiState.isRefreshing, uiState.hasMore, uiState.articles.size) {
        if (!isMediaGrid && !uiState.isLoading && !uiState.isRefreshing && uiState.hasMore && uiState.articles.isNotEmpty()) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastIndex ->
                    if (lastIndex != null && lastIndex >= uiState.articles.size - 3 && uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing) {
                        viewModel.loadArticles(refresh = false)
                    }
                }
        }
    }

    // Load thêm trang — lưới ảnh/video
    LaunchedEffect(gridState, isMediaGrid, uiState.isLoading, uiState.isRefreshing, uiState.hasMore, uiState.articles.size) {
        if (isMediaGrid && !uiState.isLoading && !uiState.isRefreshing && uiState.hasMore && uiState.articles.isNotEmpty()) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastIndex ->
                    if (lastIndex != null && lastIndex >= uiState.articles.size - 10 && uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing) {
                        viewModel.loadArticles(refresh = false)
                    }
                }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedContent(
                targetState = uiState.isSelectionMode,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                },
                label = "topBarTransition"
            ) { isSelectionMode ->
                if (isSelectionMode) {
                    SelectionModeTopBar(uiState = uiState, viewModel = viewModel,
                        onDeleteMultiple = { showDeleteMultipleConfirmDialog = true })
                } else {
                    LibraryTopBar(
                        uiState = uiState,
                        isMediaGrid = isMediaGrid,
                        showSortMenu = showSortMenu,
                        onSearchChange = viewModel::onSearchQueryChange,
                        onShowSortMenu = { showSortMenu = true },
                        onDismissSortMenu = { showSortMenu = false },
                        onSortChange = { viewModel.onSortChange(it); showSortMenu = false },
                        onSelectAll = viewModel::onSelectAll,
                        onToggleFavorites = viewModel::onToggleFavoritesFilter,
                        onContentTypeChange = viewModel::onContentTypeFilterChange,
                        onCategoryChange = viewModel::onCategoryFilterChange,
                        onStartSelectionMode = viewModel::startSelectionMode
                    )
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
                // Upload progress banner
                AnimatedVisibility(
                    visible = uiState.isUploading,
                    enter = expandVertically(tween(300)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
                ) {
                    UploadProgressBanner(
                        message = uiState.uploadProgressMessage ?: "Đang lưu tệp tin..."
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        // ── SHIMMER SKELETON khi tải lần đầu ──────────────────
                        uiState.isLoading && uiState.articles.isEmpty() -> {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(5) { ArticleCardSkeleton() }
                            }
                        }

                        // ── EMPTY STATE ────────────────────────────────────────
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

                        // ── MEDIA GRID (Hình ảnh / Video) ─────────────────────
                        isMediaGrid -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                state = gridState,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(uiState.articles, key = { _, it -> it.id }) { _, article ->
                                    MediaGridItem(
                                        article = article,
                                        isVideo = uiState.selectedContentType == "video",
                                        isSelectionMode = uiState.isSelectionMode,
                                        isSelected = uiState.selectedMediaIds.contains(article.id),
                                        onClick = {
                                            if (uiState.isSelectionMode) viewModel.toggleSelectMedia(article.id)
                                            else onArticleClick(article.id)
                                        },
                                        onLongClick = {
                                            if (!uiState.isSelectionMode) viewModel.startSelectionMode(article.id)
                                            else viewModel.toggleSelectMedia(article.id)
                                        }
                                    )
                                }
                                if (uiState.isLoading && uiState.articles.isNotEmpty()) {
                                    item(span = { GridItemSpan(5) }) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }

                        // ── ARTICLE LIST (Tất cả, Bài viết, Tệp tin) ──────────
                        else -> {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(uiState.articles, key = { _, it -> it.id }) { index, article ->
                                    ArticleCard(
                                            article = article,
                                            isHeroBanner = (index == 0 && !article.imageUrl.isNullOrBlank()),
                                            onClick = { onArticleClick(article.id) },
                                            onToggleFavorite = { viewModel.toggleFavorite(article) },
                                            onDelete = { articleToDelete = article },
                                            onOpenSource = {
                                                val url = article.sourceUrl
                                                if (!url.isNullOrBlank()) {
                                                    try {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                    } catch (_: Exception) {}
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
                                            onChangeCategory = { changingCategoryArticle = article },
                                            onEditTitle = {
                                                editingArticle = article
                                                newTitleInput = article.title
                                            }
                                        )
                                }

                                if (uiState.isLoading && uiState.articles.isNotEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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

    // ── DIALOG: Sửa Tiêu Đề ───────────────────────────────────────────────
    if (editingArticle != null) {
        AlertDialog(
            onDismissRequest = { editingArticle = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.action_edit_title), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newTitleInput,
                    onValueChange = { newTitleInput = it },
                    label = { Text("Tiêu đề mới") },
                    shape = RoundedCornerShape(14.dp),
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
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingArticle = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // Auto-tải danh mục mới nhất khi mở dialog đổi danh mục
    LaunchedEffect(changingCategoryArticle) {
        if (changingCategoryArticle != null) viewModel.loadCategories()
    }

    // ── DIALOG: Đổi Danh Mục ──────────────────────────────────────────────
    if (changingCategoryArticle != null) {
        AlertDialog(
            onDismissRequest = { changingCategoryArticle = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.action_change_category), fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    uiState.categories.forEach { cat ->
                        val isSelected = changingCategoryArticle?.categoryId == cat.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    viewModel.updateArticleCategory(changingCategoryArticle!!.id, cat.id)
                                    changingCategoryArticle = null
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(com.podoc.app.ui.theme.parseHexColor(cat.color))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { changingCategoryArticle = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // ── DIALOG: Xác nhận xoá nhiều ───────────────────────────────────────
    if (showDeleteMultipleConfirmDialog) {
        val count = uiState.selectedMediaIds.size
        val isVideoTab = uiState.selectedContentType == "video"
        val itemTypeName = if (isVideoTab) "video" else "hình ảnh"
        AlertDialog(
            onDismissRequest = { showDeleteMultipleConfirmDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isVideoTab) "Xoá video" else "Xoá hình ảnh", fontWeight = FontWeight.SemiBold) },
            text = { Text("Bạn có chắc chắn muốn xoá $count $itemTypeName đã chọn không? Thao tác này sẽ xoá hoàn toàn các tệp tin này khỏi thư viện và máy chủ.") },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = uiState.selectedMediaIds.toList()
                        showDeleteMultipleConfirmDialog = false
                        MediaPlaybackManager.stopIfPlaying(ids, context)
                        viewModel.deleteSelectedMedia()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Xoá ($count)", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMultipleConfirmDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // ── DIALOG: Xác nhận xoá 1 bài viết ─────────────────────────────────
    if (articleToDelete != null) {
        val article = articleToDelete!!
        val isVideo = article.mimeType?.startsWith("video/") == true ||
                listOf(".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp").any { (article.fileName ?: "").lowercase().endsWith(it) }
        val isAudio = article.mimeType?.startsWith("audio/") == true ||
                listOf(".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg").any { (article.fileName ?: "").lowercase().endsWith(it) }
        val itemTypeName = when { isVideo -> "video"; isAudio -> "tệp âm thanh"; else -> "bài viết" }
        AlertDialog(
            onDismissRequest = { articleToDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Xoá $itemTypeName", fontWeight = FontWeight.SemiBold) },
            text = { Text("Bạn có chắc chắn muốn xoá \"${article.title}\" không? Tệp tin sẽ bị xoá hoàn toàn khỏi thư viện và máy chủ.") },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = article
                        articleToDelete = null
                        MediaPlaybackManager.stopIfPlaying(toDelete.id, context)
                        viewModel.deleteArticle(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Xoá", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { articleToDelete = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// SELECTION MODE TOP BAR
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun SelectionModeTopBar(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onDeleteMultiple: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.clearSelection() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Huỷ chọn",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = "Đã chọn: ${uiState.selectedMediaIds.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            TextButton(onClick = {
                if (uiState.selectedMediaIds.size == uiState.articles.size && uiState.articles.isNotEmpty()) {
                    viewModel.clearSelection()
                } else {
                    viewModel.selectAllMedia()
                }
            }) {
                Text(
                    text = if (uiState.selectedMediaIds.size == uiState.articles.size && uiState.articles.isNotEmpty())
                        "Bỏ chọn tất cả" else "Chọn tất cả",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = onDeleteMultiple,
                enabled = uiState.selectedMediaIds.isNotEmpty() && !uiState.isDeletingMultiple
            ) {
                if (uiState.isDeletingMultiple) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Xoá",
                        tint = if (uiState.selectedMediaIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// LIBRARY TOP BAR — SearchBar + Filter Chips
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTopBar(
    uiState: LibraryUiState,
    isMediaGrid: Boolean,
    showSortMenu: Boolean,
    onSearchChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortChange: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleFavorites: () -> Unit,
    onContentTypeChange: (String) -> Unit,
    onCategoryChange: (Int) -> Unit,
    onStartSelectionMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
    ) {
        // ── Search Bar ──────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (uiState.searchQuery.isEmpty()) {
                        Text(
                            text = "Tìm kiếm bài viết, tài liệu...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        cursorBrush = Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                        )
                    )
                }
                AnimatedVisibility(visible = uiState.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Xoá tìm kiếm",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                AnimatedVisibility(visible = uiState.searchQuery.isEmpty()) {
                    Box {
                        IconButton(
                            onClick = onShowSortMenu,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Bộ lọc & Sắp xếp",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = onDismissSortMenu,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_newest)) },
                                onClick = { onSortChange("newest") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_oldest)) },
                                onClick = { onSortChange("oldest") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_reading_time)) },
                                onClick = { onSortChange("reading_time") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_title)) },
                                onClick = { onSortChange("title") }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── Filter Chips ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chipShape = RoundedCornerShape(20.dp)
            val activeChipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF4F46E5),
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val chipBorder = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = false,
                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                selectedBorderColor = Color.Transparent,
                borderWidth = 1.dp
            )

            // 1. Chip Tất cả
            val isAllSelected = uiState.selectedContentType == null && !uiState.onlyFavorites && uiState.selectedCategoryId == null
            FilterChip(
                selected = isAllSelected,
                onClick = onSelectAll,
                label = { Text("Tất cả", fontWeight = if (isAllSelected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = activeChipColors,
                border = chipBorder,
                shape = chipShape
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 2. Chip Yêu thích
            FilterChip(
                selected = uiState.onlyFavorites,
                onClick = onToggleFavorites,
                label = { Text("Yêu thích", fontWeight = if (uiState.onlyFavorites) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = {
                    Icon(
                        imageVector = if (uiState.onlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null, modifier = Modifier.size(15.dp)
                    )
                },
                colors = activeChipColors, border = chipBorder, shape = chipShape
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 3–7. Content Type Chips
            data class ChipDef(val type: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
            val contentChips = listOf(
                ChipDef("article", "Bài viết", Icons.Default.Article),
                ChipDef("image",   "Hình ảnh", Icons.Default.Image),
                ChipDef("video",   "Video",    Icons.Default.Videocam),
                ChipDef("file",    "Tệp tin",  Icons.Default.InsertDriveFile),
                ChipDef("audio",   "Âm thanh", Icons.Default.Headphones)
            )
            contentChips.forEach { chip ->
                val isSelected = uiState.selectedContentType == chip.type
                FilterChip(
                    selected = isSelected,
                    onClick = { onContentTypeChange(chip.type) },
                    label = { Text(chip.label, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    leadingIcon = { Icon(chip.icon, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    colors = activeChipColors, border = chipBorder, shape = chipShape
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Category chips
            uiState.categories.forEach { cat ->
                val isSelected = uiState.selectedCategoryId == cat.id
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategoryChange(cat.id) },
                    label = { Text(cat.name, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = activeChipColors, border = chipBorder, shape = chipShape
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        // ── Media count + Selection button (chỉ hiện khi ở tab Hình ảnh/Video) ──
        AnimatedVisibility(
            visible = isMediaGrid && uiState.articles.isNotEmpty(),
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${uiState.articles.size} ${if (uiState.selectedContentType == "video") "video" else "hình ảnh"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onStartSelectionMode)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = "Chọn nhiều",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Chọn",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// UPLOAD PROGRESS BANNER
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun UploadProgressBanner(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// MEDIA GRID ITEM — Hình ảnh / Video
// ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridItem(
    article: ArticleListItem,
    isVideo: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var isImageError by remember(article.imageUrl) { mutableStateOf(false) }

    // Animated scale khi được chọn
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "gridItemScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (!article.imageUrl.isNullOrBlank() && !isImageError) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(article.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                onError = { isImageError = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (article.imageUrl.isNullOrBlank() || isImageError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Video play icon
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Selection overlay + check
        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Đã chọn",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        } else if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.3f))
                    .border(1.5.dp, Color.White, CircleShape)
            )
        }
    }
}
