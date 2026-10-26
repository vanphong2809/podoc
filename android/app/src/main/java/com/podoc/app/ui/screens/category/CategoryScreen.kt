package com.podoc.app.ui.screens.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podoc.app.R
import com.podoc.app.data.model.Category
import com.podoc.app.ui.components.PoDocTopAppBar
import com.podoc.app.ui.theme.AccentColors
import com.podoc.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    viewModel: CategoryViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var deletingCategory by remember { mutableStateOf<Category?>(null) }

    var categoryNameInput by remember { mutableStateOf("") }
    var categoryColorInput by remember { mutableStateOf("#4F46E5") }

    Scaffold(
        topBar = {
            PoDocTopAppBar(
                title = stringResource(R.string.categories_management),
                actions = {
                    IconButton(onClick = {
                        categoryNameInput = ""
                        categoryColorInput = "#4F46E5"
                        showCreateDialog = true
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Thêm danh mục")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(uiState.categories, key = { it.id }) { cat ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Vòng tròn màu danh mục
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(cat.color))
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (cat.isSystem) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "Hệ thống",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.article_count_format, cat.articleCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Thao tác sửa / xoá (nếu không phải danh mục hệ thống)
                        IconButton(onClick = {
                            editingCategory = cat
                            categoryNameInput = cat.name
                            categoryColorInput = cat.color
                        }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Sửa", modifier = Modifier.size(20.dp))
                        }

                        if (!cat.isSystem) {
                            IconButton(onClick = { deletingCategory = cat }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Xoá",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog Tạo / Sửa Danh Mục
    if (showCreateDialog || editingCategory != null) {
        val isEditing = editingCategory != null
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editingCategory = null
            },
            title = {
                Text(if (isEditing) stringResource(R.string.edit_category_title) else stringResource(R.string.create_category_title))
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = categoryNameInput,
                        onValueChange = { categoryNameInput = it },
                        label = { Text(stringResource(R.string.category_name_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.category_color_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AccentColors.forEach { colorHex ->
                            val selected = categoryColorInput.equals(colorHex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(colorHex))
                                    .clickable { categoryColorInput = colorHex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.surface,
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
                    }
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
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xoá danh mục '${deletingCategory!!.name}'?") },
            text = {
                Text(stringResource(R.string.delete_category_confirm))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCategory(deletingCategory!!.id)
                        deletingCategory = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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
