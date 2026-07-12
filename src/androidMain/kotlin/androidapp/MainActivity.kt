package androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import data.AndroidSearchListRepo
import data.AndroidSearchResultRepo
import network.AndroidWarrenSearcher
import ui.AndroidApp
import ui.PlatformActions
import ui.SearchViewModel
import ui.theme.HeaderBg
import ui.theme.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingCrash = ZArchiveApplication.readAndClearCrashLog()

        // App is always-dark (Arcane Market Ledger palette, see CLAUDE.md) -- match the system bars
        // to it instead of leaving Android's light-theme defaults from the manifest's base theme.
        window.statusBarColor = HeaderBg.toArgb()
        window.navigationBarColor = Surface.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            val vm = remember {
                SearchViewModel(
                    searchListRepo = AndroidSearchListRepo(),
                    searchResultRepo = AndroidSearchResultRepo(),
                    platformActions = PlatformActions(),
                    // Phase 11: a persistent, application-scoped WebView-based searcher for The
                    // Warren, replacing desktop's Playwright-driven BrowserSearcher.
                    warrenSearcher = AndroidWarrenSearcher(),
                )
            }
            AndroidApp(vm, pendingCrash)
        }
    }
}
