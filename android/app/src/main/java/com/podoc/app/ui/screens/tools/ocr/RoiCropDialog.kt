package com.podoc.app.ui.screens.tools.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min

@Composable
fun RoiCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirmCrop: (RectF) -> Unit
) {
    // Tọa độ chuẩn hóa của khung ROI (0.0 -> 1.0)
    var cropLeft by remember { mutableFloatStateOf(0.08f) }
    var cropTop by remember { mutableFloatStateOf(0.18f) }
    var cropRight by remember { mutableFloatStateOf(0.92f) }
    var cropBottom by remember { mutableFloatStateOf(0.55f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top bar trong dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Hủy",
                            tint = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "KHOANH VÙNG CẦN QUÉT (ROI)",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Chỉ vùng viền xanh được đưa vào OCR",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = {
                            // Reset về toàn màn hình
                            cropLeft = 0.05f
                            cropTop = 0.05f
                            cropRight = 0.95f
                            cropBottom = 0.95f
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Toàn bộ",
                            tint = Color.White
                        )
                    }
                }

                // Banner hướng dẫn khoanh vùng
                Surface(
                    color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Kéo các góc hoặc cạnh viền xanh để định vị chính xác vùng tài liệu",
                            color = Color(0xFFE0F7FA),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Khu vực hiển thị ảnh và lớp phủ ROI Interactive
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF121212)),
                    contentAlignment = Alignment.Center
                ) {
                    val density = LocalDensity.current
                    val containerWidthPx = with(density) { maxWidth.toPx() }
                    val containerHeightPx = with(density) { maxHeight.toPx() }

                    // Tính kích thước và vị trí của Bitmap sau khi Fit
                    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val containerAspect = containerWidthPx / containerHeightPx

                    val renderedWidth: Float
                    val renderedHeight: Float
                    val offsetX: Float
                    val offsetY: Float

                    if (containerAspect > bitmapAspect) {
                        renderedHeight = containerHeightPx
                        renderedWidth = renderedHeight * bitmapAspect
                        offsetX = (containerWidthPx - renderedWidth) / 2f
                        offsetY = 0f
                    } else {
                        renderedWidth = containerWidthPx
                        renderedHeight = renderedWidth / bitmapAspect
                        offsetX = 0f
                        offsetY = (containerHeightPx - renderedHeight) / 2f
                    }

                    // Tọa độ thực tế trên màn hình của khung cắt
                    val rectLeftPx = offsetX + cropLeft * renderedWidth
                    val rectTopPx = offsetY + cropTop * renderedHeight
                    val rectRightPx = offsetX + cropRight * renderedWidth
                    val rectBottomPx = offsetY + cropBottom * renderedHeight
                    val rectWidthPx = max(20f, rectRightPx - rectLeftPx)
                    val rectHeightPx = max(20f, rectBottomPx - rectTopPx)

                    // 1. Vẽ Bitmap nền
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Ảnh gốc cần crop",
                        modifier = Modifier
                            .fillMaxSize()
                    )

                    // 2. Vẽ lớp phủ Scrim (màu tối xung quanh) & Khung viền xanh ROI
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val dragXNorm = dragAmount.x / renderedWidth
                                    val dragYNorm = dragAmount.y / renderedHeight

                                    val curW = cropRight - cropLeft
                                    val curH = cropBottom - cropTop

                                    var newL = cropLeft + dragXNorm
                                    var newT = cropTop + dragYNorm
                                    var newR = cropRight + dragXNorm
                                    var newB = cropBottom + dragYNorm

                                    // Ràng buộc trong phạm vi [0..1]
                                    if (newL < 0f) {
                                        newL = 0f
                                        newR = curW
                                    }
                                    if (newR > 1f) {
                                        newR = 1f
                                        newL = 1f - curW
                                    }
                                    if (newT < 0f) {
                                        newT = 0f
                                        newB = curH
                                    }
                                    if (newB > 1f) {
                                        newB = 1f
                                        newT = 1f - curH
                                    }

                                    cropLeft = newL.coerceIn(0f, 1f)
                                    cropTop = newT.coerceIn(0f, 1f)
                                    cropRight = newR.coerceIn(0f, 1f)
                                    cropBottom = newB.coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        // Vùng tối bao quanh
                        val scrimColor = Color.Black.copy(alpha = 0.55f)

                        // Top
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(size.width, rectTopPx)
                        )
                        // Bottom
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(0f, rectBottomPx),
                            size = Size(size.width, size.height - rectBottomPx)
                        )
                        // Left
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(0f, rectTopPx),
                            size = Size(rectLeftPx, rectHeightPx)
                        )
                        // Right
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(rectRightPx, rectTopPx),
                            size = Size(size.width - rectRightPx, rectHeightPx)
                        )

                        // Khung viền xanh Neon nổi bật (Bounding Box)
                        val borderColor = Color(0xFF00E5FF)
                        drawRect(
                            color = borderColor,
                            topLeft = Offset(rectLeftPx, rectTopPx),
                            size = Size(rectWidthPx, rectHeightPx),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                        )

                        // Đường kẻ lưới 1/3 (Rule of thirds)
                        val gridColor = Color.White.copy(alpha = 0.35f)
                        val oneThirdW = rectWidthPx / 3f
                        val oneThirdH = rectHeightPx / 3f

                        drawLine(
                            color = gridColor,
                            start = Offset(rectLeftPx + oneThirdW, rectTopPx),
                            end = Offset(rectLeftPx + oneThirdW, rectBottomPx),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(rectLeftPx + oneThirdW * 2, rectTopPx),
                            end = Offset(rectLeftPx + oneThirdW * 2, rectBottomPx),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(rectLeftPx, rectTopPx + oneThirdH),
                            end = Offset(rectRightPx, rectTopPx + oneThirdH),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(rectLeftPx, rectTopPx + oneThirdH * 2),
                            end = Offset(rectRightPx, rectTopPx + oneThirdH * 2),
                            strokeWidth = 1.dp.toPx()
                        )

                        // 4 góc viền nổi bật (Corner brackets)
                        val cornerLen = 22.dp.toPx()
                        val cornerThick = 4.5.dp.toPx()
                        val cornerColor = Color(0xFF00E5FF)

                        // Top-Left
                        drawLine(cornerColor, Offset(rectLeftPx - 1, rectTopPx), Offset(rectLeftPx + cornerLen, rectTopPx), cornerThick)
                        drawLine(cornerColor, Offset(rectLeftPx, rectTopPx), Offset(rectLeftPx, rectTopPx + cornerLen), cornerThick)

                        // Top-Right
                        drawLine(cornerColor, Offset(rectRightPx + 1, rectTopPx), Offset(rectRightPx - cornerLen, rectTopPx), cornerThick)
                        drawLine(cornerColor, Offset(rectRightPx, rectTopPx), Offset(rectRightPx, rectTopPx + cornerLen), cornerThick)

                        // Bottom-Left
                        drawLine(cornerColor, Offset(rectLeftPx - 1, rectBottomPx), Offset(rectLeftPx + cornerLen, rectBottomPx), cornerThick)
                        drawLine(cornerColor, Offset(rectLeftPx, rectBottomPx), Offset(rectLeftPx, rectBottomPx - cornerLen), cornerThick)

                        // Bottom-Right
                        drawLine(cornerColor, Offset(rectRightPx + 1, rectBottomPx), Offset(rectRightPx - cornerLen, rectBottomPx), cornerThick)
                        drawLine(cornerColor, Offset(rectRightPx, rectBottomPx), Offset(rectRightPx, rectBottomPx - cornerLen), cornerThick)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Các nút Preset mẫu nhanh (Căn cước, Dòng chữ, Tự do)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            // Preset CCCD / ID Card (Tỷ lệ khung ngang)
                            cropLeft = 0.08f
                            cropRight = 0.92f
                            cropTop = 0.22f
                            cropBottom = 0.75f
                        },
                        label = { Text("Mẫu CCCD/Thẻ", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            iconColor = Color(0xFF00E5FF)
                        )
                    )

                    FilterChip(
                        selected = false,
                        onClick = {
                            // Preset Dòng chữ hẹp (Quét 1-2 dòng)
                            cropLeft = 0.10f
                            cropRight = 0.90f
                            cropTop = 0.40f
                            cropBottom = 0.55f
                        },
                        label = { Text("1-2 Dòng chữ", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShortText, null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            iconColor = Color(0xFF00E5FF)
                        )
                    )

                    FilterChip(
                        selected = false,
                        onClick = {
                            // Toàn trang
                            cropLeft = 0.02f
                            cropRight = 0.98f
                            cropTop = 0.02f
                            cropBottom = 0.98f
                        },
                        label = { Text("Toàn ảnh", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            iconColor = Color(0xFF00E5FF)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Nút xác nhận Cắt & Quét
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Hủy")
                    }

                    Button(
                        onClick = {
                            val rect = RectF(cropLeft, cropTop, cropRight, cropBottom)
                            onConfirmCrop(rect)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1.8f)
                    ) {
                        Icon(imageVector = Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cắt & Quét vùng này (ROI)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
