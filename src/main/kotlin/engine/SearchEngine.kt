package engine

import data.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.*
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.X509TrustManager

// ── Throttle profiles ──────────────────────────────────────────────────────────

/**
 * Per-domain concurrency and pacing parameters.
 *
 * Profiles are assigned per store at search start based on persisted CF throttle rules
 * ([CfThrottleRule] from the DB). Stores with no rule, or whose rule's card threshold
 * is above the current search size, run at NONE (unrestricted).
 *
 *   Tier 1 — first 429 recorded: light throttle, keeps the store useful.
 *   Tier 2 — second independent 429: medium throttle.
 *   Tier 3 — third+ independent 429: heavy throttle.
 */
data class ThrottleProfile(val maxConcurrent: Int, val minDelayMs: Long) {
    companion object {
        val NONE  = ThrottleProfile(2, 0L)
        val TIER1 = ThrottleProfile(2, 700L)
        val TIER2 = ThrottleProfile(1, 1_500L)
        val TIER3 = ThrottleProfile(1, 2_500L)
        fun forTier(tier: Int): ThrottleProfile = when (tier) {
            1    -> TIER1
            2    -> TIER2
            else -> TIER3
        }
    }
}

// ── Per-host rate limiter ──────────────────────────────────────────────────────

/**
 * Installed into the Ktor client via HttpSend.intercept so that every client.get() —
 * including nested handle.js / product-page fetches inside a single card search —
 * is throttled per domain automatically.
 *
 * [hostProfiles] maps domain names (e.g. "greedygold.co.za") to their throttle profile.
 * Hosts not in the map use ThrottleProfile.NONE (no delay, no semaphore).
 */
class PerHostRateLimiter(
    private val hostProfiles: Map<String, ThrottleProfile>,
) {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> withThrottle(host: String, block: suspend () -> T): T {
        val profile = hostProfiles[host] ?: ThrottleProfile.NONE
        if (profile === ThrottleProfile.NONE) return block()
        val sem = semaphores.getOrPut(host) { Semaphore(profile.maxConcurrent) }
        return sem.withPermit {
            delay(profile.minDelayMs)
            block()
        }
    }
}

// ── Internal state ─────────────────────────────────────────────────────────────

private val platformCache  = ConcurrentHashMap<String, Platform>()
private val cfBlockedStores = ConcurrentHashMap.newKeySet<String>()

// Retry delays for transient non-429 errors: 1 s then 5 s then propagate.
private val RETRY_DELAYS_MS = listOf(1_000L, 5_000L)

private fun extractHost(baseUrl: String): String? =
    runCatching { java.net.URI(baseUrl).host?.takeIf { it.isNotBlank() } }.getOrNull()

// ── HTTP client ────────────────────────────────────────────────────────────────

// Accept any certificate — small SA stores often have expired/self-signed certs.
private val permissiveTrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun buildHttpClient(rateLimiter: PerHostRateLimiter): HttpClient {
    val client = HttpClient(CIO) {
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
    // Intercept every request at the send level so all HTTP calls — including nested
    // handle.js / product-page fetches — are throttled per domain.
    client.plugin(HttpSend).intercept { request ->
        rateLimiter.withThrottle(request.url.host) { execute(request) }
    }
    return client
}

// ── Retry / CF-block handling ──────────────────────────────────────────────────

/**
 * Runs [block] with up to 2 retries for transient errors.
 * On a [CloudflareBlockedException]: marks the store blocked for this session,
 * invokes [onCfBlocked] (so the caller can persist the event to the DB), and rethrows.
 */
private suspend fun <T> withRetry(
    baseUrl: String,
    onCfBlocked: ((String) -> Unit)? = null,
    block: suspend () -> T,
): T {
    if (baseUrl in cfBlockedStores) throw CloudflareBlockedException()

    var lastError: Exception? = null
    for (attempt in 0..2) {
        if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            cfBlockedStores.add(baseUrl)
            onCfBlocked?.invoke(baseUrl)
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError!!
}

// ── Per-store search ───────────────────────────────────────────────────────────

// onResults is called once per card as results arrive; onStoreComplete once per store.
suspend fun checkStore(
    client: HttpClient,
    storeName: String,
    baseUrl: String,
    cards: List<String>,
    browserSearcher: BrowserSearcher?,
    onProgress: suspend (String) -> Unit,
    onResults: suspend (List<SearchResult>) -> Unit,
    onStoreComplete: suspend (String) -> Unit,
    // Called on the IO thread when a 429 is encountered; receives the base URL and
    // the number of cards being searched so the caller can persist a throttle rule.
    onCfBlocked: ((baseUrl: String, cardCount: Int) -> Unit)? = null,
) {
    onProgress(storeName)
    // Only successful detections are cached for the session. UNKNOWN/UNREACHABLE results —
    // usually a transient blip or momentary rate-limit — are NOT cached so the store is
    // retried on the next search rather than stuck "check manually" until restart.
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

    // 2 concurrent card-processing lanes per store, with a random jitter before each.
    // Actual HTTP request pacing is handled by the per-host rate limiter in the client.
    val sem = Semaphore(2)
    coroutineScope {
        cards.map { card ->
            async {
                sem.withPermit {
                    delay((500L..2000L).random())
                    val rows = try {
                        val hits = withRetry(
                            baseUrl,
                            onCfBlocked = { url -> onCfBlocked?.invoke(url, cards.size) },
                        ) { searcher(client, baseUrl, card) }
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

// ── Top-level search orchestrator ──────────────────────────────────────────────

suspend fun runSearch(
    cards: List<String>,
    stores: Map<String, String> = STORES,
    onProgress: suspend (String) -> Unit,
    onResults: suspend (List<SearchResult>) -> Unit,
    onStoreComplete: suspend (String) -> Unit,
    // Pass a long-lived BrowserSearcher from the ViewModel so the Playwright browser and its
    // CF-clearance session survive between search clicks. If null, a temporary one is created.
    sharedBrowserSearcher: BrowserSearcher? = null,
) {
    cfBlockedStores.clear()

    // Load persisted CF throttle rules. For each store whose rule's card threshold is
    // met by the current search size, apply the tier's throttle profile to that domain.
    // Stores with no rule, or below threshold, run unrestricted.
    val cfRules = AppDatabase.loadActiveCfRules()
    val hostProfiles: Map<String, ThrottleProfile> = stores.values.mapNotNull { baseUrl ->
        val host = extractHost(baseUrl) ?: return@mapNotNull null
        val rule = cfRules[baseUrl] ?: return@mapNotNull null
        if (cards.size >= rule.cardThreshold) host to ThrottleProfile.forTier(rule.effectiveTier)
        else null
    }.toMap()

    val rateLimiter = PerHostRateLimiter(hostProfiles)
    val client = buildHttpClient(rateLimiter)

    // When a 429 fires, persist a throttle rule for this store so future searches
    // pre-throttle it at the appropriate card-count threshold. The write runs on the
    // same IO thread that caught the 429 — it's a fast upsert and errors are swallowed.
    val cfBlockedCallback: (String, Int) -> Unit = { baseUrl, cardCount ->
        runCatching { AppDatabase.recordCfBlock(baseUrl, cardCount) }
    }

    val hasBrowserStores = stores.values.any { KNOWN_PLATFORMS[it] == Platform.BROWSER }
    val localBrowserSearcher = if (hasBrowserStores && sharedBrowserSearcher == null) {
        val parallelism = (cards.size / 3 + 1).coerceIn(1, 3)
        BrowserSearcher(parallelism)
    } else null
    val effectiveBrowserSearcher = sharedBrowserSearcher ?: localBrowserSearcher

    try {
        coroutineScope {
            stores.map { (name, base) ->
                async(Dispatchers.IO) {
                    checkStore(
                        client, name, base, cards, effectiveBrowserSearcher,
                        onProgress, onResults, onStoreComplete,
                        onCfBlocked = cfBlockedCallback,
                    )
                }
            }.awaitAll()
        }
    } finally {
        client.close()
        localBrowserSearcher?.close()
    }
}
