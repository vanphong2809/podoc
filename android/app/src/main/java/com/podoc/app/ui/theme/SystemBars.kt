package com.podoc.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}

/** White icons over the black media viewer, with the app appearance restored on exit. */
@Composable
internal fun MediaSystemBars() {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    val defaultStatusIcons by rememberUpdatedState(MaterialTheme.colorScheme.background.luminance() > 0.5f)
    val defaultNavigationIcons by rememberUpdatedState(
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && MaterialTheme.colorScheme.surface.luminance() > 0.5f
    )
    val controller = WindowCompat.getInsetsController(window, view)

    DisposableEffect(window, view) {
        onDispose {
            controller.isAppearanceLightStatusBars = defaultStatusIcons
            controller.isAppearanceLightNavigationBars = defaultNavigationIcons
        }
    }
    SideEffect {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
    LifecycleResumeEffect(window, view) {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onPauseOrDispose { }
    }
}
