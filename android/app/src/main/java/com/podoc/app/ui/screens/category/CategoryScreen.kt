package com.podoc.app.ui.screens.category

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podoc.app.R
import com.podoc.app.data.model.Category
import com.podoc.app.ui.theme.AccentColors
import com.podoc.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    viewModel: CategoryViewModel,
    themeMode: String = "system",
    onCategoryClick: (Category) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> MaterialTheme.colorScheme.surface.luminance() < 0.5f || systemDark
    }
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF4F6FB)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surface else Color.White
    val brandBlue = if (isDark) Color(0xFF60A5FA) else Color(0xFF0B57D0)

    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var deletingCategory by remember { mutableStateOf<Category?>(null) }

    var categoryNameInput by remember { mutableStateOf("") }
    var categoryColorInput by remember { mutableStateOf("#4F46E5") }

    val filteredCategories = remember(uiState.categories, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.categories
        } else {
            uiState.categories.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp)
        ) {
            // Thanh Tìm kiếm bo tròn chuẩn theo thiết kế hình 1
            Surface(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF374151) else Color(0xFFE5E7EB)),
                color = cardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(46.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Tìm kiếm danh mục...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF9CA3AF)
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 0.dp)
                    )

                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Tìm kiếm",
                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF6B7280),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Hàng Tiêu đề thu gọn sát lề: "Quản lý danh mục" + nút thêm (+)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.categories_management),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        categoryNameInput = ""
                        categoryColorInput = "#4F46E5"
                        showCreateDialog = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Thêm danh mục",
                        tint = brandBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Danh sách các Card danh mục bo tròn đẹp mắt
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredCategories, key = { it.id }) { cat ->
                    var showItemMenu by remember { mutableStateOf(false) }

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 0.5.dp),
                        border = if (isDark) BorderStroke(1.dp, Color(0xFF334155)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onCategoryClick(cat) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Huy hiệu hình tròn có icon và màu tương ứng theo thiết kế hình 1
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(cat.color)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getCategoryIcon(cat.name, cat.icon),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // Tên danh mục và số lượng bài viết
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                val count = cat.articleCount ?: 0
                                Text(
                                    text = "$count mục",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp,
                                        fontWeight = if (count > 0) FontWeight.Medium else FontWeight.Normal
                                    ),
                                    color = if (count > 0) (if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)) else (if (isDark) Color(0xFF94A3B8) else Color(0xFF9CA3AF))
                                )
                            }

                            // Menu tuỳ chọn 3 chấm dọc
                            Box {
                                IconButton(
                                    onClick = { showItemMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Tuỳ chọn",
                                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF9CA3AF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_edit_category)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                             showItemMenu = false
                                             editingCategory = cat
                                             categoryNameInput = cat.name
                                             categoryColorInput = cat.color
                                        }
                                    )
                                    if (!cat.isSystem) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.action_delete_category),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                showItemMenu = false
                                                deletingCategory = cat
                                            }
                                        )
                                    }
                                }
                            }

                            // Mũi tên chỉ dẫn bấm vào
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Xem danh mục",
                                tint = if (isDark) Color(0xFF64748B) else Color(0xFFD1D5DB),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog: Tạo / Sửa Danh Mục với Live Preview
    val isEditing = editingCategory != null
    if (showCreateDialog || isEditing) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editingCategory = null
            },
            title = {
                Text(
                    text = if (isEditing) stringResource(R.string.edit_category_title) else stringResource(R.string.create_category_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Xem trước màu sắc và icon thực tế của danh mục
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = parseHexColor(categoryColorInput).copy(alpha = if (isDark) 0.22f else 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(categoryColorInput)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getCategoryIcon(categoryNameInput, null),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = categoryNameInput.ifBlank { "Tên danh mục" },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isDark) Color(0xFFF1F5F9) else parseHexColor(categoryColorInput)
                                )
                                Text(
                                    text = "0 mục",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDark) Color(0xFF94A3B8) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = categoryNameInput,
                        onValueChange = { categoryNameInput = it },
                        label = { Text(stringResource(R.string.category_name_label)) },
                        placeholder = { Text("Ví dụ: Công nghệ, Mua sắm...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Chọn màu đại diện:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bảng màu 6 màu hiện đại
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AccentColors.forEach { colorHex ->
                            val isSelected = categoryColorInput.equals(colorHex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(colorHex))
                                    .clickable { categoryColorInput = colorHex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (categoryNameInput.isNotBlank()) {
                            if (isEditing) {
                                viewModel.updateCategory(
                                    id = editingCategory!!.id,
                                    name = categoryNameInput.trim(),
                                    color = categoryColorInput,
                                    icon = null,
                                    sortOrder = null
                                )
                            } else {
                                viewModel.createCategory(
                                    name = categoryNameInput.trim(),
                                    color = categoryColorInput,
                                    icon = "folder",
                                    sortOrder = 0
                                )
                            }
                            showCreateDialog = false
                            editingCategory = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    editingCategory = null
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Dialog Xác nhận Xoá Danh Mục
    if (deletingCategory != null) {
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.action_delete_category)) },
            text = {
                Text(stringResource(R.string.delete_category_confirm))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCategory(deletingCategory!!.id)
                        deletingCategory = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCategory = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * Ánh xạ icon phù hợp cho danh mục chuẩn theo thiết kế hình ảnh 1
 */
private fun getCategoryIcon(name: String, iconStr: String?): ImageVector {
    val lower = (iconStr ?: name).lowercase()
    return when {
        lower.contains("mua") || lower.contains("shop") || lower.contains("sắm") -> Icons.Default.ShoppingBag
        lower.contains("công nghệ") || lower.contains("tech") || lower.contains("laptop") || lower.contains("máy") -> Icons.Default.Laptop
        lower.contains("sức khoẻ") || lower.contains("khoẻ") || lower.contains("health") || lower.contains("y tế") -> Icons.Default.Favorite
        lower.contains("tài chính") || lower.contains("tiền") || lower.contains("finance") || lower.contains("đầu tư") -> Icons.Default.TrendingUp
        lower.contains("học") || lower.contains("study") || lower.contains("school") || lower.contains("giáo dục") -> Icons.Default.School
        lower.contains("việc") || lower.contains("work") || lower.contains("công việc") || lower.contains("career") -> Icons.Default.Work
        lower.contains("giải trí") || lower.contains("game") || lower.contains("chơi") || lower.contains("play") -> Icons.Default.SportsEsports
        lower.contains("sách") || lower.contains("book") || lower.contains("đọc") -> Icons.Default.MenuBook
        else -> Icons.Default.Folder
    }
}
