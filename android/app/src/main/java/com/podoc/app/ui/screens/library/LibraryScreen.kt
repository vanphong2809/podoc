package com.podoc.app.ui.screens.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    val gridState = rememberLazyGridState()
    val isMediaGrid = uiState.selectedContentType in listOf("image", "video")
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state
    var editingArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var changingCategoryArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var newTitleInput by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteMultipleConfirmDialog by remember { mutableStateOf(false) }

    // Xử lý nút Back của điện thoại khi đang ở chế độ chọn nhiều
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

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

    // Cuộn về đầu danh sách mỗi khi chuyển bộ lọc hoặc chuyển tab
    LaunchedEffect(uiState.selectedContentType, uiState.selectedCategoryId, uiState.onlyFavorites) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    // Tải thêm trang tiếp theo khi cuộn gần cuối trong Danh sách
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

    // Tải thêm trang tiếp theo khi cuộn gần cuối trong Lưới Hình ảnh/Video
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
            if (uiState.isSelectionMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp
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
                            fontWeight = FontWeight.Bold,
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
                                text = if (uiState.selectedMediaIds.size == uiState.articles.size && uiState.articles.isNotEmpty()) "Bỏ chọn tất cả" else "Chọn tất cả",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        IconButton(
                            onClick = { showDeleteMultipleConfirmDialog = true },
                            enabled = uiState.selectedMediaIds.isNotEmpty() && !uiState.isDeletingMultiple
                        ) {
                            if (uiState.isDeletingMultiple) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Xoá",
                                    tint = if (uiState.selectedMediaIds.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Gray
                                )
                            }
                        }
                    }
                }
            } else {
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

                // Dòng Filter Chips (Tất cả, Yêu thích, Bài viết, Hình ảnh, Video, Tệp tin, Danh mục...)
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

                    // 1. Chip Tất cả (Chứa tất cả bài viết, hình ảnh, video, file)
                    val isAllSelected = uiState.selectedContentType == null && !uiState.onlyFavorites && uiState.selectedCategoryId == null
                    FilterChip(
                        selected = isAllSelected,
                        onClick = { viewModel.onSelectAll() },
                        label = { Text("Tất cả", fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium) },
                        colors = activeChipColors,
                        shape = chipShape
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 2. Chip Yêu thích
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

                    // 3. Chip Bài viết (Chỉ chứa bài viết)
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

                    // 4. Chip Hình ảnh (Hiển thị dạng lưới 5 ảnh/hàng, page size 80)
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

                    // 5. Chip Video (Hiển thị dạng lưới 5 video/hàng, page size 80)
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

                    // 6. Chip Tệp tin (Hiển thị dạng khối thẻ như bình thường, page size 20)
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

                // Dòng trạng thái số lượng & nút Chọn khi ở tab Hình ảnh / Video
                if (isMediaGrid && uiState.articles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.articles.size} ${if (uiState.selectedContentType == "video") "video" else "hình ảnh"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.startSelectionMode() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = "Chọn nhiều ảnh",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
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
                        isMediaGrid -> {
                            // DẠNG LƯỚI CHO HÌNH ẢNH & VIDEO (1 hàng 5 item, cuộn trang phân trang 80 item)
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
                                            if (uiState.isSelectionMode) {
                                                viewModel.toggleSelectMedia(article.id)
                                            } else {
                                                onArticleClick(article.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (!uiState.isSelectionMode) {
                                                viewModel.startSelectionMode(article.id)
                                            } else {
                                                viewModel.toggleSelectMedia(article.id)
                                            }
                                        }
                                    )
                                }

                                if (uiState.isLoading && uiState.articles.isNotEmpty()) {
                                    item(span = { GridItemSpan(5) }) {
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
                        else -> {
                            // DẠNG KHỐI THẺ CHO TẤT CẢ, BÀI VIẾT, TỆP TIN (Đúng thiết kế từng khối)
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(uiState.articles, key = { _, it -> it.id }) { index, article ->
                                    ArticleCard(
                                        article = article,
                                        isHeroBanner = (index == 0 && !article.imageUrl.isNullOrBlank()),
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

    // Dialog xác nhận xoá nhiều hình ảnh/video đã chọn
    if (showDeleteMultipleConfirmDialog) {
        val count = uiState.selectedMediaIds.size
        val isVideoTab = uiState.selectedContentType == "video"
        val itemTypeName = if (isVideoTab) "video" else "hình ảnh"
        AlertDialog(
            onDismissRequest = { showDeleteMultipleConfirmDialog = false },
            title = {
                Text(
                    text = if (isVideoTab) "Xoá video" else "Xoá hình ảnh",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Bạn có chắc chắn muốn xoá $count $itemTypeName đã chọn không? Thao tác này sẽ xoá hoàn toàn các tệp tin này khỏi thư viện và máy chủ.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteMultipleConfirmDialog = false
                        viewModel.deleteSelectedMedia()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xoá ($count)", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMultipleConfirmDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isSelected) {
                    Modifier.border(2.5.dp, Color(0xFF2563EB), RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (!article.imageUrl.isNullOrBlank() && !isImageError) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(article.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = article.title,
                contentScale = ContentScale.Crop,
                onError = {
                    isImageError = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (article.imageUrl.isNullOrBlank() || isImageError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Overlay và Check badge khi ở chế độ Selection Mode
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2563EB).copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2563EB)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Đã chọn",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, CircleShape)
            )
        }
    }
}
