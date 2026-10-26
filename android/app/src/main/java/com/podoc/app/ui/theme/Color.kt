package com.podoc.app.ui.theme

import androidx.compose.ui.graphics.Color

// Default Indigo Accent
val PrimaryLight = Color(0xFF4F46E5)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE0E7FF)
val OnPrimaryContainerLight = Color(0xFF1E1B4B)

val PrimaryDark = Color(0xFF818CF8)
val OnPrimaryDark = Color(0xFF1E1B4B)
val PrimaryContainerDark = Color(0xFF3730A3)
val OnPrimaryContainerDark = Color(0xFFE0E7FF)

// Background & Surface
val BackgroundLight = Color(0xFFF8FAFC)
val OnBackgroundLight = Color(0xFF0F172A)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFF1F5F9)
val OnSurfaceVariantLight = Color(0xFF475569)

val BackgroundDark = Color(0xFF0F172A)
val OnBackgroundDark = Color(0xFFF8FAFC)
val SurfaceDark = Color(0xFF1E293B)
val OnSurfaceDark = Color(0xFFF8FAFC)
val SurfaceVariantDark = Color(0xFF334155)
val OnSurfaceVariantDark = Color(0xFF94A3B8)

// Outline
val OutlineLight = Color(0xFFCBD5E1)
val OutlineDark = Color(0xFF475569)

// Predefined Accent Colors
val AccentColors = listOf(
    "#4F46E5", // Indigo (Mặc định)
    "#2563EB", // Blue
    "#10B981", // Emerald
    "#8B5CF6", // Violet
    "#EC4899", // Rose
    "#F59E0B", // Amber
    "#06B6D4"  // Cyan
)

fun parseHexColor(hexString: String): Color {
    return try {
        val clean = hexString.removePrefix("#")
        val colorInt = clean.toLong(16)
        if (clean.length == 6) {
            Color(0xFF000000 or colorInt)
        } else {
            Color(colorInt)
        }
    } catch (e: Exception) {
        PrimaryLight
    }
}
