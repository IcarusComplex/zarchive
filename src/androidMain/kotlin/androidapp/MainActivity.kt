package androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import data.AndroidSearchListRepo
import data.AndroidSearchResultRepo
import network.AndroidAuthorizationBridge
import network.AndroidWarrenSearcher
import ui.AndroidApp
import ui.PlatformActions
import ui.SearchViewModel
import ui.theme.HeaderBg
import ui.theme.Surface

class MainActivity : ComponentActivity() {
    // Must be registered unconditionally during initialization (before onCreate reaches STARTED),
    // per the ActivityResultLauncher contract -- backs the Google Drive sign-in consent screen
    // Play Services' Authorization API hands back as a PendingIntent (see
    // network/GoogleOAuthFlow.android.kt).
    private val authorizationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        AndroidAuthorizationBridge.pendingDeferred?.complete(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AndroidAuthorizationBridge.launcher = authorizationLauncher
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
