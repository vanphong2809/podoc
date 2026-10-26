package com.podoc.app.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Library : Screen("library")
    object Categories : Screen("categories")
    object Settings : Screen("settings")
    object Reader : Screen("reader/{articleId}") {
        fun createRoute(articleId: Int) = "reader/$articleId"
    }
    object Preview : Screen("preview")
}
