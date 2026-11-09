package com.podoc.app.ui.screens.media

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.podoc.app.R
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.service.MediaPlaybackManager
import com.podoc.app.ui.components.ArticleCard
import com.podoc.app.ui.components.EmptyStateView
import com.podoc.app.ui.components.FileInfoBottomSheet
import com.podoc.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListScreen(
    viewModel: MediaListViewModel,
    onNavigateBack: () -> Unit,
    onArticleClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearchActive by remember { mutableStateOf(false) }
    var editingArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var changingCategoryArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var newTitleInput by remember { mutableStateOf("") }
    var showMoveMultipleDialog by remember { mutableStateOf(false) }
    var showDeleteMultipleDialog by remember { mutableStateOf(false) }
    var articleToDelete by remember { mutableStateOf<ArticleListItem?>(null) }
    var fileInfoArticle by remember { mutableStateOf<ArticleListItem?>(null) }

    val isMediaFormat = uiState.contentType in listOf("image", "video")

    // Hiển thị thông báo
    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearErrorMessage()
        }
    }

    // Tự động load thêm trang khi cuộn tới gần cuối danh sách
    LaunchedEffect(listState, gridState, uiState.isGridView, uiState.isLoading, uiState.isRefreshing, uiState.hasMore, uiState.articles.size) {
        if (!uiState.isLoading && !uiState.isRefreshing && uiState.hasMore && uiState.articles.isNotEmpty()) {
            if (isMediaFormat && uiState.isGridView) {
                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .collect { lastIndex ->
                        if (lastIndex != null && lastIndex >= uiState.articles.size - 12 && uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing) {
                            viewModel.loadArticles(refresh = false)
                        }
                    }
            } else {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .collect { lastIndex ->
                        if (lastIndex != null && lastIndex >= uiState.articles.size - 5 && uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing) {
                            viewModel.loadArticles(refresh = false)
                        }
                    }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Bỏ chọn")
                        }
                    },
                    title = {
                        Text(
                            text = "Đã chọn ${uiState.selectedIds.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        TextButton(onClick = viewModel::selectAll) {
                            Text("Chọn tất cả", fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(
                            onClick = {
                                viewModel.loadCategories()
                                showMoveMultipleDialog = true
                            },
                            enabled = uiState.selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Chuyển danh mục")
                        }
                        IconButton(
                            onClick = { showDeleteMultipleDialog = true },
                            enabled = uiState.selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Xoá",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = uiState.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${uiState.articles.size} tệp tin",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        // Nút tìm kiếm
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Tìm kiếm"
                            )
                        }

                        // Toggle Grid/List cho Hình ảnh và Video
                        if (isMediaFormat) {
                            IconButton(onClick = viewModel::toggleViewMode) {
                                Icon(
                                    imageVector = if (uiState.isGridView) Icons.Default.ViewAgenda else Icons.Default.GridView,
                                    contentDescription = "Đổi giao diện"
                                )
                            }
                        }

                        // Nút bật chế độ chọn nhiều
                        if (uiState.articles.isNotEmpty()) {
                            IconButton(onClick = viewModel::startSelectionMode) {
                                Icon(Icons.Default.Checklist, contentDescription = "Chọn nhiều")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Thanh tìm kiếm mở rộng
            AnimatedVisibility(visible = isSearchActive) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = { Text("Tìm trong ${uiState.title.lowercase()}...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Xoá")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.loadArticles(refresh = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading && uiState.articles.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.articles.isEmpty() -> {
                        EmptyStateView(
                            title = if (uiState.searchQuery.isNotEmpty()) stringResource(R.string.empty_search_title) else "Chưa có ${uiState.title.lowercase()} nào",
                            description = if (uiState.searchQuery.isNotEmpty()) stringResource(R.string.empty_search_desc) else "Các tệp ${uiState.title.lowercase()} bạn tải lên sẽ xuất hiện tại đây.",
                            icon = if (uiState.contentType == "image") Icons.Default.Image else if (uiState.contentType == "video") Icons.Default.Videocam else Icons.Default.FolderOpen
                        )
                    }

                    // Giao diện Lưới (Grid) cho Ảnh và Video
                    isMediaFormat && uiState.isGridView -> {
                        val columnsCount = if (uiState.contentType == "image") 4 else 3
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columnsCount),
                            state = gridState,
                            contentPadding = PaddingValues(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.articles, key = { it.id }) { article ->
                                MediaGridCard(
                                    article = article,
                                    isVideo = uiState.contentType == "video",
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedIds.contains(article.id),
                                    onClick = {
                                        if (uiState.isSelectionMode) viewModel.toggleSelect(article.id)
                                        else onArticleClick(article.id)
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelect(article.id)
                                    }
                                )
                            }
                        }
                    }

                    // Giao diện Danh sách (List) với ArticleCard chuẩn
                    else -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.articles, key = { it.id }) { article ->
                                ArticleCard(
                                    article = article,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedIds.contains(article.id),
                                    onClick = {
                                        if (uiState.isSelectionMode) viewModel.toggleSelect(article.id)
                                        else onArticleClick(article.id)
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelect(article.id)
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(article.id) },
                                    onDelete = { articleToDelete = article },
                                    onOpenSource = {},
                                    onShare = {},
                                    onChangeCategory = {
                                        viewModel.loadCategories()
                                        changingCategoryArticle = article
                                    },
                                    onEditTitle = {
                                        editingArticle = article
                                        newTitleInput = article.title
                                    },
                                    onShowFileInfo = { fileInfoArticle = article }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── DIALOG: Chuyển nhiều mục đã chọn sang Danh mục ─────────────────────────
    if (showMoveMultipleDialog) {
        val count = uiState.selectedIds.size
        AlertDialog(
            onDismissRequest = { showMoveMultipleDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Chuyển $count mục đã chọn", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = "Chọn danh mục đích:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )

                    uiState.categories.forEach { cat ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    viewModel.moveSelectedArticlesToCategory(cat.id)
                                    showMoveMultipleDialog = false
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(parseHexColor(cat.color))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveMultipleDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── DIALOG: Xác nhận xoá nhiều mục ──────────────────────────────────────────
    if (showDeleteMultipleDialog) {
        val count = uiState.selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteMultipleDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Xoá $count ${uiState.title.lowercase()} đã chọn", fontWeight = FontWeight.SemiBold) },
            text = { Text("Bạn có chắc chắn muốn xoá $count ${uiState.title.lowercase()} đã chọn không? Thao tác này sẽ xoá hoàn toàn khỏi máy chủ.") },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = uiState.selectedIds.toList()
                        showDeleteMultipleDialog = false
                        MediaPlaybackManager.stopIfPlaying(ids, context)
                        viewModel.deleteSelectedArticles()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Xoá ($count)", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMultipleDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── DIALOG: Đổi danh mục 1 mục ─────────────────────────────────────────────
    if (changingCategoryArticle != null) {
        AlertDialog(
            onDismissRequest = { changingCategoryArticle = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Chuyển danh mục", fontWeight = FontWeight.SemiBold) },
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
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
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
                                        .background(parseHexColor(cat.color))
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
                TextButton(onClick = { changingCategoryArticle = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── DIALOG: Đổi tiêu đề ─────────────────────────────────────────────────────
    if (editingArticle != null) {
        AlertDialog(
            onDismissRequest = { editingArticle = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Đổi tiêu đề", fontWeight = FontWeight.SemiBold) },
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
                TextButton(onClick = { editingArticle = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── DIALOG: Xoá 1 mục ───────────────────────────────────────────────────────
    if (articleToDelete != null) {
        val article = articleToDelete!!
        AlertDialog(
            onDismissRequest = { articleToDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Xoá ${uiState.title.lowercase()}", fontWeight = FontWeight.SemiBold) },
            text = { Text("Bạn có chắc chắn muốn xoá \"${article.title}\" không?") },
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
                TextButton(onClick = { articleToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── BOTTOM SHEET: Thông tin tệp tin ───────────────────────────────────
    if (fileInfoArticle != null) {
        FileInfoBottomSheet(
            articleId = fileInfoArticle!!.id,
            repository = viewModel.articleRepository,
            onDismiss = { fileInfoArticle = null },
            fallbackTitle = fileInfoArticle!!.title,
            fallbackFileName = fileInfoArticle!!.fileName,
            fallbackCategory = fileInfoArticle!!.categoryName,
            fallbackCreatedAt = fileInfoArticle!!.createdAt
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridCard(
    article: ArticleListItem,
    isVideo: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var isImageError by remember(article.imageUrl) { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mediaGridCardScale"
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
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Overlay thông tin danh mục ở góc dưới
        if (!article.categoryName.isNullOrBlank()) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = article.categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        // Icon Play cho video
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Checkbox chọn nhiều
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.4f)
                    )
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

