package co.za.zarchive.poc

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/**
 * Phase 0D spike: Ktor client on the OkHttp engine (genuinely multiplatform — unlike CIO's JVM/
 * Native/JS-only reach at the time this was written), exercising the exact real-world behaviors
 * network/SearchEngine.kt, network/CardImageService.kt, and network/GitHubService.kt depend on:
 * custom User-Agent/Accept headers (Scryfall 400s without both — CLAUDE.md), redirect-follow
 * (GitHubService.downloadRelease's HttpRedirect + checkHttpMethod = false), and streaming download
 * semantics (bodyAsChannel + readAvailable loop).
 */

private fun buildSpikeClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
    }
    install(HttpRedirect) {
        checkHttpMethod = false
    }
}

suspend fun runKtorSpike(): String {
    val results = mutableListOf<String>()
    val client = buildSpikeClient()
    try {
        // 1) Scryfall requires both User-Agent and Accept headers (plain defaults 403/400).
        try {
            val resp = client.get("https://api.scryfall.com/cards/named") {
                parameter("fuzzy", "Lightning Bolt")
                header("User-Agent", "ZArchive-Spike/0.1")
                header("Accept", "application/json")
            }
            results += if (resp.status.isSuccess()) {
                val body = resp.bodyAsText()
                "Scryfall headers: OK (${resp.status.value}, ${body.length} chars, name=${
                    Regex("\"name\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                })"
            } else {
                "Scryfall headers: FAIL (status ${resp.status.value})"
            }
        } catch (e: Exception) {
            results += "Scryfall headers: EXCEPTION ${e::class.simpleName}: ${e.message}"
        }

        // 2) A real store endpoint from ZArchive's STORES list (Shopify platform).
        try {
            val resp = client.get("https://cardcache.co.za/search/suggest.json") {
                parameter("q", "lightning")
                parameter("resources[type]", "product")
            }
            results += if (resp.status.isSuccess()) {
                "Real store GET (cardcache Shopify): OK (${resp.status.value}, ${resp.bodyAsText().length} chars)"
            } else {
                "Real store GET (cardcache Shopify): status ${resp.status.value}"
            }
        } catch (e: Exception) {
            results += "Real store GET: EXCEPTION ${e::class.simpleName}: ${e.message}"
        }

        // 3) Redirect-follow: GitHub /releases/latest 302s to a tag-specific URL.
        try {
            val resp = client.get("https://github.com/JetBrains/compose-multiplatform/releases/latest")
            results += "Redirect-follow (GitHub releases/latest): final status ${resp.status.value}, " +
                "final url host=${resp.request.url.host}${resp.request.url.encodedPath.take(60)}"
        } catch (e: Exception) {
            results += "Redirect-follow: EXCEPTION ${e::class.simpleName}: ${e.message}"
        }

        // 4) Streaming semantics: bodyAsChannel + readAvailable loop (mirrors GitHubService.downloadRelease).
        try {
            val resp = client.get("https://api.scryfall.com/sets") {
                header("User-Agent", "ZArchive-Spike/0.1")
                header("Accept", "application/json")
            }
            val channel = resp.bodyAsChannel()
            var total = 0L
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                total += read
            }
            results += "Streaming download (Scryfall /sets): OK, streamed $total bytes"
        } catch (e: Exception) {
            results += "Streaming download: EXCEPTION ${e::class.simpleName}: ${e.message}"
        }
    } finally {
        client.close()
    }
    return results.joinToString("\n")
}
