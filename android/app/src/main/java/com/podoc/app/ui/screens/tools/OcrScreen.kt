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
                            text = "Gemini 3.8 Flash Vision (Chính xác) + Fallback OCR",
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

            // Khu vực chọn/chụp ảnh ban đầu
            if (viewModel.activeBitmap == null && viewModel.originalBitmap == null && viewModel.imageUri == null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF7C3AED).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Chọn hình ảnh cần nhận dạng",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Chụp hoặc chọn ảnh tài liệu, CCCD, hóa đơn. Hỗ trợ khoanh vùng ROI viền xanh chỉ quét thông tin mong muốn và AI bóc tách JSON theo schema.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { cameraLauncher.launch(null) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7C3AED),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Chụp ảnh", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Chọn từ máy", fontWeight = FontWeight.Bold)
                            }
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
                                            text = "Đang quét vùng chọn ROI (Đã lọc viền thừa)",
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
                                        Text("Khôi phục ảnh gốc", fontSize = 11.sp, color = Color(0xFF00838F))
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

                        // Bộ nút tác vụ trên ảnh: Cắt vùng ROI, Đổi ảnh, Bộ lọc nét
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.openCropDialog() },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF00838F)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Khoanh vùng (ROI)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            FilterChip(
                                selected = viewModel.isEnhanceContrastEnabled,
                                onClick = { viewModel.toggleEnhanceContrast() },
                                label = { Text("Tăng nét", fontSize = 11.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (viewModel.isEnhanceContrastEnabled) Icons.Default.Contrast else Icons.Default.FilterNone,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.height(34.dp)
                            )

                            TextButton(
                                onClick = {
                                    if (viewModel.originalBitmap != null) {
                                        cameraLauncher.launch(null)
                                    } else {
                                        imagePickerLauncher.launch("image/*")
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Đổi ảnh", fontSize = 12.sp)
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
                                text = if (isGemini) "Gemini 3.8 Flash đang đọc văn bản..." else "Đang nhận dạng văn bản bằng OCR thiết bị...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7C3AED)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isGemini) "Multimodal Vision đọc trực tiếp ảnh (chính xác như trong chat)"
                                else "Sử dụng Google ML Kit nội bộ + Tiền xử lý + Sửa lỗi",
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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF7C3AED).copy(alpha = 0.06f)
                    ),
                    border = BorderStroke(1.2.dp, Color(0xFF7C3AED).copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BÓC TÁCH THÔNG TIN AI (KIE)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7C3AED)
                            )

                            Text(
                                text = if (viewModel.geminiApiKey.isNotBlank()) "Gemini 3.8 Flash" else "Chế độ Heuristic",
                                fontSize = 11.sp,
                                color = if (viewModel.geminiApiKey.isNotBlank()) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

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
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF7C3AED),
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.height(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Nút kích hoạt AI Trích xuất
                        Button(
                            onClick = { viewModel.extractStructuredWithAi() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7C3AED),
                                contentColor = Color.White
                            ),
                            enabled = !viewModel.isExtractingAi,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (viewModel.isExtractingAi) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("AI đang bóc tách & sửa lỗi ngữ cảnh...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Bóc tách & Chuẩn hóa theo Schema (AI)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // TAB CHUYỂN ĐỔI: [Văn bản sạch] vs [Dữ liệu bóc tách AI]
                TabRow(
                    selectedTabIndex = viewModel.activeTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 12.dp)
                ) {
                    Tab(
                        selected = viewModel.activeTab == 0,
                        onClick = { viewModel.switchTab(0) },
                        text = { Text("Văn bản sạch (${viewModel.recognizedText.lines().filter { it.isNotBlank() }.size} dòng)") }
                    )
                    Tab(
                        selected = viewModel.activeTab == 1,
                        onClick = { viewModel.switchTab(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Dữ liệu bóc tách (AI)")
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
                    val words = viewModel.recognizedText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    val lines = viewModel.recognizedText.lines().filter { it.isNotBlank() }.size

                    // Huy hiệu thông báo nguồn nhận dạng (Gemini 3.8 Flash hoặc ML Kit)
                    if (viewModel.readerNotice != null) {
                        Surface(
                            color = Color(0xFF7C3AED).copy(alpha = 0.09f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF7C3AED).copy(alpha = 0.25f)),
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
                                    tint = Color(0xFF7C3AED),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = viewModel.readerNotice ?: "",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF7C3AED)
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
                                        text = "Đã tự động sửa ${viewModel.corrections.size} điểm nhầm lẫn chính tả / số liệu",
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

                    // Chuyển đổi qua lại giữa Văn bản sạch và Văn bản gốc
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
                                text = "Tự động sửa lỗi",
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
                                text = if (viewModel.isShowingCleaned) "Xem bản OCR gốc" else "Xem bản đã sửa",
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Hộp chỉnh sửa / xem văn bản
                    OutlinedTextField(
                        value = viewModel.recognizedText,
                        onValueChange = { viewModel.updateRecognizedText(it) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 340.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Các nút tác vụ nhanh cho văn bản thuần
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
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sao chép", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, viewModel.recognizedText)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ văn bản OCR"))
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Chia sẻ", maxLines = 1)
                        }

                        Button(
                            onClick = {
                                saveAsStructured = false
                                showSaveDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7C3AED),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Lưu thư viện", maxLines = 1, fontWeight = FontWeight.Bold)
                        }
                    }

                } else {
                    // TAB 1: DỮ LIỆU BÓC TÁCH CÓ CẤU TRÚC (STRUCTURED KIE CARDS)
                    val result = viewModel.structuredResult
                    if (result == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Chưa có dữ liệu bóc tách",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Hãy bấm nút 'Bóc tách & Chuẩn hóa theo Schema (AI)' phía trên để AI tự động lọc bỏ văn bản rác và điền vào các trường chuẩn.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // Thẻ kết quả Validation
                        Surface(
                            color = if (result.validation.isValid) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFF59E0B).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (result.validation.isValid) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFFF59E0B).copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
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
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (result.validation.isValid) "Dữ liệu hợp lệ theo quy chuẩn" else "Cần kiểm tra lại một số trường",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
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
                                    color = Color.White.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "${(result.validation.score * 100).toInt()}% tin cậy",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = mapFieldKeyToLabel(key),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF7C3AED),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = valueStr,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
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
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Ghi chú sửa lỗi chính tả ngữ cảnh:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    result.confidenceNotes.forEach { note ->
                                        Text(
                                            text = "• $note",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
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
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy JSON", maxLines = 1)
                            }

                            Button(
                                onClick = {
                                    saveAsStructured = true
                                    showSaveDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7C3AED),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1.4f)
                            ) {
                                Icon(imageVector = Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lưu bảng vào Thư viện", maxLines = 1, fontWeight = FontWeight.Bold)
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

    // Dialog cấu hình Google Gemini API Key
    if (viewModel.showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showApiKeyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Cấu hình Google Gemini 3.8 Flash API", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = "Nhập Google AI Studio API Key cá nhân của bạn để sử dụng mô hình Gemini 3.8 Flash nhận diện và sửa lỗi tiếng Việt chuyên sâu nhất:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nếu để trống, hệ thống sẽ sử dụng API Key mặc định của máy chủ PoDoc hoặc chạy chế độ Heuristic Regex ngoại tuyến.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveGeminiApiKey(tempApiKey) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    Text("Lưu API Key")
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
                    text = if (saveAsStructured) "Lưu bảng dữ liệu vào Thư viện" else "Lưu văn bản vào Thư viện",
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
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    enabled = !viewModel.isSaving
                ) {
                    if (viewModel.isSaving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Lưu & Mở đọc")
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
                    tint = Color(0xFF7C3AED),
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
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
                            Text("Dùng OCR máy")
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
