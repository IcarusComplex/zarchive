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
import data.BuildInfo
import engine.runSearch
import network.CardImageService

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
    LaunchedEffect(Unit) {
        verification = try {
            runPhase3VerificationSearch()
        } catch (e: Exception) {
            "FAILED: ${e::class.simpleName}: ${e.message}"
        }
        Log.i("ZArchive", "[Phase 3 verification]\n$verification")
    }
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("ZArchive Android — Phase 1 skeleton (v${BuildInfo.VERSION})\n\n$verification")
        }
    }
}
