package co.za.zarchive.poc

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val jsoupResult = runJsoupSpike()
    println("[0A spike, desktop] $jsoupResult")
    Window(onCloseRequest = ::exitApplication, title = "KMP POC (desktop)") {
        var ktorResult by remember { mutableStateOf("running...") }
        LaunchedEffect(Unit) {
            ktorResult = runKtorSpike()
            println("[0D spike, desktop]\n$ktorResult")
        }
        App(jsoupResult, ktorResult)
    }
}
