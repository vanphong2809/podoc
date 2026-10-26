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
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.podoc.app.ui.components.AddOptionBottomSheet
import com.podoc.app.ui.components.PoDocBottomBar
import com.podoc.app.ui.navigation.Screen
import com.podoc.app.ui.screens.auth.LoginScreen
import com.podoc.app.ui.screens.auth.LoginViewModel
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
            val loginViewModel = remember {
                LoginViewModel(container.authRepository)
            }

            // Launcher SAF chọn 1 hoặc nhiều file từ máy (Phase 2)
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    if (uris.size == 1) {
                        previewViewModel.extractFile(uris[0])
                    } else {
                        previewViewModel.extractMultipleFiles(uris)
                    }
                    navController.navigate(Screen.Preview.route) {
                        launchSingleTop = true
                    }
                }
            }

            // Lắng nghe sự kiện token hết hạn -> Quay về Login
            LaunchedEffect(Unit) {
                container.tokenExpiredEvent.collect {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
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
                        if (isBottomBarVisible) {
                            FloatingActionButton(
                                onClick = { showAddSheet = true },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Thêm mới")
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = if (authToken.isNotBlank()) Screen.Library.route else Screen.Login.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        // Màn hình Đăng nhập
                        composable(Screen.Login.route) {
                            LoginScreen(
                                viewModel = loginViewModel,
                                onLoginSuccess = {
                                    libraryViewModel.loadArticles(refresh = true)
                                    navController.navigate(Screen.Library.route) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

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
                                }
                            )
                        }

                        // Màn hình Quản lý Danh mục
                        composable(Screen.Categories.route) {
                            CategoryScreen(viewModel = categoryViewModel)
                        }

                        // Màn hình Cài đặt
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onLogout = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }

                    // BottomSheet Thêm mới: Dán link hoặc Chọn file
                    if (showAddSheet) {
                        AddOptionBottomSheet(
                            onDismiss = { showAddSheet = false },
                            onPasteLink = { url ->
                                previewViewModel.extractUrl(url)
                                navController.navigate(Screen.Preview.route) {
                                    launchSingleTop = true
                                }
                            },
                            onPickFiles = {
                                // Mở trình chọn file cho phép chọn mọi loại file văn bản (PDF, DOCX, TXT, MD...)
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/msword",
                                        "text/plain",
                                        "text/markdown",
                                        "text/html",
                                        "*/*"
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
