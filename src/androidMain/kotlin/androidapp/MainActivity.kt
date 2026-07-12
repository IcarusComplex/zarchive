package androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import data.AndroidSearchListRepo
import data.AndroidSearchResultRepo
import ui.AndroidApp
import ui.PlatformActions
import ui.SearchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm = remember {
                SearchViewModel(
                    searchListRepo = AndroidSearchListRepo(),
                    searchResultRepo = AndroidSearchResultRepo(),
                    platformActions = PlatformActions(),
                    // warrenSearcher stays null until Phase 11's WebView-based searcher exists —
                    // The Warren is simply skipped (SearchEngine.runSearch's existing behavior for
                    // a browser-backed store with no searcher supplied), not a crash.
                )
            }
            AndroidApp(vm)
        }
    }
}
