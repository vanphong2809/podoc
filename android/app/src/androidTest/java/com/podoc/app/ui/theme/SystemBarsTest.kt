package com.podoc.app.ui.theme

import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mediaExitAndResumeRestoreContrastForTheCurrentTheme() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val media = mutableStateOf(false)
        val dark = mutableStateOf(false)
        compose.setContent {
            PoDocTheme(themeMode = if (dark.value) "dark" else "light", dynamicColor = false) {
                if (media.value) MediaSystemBars()
                Box(Modifier.fillMaxSize())
            }
        }
        val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        compose.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }
        compose.runOnIdle { media.value = true }
        compose.runOnIdle { assertFalse(controller.isAppearanceLightStatusBars) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertFalse(controller.isAppearanceLightStatusBars) }
        compose.runOnIdle { media.value = false }
        compose.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }

        // Simulate returning from an external picker that changed system-bar appearance.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }

        compose.runOnIdle { media.value = true }
        compose.runOnIdle { dark.value = true }
        compose.runOnIdle { assertFalse(controller.isAppearanceLightStatusBars) }
        compose.runOnIdle { media.value = false }
        compose.runOnIdle { assertFalse(controller.isAppearanceLightStatusBars) }
        compose.runOnIdle { dark.value = false }
        compose.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }
    }

    @Test fun themedContextsResolveTheirActivity() {
        assertSame(compose.activity, ContextThemeWrapper(compose.activity, android.R.style.Theme_Material).findActivity())
    }
}
