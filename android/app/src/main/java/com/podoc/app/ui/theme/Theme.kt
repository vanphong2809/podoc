package com.podoc.app.ui.theme

import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

@Composable
fun PoDocTheme(
    themeMode: String = "system", // "system", "light", "dark"
    dynamicColor: Boolean = true,
    accentColorHex: String = "#4F46E5",
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> {
            val customAccent = parseHexColor(accentColorHex)
            DarkColorScheme.copy(primary = customAccent)
        }
        else -> {
            val customAccent = parseHexColor(accentColorHex)
            LightColorScheme.copy(primary = customAccent)
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val applySystemBars = {
            val window = view.context.findActivity()?.window
            if (window != null) {
                window.statusBarColor = AndroidColor.TRANSPARENT
                // API 26 cannot draw dark navigation icons, so retain a dark fallback there.
                window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    AndroidColor.TRANSPARENT
                } else AndroidColor.BLACK
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = colorScheme.background.luminance() > 0.5f
                insetsController.isAppearanceLightNavigationBars =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && colorScheme.surface.luminance() > 0.5f
            }
        }
        SideEffect { applySystemBars() }
        LifecycleResumeEffect(view, colorScheme) {
            applySystemBars()
            onPauseOrDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
