package co.za.zarchive.poc

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 0B spike: the highest-uncertainty item in the whole Android port. Proves (or disproves)
 * that the trick network/BrowserSearcher.kt relies on for The Warren — navigate a real browser to
 * the search page so its own React app mints a session and fires the first API call, intercept that
 * response, then reuse the session for paginated follow-up calls — also works from an Android
 * WebView, under a real mobile-vs-desktop User-Agent, against the live production site.
 *
 * Mechanism: WebViewCompat.addDocumentStartJavaScript monkey-patches window.fetch before any page
 * script runs (mirrors Playwright's ctx.addInitScript). The patched fetch reports every matching
 * /product/search response back through a @JavascriptInterface bridge — both the one triggered by
 * the page's own React app on load, AND any subsequent one triggered by a bare evaluateJavascript
 * fetch() call for pagination. This sidesteps evaluateJavascript's real limitation: it does NOT
 * await Promises, so a naive "evaluateJavascript(fetch(...))" would return the Promise's
 * synchronous JSON representation, not the resolved response body — exactly the risk flagged in
 * docs/android-port/phase-0-risk-spikes.md. Routing pagination through the same interception path
 * avoids that trap entirely instead of fighting it.
 */

private const val SEARCH_HOST = "https://thewarren.co.za"
private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
                    if (window.ZArchiveBridge) {
                        window.ZArchiveBridge.onProductSearchResponse(url, text);
                    }
                }).catch(function(e) {
                    if (window.ZArchiveBridge) window.ZArchiveBridge.onError('body-read: ' + e);
                });
            }
            return response;
        }).catch(function(e) {
            if (window.ZArchiveBridge) window.ZArchiveBridge.onError('fetch: ' + e);
            throw e;
        });
    };
})();
"""

private class JsBridge(private val channel: Channel<Pair<String, String>>) {
    @JavascriptInterface
    fun onProductSearchResponse(url: String, json: String) {
        channel.trySend(url to json)
    }

    @JavascriptInterface
    fun onError(message: String) {
        channel.trySend("__error__" to message)
    }
}

data class WarrenSpikeOutcome(
    val label: String,
    val documentStartScriptSupported: Boolean,
    val page1Ok: Boolean,
    val page1Summary: String,
    val page2Ok: Boolean,
    val page2Summary: String,
)

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
suspend fun runWarrenWebViewSpike(context: Context, card: String, useDesktopUa: Boolean): WarrenSpikeOutcome =
    withContext(Dispatchers.Main) {
        val label = if (useDesktopUa) "spoofed desktop-Chrome UA" else "unmodified WebView UA"
        val channel = Channel<Pair<String, String>>(capacity = Channel.UNLIMITED)
        val webView = WebView(context)
        val supportsDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        if (useDesktopUa) {
            webView.settings.userAgentString = DESKTOP_UA
        }
        webView.addJavascriptInterface(JsBridge(channel), "ZArchiveBridge")
        webView.webViewClient = WebViewClientCompat()

        if (supportsDocStart) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                webView, INTERCEPT_SCRIPT, setOf(SEARCH_HOST)
            )
        }

        val encoded = java.net.URLEncoder.encode(card, "UTF-8")
        val searchUrl = "$SEARCH_HOST/search?f=1&q=$encoded&t=1&c=3&sc=7&is=1&o=0"
        webView.loadUrl(searchUrl)

        var page1Ok = false
        var page1Summary = "TIMEOUT waiting for first /product/search response (20s)"
        var page2Ok = false
        var page2Summary = "not attempted (page1 failed)"
        var total = 0
        var firstUrl: String? = null

        val first = withTimeoutOrNull(20_000) { channel.receive() }
        if (first != null) {
            val (url, payload) = first
            if (url == "__error__") {
                page1Summary = "JS-side error: $payload"
            } else {
                firstUrl = url
                android.util.Log.i("ZArchivePoc", "[0B RAW page1, url=$url] ${payload.take(1500)}")
                try {
                    val root = Json.parseToJsonElement(payload).jsonObject
                    val arr = root["data"]?.jsonArray
                    total = arr?.firstOrNull()?.jsonObject?.get("total_records")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val names = arr?.mapNotNull { it.jsonObject["product_name"]?.jsonPrimitive?.content }?.take(5) ?: emptyList()
                    page1Ok = true
                    page1Summary = "OK: ${arr?.size ?: 0} items on page 1, total_records=$total, sample=$names"
                } catch (e: Exception) {
                    page1Summary = "JSON parse failed: ${e.message} (raw len=${payload.length})"
                }
            }
        }

        if (page1Ok && total > 20) {
            val apiPath = "/openapi/1.0.0fe/client/product/search" +
                "?limit=20&offset=20&search_term=$encoded" +
                "&in_stock=1&cmc_filter=e&white=0&blue=0&black=0&red=0&green=0" +
                "&multicoloured=0&colourless=0&is_foil=0&is_token=0&is_art=0" +
                "&swu_cost_filter=e&swu_power_filter=e&swu_hp_filter=e&swu_is_foil=0"
            // Fire-and-forget: don't rely on evaluateJavascript's return value (it doesn't await
            // Promises) — rely on the same window.fetch interception instead.
            webView.evaluateJavascript("fetch('$apiPath');", null)
            val second = withTimeoutOrNull(20_000) { channel.receive() }
            if (second != null) {
                val (url2, payload2) = second
                if (url2 == "__error__") {
                    page2Summary = "JS-side error: $payload2"
                } else {
                    try {
                        val arr2 = Json.parseToJsonElement(payload2).jsonObject["data"]?.jsonArray
                        page2Ok = true
                        page2Summary = "OK: ${arr2?.size ?: 0} items on page 2 (via evaluateJavascript-triggered fetch, " +
                            "captured through the same interception path, url=$url2)"
                    } catch (e: Exception) {
                        page2Summary = "JSON parse failed: ${e.message}"
                    }
                }
            } else {
                page2Summary = "TIMEOUT waiting for paginated response"
            }
        } else if (page1Ok) {
            page2Summary = "not needed (total_records=$total <= 20, no pagination required)"
        }

        webView.destroy()
        WarrenSpikeOutcome(label, supportsDocStart, page1Ok, page1Summary, page2Ok, page2Summary)
    }
