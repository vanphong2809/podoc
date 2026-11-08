package com.podoc.app.ui.theme

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.podoc.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainActivityInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun librarySearchUsesTheTopSafeAreaOnce() {
        compose.onNode(hasSetTextAction()).assertExists()
        val top = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val density = view.resources.displayMetrics.density
            assertTrue("Search must remain below the status bar/camera: $top vs ${insets.top}", top >= insets.top)
            // The text is vertically centered inside the 50 dp search bar, below its 10 dp margin.
            assertTrue("Top inset was applied more than once: $top vs ${insets.top}", top <= insets.top + 36 * density)
        }
    }
}
