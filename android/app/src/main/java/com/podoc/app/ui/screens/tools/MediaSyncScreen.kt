package com.podoc.app.ui.screens.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.podoc.app.service.sync.LocalMediaScanner
import com.podoc.app.service.sync.TodayMediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSyncScreen(
    viewModel: MediaSyncViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Launcher xin quyền đọc MediaStore
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            viewModel.loadSettingsAndStatus()
        }
    }

    // Tự động kiểm tra lại mạng và quyền mỗi khi màn hình hiển thị
    LaunchedEffect(Unit) {
        viewModel.loadSettingsAndStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Đồng bộ Ảnh & Video",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tự động lúc 23:59 • Chỉ qua Wi-Fi",
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
                actions = {
                    IconButton(
                        onClick = { viewModel.loadSettingsAndStatus() },
                        enabled = !uiState.isSyncing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Quét lại"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {

            // 1. Cảnh báo quyền nếu chưa cấp
            if (!uiState.hasPermissions) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Yêu cầu quyền truy cập Ảnh & Video",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF92400E)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "PoDoc cần quyền đọc thư viện để quét ảnh và video chụp trong ngày hôm nay.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFB45309)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(LocalMediaScanner.getRequiredPermissions())
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Cấp quyền ngay", fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Card Thiết lập Tự động 23:59
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF6366F1).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = Color(0xFF6366F1),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Đồng bộ lúc 23:59 mỗi ngày",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tự động phân loại vào 'Hình ảnh' & 'Video'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.isAutoSyncEnabled,
                                onCheckedChange = { viewModel.toggleAutoSync(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Huy hiệu quy tắc Wi-Fi
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0284C7).copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = null,
                                        tint = Color(0xFF0284C7),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Chỉ đồng bộ khi bắt Wi-Fi (Chặn 3G/4G/5G)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0284C7)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Lần đồng bộ gần nhất:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = uiState.lastSyncTimeText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (uiState.lastSyncSummary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Kết quả: ${uiState.lastSyncSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.lastSyncStatus == "Thành công") Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 3. Card Trạng thái mạng hiện tại
            item {
                val netColor = if (uiState.isWifi) Color(0xFF16A34A) else Color(0xFFEA580C)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = netColor.copy(alpha = 0.08f)
                    ),
                    border = BorderStroke(1.dp, netColor.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(netColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (uiState.isWifi) Icons.Default.Wifi else Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = netColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.isWifi) "Kết nối Wi-Fi hợp lệ" else "Không dùng Wi-Fi",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = netColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.networkDescription,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Khung hành động "Đồng bộ ngay" (Sync Now)
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Kiểm tra & Đồng bộ tức thì",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Bấm nút dưới đây để quét và đưa ngay các ảnh/video hôm nay lên PoDoc để kiểm tra trước khi chờ đến 23:59.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tiến trình đồng bộ (nếu đang chạy)
                        if (uiState.isSyncing) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                LinearProgressIndicator(
                                    progress = { uiState.syncProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF6366F1),
                                    trackColor = Color(0xFF6366F1).copy(alpha = 0.2f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = uiState.syncProgressText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF6366F1)
                                    )
                                    Text(
                                        text = uiState.currentFileName,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = { viewModel.syncNow() },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Đồng bộ ngay",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 5. Danh sách Ảnh & Video hôm nay
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Ảnh & Video hôm nay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val total = uiState.todayItems.size
                        val imageCount = uiState.todayItems.count { !it.isVideo }
                        val videoCount = uiState.todayItems.count { it.isVideo }
                        Text(
                            text = "Tìm thấy $total mục ($imageCount ảnh, $videoCount video)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (uiState.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (uiState.todayItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (!uiState.hasPermissions) "Chưa cấp quyền đọc thư viện ảnh" else "Hôm nay chưa có ảnh hoặc video mới",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Hiển thị lưới các ảnh & video phát hiện hôm nay kèm trạng thái
                item {
                    val items = uiState.todayItems
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (item in rowItems) {
                                    val isSynced = uiState.syncedKeys.contains(item.syncKey)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(item.uri)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = item.displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Badge loại (Ảnh/Video) ở góc trên bên trái
                                        Box(
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .align(Alignment.TopStart)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Black.copy(alpha = 0.6f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = if (item.isVideo) "Video" else "Ảnh",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // Badge trạng thái đồng bộ ở góc dưới bên phải
                                        Box(
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .align(Alignment.BottomEnd)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isSynced) Color(0xFF16A34A).copy(alpha = 0.85f)
                                                    else Color(0xFFEA580C).copy(alpha = 0.85f)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (isSynced) Icons.Default.Check else Icons.Default.CloudUpload,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = if (isSynced) "Đã đồng bộ" else "Chưa",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                // Đệm trống nếu hàng cuối ít hơn 3 phần tử
                                for (i in 0 until (3 - rowItems.size)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog cảnh báo (ví dụ đang dùng mạng 3G/4G/5G hoặc thiếu quyền)
    if (uiState.alertMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAlert() },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFEA580C),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Thông báo đồng bộ",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = uiState.alertMessage ?: "")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                ) {
                    Text("Đã hiểu")
                }
            }
        )
    }

    // Dialog thông báo thành công
    if (uiState.successToast != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSuccessToast() },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF16A34A),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Đồng bộ thành công!",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = uiState.successToast ?: "")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSuccessToast() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Text("Tuyệt vời")
                }
            }
        )
    }
}
