package com.podoc.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.podoc.app.ui.components.AddOptionBottomSheet
import com.podoc.app.ui.components.PoDocBottomBar
import com.podoc.app.ui.navigation.Screen
import com.podoc.app.ui.screens.category.CategoryDetailScreen
import com.podoc.app.ui.screens.category.CategoryDetailViewModel
import com.podoc.app.ui.screens.category.CategoryScreen
import com.podoc.app.ui.screens.category.CategoryViewModel
import com.podoc.app.ui.screens.library.LibraryScreen
import com.podoc.app.ui.screens.library.LibraryViewModel
import com.podoc.app.ui.screens.preview.PreviewScreen
import com.podoc.app.ui.screens.preview.PreviewViewModel
import com.podoc.app.ui.screens.reader.ReaderScreen
import com.podoc.app.ui.screens.reader.ReaderViewModel
import com.podoc.app.ui.screens.settings.SettingsScreen
import com.podoc.app.ui.screens.settings.SettingsViewModel
import com.podoc.app.ui.screens.tools.OcrScreen
import com.podoc.app.ui.screens.tools.OcrViewModel
import com.podoc.app.ui.screens.tools.ToolsScreen
import com.podoc.app.ui.screens.tools.ToolsViewModel
import com.podoc.app.ui.theme.PoDocTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PoDocApplication).container

        setContent {
            val themeMode by container.dataStoreManager.themeModeFlow.collectAsState(initial = "system")
            val dynamicColor by container.dataStoreManager.dynamicColorFlow.collectAsState(initial = true)
            val accentColor by container.dataStoreManager.accentColorFlow.collectAsState(initial = "#4F46E5")
            val authToken by container.dataStoreManager.authTokenFlow.collectAsState(initial = "")

            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            var showAddSheet by remember { mutableStateOf(false) }

            // ViewModel instances (khởi tạo thủ công qua AppContainer)
            val libraryViewModel = remember {
                LibraryViewModel(container.articleRepository, container.categoryRepository)
            }
            val previewViewModel = remember {
                PreviewViewModel(container.articleRepository, container.categoryRepository)
            }
            val categoryViewModel = remember {
                CategoryViewModel(container.categoryRepository)
            }
            val settingsViewModel = remember {
                SettingsViewModel(container.authRepository, container.articleRepository, container.dataStoreManager)
            }
            val toolsViewModel = remember {
                ToolsViewModel(container.articleRepository)
            }
            val ocrViewModel = remember {
                OcrViewModel(
                    container.articleRepository,
                    container.categoryRepository,
                    container.dataStoreManager,
                    apiServiceProvider = { container.getApiService() }
                )
            }

            // Reload khi server URL thay đổi
            val serverUrl by container.dataStoreManager.serverUrlFlow.collectAsState(initial = "")
            LaunchedEffect(serverUrl) {
                if (serverUrl.isNotBlank()) {
                    libraryViewModel.loadArticles(refresh = true)
                    categoryViewModel.loadCategories()
                }
            }

            // Launcher SAF chọn 1 hoặc nhiều file từ máy để lưu trực tiếp vào thư viện (không trích xuất)
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    libraryViewModel.uploadFiles(uris)
                    if (currentRoute != Screen.Library.route) {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Library.route) { inclusive = true }
                        }
                    }
                }
            }

            // Launcher chọn 1 hoặc nhiều ảnh trực tiếp từ thư viện ảnh
            val imagePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetMultipleContents()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    libraryViewModel.uploadFiles(uris)
                    if (currentRoute != Screen.Library.route) {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Library.route) { inclusive = true }
                        }
                    }
                }
            }

            // Launcher chọn 1 hoặc nhiều video trực tiếp từ máy (tối đa 50 MB)
            val videoPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetMultipleContents()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    libraryViewModel.uploadFiles(uris)
                    if (currentRoute != Screen.Library.route) {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Library.route) { inclusive = true }
                        }
                    }
                }
            }

            // Launcher chọn 1 hoặc nhiều tệp âm thanh trực tiếp từ máy (.mp3, .wav, .m4a... tối đa 50 MB)
            val audioPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetMultipleContents()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    libraryViewModel.uploadFiles(uris)
                    if (currentRoute != Screen.Library.route) {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Library.route) { inclusive = true }
                        }
                    }
                }
            }

            // Launcher chọn file Office từ máy để chuyển đổi sang PDF
            val officeToPdfLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    toolsViewModel.uploadAndConvertToPdf(uri) { newArticle ->
                        libraryViewModel.loadArticles(refresh = true)
                        categoryViewModel.loadCategories()
                        navController.navigate(Screen.Reader.createRoute(newArticle.id))
                    }
                }
            }

            // Mở trực tiếp bài viết nếu được chuyển sang từ ShareReceiveActivity
            LaunchedEffect(Unit) {
                val openArticleId = intent?.getIntExtra("open_article_id", -1)?.takeIf { it > 0 }
                if (openArticleId != null) {
                    navController.navigate(Screen.Reader.createRoute(openArticleId))
                }
            }

            PoDocTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                accentColorHex = accentColor
            ) {
                val isBottomBarVisible = currentRoute in listOf(
                    Screen.Library.route,
                    Screen.Categories.route,
                    Screen.Tools.route,
                    Screen.Settings.route
                )

                Scaffold(
                    bottomBar = {
                        if (isBottomBarVisible) {
                            PoDocBottomBar(
                                currentRoute = currentRoute ?: Screen.Library.route,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        popUpTo(Screen.Library.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    },
                    floatingActionButton = {
                        if (currentRoute in listOf(Screen.Library.route, Screen.Categories.route)) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .shadow(elevation = 8.dp, shape = CircleShape, spotColor = Color(0x664F46E5))
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF6366F1), Color(0xFF4338CA))
                                        )
                                    )
                                    .clickable { showAddSheet = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Thêm mới",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Library.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        // Màn hình Thư viện
                        composable(Screen.Library.route) {
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onArticleClick = { articleId ->
                                    navController.navigate(Screen.Reader.createRoute(articleId))
                                }
                            )
                        }

                        // Màn hình Xem trước
                        composable(Screen.Preview.route) {
                            PreviewScreen(
                                viewModel = previewViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenArticle = { articleId ->
                                    libraryViewModel.loadArticles(refresh = true)
                                    navController.navigate(Screen.Reader.createRoute(articleId)) {
                                        popUpTo(Screen.Preview.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Màn hình Đọc bài viết (Reader)
                        composable(
                            route = Screen.Reader.route,
                            arguments = listOf(navArgument("articleId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val articleId = backStackEntry.arguments?.getInt("articleId") ?: return@composable
                            val readerViewModel = remember(articleId) {
                                ReaderViewModel(container.articleRepository, container.dataStoreManager)
                            }
                            ReaderScreen(
                                articleId = articleId,
                                viewModel = readerViewModel,
                                onNavigateBack = {
                                    libraryViewModel.loadArticles(refresh = true)
                                    navController.popBackStack()
                                },
                                onNavigateToArticle = { newArticleId ->
                                    navController.navigate(Screen.Reader.createRoute(newArticleId)) {
                                        popUpTo(Screen.Reader.createRoute(articleId)) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Màn hình Quản lý Danh mục
                        composable(Screen.Categories.route) {
                            CategoryScreen(
                                viewModel = categoryViewModel,
                                themeMode = themeMode,
                                onCategoryClick = { cat ->
                                    navController.navigate(Screen.CategoryDetail.createRoute(cat.id, cat.name, cat.color))
                                }
                            )
                        }

                        // Màn hình Chi tiết Danh mục (danh sách bài viết/file theo danh mục, tìm kiếm, lọc content_type)
                        composable(
                            route = Screen.CategoryDetail.route,
                            arguments = listOf(
                                navArgument("categoryId") { type = NavType.IntType },
                                navArgument("name") { type = NavType.StringType; defaultValue = "Danh mục" },
                                navArgument("color") { type = NavType.StringType; defaultValue = "#4F46E5" }
                            )
                        ) { backStackEntry ->
                            val catId = backStackEntry.arguments?.getInt("categoryId") ?: return@composable
                            val rawName = backStackEntry.arguments?.getString("name") ?: "Danh mục"
                            val rawColor = backStackEntry.arguments?.getString("color") ?: "#4F46E5"
                            val catName = try { java.net.URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
                            val catColor = try { java.net.URLDecoder.decode(rawColor, "UTF-8") } catch (_: Exception) { rawColor }

                            val detailViewModel = remember(catId) {
                                CategoryDetailViewModel(
                                    categoryId = catId,
                                    categoryName = catName,
                                    categoryColor = catColor,
                                    articleRepository = container.articleRepository,
                                    categoryRepository = container.categoryRepository
                                )
                            }

                            CategoryDetailScreen(
                                viewModel = detailViewModel,
                                onNavigateBack = {
                                    categoryViewModel.loadCategories()
                                    libraryViewModel.loadArticles(refresh = true)
                                    navController.popBackStack()
                                },
                                onArticleClick = { articleId ->
                                    navController.navigate(Screen.Reader.createRoute(articleId))
                                },
                                onPasteLink = { url, asWebFormat ->
                                    previewViewModel.extractUrl(url, initialCategoryId = catId, asWebFormat = asWebFormat)
                                    navController.navigate(Screen.Preview.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        // Màn hình Hộp công cụ tiện ích
                        composable(Screen.Tools.route) {
                            ToolsScreen(
                                viewModel = toolsViewModel,
                                onPickOfficeForPdf = {
                                    officeToPdfLauncher.launch(
                                        arrayOf(
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/vnd.ms-excel",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.ms-powerpoint",
                                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                            "application/rtf",
                                            "text/rtf",
                                            "application/vnd.oasis.opendocument.text",
                                            "application/vnd.oasis.opendocument.spreadsheet",
                                            "application/vnd.oasis.opendocument.presentation",
                                            "*/*"
                                        )
                                    )
                                },
                                onPasteLink = { url, asWebFormat ->
                                    previewViewModel.extractUrl(url, asWebFormat = asWebFormat)
                                    navController.navigate(Screen.Preview.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenOcr = {
                                    navController.navigate(Screen.Ocr.route)
                                }
                            )
                        }

                        // Màn hình Quét chữ từ ảnh (OCR)
                        composable(Screen.Ocr.route) {
                            OcrScreen(
                                viewModel = ocrViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenArticle = { articleId ->
                                    libraryViewModel.loadArticles(refresh = true)
                                    categoryViewModel.loadCategories()
                                    navController.navigate(Screen.Reader.createRoute(articleId)) {
                                        popUpTo(Screen.Ocr.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Màn hình Cài đặt
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onNavigateBack = {
                                    if (!navController.popBackStack()) {
                                        navController.navigate(Screen.Library.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // BottomSheet Thêm mới: Dán link hoặc Chọn file
                    if (showAddSheet) {
                        AddOptionBottomSheet(
                            onDismiss = { showAddSheet = false },
                            onPasteLink = { url, asWebFormat ->
                                previewViewModel.extractUrl(url, asWebFormat = asWebFormat)
                                navController.navigate(Screen.Preview.route) {
                                    launchSingleTop = true
                                }
                            },
                            onPickFiles = {
                                // Mở trình chọn file cho phép chọn mọi loại file (PDF, Word, HTML, TXT, MD...)
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onPickImages = {
                                // Mở trình chọn ảnh từ máy (PNG, JPG, WEBP, GIF, SVG...)
                                imagePickerLauncher.launch("image/*")
                            },
                            onPickVideos = {
                                // Mở trình chọn video từ máy (MP4, MKV, MOV, WEBM...)
                                videoPickerLauncher.launch("video/*")
                            },
                            onPickAudios = {
                                // Mở trình chọn tệp âm thanh từ máy (MP3, WAV, M4A, FLAC...)
                                audioPickerLauncher.launch("audio/*")
                            }
                        )
                    }
                }
            }
        }
    }
}
