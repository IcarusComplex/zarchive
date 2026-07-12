package androidapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import data.AndroidSearchListRepo
import data.AndroidSearchResultRepo
import data.BuildInfo
import engine.runSearch
import network.CardImageService
import ui.PlatformActions
import ui.SearchViewModel

/**
 * Placeholder Android entry point (Phase 1 of the Android port — see docs/android-port/).
 * Replaced by the real app shell in Phase 6; this only proves the KMP + Android module
 * restructuring builds and runs before any real feature code moves.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaceholderApp()
        }
    }
}

// Phase 4 throwaway verification hook: constructs the real (now-shared) SearchViewModel with
// Android's in-memory stub repos + PlatformActions and runs an actual vm.search() through it —
// not just the raw engine.runSearch used by Phase 3's hook — to prove the ViewModel itself works
// end-to-end on Android (settings persistence, coroutine scope on Dispatchers.Main, etc.). Deleted
// once Phase 6 builds the real search screen.
private suspend fun runPhase4VerificationSearch(): String {
    val vm = SearchViewModel(
        searchListRepo = AndroidSearchListRepo(),
        searchResultRepo = AndroidSearchResultRepo(),
        platformActions = PlatformActions(),
    )
    vm.enabledStores = setOf("The Cantina", "Dracoti")
    vm.query = "Sol Ring"
    vm.search()
    // vm.search() launches on the VM's own scope (Dispatchers.Main); poll briefly for completion
    // since this hook has no Compose recomposition loop of its own driving it forward.
    var waited = 0
    while (vm.isSearching && waited < 20_000) {
        kotlinx.coroutines.delay(200)
        waited += 200
    }
    val summary = vm.results.joinToString("\n") { "${it.store}: ${it.title} - R${it.priceZar} (${it.note})" }
    return "vm.search(): ${vm.statusText}\n$summary\n\ncanInstallUpdate=${vm.canInstallUpdate}"
}

// Phase 5 throwaway verification hook: confirms the real SQLDelight-backed AndroidSearchListRepo
// persists across process kills (not just in-memory for this launch) and seeds exactly once.
// Deleted once Phase 6 builds the real saved-lists screen.
private fun runPhase5VerificationCheck(): String {
    val repo = data.AndroidSearchListRepo()
    val lists = repo.lists.value
    return "Lists: ${lists.size} (${lists.joinToString(", ") { "${it.name} [${it.cards.size} cards]" }})"
}

// Phase 3 throwaway verification hook: exercises the just-moved SearchEngine/Searchers/
// CardImageService stack against real store endpoints from the Android emulator, to prove they
// actually run (not just compile) under the OkHttp engine + jvmCommonMain restructuring. Deleted
// once Phase 6 builds the real search screen.
private suspend fun runPhase3VerificationSearch(): String {
    val stores = mapOf(
        "The Cantina" to "https://thecantina.co.za",
        "Dracoti" to "https://shop.dracoti.co.za",
    )
    val results = mutableListOf<String>()
    runSearch(
        cards = listOf("Sol Ring"),
        stores = stores,
        onProgress = {},
        onResults = { rows -> rows.forEach { results += "${it.store}: ${it.title} - R${it.priceZar} (${it.note})" } },
        onStoreComplete = {},
    )
    val imageService = CardImageService()
    val images = try {
        imageService.resolveImages(listOf("Sol Ring"))
    } finally {
        imageService.close()
    }
    return "Search: ${results.size} results\n${results.joinToString("\n")}\n\nImage resolved: ${images["Sol Ring"] != null}"
}

@Composable
private fun PlaceholderApp() {
    var verification by remember { mutableStateOf("running Phase 3 verification search...") }
    var vmVerification by remember { mutableStateOf("running Phase 4 SearchViewModel verification...") }
    LaunchedEffect(Unit) {
        verification = try {
            runPhase3VerificationSearch()
        } catch (e: Exception) {
            "FAILED: ${e::class.simpleName}: ${e.message}"
        }
        Log.i("ZArchive", "[Phase 3 verification]\n$verification")
    }
    LaunchedEffect(Unit) {
        vmVerification = try {
            runPhase4VerificationSearch()
        } catch (e: Exception) {
            "FAILED: ${e::class.simpleName}: ${e.message}"
        }
        Log.i("ZArchive", "[Phase 4 verification]\n$vmVerification")
    }
    var dbVerification by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        dbVerification = try {
            runPhase5VerificationCheck()
        } catch (e: Exception) {
            "FAILED: ${e::class.simpleName}: ${e.message}"
        }
        Log.i("ZArchive", "[Phase 5 verification]\n$dbVerification")
    }
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("ZArchive Android — Phase 1 skeleton (v${BuildInfo.VERSION})\n\n$verification\n\n$vmVerification\n\n$dbVerification")
        }
    }
}
