package com.podoc.app.ui.screens.tools

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.PdfMergeInput
import com.podoc.app.data.model.PdfSplitSource
import com.podoc.app.data.model.SplitPdfResponse

private enum class LibraryPickerTarget {
    MERGE,
    SPLIT
}

private val PdfAccentBlue = Color(0xFF0284C7)
private val PdfAccentIndigo = Color(0xFF4F46E5)
private val PdfSuccessGreen = Color(0xFF16A34A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(
    viewModel: PdfToolsViewModel,
    onNavigateBack: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    val context = LocalContext.current
    var libraryPickerTarget by remember { mutableStateOf<LibraryPickerTarget?>(null) }

    val pickMultiplePdfsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addLocalPdfsToMerge(uris)
        }
    }

    val pickSinglePdfForSplitLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.selectLocalPdfForSplit(uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Ghép & Tách file PDF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Xử lý giữ nguyên chất lượng trang PDF gốc",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Segmented Switch Tabs: Ghép PDF | Tách PDF
            PdfModeSegmentedTabs(
                activeTab = viewModel.activeTab,
                onSelectTab = { viewModel.activeTab = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (viewModel.activeTab == 0) {
                MergePdfSection(
                    viewModel = viewModel,
                    onPickLocalPdfs = {
                        pickMultiplePdfsLauncher.launch(arrayOf("application/pdf"))
                    },
                    onOpenLibraryPicker = {
                        viewModel.librarySearchQuery = ""
                        viewModel.loadLibraryPdfs()
                        libraryPickerTarget = LibraryPickerTarget.MERGE
                    },
                    onOpenArticle = onOpenArticle
                )
            } else {
                SplitPdfSection(
                    viewModel = viewModel,
                    onPickLocalPdf = {
                        pickSinglePdfForSplitLauncher.launch(arrayOf("application/pdf"))
                    },
                    onOpenLibraryPicker = {
                        viewModel.librarySearchQuery = ""
                        viewModel.loadLibraryPdfs()
                        libraryPickerTarget = LibraryPickerTarget.SPLIT
                    },
                    onOpenArticle = onOpenArticle
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    // BottomSheet chọn PDF từ Thư viện PoDoc
    val pickerTarget = libraryPickerTarget
    if (pickerTarget != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { libraryPickerTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val filtered = remember(viewModel.libraryPdfs, viewModel.librarySearchQuery) {
                val query = viewModel.librarySearchQuery.trim().lowercase()
                if (query.isEmpty()) {
                    viewModel.libraryPdfs
                } else {
                    viewModel.libraryPdfs.filter { item ->
                        item.title.lowercase().contains(query) ||
                            (item.fileName?.lowercase()?.contains(query) == true) ||
                            (item.categoryName?.lowercase()?.contains(query) == true)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chọn PDF từ Thư viện",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (pickerTarget == LibraryPickerTarget.MERGE) {
                                "Chạm vào tài liệu để thêm vào danh sách ghép"
                            } else {
                                "Chọn 1 tài liệu PDF cần tách trang"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pickerTarget == LibraryPickerTarget.MERGE) {
                        TextButton(onClick = { libraryPickerTarget = null }) {
                            Text(
                                text = "Xong (${viewModel.mergeItems.size})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = viewModel.librarySearchQuery,
                    onValueChange = { viewModel.librarySearchQuery = it },
                    placeholder = { Text("Tìm theo tên tài liệu PDF...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (viewModel.librarySearchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.librarySearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Xoá"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 420.dp)
                ) {
                    when {
                        viewModel.isLoadingLibraryPdfs -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        filtered.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (viewModel.libraryPdfs.isEmpty()) {
                                        "Chưa có tệp PDF nào trong Thư viện của bạn"
                                    } else {
                                        "Không tìm thấy tệp PDF phù hợp"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        else -> {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filtered, key = { it.id }) { article ->
                                    val alreadyCount = if (pickerTarget == LibraryPickerTarget.MERGE) {
                                        viewModel.mergeItems.count {
                                            it is PdfMergeInput.LibraryArticle && it.articleId == article.id
                                        }
                                    } else 0

                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (alreadyCount > 0) {
                                            PdfAccentBlue.copy(alpha = 0.07f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (alreadyCount > 0) {
                                                PdfAccentBlue.copy(alpha = 0.45f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                if (pickerTarget == LibraryPickerTarget.MERGE) {
                                                    viewModel.addLibraryPdfToMerge(article)
                                                    Toast.makeText(
                                                        context,
                                                        "Đã thêm: ${article.title}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    viewModel.selectLibraryPdfForSplit(article)
                                                    libraryPickerTarget = null
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFFEE2E2)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PictureAsPdf,
                                                    contentDescription = null,
                                                    tint = Color(0xFFDC2626),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = article.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val subParts = buildList {
                                                    article.fileName?.takeIf { it != article.title }?.let { add(it) }
                                                    article.categoryName?.let { add(it) }
                                                }
                                                if (subParts.isNotEmpty()) {
                                                    Text(
                                                        text = subParts.joinToString(" • "),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (alreadyCount > 0) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = PdfAccentBlue
                                                ) {
                                                    Text(
                                                        text = "Đã chọn ($alreadyCount)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Chọn",
                                                    tint = PdfAccentBlue
                                                )
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
    }

    // Dialog đang xử lý (Ghép hoặc Tách)
    if (viewModel.isMerging || viewModel.isSplitting) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = PdfAccentBlue)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (viewModel.isMerging) {
                            "Đang ghép ${viewModel.mergeItems.size} tệp PDF..."
                        } else {
                            "Đang tách file PDF..."
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Quá trình xử lý và lưu vào Thư viện có thể mất vài giây.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Dialog thông báo lỗi
    val error = viewModel.errorMessage
    if (error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(
                    text = "Không thể thực hiện",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = error)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("Đóng")
                }
            }
        )
    }
}

@Composable
private fun PdfModeSegmentedTabs(
    activeTab: Int,
    onSelectTab: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SegmentedTabButton(
                title = "Ghép PDF",
                icon = Icons.Default.Layers,
                selected = activeTab == 0,
                onClick = { onSelectTab(0) },
                modifier = Modifier.weight(1f)
            )
            SegmentedTabButton(
                title = "Tách PDF",
                icon = Icons.AutoMirrored.Filled.CallSplit,
                selected = activeTab == 1,
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SegmentedTabButton(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 2.dp else 0.dp,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) PdfAccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) PdfAccentBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MergePdfSection(
    viewModel: PdfToolsViewModel,
    onPickLocalPdfs: () -> Unit,
    onOpenLibraryPicker: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    // Kết quả sau khi ghép thành công
    val mergedArticle = viewModel.mergedResult
    if (mergedArticle != null) {
        MergeSuccessCard(
            article = mergedArticle,
            onOpenArticle = { onOpenArticle(mergedArticle.id) },
            onResetMerge = { viewModel.resetMerge() }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Nút thêm nguồn PDF
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Thêm các tệp PDF cần ghép",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Có thể kết hợp nhiều file từ bộ nhớ thiết bị và từ Thư viện PoDoc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickLocalPdfs,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PdfAccentBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chọn từ máy", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onOpenLibraryPicker,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PdfAccentBlue),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = PdfAccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Từ Thư viện",
                        color = PdfAccentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Danh sách thứ tự ghép
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val knownTotalPages = viewModel.mergeItems.sumOf { it.pageCount ?: 0 }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thứ tự ghép (${viewModel.mergeItems.size} tệp)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (knownTotalPages > 0) {
                        Text(
                            text = "Tổng cộng đã biết: $knownTotalPages trang",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (viewModel.mergeItems.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.resetMerge() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Xoá hết",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (viewModel.mergeItems.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Chưa có tệp PDF nào được chọn",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Chọn từ 2 tệp trở lên, sau đó dùng nút mũi tên để sắp xếp thứ tự trang khi ghép.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.mergeItems.forEachIndexed { index, item ->
                        MergeFileItemRow(
                            index = index,
                            total = viewModel.mergeItems.size,
                            item = item,
                            onMoveUp = { viewModel.moveMergeItemUp(index) },
                            onMoveDown = { viewModel.moveMergeItemDown(index) },
                            onRemove = { viewModel.removeMergeItem(index) }
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Cấu hình đầu ra
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Tệp kết quả & Lưu trữ",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = viewModel.mergeOutputTitle,
                onValueChange = { viewModel.mergeOutputTitle = it },
                label = { Text("Tên tài liệu sau khi ghép") },
                placeholder = { Text("Ví dụ: Hop_dong_hoan_chinh") },
                suffix = { Text(".pdf") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            CategoryPickerRow(
                categories = viewModel.categories,
                selectedCategoryId = viewModel.selectedCategoryId,
                onSelectCategory = { viewModel.selectedCategoryId = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            val canMerge = viewModel.mergeItems.size >= 2 && !viewModel.isMerging
            Button(
                onClick = { viewModel.executeMerge() },
                enabled = canMerge,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PdfAccentIndigo,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (viewModel.mergeItems.size < 2) {
                        "Chọn thêm ${2 - viewModel.mergeItems.size} tệp để ghép"
                    } else {
                        "Ghép ${viewModel.mergeItems.size} tệp PDF ngay"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MergeFileItemRow(
    index: Int,
    total: Int,
    item: PdfMergeInput,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(PdfAccentBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.isFromLibrary) Color(0xFFEDE9FE) else Color(0xFFE0F2FE)
                    ) {
                        Text(
                            text = if (item.isFromLibrary) "Thư viện" else "Trên máy",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.5.sp,
                            color = if (item.isFromLibrary) Color(0xFF6D28D9) else Color(0xFF0369A1),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    val metaText = buildList {
                        item.pageCount?.let { add("$it trang") }
                        item.fileSizeFormatted?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }.joinToString(" • ")
                    if (metaText.isNotEmpty()) {
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            IconButton(
                onClick = onMoveUp,
                enabled = index > 0,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Lên trên",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = index < total - 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Xuống dưới",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Gỡ",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MergeSuccessCard(
    article: ArticleDetail,
    onOpenArticle: () -> Unit,
    onResetMerge: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
        border = BorderStroke(1.dp, Color(0xFF86EFAC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = PdfSuccessGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ghép PDF thành công!",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF14532D)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF14532D)
            )
            article.fileName?.let { fileName ->
                Text(
                    text = "Tệp: $fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF166534)
                )
            }
            article.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF15803D)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenArticle,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PdfSuccessGreen,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mở xem ngay", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                OutlinedButton(
                    onClick = onResetMerge,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PdfSuccessGreen)
                ) {
                    Text("Ghép bộ khác", color = Color(0xFF15803D))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitPdfSection(
    viewModel: PdfToolsViewModel,
    onPickLocalPdf: () -> Unit,
    onOpenLibraryPicker: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    // Kết quả sau khi tách thành công
    val splitRes = viewModel.splitResult
    if (splitRes != null) {
        SplitSuccessCard(
            result = splitRes,
            onOpenArticle = onOpenArticle,
            onDismissResult = { viewModel.clearSplitResult() }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Chọn tệp nguồn để tách
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Tệp PDF nguồn cần tách",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            val src = viewModel.splitSource
            if (src == null) {
                Text(
                    text = "Chọn 1 tệp PDF từ máy hoặc từ Thư viện để xem tổng số trang và cấu hình cách tách.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPickLocalPdf,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PdfAccentBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Chọn từ máy", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onOpenLibraryPicker,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, PdfAccentBlue),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = PdfAccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Từ Thư viện",
                            color = PdfAccentBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFF0F9FF),
                    border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFEE2E2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0C4A6E),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            if (viewModel.isInspectingSplitSource) {
                                Text(
                                    text = "Đang kiểm tra số trang...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PdfAccentBlue
                                )
                            } else {
                                val metaParts = buildList {
                                    add(if (src.isFromLibrary) "Thư viện" else "Trên máy")
                                    src.pageCount?.let { add("$it trang") }
                                    src.fileSizeFormatted?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                                Text(
                                    text = metaParts.joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF0369A1)
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.clearSplitSource() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Đổi tệp khác",
                                tint = Color(0xFF0369A1)
                            )
                        }
                    }
                }
            }
        }
    }

    val src = viewModel.splitSource
    if (src != null) {
        Spacer(modifier = Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Chế độ tách trang",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = viewModel.splitMode == "ranges",
                        onClick = { viewModel.splitMode = "ranges" },
                        label = { Text("Theo khoảng trang") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PdfAccentBlue.copy(alpha = 0.14f),
                            selectedLabelColor = PdfAccentBlue
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = viewModel.splitMode == "every_n",
                        onClick = { viewModel.splitMode = "every_n" },
                        label = { Text("Chia đều mỗi N trang") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PdfAccentBlue.copy(alpha = 0.14f),
                            selectedLabelColor = PdfAccentBlue
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.splitMode == "ranges") {
                    val rangeError = PdfSplitHelper.validateRangesInput(
                        ranges = viewModel.splitRangesInput,
                        totalPages = src.pageCount,
                        mergeRanges = viewModel.splitMergeRanges
                    )

                    OutlinedTextField(
                        value = viewModel.splitRangesInput,
                        onValueChange = { viewModel.splitRangesInput = it },
                        label = { Text("Khoảng trang cần tách") },
                        placeholder = { Text("Ví dụ: 1-3, 5, 7-9") },
                        isError = rangeError != null,
                        supportingText = {
                            if (rangeError != null) {
                                Text(text = rangeError)
                            } else {
                                Text(
                                    text = if (viewModel.splitMergeRanges) {
                                        "Các khoảng được chọn sẽ ghép thành 1 tệp PDF duy nhất."
                                    } else {
                                        "Mỗi nhóm phân cách bởi dấu phẩy sẽ tạo ra 1 tệp PDF riêng."
                                    }
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val pageCount = src.pageCount
                    if (pageCount != null && pageCount >= 2) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val mid = (pageCount / 2).coerceAtLeast(1)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AssistChip(
                                onClick = { viewModel.splitRangesInput = "1" },
                                label = { Text("Trang 1") }
                            )
                            AssistChip(
                                onClick = { viewModel.splitRangesInput = "1-$mid" },
                                label = { Text("Nửa đầu (1-$mid)") }
                            )
                            AssistChip(
                                onClick = { viewModel.splitRangesInput = "${mid + 1}-$pageCount" },
                                label = { Text("Nửa sau (${mid + 1}-$pageCount)") }
                            )
                            AssistChip(
                                onClick = { viewModel.splitRangesInput = "1-$mid, ${mid + 1}-$pageCount" },
                                label = { Text("Chia đôi 2 phần") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gộp các trang đã chọn thành 1 tệp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tắt: xuất nhiều tệp riêng lẻ • Bật: trích thành 1 tệp",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = viewModel.splitMergeRanges,
                                onCheckedChange = { viewModel.splitMergeRanges = it }
                            )
                        }
                    }
                } else {
                    val estimatedCount = PdfSplitHelper.estimateEveryNSplitCount(
                        totalPages = src.pageCount,
                        pagesPerSplitInput = viewModel.splitPagesPerFileInput
                    )
                    val perSplitInt = viewModel.splitPagesPerFileInput.trim().toIntOrNull()
                    val everyNError = when {
                        perSplitInt == null || perSplitInt < 1 -> "Nhập số nguyên lớn hơn hoặc bằng 1."
                        estimatedCount != null && estimatedCount > PdfSplitHelper.MAX_SPLIT_OUTPUTS ->
                            "Tạo ra $estimatedCount tệp, vượt quá giới hạn ${PdfSplitHelper.MAX_SPLIT_OUTPUTS} tệp/lần."
                        else -> null
                    }

                    OutlinedTextField(
                        value = viewModel.splitPagesPerFileInput,
                        onValueChange = { input ->
                            viewModel.splitPagesPerFileInput = input.filter { it.isDigit() }
                        },
                        label = { Text("Số trang mỗi tệp kết quả") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = everyNError != null,
                        supportingText = {
                            if (everyNError != null) {
                                Text(text = everyNError)
                            } else if (estimatedCount != null) {
                                Text(text = "Dự kiến tạo ra $estimatedCount tệp PDF (${src.pageCount} trang gốc).")
                            } else {
                                Text(text = "Tối đa ${PdfSplitHelper.MAX_SPLIT_OUTPUTS} tệp cho mỗi lần tách.")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 5, 10).forEach { preset ->
                            AssistChip(
                                onClick = { viewModel.splitPagesPerFileInput = preset.toString() },
                                label = { Text("$preset trang/tệp") }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = viewModel.splitTitlePrefix,
                    onValueChange = { viewModel.splitTitlePrefix = it },
                    label = { Text("Tiền tố tên tệp đầu ra") },
                    placeholder = { Text("Ví dụ: Tai_lieu_tach") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                CategoryPickerRow(
                    categories = viewModel.categories,
                    selectedCategoryId = viewModel.selectedCategoryId,
                    onSelectCategory = { viewModel.selectedCategoryId = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                val splitValidationError = viewModel.validateSplitInput()
                Button(
                    onClick = { viewModel.executeSplit() },
                    enabled = !viewModel.isSplitting &&
                        !viewModel.isInspectingSplitSource &&
                        splitValidationError == null,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PdfAccentIndigo,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tách file PDF ngay", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SplitSuccessCard(
    result: SplitPdfResponse,
    onOpenArticle: (Int) -> Unit,
    onDismissResult: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
        border = BorderStroke(1.dp, Color(0xFF86EFAC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PdfSuccessGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tách PDF thành công!",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF14532D)
                    )
                }
                TextButton(onClick = onDismissResult) {
                    Text("Ẩn", color = Color(0xFF15803D))
                }
            }
            Text(
                text = "Đã tạo ${result.createdCount} tệp PDF từ ${result.sourcePageCount} trang gốc và lưu vào Thư viện.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF166534)
            )
            Spacer(modifier = Modifier.height(10.dp))

            result.articles.forEachIndexed { idx, article ->
                if (idx > 0) {
                    HorizontalDivider(
                        color = Color(0xFFBBF7D0),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = article.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF14532D),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val desc = listOfNotNull(
                            article.fileName,
                            article.excerpt?.takeIf { it.isNotBlank() }
                        ).joinToString(" • ")
                        if (desc.isNotEmpty()) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF166534),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onOpenArticle(article.id) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PdfSuccessGreen,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Mở", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerRow(
    categories: List<Category>,
    selectedCategoryId: Int?,
    onSelectCategory: (Int?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Lưu vào danh mục",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (categories.isEmpty()) {
            Text(
                text = "Sẽ lưu vào danh mục mặc định",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategoryId == cat.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCategory(cat.id) },
                        label = {
                            Text(
                                text = cat.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PdfAccentBlue.copy(alpha = 0.14f),
                            selectedLabelColor = PdfAccentBlue
                        )
                    )
                }
            }
        }
    }
}

