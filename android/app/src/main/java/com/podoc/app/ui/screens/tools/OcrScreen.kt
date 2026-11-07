package com.podoc.app.ui.screens.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.podoc.app.ui.screens.tools.ocr.RoiCropDialog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    viewModel: OcrViewModel,
    onNavigateBack: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    val context = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveAsStructured by remember { mutableStateOf(false) }
    var showCorrectionsDialog by remember { mutableStateOf(false) }
    var tempApiKey by remember { mutableStateOf(viewModel.geminiApiKey) }

    LaunchedEffect(viewModel.geminiApiKey) {
        tempApiKey = viewModel.geminiApiKey
    }

    // Launcher chọn ảnh từ bộ nhớ (Gallery / Files)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.processImageUri(context, uri)
        }
    }

    // Launcher chụp ảnh từ Camera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            viewModel.processBitmap(bitmap)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OCR & Trích xuất văn bản",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Số hóa tài liệu & bóc tách dữ liệu",
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
                    // Nút cấu hình API Key
                    IconButton(onClick = { viewModel.showApiKeyDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Cấu hình AI Key",
                            tint = if (viewModel.geminiApiKey.isNotBlank()) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (viewModel.activeBitmap != null || viewModel.originalBitmap != null || viewModel.recognizedText.isNotBlank()) {
                        IconButton(onClick = { viewModel.clearAll() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Làm mới"
                            )
                        }
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

            // Khu vực chọn/chụp ảnh ban đầu (theo redesign 05_ocr_screen_main_redesign.png)
            if (viewModel.activeBitmap == null && viewModel.originalBitmap == null && viewModel.imageUri == null) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Đồ họa minh họa 3D tài liệu kính mờ và biểu tượng [ T ]
                        Box(
                            modifier = Modifier
                                .size(110.dp, 90.dp)
                                .padding(bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Tấm kính mờ phía sau
                            Box(
                                modifier = Modifier
                                    .size(76.dp, 76.dp)
                                    .offset(x = 16.dp, y = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF818CF8).copy(alpha = 0.25f))
                            )
                            // Trang tài liệu ở giữa có dòng chữ tượng trưng
                            Box(
                                modifier = Modifier
                                    .size(76.dp, 76.dp)
                                    .offset(x = (-8).dp, y = (-4).dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFEEF2FF))
                                    .border(1.dp, Color(0xFFC7D2FE), RoundedCornerShape(16.dp))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Box(modifier = Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Color(0xFF818CF8).copy(alpha = 0.5f)))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(modifier = Modifier.width(26.dp).height(4.dp).clip(CircleShape).background(Color(0xFF818CF8).copy(alpha = 0.3f)))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(modifier = Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(Color(0xFF818CF8).copy(alpha = 0.4f)))
                                }
                            }
                            // Khung quét nhận dạng với chữ [ T ] nổi bật ở trung tâm
                            Box(
                                modifier = Modifier
                                    .size(54.dp, 54.dp)
                                    .offset(x = 6.dp, y = 2.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFEEF2FF).copy(alpha = 0.95f))
                                    .border(2.dp, Color(0xFF4F46E5), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "T",
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF4F46E5)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Chọn hình ảnh cần nhận dạng",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Chụp hoặc chọn ảnh tài liệu, giấy tờ, hóa đơn để quét và số hóa nội dung.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 2 nút xếp dọc theo đúng Mockup 05: đầy đủ chiều ngang, không bao giờ bị cắt chữ
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { cameraLauncher.launch(null) },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4F46E5),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Chụp ảnh", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
                            }

                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.2.dp, Color(0xFFCBD5E1)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = Color(0xFF4F46E5),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Chọn từ máy",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Hộp 3 tính năng bảo mật / tốc độ / ngoại tuyến theo đúng Mockup 05
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shadowElevation = 0.5.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Bảo mật\ntrên thiết bị", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Nhanh &\nchính xác", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.WifiOff, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hoạt động\nngoại tuyến", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Card xem trước hình ảnh và thanh công cụ xử lý ảnh
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Badge thông báo nếu đang áp dụng ROI Crop
                        if (viewModel.isRoiActive) {
                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Crop,
                                            contentDescription = null,
                                            tint = Color(0xFF0097A7),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Vùng quét tùy chọn (ROI)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF00838F)
                                        )
                                    }

                                    TextButton(
                                        onClick = { viewModel.resetRoi() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Bỏ chọn vùng", fontSize = 11.sp, color = Color(0xFF00838F))
                                    }
                                }
                            }
                        }

                        // Khung xem trước ảnh
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (viewModel.activeBitmap != null) {
                                Image(
                                    bitmap = viewModel.activeBitmap!!.asImageBitmap(),
                                    contentDescription = "Ảnh đang quét",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (viewModel.imageUri != null) {
                                AsyncImage(
                                    model = viewModel.imageUri,
                                    contentDescription = "Ảnh từ máy",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bộ nút tác vụ trên ảnh: Cắt vùng ROI, Đổi ảnh, Bộ lọc nét (theo đúng Mockup 08)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.openCropDialog() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEEF2FF),
                                    contentColor = Color(0xFF4338CA)
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Khoanh vùng", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }

                            Button(
                                onClick = { viewModel.toggleEnhanceContrast() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (viewModel.isEnhanceContrastEnabled) Color(0xFF4F46E5) else Color(0xFFEEF2FF),
                                    contentColor = if (viewModel.isEnhanceContrastEnabled) Color.White else Color(0xFF4338CA)
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (viewModel.isEnhanceContrastEnabled) Icons.Default.Contrast else Icons.Default.FilterNone,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Tăng nét", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (viewModel.originalBitmap != null) {
                                        cameraLauncher.launch(null)
                                    } else {
                                        imagePickerLauncher.launch("image/*")
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null, tint = Color(0xFF4338CA), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Đổi ảnh", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4338CA), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // Trạng thái đang nhận dạng văn bản (Gemini 3.8 Flash Vision hoặc ML Kit)
            if (viewModel.isRecognizing) {
                val isGemini = viewModel.isReadingWithGemini
                Surface(
                    color = Color(0xFF7C3AED).copy(alpha = 0.08f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF7C3AED).copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF7C3AED),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = if (isGemini) "Đang đọc văn bản với AI Vision..." else "Đang nhận dạng văn bản...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7C3AED)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isGemini) "Phân tích và trích xuất chi tiết từ hình ảnh"
                                else "Sử dụng công cụ nhận dạng trên thiết bị",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // KHI ĐÃ CÓ VĂN BẢN OCR
            if (viewModel.recognizedText.isNotBlank()) {

                // KHỐI CHỌN LOẠI TÀI LIỆU VÀ NÚT GỌI AI BÓC TÁCH KIE
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFEEF2FF)
                    ),
                    border = BorderStroke(1.2.dp, Color(0xFFC7D2FE)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TRÍCH XUẤT DỮ LIỆU CẤU TRÚC (KIE)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4338CA)
                            )

                            Text(
                                text = if (viewModel.geminiApiKey.isNotBlank()) "Gemini 3.8 Flash" else "Chế độ ngoại tuyến",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (viewModel.geminiApiKey.isNotBlank()) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Chọn loại tài liệu (Chung, CCCD, Hóa đơn, Danh thiếp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "general" to "Văn bản chung",
                                "id_card" to "Căn cước",
                                "invoice" to "Hóa đơn",
                                "business_card" to "Danh thiếp"
                            ).forEach { (typeKey, label) ->
                                val selected = viewModel.selectedDocType == typeKey
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setDocType(typeKey) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF4F46E5),
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.height(34.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Nút kích hoạt AI Trích xuất
                        Button(
                            onClick = { viewModel.extractStructuredWithAi() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4F46E5),
                                contentColor = Color.White
                            ),
                            enabled = !viewModel.isExtractingAi,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            if (viewModel.isExtractingAi) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Đang phân tích & trích xuất dữ liệu...", fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Trích xuất thông tin chi tiết", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // TAB CHUYỂN ĐỔI: [Văn bản] vs [Thông tin chi tiết]
                TabRow(
                    selectedTabIndex = viewModel.activeTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[viewModel.activeTab]),
                            color = Color(0xFF4F46E5)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .padding(bottom = 12.dp)
                ) {
                    Tab(
                        selected = viewModel.activeTab == 0,
                        onClick = { viewModel.switchTab(0) },
                        text = {
                            Text(
                                "Văn bản (${viewModel.recognizedText.lines().filter { it.isNotBlank() }.size} dòng)",
                                fontWeight = if (viewModel.activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (viewModel.activeTab == 0) Color(0xFF4F46E5) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    Tab(
                        selected = viewModel.activeTab == 1,
                        onClick = { viewModel.switchTab(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Dữ liệu cấu trúc",
                                    fontWeight = if (viewModel.activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (viewModel.activeTab == 1) Color(0xFF4F46E5) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (viewModel.structuredResult != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    )
                }

                // NỘI DUNG THEO TAB ĐANG CHỌN
                if (viewModel.activeTab == 0) {
                    // TAB 0: VĂN BẢN SẠCH & SỬA LỖI HEURISTIC
                    val lines = viewModel.recognizedText.lines().filter { it.isNotBlank() }.size

                    // Tiêu đề khu vực văn bản và Badge số dòng
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nội dung văn bản đã nhận dạng",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Surface(
                            color = Color(0xFFEEF2FF),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "$lines dòng",
                                color = Color(0xFF4338CA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Huy hiệu thông báo nguồn nhận dạng (Gemini 3.8 Flash hoặc ML Kit)
                    if (viewModel.readerNotice != null) {
                        Surface(
                            color = Color(0xFFEEF2FF),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFC7D2FE)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF4F46E5),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = viewModel.readerNotice ?: "",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF4338CA)
                                )
                            }
                        }
                    }

                    // Thẻ thông báo AI / Heuristic đã sửa lỗi
                    if (viewModel.corrections.isNotEmpty()) {
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clickable { showCorrectionsDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoFixHigh,
                                        contentDescription = null,
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Đã chuẩn hóa ${viewModel.corrections.size} lỗi chính tả & định dạng",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF047857)
                                    )
                                }
                                Text(
                                    text = "Chi tiết >",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF047857)
                                )
                            }
                        }
                    }

                    // Chuyển đổi qua lại giữa Văn bản chuẩn hóa và Văn bản gốc
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = viewModel.isAutoCorrectionEnabled,
                                onCheckedChange = { viewModel.toggleAutoCorrection() },
                                modifier = Modifier.scale(0.8f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Tự động chuẩn hóa lỗi",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        TextButton(
                            onClick = { viewModel.toggleShowingCleaned() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = if (viewModel.isShowingCleaned) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (viewModel.isShowingCleaned) "Xem bản gốc" else "Xem bản chuẩn hóa",
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Hộp chỉnh sửa / xem văn bản
                    OutlinedTextField(
                        value = viewModel.recognizedText,
                        onValueChange = { viewModel.updateRecognizedText(it) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 340.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Các nút tác vụ nhanh cho văn bản thuần (theo redesign 09_ocr_actions_screen_redesign.png)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("OCR text", viewModel.recognizedText)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Đã sao chép văn bản", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sao", maxLines = 1, softWrap = false, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, viewModel.recognizedText)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ văn bản OCR"))
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Chia sẻ", maxLines = 1, softWrap = false, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                saveAsStructured = false
                                showSaveDialog = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4F46E5),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Lưu thư viện", maxLines = 1, softWrap = false, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                } else {
                    // TAB 1: DỮ LIỆU BÓC TÁCH CÓ CẤU TRÚC (STRUCTURED KIE CARDS)
                    val result = viewModel.structuredResult
                    if (result == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEEF2FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = Color(0xFF4F46E5),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Chưa có dữ liệu cấu trúc",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Bấm nút 'Trích xuất thông tin chi tiết' phía trên để hệ thống tự động phân loại và bóc tách các trường thông tin quan trọng.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // Thẻ kết quả Validation
                        Surface(
                            color = if (result.validation.isValid) Color(0xFFECFDF5) else Color(0xFFFFFBEB),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (result.validation.isValid) Color(0xFFA7F3D0) else Color(0xFFFDE68A)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (result.validation.isValid) Icons.Default.Verified else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (result.validation.isValid) Color(0xFF059669) else Color(0xFFD97706),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (result.validation.isValid) "Dữ liệu hợp lệ theo quy chuẩn" else "Cần kiểm tra lại một số trường",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            color = if (result.validation.isValid) Color(0xFF047857) else Color(0xFFB45309)
                                        )
                                        if (result.validation.warnings.isNotEmpty()) {
                                            Text(
                                                text = result.validation.warnings.first(),
                                                fontSize = 11.sp,
                                                color = Color(0xFFB45309)
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    color = Color.White.copy(alpha = 0.9f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${(result.validation.score * 100).toInt()}% tin cậy",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        color = if (result.validation.isValid) Color(0xFF047857) else Color(0xFFB45309),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // Danh sách các thẻ thông tin bóc tách được (Key-Value)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            result.structuredData.forEach { (key, jsonElem) ->
                                val valueStr = jsonElem.jsonPrimitive.contentOrNull ?: jsonElem.toString().replace("\"", "")
                                if (valueStr.isNotBlank() && valueStr != "null") {
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = mapFieldKeyToLabel(key),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4F46E5),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = valueStr,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                    val clip = ClipData.newPlainText(key, valueStr)
                                                    clipboard?.setPrimaryClip(clip)
                                                    Toast.makeText(context, "Đã sao chép $valueStr", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Sao chép trường này",
                                                    tint = Color(0xFF64748B),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Ghi chú sửa lỗi của AI
                        if (result.confidenceNotes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Ghi chú chuẩn hóa dữ liệu:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    result.confidenceNotes.forEach { note ->
                                        Text(
                                            text = "• $note",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Các nút hành động trên Dữ liệu bóc tách
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("JSON", result.structuredData.toString())
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Đã sao chép JSON vào bộ nhớ tạm", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sao chép JSON", maxLines = 1, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = {
                                    saveAsStructured = true
                                    showSaveDialog = true
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4F46E5),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(46.dp)
                            ) {
                                Icon(imageVector = Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lưu vào Thư viện", maxLines = 1, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dialog khoanh vùng cắt ROI Interactive
    if (viewModel.showCropDialog && viewModel.originalBitmap != null) {
        RoiCropDialog(
            bitmap = viewModel.originalBitmap!!,
            onDismiss = { viewModel.closeCropDialog() },
            onConfirmCrop = { rect ->
                viewModel.applyRoiCrop(rect)
            }
        )
    }

    // Dialog cấu hình Google Gemini API Key (theo redesign 06_ocr_api_key_dialog_redesign.png)
    if (viewModel.showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showApiKeyDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEEF2FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = Color(0xFF4F46E5),
                        modifier = Modifier.size(26.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Cấu hình Gemini API Key",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        text = "Nhập khóa API cá nhân của bạn để sử dụng các tính năng nâng cao (KIE, trích xuất cấu trúc):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = {
                            tempApiKey = it
                            viewModel.clearApiKeyTestResult()
                        },
                        placeholder = { Text("AIzaSy...") },
                        leadingIcon = {
                            Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF4F46E5))
                        },
                        trailingIcon = {
                            if (tempApiKey.isNotEmpty()) {
                                IconButton(onClick = { tempApiKey = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Xóa")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testGeminiApiKey(tempApiKey) },
                            enabled = !viewModel.isTestingApiKey && tempApiKey.isNotBlank(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (viewModel.isTestingApiKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Đang kiểm tra...", fontSize = 12.sp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CheckCircleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Kiểm tra kết nối", fontSize = 12.sp)
                            }
                        }
                    }
                    if (viewModel.apiKeyTestResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val isOk = viewModel.apiKeyTestResult?.startsWith("✅") == true
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isOk) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = viewModel.apiKeyTestResult ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = if (isOk) Color(0xFF047857) else Color(0xFFB91C1C),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFFEEF2FF),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Khóa API được lưu cục bộ an toàn trên thiết bị của bạn.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.5.sp,
                                color = Color(0xFF4338CA)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveGeminiApiKey(tempApiKey) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4F46E5),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lưu API Key", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showApiKeyDialog = false }) {
                    Text("Đóng")
                }
            }
        )
    }

    // Dialog xem chi tiết các điểm đã sửa lỗi chính tả Heuristic
    if (showCorrectionsDialog) {
        AlertDialog(
            onDismissRequest = { showCorrectionsDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = Color(0xFF059669),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Chi tiết sửa lỗi chính tả (${viewModel.corrections.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Hệ thống Heuristic & Fuzzy Matching đã phân tích ngữ cảnh để sửa các lỗi nhầm lẫn OCR:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    viewModel.corrections.forEachIndexed { index, item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "${index + 1}. [${item.category}]",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF059669)
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.original,
                                        fontSize = 13.sp,
                                        color = Color.Red,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "➔", fontSize = 12.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item.corrected,
                                        fontSize = 13.sp,
                                        color = Color(0xFF059669),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.reason,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCorrectionsDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Đóng")
                }
            }
        )
    }

    // Dialog xác nhận lưu vào Thư viện PoDoc
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = {
                Text(
                    text = if (saveAsStructured) "Lưu bảng dữ liệu" else "Lưu văn bản",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (saveAsStructured)
                            "Dữ liệu bóc tách sẽ được lưu dưới dạng bài viết có Bảng Markdown trực quan để đọc và nghe giọng nói."
                        else
                            "Văn bản trích xuất sẽ được lưu dưới dạng bài viết Markdown để bạn có thể đọc và nghe giọng nói.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Tiêu đề bài viết:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = viewModel.documentTitle,
                        onValueChange = { viewModel.setDocTitle(it) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Chọn danh mục:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        viewModel.categories.forEach { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = viewModel.selectedCategoryId == cat.id,
                                    onClick = { viewModel.setSelectedCategory(cat.id) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${cat.icon} ${cat.name}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        viewModel.saveToLibrary(useStructuredTable = saveAsStructured) { article ->
                            Toast.makeText(context, "Đã lưu tài liệu thành công!", Toast.LENGTH_SHORT).show()
                            onOpenArticle(article.id)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                    enabled = !viewModel.isSaving
                ) {
                    if (viewModel.isSaving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Lưu & Mở đọc", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Huỷ")
                }
            }
        )
    }

    // Thông báo lỗi nhận dạng nếu có
    if (viewModel.errorMessage != null) {
        val errorText = viewModel.errorMessage ?: ""
        val canUseMlKit = viewModel.activeBitmap != null || viewModel.originalBitmap != null

        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF4F46E5),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Thông báo nhận dạng", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(errorText)
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (errorText.contains("chìa khóa") || errorText.contains("API Key")) {
                        Button(
                            onClick = {
                                viewModel.clearError()
                                viewModel.showApiKeyDialog = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                        ) {
                            Text("Nhập API Key")
                        }
                    }

                    if (canUseMlKit && errorText.contains("OCR thiết bị")) {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearError()
                                viewModel.runMlKitOcrManual()
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Dùng OCR thiết bị")
                        }
                    }

                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Đóng")
                    }
                }
            }
        )
    }

    // Thông báo lỗi AI nếu có
    if (viewModel.aiErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearAiError() },
            icon = {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Thông báo AI Trích xuất", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(viewModel.aiErrorMessage ?: "")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearAiError() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Đóng")
                }
            }
        )
    }
}

@Composable
private fun FeatureBadgeItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEEF2FF))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF4F46E5),
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF4338CA)
        )
    }
}

// Chuyển đổi tên trường JSON sang tiếng Việt thân thiện
private fun mapFieldKeyToLabel(key: String): String {
    return when (key) {
        "id_number" -> "SỐ ĐỊNH DANH (CCCD / CMND)"
        "full_name" -> "HỌ VÀ TÊN"
        "date_of_birth" -> "NGÀY SINH"
        "gender" -> "GIỚI TÍNH"
        "nationality" -> "QUỐC TỊCH"
        "place_of_origin" -> "QUÊ QUÁN"
        "place_of_residence" -> "NƠI THƯỜNG TRÚ"
        "expiry_date" -> "CÓ GIÁ TRỊ ĐẾN"
        "invoice_number" -> "SỐ HÓA ĐƠN"
        "seller_name" -> "ĐƠN VỊ BÁN HÀNG"
        "seller_tax_id" -> "MÃ SỐ THUẾ"
        "invoice_date" -> "NGÀY LẬP HÓA ĐƠN"
        "buyer_name" -> "NGƯỜI MUA HÀNG"
        "total_amount" -> "TỔNG TIỀN THANH TOÁN"
        "items_summary" -> "TÓM TẮT MẶT HÀNG"
        "job_title" -> "CHỨC DANH / VỊ TRÍ"
        "company" -> "CÔNG TY"
        "phone_number" -> "SỐ ĐIỆN THOẠI"
        "email" -> "EMAIL"
        "address" -> "ĐỊA CHỈ"
        "website" -> "WEBSITE"
        "title" -> "TIÊU ĐỀ TÀI LIỆU"
        "cleaned_content" -> "NỘI DUNG ĐÃ LÀM SẠCH"
        "key_points" -> "CÁC ĐIỂM CHÍNH"
        else -> key.replace("_", " ").uppercase()
    }
}
