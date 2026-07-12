package network

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidapp.ZArchiveApplication
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import data.SearchResult
import data.isRelevant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

private const val SEARCH_HOST = "https://thewarren.co.za"

// Monkey-patches window.fetch before any page script runs (WebViewCompat.addDocumentStartJavaScript
// mirrors Playwright's ctx.addInitScript on desktop), reporting every /product/search response back
// through a @JavascriptInterface bridge. This covers both the page's own React app firing its first
// API call on load AND any subsequent evaluateJavascript-triggered fetch() used for pagination --
// deliberately not relying on evaluateJavascript's return value, since it does not await Promises
// (a documented Android WebView limitation with no desktop-Playwright equivalent; validated in the
// Phase 0B spike, spikes/kmp-poc/src/androidMain/kotlin/WarrenWebViewSpike.kt).
private const val INTERCEPT_SCRIPT = """
(function() {
    if (window.__zarchiveFetchPatched) return;
    window.__zarchiveFetchPatched = true;
    const origFetch = window.fetch;
    window.fetch = function(...args) {
        const urlArg = args[0];
        const url = typeof urlArg === 'string' ? urlArg : (urlArg && urlArg.url) || '';
        return origFetch.apply(this, args).then(function(response) {
            if (url.indexOf('/product/search') !== -1) {
                response.clone().text().then(function(text) {
                    if (window.ZArchiveWarrenBridge) {
                        window.ZArchiveWarrenBridge.onProductSearchResponse(text);
                    }
                }).catch(function(e) {
                    if (window.ZArchiveWarrenBridge) window.ZArchiveWarrenBridge.onError('body-read: ' + e);
                });
            }
            return response;
        }).catch(function(e) {
            if (window.ZArchiveWarrenBridge) window.ZArchiveWarrenBridge.onError('fetch: ' + e);
            throw e;
        });
    };
})();
"""

/**
 * Android's replacement for desktop's Playwright-driven `BrowserSearcher` for The Warren (the only
 * `Platform.BROWSER` store) -- a persistent, application-scoped `WebView` instead of a headless
 * Chromium instance, using the fetch-interception mechanism validated in the Phase 0B spike.
 * Session cookies persist across searches because the same `WebView` instance is reused (each
 * search navigates it to a fresh search URL, mirroring desktop's `page.navigate` reuse of one
 * persistent `warrenPage`).
 *
 * `SearchEngine.checkStore` calls `search()` once per card, sequentially (not concurrently) for the
 * BROWSER platform, so the `Mutex` here is a defensive guard against unexpected concurrent
 * invocation rather than a real contention point in practice.
 */
class AndroidWarrenSearcher : BrowserBackedSearcher {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var channel = Channel<String>(Channel.UNLIMITED)

    private inner class Bridge {
        @JavascriptInterface
        fun onProductSearchResponse(json: String) {
            channel.trySend(json)
        }

        @JavascriptInterface
        fun onError(message: String) {
            channel.trySend("__error__:$message")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val wv = WebView(ZArchiveApplication.appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        wv.addJavascriptInterface(Bridge(), "ZArchiveWarrenBridge")
        wv.webViewClient = WebViewClientCompat()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, INTERCEPT_SCRIPT, setOf(SEARCH_HOST))
        }
        // This WebView is never attached to a visible view hierarchy (it's a headless data
        // fetcher, not UI) -- Chromium can throttle/pause JS timers and fetch resolution on a
        // WebView it considers not visible/foregrounded, so explicitly resume it. Flagged as an
        // unvalidated risk in the Phase 0B spike notes; onResume()/resumeTimers() is the fix.
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        wv
    }

    override suspend fun search(base: String, card: String): List<SearchResult> = mutex.withLock {
        val wv = ensureWebView()
        withContext(Dispatchers.Main) { wv.onResume(); wv.resumeTimers() }
        // Drain any stale/late responses from a previous search before starting a new one.
        while (channel.tryReceive().isSuccess) { /* discard */ }

        val encoded = URLEncoder.encode(card, "UTF-8")
        val searchUrl = "$SEARCH_HOST/search?f=1&q=$encoded&t=1&c=3&sc=7&is=1&o=0"
        withContext(Dispatchers.Main) { wv.loadUrl(searchUrl) }

        val firstJson = withTimeoutOrNull(20_000) { channel.receive() }
            ?.takeUnless { it.startsWith("__error__:") } ?: return@withLock emptyList()

        val results = parseWarrenApiJson(firstJson, card).toMutableList()
        val total = Json.parseToJsonElement(firstJson).jsonObject["data"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("total_records")
            ?.jsonPrimitive?.intOrNull ?: 0
        val pageSize = 20
        if (total > pageSize) {
            var offset = pageSize
            while (offset < total) {
                val apiPath = "/openapi/1.0.0fe/client/product/search" +
                    "?limit=$pageSize&offset=$offset&search_term=$encoded" +
                    "&in_stock=1&cmc_filter=e&white=0&blue=0&black=0&red=0&green=0" +
                    "&multicoloured=0&colourless=0&is_foil=0&is_token=0&is_art=0" +
                    "&swu_cost_filter=e&swu_power_filter=e&swu_hp_filter=e&swu_is_foil=0"
                // Fire-and-forget: evaluateJavascript does not await Promises, so its return value
                // is useless here -- the already-patched window.fetch interceptor reports the
                // resolved body back through the same bridge/channel used for page 1.
                withContext(Dispatchers.Main) { wv.evaluateJavascript("fetch('$apiPath');", null) }
                val moreJson = withTimeoutOrNull(15_000) { channel.receive() }
                    ?.takeUnless { it.startsWith("__error__:") } ?: break
                results.addAll(parseWarrenApiJson(moreJson, card))
                offset += pageSize
            }
        }
        results.distinctBy { it.url }
    }

    private fun parseWarrenApiJson(json: String, card: String): List<SearchResult> {
        return try {
            val arr = Json.parseToJsonElement(json).jsonObject["data"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["product_name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (!isRelevant(card, title)) return@mapNotNull null
                val available = (obj["stock_available"]?.jsonPrimitive?.intOrNull ?: 1) > 0
                val priceRaw = obj["discount_price"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
                    ?: obj["actual_price"]?.jsonPrimitive?.doubleOrNull
                val productId = obj["product_id"]?.jsonPrimitive?.longOrNull
                val variantId = obj["mtg_product_variant_id"]?.jsonPrimitive?.longOrNull
                SearchResult(
                    store = "", card = card, title = title,
                    priceZar = priceRaw,
                    available = available,
                    url = "https://thewarren.co.za/mtg?id=$productId",
                    note = if (available) "In stock" else "Out of stock",
                    variantId = variantId,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Android has no explicit app-quit workflow, so this realistically never runs during normal
    // use -- the WebView lives for the process's lifetime. Best-effort cleanup if it ever is called.
    override fun close() {
        webView?.destroy()
        webView = null
    }
}
