package com.podoc.app.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Library : Screen("library")
    object Categories : Screen("categories")
    object CategoryDetail : Screen("category_detail/{categoryId}?name={name}&color={color}") {
        fun createRoute(categoryId: Int, name: String, color: String = "#4F46E5"): String {
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
            val encodedColor = java.net.URLEncoder.encode(color, "UTF-8")
            return "category_detail/$categoryId?name=$encodedName&color=$encodedColor"
        }
    }
    object Settings : Screen("settings")
    object Reader : Screen("reader/{articleId}") {
        fun createRoute(articleId: Int) = "reader/$articleId"
    }
    object Preview : Screen("preview")
}
