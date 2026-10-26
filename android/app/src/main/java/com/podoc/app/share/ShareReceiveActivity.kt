package com.podoc.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.podoc.app.PoDocApplication
import com.podoc.app.ui.screens.preview.PreviewScreen
import com.podoc.app.ui.screens.preview.PreviewViewModel
import com.podoc.app.ui.theme.PoDocTheme
import java.util.regex.Pattern

class ShareReceiveActivity : ComponentActivity() {

    private val urlPattern = Pattern.compile(
        "(https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*))",
        Pattern.CASE_INSENSITIVE
    )

    private var previewViewModel: PreviewViewModel? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        previewViewModel?.reset()
        previewViewModel?.let { handleIncomingIntent(intent, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PoDocApplication).container
        val vm = PreviewViewModel(
            articleRepository = container.articleRepository,
            categoryRepository = container.categoryRepository
        )
        previewViewModel = vm

        handleIncomingIntent(intent, vm)

        setContent {
            val themeMode by container.dataStoreManager.themeModeFlow.collectAsState(initial = "system")
            val dynamicColor by container.dataStoreManager.dynamicColorFlow.collectAsState(initial = true)
            val accentColor by container.dataStoreManager.accentColorFlow.collectAsState(initial = "#4F46E5")

            PoDocTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                accentColorHex = accentColor
            ) {
                // Background trong suốt bao quanh BottomSheet
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                    ) {
                        PreviewScreen(
                            viewModel = vm,
                            onNavigateBack = { finish() },
                            onOpenArticle = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?, viewModel: PreviewViewModel) {
        if (intent == null) {
            finish()
            return
        }

        when (intent.action) {
            // Chia sẻ văn bản hoặc 1 file
            Intent.ACTION_SEND -> {
                val mimeType = intent.type
                if (mimeType == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    val foundUrl = extractFirstUrl(sharedText)
                    if (!foundUrl.isNullOrBlank()) {
                        viewModel.extractUrl(foundUrl)
                    } else {
                        finish()
                    }
                } else {
                    // File đính kèm
                    val fileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }

                    if (fileUri != null) {
                        viewModel.extractFile(fileUri)
                    } else {
                        finish()
                    }
                }
            }

            // Chia sẻ nhiều file cùng lúc (Phase 2)
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    viewModel.extractMultipleFiles(uris)
                } else {
                    finish()
                }
            }
            else -> finish()
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}
