package com.podoc.app.ui.screens.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podoc.app.R
import com.podoc.app.ui.theme.AccentColors
import com.podoc.app.ui.theme.parseHexColor
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (uiState.themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServerUrl by remember { mutableStateOf("") }

    // Màu sắc theo thiết kế chuẩn Indigo / Purple mới của PoDoc
    val brandPrimary = Color(0xFF4F46E5)
    val brandPrimaryDark = Color(0xFF4338CA)
    val selectedSegmentBg = if (isDark) Color(0xFF312E81) else Color(0xFFEEF2FF)
    val selectedSegmentText = if (isDark) Color(0xFFC7D2FE) else Color(0xFF4338CA)
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF4F6FB)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surface else Color.White

    // Launcher cho Storage Access Framework (SAF) để lưu file ZIP xuất dữ liệu
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportDataToUri(context, uri)
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Thanh tiêu đề phía trên thu gọn: Nút Back + Bánh răng + Tiêu đề Cài đặt hệ thống
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = brandPrimary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Danh sách các Cards cài đặt
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // CARD 1: Giao diện
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.section_appearance),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = brandPrimary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.theme_label),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Segmented Pill Button: Theo hệ thống | Chế độ Sáng | Chế độ Tối
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF374151) else Color(0xFFD1D5DB)),
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(3.5.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                ThemeSegmentItem(
                                    title = "Theo hệ\nthống",
                                    isSelected = uiState.themeMode == "system",
                                    selectedBg = selectedSegmentBg,
                                    selectedTextColor = selectedSegmentText,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setThemeMode("system") }
                                )
                                ThemeSegmentItem(
                                    title = "Chế độ\nSáng",
                                    isSelected = uiState.themeMode == "light",
                                    selectedBg = selectedSegmentBg,
                                    selectedTextColor = selectedSegmentText,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setThemeMode("light") }
                                )
                                ThemeSegmentItem(
                                    title = "Chế độ\nTối",
                                    isSelected = uiState.themeMode == "dark",
                                    selectedBg = selectedSegmentBg,
                                    selectedTextColor = selectedSegmentText,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setThemeMode("dark") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Switch Dynamic Color (Android 12+)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.dynamic_color_label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = uiState.dynamicColor,
                                onCheckedChange = { viewModel.setDynamicColor(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = brandPrimary,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = if (isDark) Color(0xFF4B5563) else Color(0xFFD1D5DB),
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )
                        }

                        // Chọn màu nhấn nếu không bật Dynamic Color
                        if (!uiState.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.accent_color_label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AccentColors.forEach { colorHex ->
                                    val selected = uiState.accentColor.equals(colorHex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(parseHexColor(colorHex))
                                            .clickable { viewModel.setAccentColor(colorHex) },
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

                        Spacer(modifier = Modifier.height(18.dp))

                        // Cỡ chữ đọc mặc định
                        var sliderFontSize by remember(uiState.defaultFontSize) {
                            mutableFloatStateOf(uiState.defaultFontSize.toFloat())
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.default_font_size_label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = brandPrimary.copy(alpha = if (isDark) 0.25f else 0.12f)
                            ) {
                                Text(
                                    text = "${sliderFontSize.roundToInt()}sp",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = brandPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = sliderFontSize,
                            onValueChange = { sliderFontSize = it },
                            onValueChangeFinished = {
                                viewModel.setDefaultFontSize(sliderFontSize.roundToInt())
                            },
                            valueRange = 14f..24f,
                            steps = 4,
                            colors = SliderDefaults.colors(
                                thumbColor = brandPrimary,
                                activeTrackColor = brandPrimary,
                                inactiveTrackColor = if (isDark) Color(0xFF374151) else Color(0xFFEEF2FF),
                                activeTickColor = Color.White,
                                inactiveTickColor = brandPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Khung xem trước trực tiếp cỡ chữ
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Xem trước cỡ chữ đọc:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "PoDoc lưu trữ và đọc tài liệu cá nhân của bạn trên mọi thiết bị.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = sliderFontSize.sp,
                                        lineHeight = (sliderFontSize * 1.5f).sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // CARD 2: Dữ liệu & Xuất
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.section_data),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = brandPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.export_note),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        OutlinedButton(
                            onClick = {
                                val defaultName = "podoc_backup_${System.currentTimeMillis()}.zip"
                                exportLauncher.launch(defaultName)
                            },
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(1.2.dp, brandPrimary),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = brandPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(brandPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_export_markdown),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp,
                                color = brandPrimary
                            )
                        }
                    }
                }

                // CARD 3: Máy chủ kết nối
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Máy chủ kết nối",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = brandPrimary,
                                modifier = Modifier.weight(1f)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        editingServerUrl = uiState.serverUrl
                                        showServerDialog = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Chỉnh sửa",
                                    tint = brandPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Chỉnh sửa",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    ),
                                    color = brandPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF374151) else Color(0xFFE2E8F0)),
                            color = if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingServerUrl = uiState.serverUrl
                                    showServerDialog = true
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = brandPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Máy chủ: ${uiState.serverUrl.ifBlank { "Chưa thiết lập" }}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF6B7280),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Phần hiển thị thông số phần cứng của Máy chủ (RAM, Dung lượng ổ đĩa, CPU)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Thông số hệ thống máy chủ",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            if (uiState.isLoadingServerStats) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = brandPrimary
                                )
                            } else {
                                IconButton(
                                    onClick = { viewModel.loadServerStats() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Tải lại thông số",
                                        tint = brandPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (uiState.serverStats != null) {
                            val stats = uiState.serverStats!!
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 1. RAM
                                ServerHardwareMetricItem(
                                    title = "Bộ nhớ RAM",
                                    value = "${stats.ram.percentUsed}%",
                                    subtext = "${stats.ram.formattedUsed} / ${stats.ram.formattedTotal} (Còn trống: ${stats.ram.formattedFree})",
                                    percent = stats.ram.percentUsed,
                                    icon = Icons.Default.Memory,
                                    color = Color(0xFF8B5CF6),
                                    isDark = isDark
                                )

                                // 2. Dung lượng bộ nhớ (Disk)
                                ServerHardwareMetricItem(
                                    title = "Dung lượng ổ đĩa",
                                    value = "${stats.disk.percentUsed}%",
                                    subtext = "${stats.disk.formattedUsed} / ${stats.disk.formattedTotal} (Còn trống: ${stats.disk.formattedFree})",
                                    percent = stats.disk.percentUsed,
                                    icon = Icons.Default.Storage,
                                    color = Color(0xFF0284C7),
                                    isDark = isDark
                                )

                                // 3. CPU
                                ServerHardwareMetricItem(
                                    title = "Vi xử lý (CPU)",
                                    value = "${stats.cpu.percentUsed}%",
                                    subtext = "${stats.cpu.cores} nhân • ${stats.cpu.modelName.ifBlank { "Chuẩn" }}",
                                    percent = stats.cpu.percentUsed,
                                    icon = Icons.Default.Speed,
                                    color = Color(0xFF10B981),
                                    isDark = isDark
                                )

                                // HĐH & Thời gian hoạt động
                                if (stats.osName.isNotBlank() || stats.uptimeSeconds > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "HĐH: ${stats.osName}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.5.sp
                                                ),
                                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Uptime: ${formatUptime(stats.uptimeSeconds)}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (uiState.serverStatsError != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF451A1A) else Color(0xFFFEF2F2),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF7F1D1D) else Color(0xFFFECACA)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = uiState.serverStatsError ?: "Không thể kết nối máy chủ",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { viewModel.loadServerStats() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Thử lại", fontSize = 12.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // CARD 4: Về ứng dụng
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.section_about),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = brandPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.app_description),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.app_version),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Dialog cập nhật Địa chỉ máy chủ (theo redesign 15_settings_server_redesign.png)
    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(brandPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = brandPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            },
            title = {
                Text(
                    "Cấu hình Máy chủ PoDoc",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        "Nhập địa chỉ máy chủ backend để đồng bộ và xử lý tài liệu:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = editingServerUrl,
                        onValueChange = { editingServerUrl = it },
                        label = { Text("URL máy chủ") },
                        placeholder = { Text("http://10.0.2.2:8000") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null, tint = brandPrimary)
                        },
                        trailingIcon = {
                            if (editingServerUrl.isNotEmpty()) {
                                IconButton(onClick = { editingServerUrl = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Xóa")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showServerDialog = false
                        viewModel.setServerUrl(editingServerUrl) {
                            android.widget.Toast.makeText(
                                context,
                                "Đã áp dụng cấu hình máy chủ mới!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * Mục chọn phân đoạn trong Pill selector của Theme
 */
@Composable
private fun ThemeSegmentItem(
    title: String,
    isSelected: Boolean,
    selectedBg: Color,
    selectedTextColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) selectedBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.5.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center
            ),
            color = if (isSelected) selectedTextColor else Color(0xFF374151)
        )
    }
}

/**
 * Mục hiển thị thông số phần cứng máy chủ (RAM, Disk, CPU)
 */
@Composable
private fun ServerHardwareMetricItem(
    title: String,
    value: String,
    subtext: String? = null,
    percent: Float,
    icon: ImageVector,
    color: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC)
    val border = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = if (isDark) 0.2f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtext != null) {
                        Text(
                            text = subtext,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp
                            ),
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = color
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
            )
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
