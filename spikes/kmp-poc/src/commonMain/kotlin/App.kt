package co.za.zarchive.poc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun App(
    jsoupSpikeResult: String = "",
    ktorSpikeResult: String = "",
    dbSpikeResult: String = "",
    warrenSpikeResult: String = "",
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Hello from commonMain Compose (Phase 0E proof)\n\n" +
                    "Jsoup spike (0A): $jsoupSpikeResult\n\n" +
                    "Ktor/OkHttp spike (0D):\n$ktorSpikeResult\n\n" +
                    "SQLDelight spike (0C): $dbSpikeResult\n\n" +
                    "Warren WebView spike (0B): $warrenSpikeResult"
            )
        }
    }
}
