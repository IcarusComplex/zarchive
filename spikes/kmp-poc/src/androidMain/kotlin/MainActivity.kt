package co.za.zarchive.poc

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jsoupResult = runJsoupSpike()
        Log.i("ZArchivePoc", "[0A spike, android] $jsoupResult")
        setContent {
            var ktorResult by remember { mutableStateOf("running...") }
            var dbResult by remember { mutableStateOf("running...") }
            LaunchedEffect(Unit) {
                ktorResult = runKtorSpike()
                Log.i("ZArchivePoc", "[0D spike, android]\n$ktorResult")
            }
            LaunchedEffect(Unit) {
                dbResult = runDbSpike(applicationContext)
                Log.i("ZArchivePoc", "[0C spike, android] $dbResult")
            }
            var warrenResult by remember { mutableStateOf("running (this can take up to ~40s)...") }
            LaunchedEffect(Unit) {
                val mobile = runWarrenWebViewSpike(applicationContext, "dragon", useDesktopUa = false)
                Log.i("ZArchivePoc", "[0B spike, android, mobile UA] $mobile")
                val desktop = runWarrenWebViewSpike(applicationContext, "dragon", useDesktopUa = true)
                Log.i("ZArchivePoc", "[0B spike, android, desktop UA] $desktop")
                warrenResult = "mobile-UA: page1=${mobile.page1Ok} (${mobile.page1Summary}) | " +
                    "desktop-UA: page1=${desktop.page1Ok} (${desktop.page1Summary})"
            }
            App(jsoupResult, ktorResult, dbResult, warrenResult)
        }
    }
}
