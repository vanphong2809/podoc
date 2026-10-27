package com.podoc.app.ui.screens.category

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podoc.app.R
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.ui.components.AddOptionBottomSheet
import com.podoc.app.ui.components.ArticleCard
import com.podoc.app.ui.components.EmptyStateView
import com.podoc.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    viewModel: CategoryDetailViewModel,
    onNavigateBack: () -> Unit,
    onArticleClick: (Int) -> Unit,
    onPasteLink: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddSheet by remember { mutableStateOf(false) }
    var editingArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var changingCategoryArticle by remember { mutableStateOf<ArticleListItem?>(null) }
    var newTitleInput by remember { mutableStateOf("") }

    // Launcher chọn File từ máy
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
        }
    }

    // Launcher chọn Ảnh từ thư viện máy
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
        }
    }

    // Launcher chọn Video từ thư viện máy
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
        }
    }

    // Hiển thị thông báo người dùng
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(uiState.categoryColor))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.categoryName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${uiState.articles.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadArticles(refresh = true) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Làm mới"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = parseHexColor(uiState.categoryColor),
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Thêm vào danh mục")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Thanh tìm kiếm trong danh mục
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                placeholder = { Text(stringResource(R.string.search_in_category_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Xoá")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Thanh lọc theo loại nội dung (Content Type Filter Chips)
            val filterOptions = listOf(
                Pair(null, stringResource(R.string.filter_content_all)),
                Pair("article", stringResource(R.string.filter_content_article)),
                Pair("file", stringResource(R.string.filter_content_file)),
                Pair("image", stringResource(R.string.filter_content_image)),
                Pair("video", stringResource(R.string.filter_content_video))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterOptions.forEach { (typeKey, label) ->
                    val isSelected = uiState.selectedContentType == typeKey
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onContentTypeChange(typeKey) },
                        label = {
                            Text(
                                text = label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Thanh tiến trình tải lên (nếu có)
            AnimatedVisibility(visible = uiState.isUploading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Danh sách nội dung
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.loadArticles(refresh = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading && uiState.articles.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.articles.isEmpty() -> {
                        EmptyStateView(
                            title = if (uiState.searchQuery.isNotEmpty() || uiState.selectedContentType != null) {
                                stringResource(R.string.empty_search_title)
                            } else {
                                stringResource(R.string.empty_category_title)
                            },
                            description = if (uiState.searchQuery.isNotEmpty() || uiState.selectedContentType != null) {
                                stringResource(R.string.empty_search_desc)
                            } else {
                                stringResource(R.string.empty_category_desc)
                            },
                            icon = if (uiState.searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.FolderOpen
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                // Ignore browser error
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
                        }
                    }
                }
            }
        }
    }

    // BottomSheet Thêm mới vào danh mục này
    if (showAddSheet) {
        AddOptionBottomSheet(
            onDismiss = { showAddSheet = false },
            onPasteLink = { url ->
                onPasteLink(url)
            },
            onPickFiles = {
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onPickImages = {
                imagePickerLauncher.launch("image/*")
            },
            onPickVideos = {
                videoPickerLauncher.launch("video/*")
            }
        )
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
    // Tự động tải lại danh sách danh mục mới nhất khi mở hộp thoại Đổi danh mục
    LaunchedEffect(changingCategoryArticle) {
        if (changingCategoryArticle != null) {
            viewModel.loadCategories()
        }
    }

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
                                            .background(parseHexColor(cat.color))
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
