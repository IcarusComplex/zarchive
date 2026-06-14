package engine

import data.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.*
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

private val platformCache = java.util.concurrent.ConcurrentHashMap<String, Platform>()

// Stores blocked by a Cloudflare challenge this search session.
// Once one card hits a 429 from a store, all remaining cards skip it without firing a request.
private val cfBlockedStores = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

// Retry delays for transient non-429 errors: immediate → 1 s → 5 s → propagate.
private val RETRY_DELAYS_MS = listOf(1_000L, 5_000L)

private suspend fun <T> withRetry(baseUrl: String, block: suspend () -> T): T {
    if (baseUrl in cfBlockedStores) throw CloudflareBlockedException()

    var lastError: Exception? = null
    for (attempt in 0..2) {
        if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            // Mark immediately and give up — retrying a CF challenge makes it worse.
            cfBlockedStores.add(baseUrl)
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError!!
}

// Accept any certificate — small SA stores often have expired/self-signed certs
private val permissiveTrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun buildHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 20_000
        endpoint { connectTimeout = 12_000 }
        https { trustManager = permissiveTrustManager }
    }
    install(HttpRedirect) { checkHttpMethod = false }
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 12_000
    }
    install(DefaultRequest) {
        header(HttpHeaders.UserAgent,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        header(HttpHeaders.Accept,
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8")
        header(HttpHeaders.AcceptLanguage, "en-ZA,en;q=0.9")
        header("sec-ch-ua",
            "\"Google Chrome\";v=\"124\",\"Chromium\";v=\"124\",\"Not-A.Brand\";v=\"99\"")
        header("sec-ch-ua-mobile",   "?0")
        header("sec-ch-ua-platform", "\"Windows\"")
        header("sec-fetch-dest",     "document")
        header("sec-fetch-mode",     "navigate")
        header("sec-fetch-site",     "none")
        header("sec-fetch-user",     "?1")
        header("upgrade-insecure-requests", "1")
    }
}

// onResults is called once per card as results arrive; onStoreComplete is called once per store.
suspend fun checkStore(
    client: HttpClient,
    storeName: String,
    baseUrl: String,
    cards: List<String>,
    browserSearcher: BrowserSearcher?,
    onProgress: suspend (String) -> Unit,
    onResults: suspend (List<SearchResult>) -> Unit,
    onStoreComplete: suspend (String) -> Unit,
) {
    onProgress(storeName)
    // Only successful detections are cached for the session. UNKNOWN/UNREACHABLE results —
    // which are usually a transient network blip or a momentary rate-limit — are NOT cached,
    // so the store is retried on the next search instead of being stuck "check manually" until
    // the app is restarted.
    val platform = platformCache[baseUrl] ?: run {
        val detected = KNOWN_PLATFORMS[baseUrl] ?: detectPlatform(client, baseUrl)
        if (detected != Platform.UNKNOWN && detected != Platform.UNREACHABLE) {
            platformCache[baseUrl] = detected
        }
        detected
    }

    if (platform == Platform.UNKNOWN || platform == Platform.UNREACHABLE) {
        cards.forEach { card ->
            onResults(listOf(SearchResult(
                store = storeName, card = card, title = null,
                priceZar = null, available = null, url = baseUrl,
                note = "[${platform.name.lowercase()} — check manually]",
            )))
        }
        onStoreComplete(storeName)
        return
    }

    if (platform == Platform.BROWSER) {
        if (browserSearcher == null) {
            cards.forEach { card ->
                onResults(listOf(SearchResult(
                    store = storeName, card = card, title = null,
                    priceZar = null, available = null, url = baseUrl,
                    note = "[browser unavailable]",
                )))
            }
        } else {
            for (card in cards) {
                val rows = try {
                    val hits = browserSearcher.search(baseUrl, card)
                    if (hits.isEmpty()) listOf(SearchResult(
                        store = storeName, card = card, title = null,
                        priceZar = null, available = null, url = baseUrl, note = "not stocked",
                    ))
                    else hits.map { it.copy(store = storeName) }
                } catch (e: Exception) {
                    listOf(SearchResult(
                        store = storeName, card = card, title = null,
                        priceZar = null, available = null, url = baseUrl,
                        note = "[error: ${e.message?.take(60)}]",
                    ))
                }
                onResults(rows)
            }
        }
        onStoreComplete(storeName)
        return
    }

    val searcher: suspend (HttpClient, String, String) -> List<SearchResult> = when (platform) {
        Platform.SHOPIFY      -> ::searchShopify
        Platform.WOOCOMMERCE  -> ::searchWooCommerce
        Platform.WC_STORE_API -> ::searchWcStoreApi
        Platform.OPENCART     -> ::searchOpenCart
        Platform.BIGCOMMERCE  -> ::searchBigCommerce
        Platform.PRESTASHOP   -> ::searchPrestaShop
        Platform.WARREN_API   -> ::searchWarrenApi
        else                  -> { _, _, _ -> emptyList() }
    }

    // 2 concurrent lanes per store with a random 500–2000 ms jitter before each request.
    // Keeps traffic human-looking and avoids Cloudflare bot detection.
    val sem = Semaphore(2)
    coroutineScope {
        cards.map { card ->
            async {
                sem.withPermit {
                    delay((500L..2000L).random())
                    val rows = try {
                        val hits = withRetry(baseUrl) { searcher(client, baseUrl, card) }
                        if (hits.isEmpty()) listOf(SearchResult(
                            store = storeName, card = card, title = null,
                            priceZar = null, available = null, url = baseUrl, note = "not stocked",
                        ))
                        else hits.map { it.copy(store = storeName) }
                    } catch (e: Exception) {
                        listOf(SearchResult(
                            store = storeName, card = card, title = null,
                            priceZar = null, available = null, url = baseUrl,
                            note = "[error: ${e.message?.take(60)}]",
                        ))
                    }
                    onResults(rows)
                }
            }
        }.awaitAll()
    }
    onStoreComplete(storeName)
}

suspend fun runSearch(
    cards: List<String>,
    stores: Map<String, String> = STORES,
    onProgress: suspend (String) -> Unit,
    onResults: suspend (List<SearchResult>) -> Unit,
    onStoreComplete: suspend (String) -> Unit,
) {
    cfBlockedStores.clear()
    val client = buildHttpClient()
    val hasBrowserStores = stores.values.any { KNOWN_PLATFORMS[it] == Platform.BROWSER }
    // Scale parallelism with card count: 1 lane per 3 cards, capped at 3.
    // Each lane is a separate headless browser instance on its own thread.
    val browserParallelism = if (hasBrowserStores) (cards.size / 3 + 1).coerceIn(1, 3) else 0
    val browserSearcher = if (hasBrowserStores) BrowserSearcher(browserParallelism) else null
    try {
        coroutineScope {
            stores.map { (name, base) ->
                async(Dispatchers.IO) {
                    checkStore(client, name, base, cards, browserSearcher, onProgress, onResults, onStoreComplete)
                }
            }.awaitAll()
        }
    } finally {
        client.close()
        browserSearcher?.close()
    }
}
