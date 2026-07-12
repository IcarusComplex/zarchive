package androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import data.AndroidSearchListRepo
import data.AndroidSearchResultRepo
import network.AndroidWarrenSearcher
import ui.AndroidApp
import ui.PlatformActions
import ui.SearchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingCrash = ZArchiveApplication.readAndClearCrashLog()
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
