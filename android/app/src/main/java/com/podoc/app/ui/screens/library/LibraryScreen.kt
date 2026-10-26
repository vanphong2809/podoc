package com.podoc.app.ui.screens.library

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                // Ô tìm kiếm hiện đại bo góc lớn
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Xoá tìm kiếm")
                            }
                        } else {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(imageVector = Icons.Default.Sort, contentDescription = "Sắp xếp")
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
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Dòng Filter Chips (Tất cả, Yêu thích, Link, File, Danh mục)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chip Yêu thích
                    FilterChip(
                        selected = uiState.onlyFavorites,
                        onClick = { viewModel.onToggleFavoritesFilter() },
                        label = { Text(stringResource(R.string.filter_favorites)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.onlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Nguồn Link
                    FilterChip(
                        selected = uiState.selectedSourceType == "URL",
                        onClick = { viewModel.onSourceTypeFilterChange("URL") },
                        label = { Text(stringResource(R.string.filter_link)) },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Chip Nguồn File (Phase 2)
                    FilterChip(
                        selected = uiState.selectedSourceType == "FILE",
                        onClick = { viewModel.onSourceTypeFilterChange("FILE") },
                        label = { Text(stringResource(R.string.filter_file)) },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Các Chip danh mục
                    uiState.categories.forEach { cat ->
                        FilterChip(
                            selected = uiState.selectedCategoryId == cat.id,
                            onClick = { viewModel.onCategoryFilterChange(cat.id) },
                            label = { Text(cat.name) },
                            shape = RoundedCornerShape(12.dp)
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
                        items(uiState.articles, key = { it.id }) { article ->
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

    // Dialog Đổi Danh Mục
    if (changingCategoryArticle != null) {
        AlertDialog(
            onDismissRequest = { changingCategoryArticle = null },
            title = { Text(stringResource(R.string.action_change_category)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
                                Text(
                                    text = cat.name,
                                    fontWeight = if (changingCategoryArticle?.categoryId == cat.id) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth()
                                )
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
