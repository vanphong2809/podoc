package com.podoc.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podoc.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOptionBottomSheet(
    onDismiss: () -> Unit,
    onPasteLink: (url: String, asWebFormat: Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit = {},
    onPickVideos: () -> Unit = {},
    onPickAudios: () -> Unit = {}
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("") }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Kiểm tra clipboard khi mở
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboard?.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0)?.text?.toString()?.trim()
            if (!text.isNullOrBlank() && (text.startsWith("http://") || text.startsWith("https://"))) {
                clipboardSuggestion = text
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.add_menu_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp
                ),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            // Clipboard suggestion banner
            if (clipboardSuggestion != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .clickable { urlInput = clipboardSuggestion!! }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${stringResource(R.string.clipboard_suggest)} · chạm để điền",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = clipboardSuggestion!!,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Ô nhập link
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.paste_link_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = Color(0xFF4F46E5)
                    )
                },
                trailingIcon = {
                    if (urlInput.isNotEmpty()) {
                        IconButton(onClick = { urlInput = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Nút nhóm URL — 2 nút trong 1 Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Nút Trích xuất nội dung
                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onPasteLink(urlInput.trim(), false)
                            onDismiss()
                        }
                    },
                    enabled = urlInput.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4F46E5),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF4F46E5).copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f)
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_extract),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // Nút Lưu dạng web
                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onPasteLink(urlInput.trim(), true)
                            onDismiss()
                        }
                    },
                    enabled = urlInput.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEEF2FF),
                        contentColor = Color(0xFF4338CA),
                        disabledContainerColor = Color(0xFFEEF2FF).copy(alpha = 0.5f),
                        disabledContentColor = Color(0xFF4338CA).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lưu web",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Divider với label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "  hoặc nhập từ thiết bị  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Grid 2x2 nút nhập file — đẹp hơn, visual weight rõ ràng
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportOptionCard(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        sublabel = "mp4, mkv, mov...",
                        iconBg = Color(0xFFFFF1F2),
                        iconTint = Color(0xFFBE123C),
                        onClick = { onPickVideos(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    )
                    ImportOptionCard(
                        icon = Icons.Default.Headphones,
                        label = "Âm thanh",
                        sublabel = "mp3, wav, m4a...",
                        iconBg = Color(0xFFECFDF5),
                        iconTint = Color(0xFF059669),
                        onClick = { onPickAudios(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportOptionCard(
                        icon = Icons.Default.Image,
                        label = "Hình ảnh",
                        sublabel = "jpg, png, webp...",
                        iconBg = Color(0xFFF5F3FF),
                        iconTint = Color(0xFF7C3AED),
                        onClick = { onPickImages(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    )
                    ImportOptionCard(
                        icon = Icons.Default.Description,
                        label = "Tệp tin",
                        sublabel = "PDF, Office, mọi tệp...",
                        iconBg = Color(0xFFEFF6FF),
                        iconTint = Color(0xFF1D4ED8),
                        onClick = { onPickFiles(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// IMPORT OPTION CARD — card vuông với icon màu
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun ImportOptionCard(
    icon: ImageVector,
    label: String,
    sublabel: String,
    iconBg: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
