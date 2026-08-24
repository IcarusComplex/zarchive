package engine

import data.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import network.*
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ── Throttle profiles ──────────────────────────────────────────────────────────

/**
 * Per-domain concurrency and pacing parameters.
 *
 * The base tier for every search is determined by total card-store pairs (cards * stores):
 *   < 300  -> tier 1 (light; small searches stay fast)
 *   >= 300 -> tier 2 minimum (medium; large searches throttled to avoid CF rate limits)
 *
 * Per-store escalation stored in the DB can raise the effective tier above the base. Each
 * store tracks two independent tiers — small-bucket and large-bucket — so a 429 on a large
 * search doesn't penalise future small searches, and vice versa.
 *
 * Platform matters: Shopify makes 1 suggest.json + N handle.js requests per card and is
 * aggressively CF-protected. WooCommerce/BigCommerce make 1 request per card and rarely 429.
 * BROWSER (Playwright/WebView) stores bypass the Ktor rate limiter entirely.
 */
data class ThrottleProfile(val maxConcurrent: Int, val minDelayMs: Long) {
    companion object {
        val NONE  = ThrottleProfile(2, 0L)
        // Shopify profiles — applied when search ≥ 300 cards.
        val TIER1 = ThrottleProfile(2, 200L)   // light: ~5 req/s per store
        val TIER2 = ThrottleProfile(1, 500L)   // medium: ~2 req/s, serialised
        val TIER3 = ThrottleProfile(1, 800L)   // heavy: ~1.25 req/s, serialised

        // Cart-mutation endpoints used to probe real stock quantity (Shopify's POST /cart/add.js,
        // WooCommerce's POST /?wc-ajax=add_to_cart) get their own, deliberately much stricter
        // profile — independent of the store's normal browsing throttle above and applied on top
        // of it. These endpoints exist to protect checkout/cart flow from abuse (scalping,
        // cart-stuffing bots), so they're plausibly rate-limited far more aggressively than a
        // plain product-page GET, regardless of how gently we're already pacing the GETs.
        // Confirmed live (Aug 2026): a 17-card search including one "30x" quantity got rate-limited
        // on most Shopify stores on two different networks/IPs even with the general per-host
        // throttle already active and the 429 circuit breaker already firing — the browsing-tier
        // pacing alone wasn't conservative enough for this specific endpoint. Single-flight
        // (maxConcurrent=1) and several seconds apart, deliberately slower than "fast" — a heavy
        // quantity search taking longer to fully resolve is a fine trade for not getting blocked.
        val CART_MUTATION = ThrottleProfile(maxConcurrent = 1, minDelayMs = 2_000L)

        fun forTier(tier: Int): ThrottleProfile = when (tier) {
            1    -> TIER1
            2    -> TIER2
            else -> TIER3
        }

        // Cart-mutation pacing escalates with a store's own CfThrottleRule history exactly like
        // forTier does for browsing -- a store that has already 429'd (Knightly Gaming, Underworld
        // Connections, confirmed Aug 2026) gets slower cart probes on future searches specifically,
        // instead of every Shopify store sharing one flat 2s delay regardless of track record.
        fun cartMutationForTier(tier: Int): ThrottleProfile = when (tier) {
            1    -> CART_MUTATION
            2    -> ThrottleProfile(maxConcurrent = 1, minDelayMs = 3_500L)
            else -> ThrottleProfile(maxConcurrent = 1, minDelayMs = 5_000L)
        }

        // Non-Shopify platforms make far fewer HTTP requests per card and are rarely
        // rate-limited — use lighter profiles to keep large searches reasonably fast.
        fun forTierAndPlatform(tier: Int, platform: Platform): ThrottleProfile = when (platform) {
            Platform.SHOPIFY -> forTier(tier)
            Platform.WOOCOMMERCE, Platform.WC_STORE_API -> when (tier) {
                1    -> ThrottleProfile(3, 0L)
                2    -> ThrottleProfile(2, 150L)
                else -> ThrottleProfile(1, 300L)
            }
            Platform.BIGCOMMERCE -> when (tier) {
                1    -> ThrottleProfile(3, 0L)
                else -> ThrottleProfile(2, 100L)
            }
            Platform.PRESTASHOP -> when (tier) {
                1    -> ThrottleProfile(2, 100L)
                else -> ThrottleProfile(1, 300L)
            }
            else -> forTier(tier)
        }
    }
}

// Set on a request's attributes by a searcher (e.g. probeShopifyStock, wcAddToCartSucceeds) to
// mark it as a cart-mutation stock probe -- see ThrottleProfile.CART_MUTATION and its use in
// buildHttpClient's send interceptor below.
val CART_MUTATION_ATTR: AttributeKey<Boolean> = AttributeKey("cartMutationProbe")

// ── Per-host rate limiter ──────────────────────────────────────────────────────

/**
 * Installed into the Ktor client via HttpSend.intercept so that every client.get() —
 * including nested handle.js / product-page fetches inside a single card search —
 * is throttled per domain automatically.
 *
 * [hostProfiles] maps domain names (e.g. "greedygold.co.za") to their throttle profile.
 * Hosts not in the map use ThrottleProfile.NONE (no delay, no semaphore).
 *
 * The mutex only guards the (fast) get-or-create of a host's semaphore — the actual
 * withPermit/delay/block() execution runs outside the lock, so per-host (and cross-host)
 * concurrency is unaffected, same as the ConcurrentHashMap.getOrPut this replaces.
 */
class PerHostRateLimiter(
    private val hostProfiles: Map<String, ThrottleProfile>,
) {
    private val mutex = Mutex()
    private val semaphores = mutableMapOf<String, Semaphore>()

    suspend fun <T> withThrottle(host: String, block: suspend () -> T): T {
        val profile = hostProfiles[host] ?: ThrottleProfile.NONE
        return withThrottle(host, profile, block)
    }

    /** Looks up a profile by exact key (e.g. a per-store-escalated "$host#cart" bucket), falling
     *  back to [default] when the key wasn't in the map this search built (e.g. no CfThrottleRule
     *  history for that store yet). */
    fun profileFor(key: String, default: ThrottleProfile): ThrottleProfile = hostProfiles[key] ?: default

    // Explicit key/profile overload — used for cart-mutation requests, which key off a distinct
    // "$host#cart" bucket (see CART_MUTATION_ATTR in buildHttpClient) so they're paced by
    // ThrottleProfile.CART_MUTATION independently of that same host's normal browsing traffic,
    // rather than sharing (and being diluted by) the host's regular semaphore/delay.
    suspend fun <T> withThrottle(key: String, profile: ThrottleProfile, block: suspend () -> T): T {
        if (profile === ThrottleProfile.NONE) return block()
        val sem = mutex.withLock { semaphores.getOrPut(key) { Semaphore(profile.maxConcurrent) } }
        return sem.withPermit {
            delay(profile.minDelayMs)
            block()
        }
    }
}

// ── Internal state ─────────────────────────────────────────────────────────────

private val platformCacheMutex = Mutex()
private val platformCache = mutableMapOf<String, Platform>()
private val cfBlockedMutex = Mutex()
private val cfBlockedStores = mutableSetOf<String>()

// Dedupes generic (non-CF) error-log writes within a single search: a store timing out fails the
// same way for every card, and without this every one of those would write its own DB row.
private val errorLogMutex = Mutex()
private val loggedApiErrors = mutableSetOf<Pair<String, String>>()

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

private val permissiveSslContext: SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
}

fun buildHttpClient(rateLimiter: PerHostRateLimiter): HttpClient {
    val client = HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(permissiveSslContext.socketFactory, permissiveTrustManager)
                hostnameVerifier { _, _ -> true }
            }
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
    // handle.js / product-page fetches — are throttled per domain. Requests marked with
    // CART_MUTATION_ATTR (stock-probing cart/add.js and wc-ajax=add_to_cart calls) are paced by
    // their own, much stricter profile under a separate "$host#cart" bucket, instead of sharing
    // the host's regular browsing semaphore/delay. That bucket's profile is looked up per-store
    // (escalated by CfThrottleRule history, same as browsing) with a flat fallback for stores
    // with no escalation on record yet — see ThrottleProfile.cartMutationForTier.
    client.plugin(HttpSend).intercept { request ->
        val host = request.url.host
        if (request.attributes.getOrNull(CART_MUTATION_ATTR) == true) {
            val cartKey = "$host#cart"
            val profile = rateLimiter.profileFor(cartKey, ThrottleProfile.CART_MUTATION)
            rateLimiter.withThrottle(cartKey, profile) { execute(request) }
        } else {
            rateLimiter.withThrottle(host) { execute(request) }
        }
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
    if (cfBlockedMutex.withLock { baseUrl in cfBlockedStores }) throw CloudflareBlockedException()

    var lastError: Exception? = null
    for (attempt in 0..2) {
        if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            cfBlockedMutex.withLock { cfBlockedStores.add(baseUrl) }
            onCfBlocked?.invoke(baseUrl)
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError!!
}

// Timeout exceptions (Ktor's HttpRequestTimeoutException / ConnectTimeoutException, or a raw
// SocketTimeoutException from the underlying engine) embed the full request URL in their message,
// e.g. "Request timeout has expired [url=https://www.thehiddenrealm.co.za/wp-json/...]". A blind
// take(60) on that chops mid-domain (e.g. "...url=https://www.thehiddenrealm."), which reads as a
// mangled/broken URL rather than what it actually is: the store just isn't responding in time.
private fun errorNote(e: Exception): String = when (e) {
    is HttpRequestTimeoutException, is ConnectTimeoutException,
    is KtorSocketTimeoutException, is SocketTimeoutException -> "[timeout]"
    else -> "[error: ${e.message?.take(60)}]"
}

// ── Per-store search ───────────────────────────────────────────────────────────

// onResults is called once per card as results arrive; onStoreComplete once per store.
suspend fun checkStore(
    client: HttpClient,
    storeName: String,
    baseUrl: String,
    cards: List<String>,
    browserSearcher: BrowserBackedSearcher?,
    onProgress: suspend (String) -> Unit,
    onResults: suspend (List<SearchResult>) -> Unit,
    onStoreComplete: suspend (String) -> Unit,
    // Called on the IO thread when a 429 is encountered; receives the base URL and
    // the number of cards being searched so the caller can persist a throttle rule.
    onCfBlocked: ((baseUrl: String, cardCount: Int) -> Unit)? = null,
    // Requested quantity per card; absent cards default to 1. Threaded down to the searcher so
    // platforms with an expensive stock-quantity probe (Shopify/WooCommerce/BigCommerce) can skip
    // it entirely for qty=1 -- any available listing already satisfies a single-copy need
    // regardless of its exact stock count, so probing for qty=1 is pure wasted request volume.
    cardQuantities: Map<String, Int> = emptyMap(),
) {
    onProgress(storeName)
    // Only successful detections are cached for the session. UNKNOWN/UNREACHABLE results —
    // usually a transient blip or momentary rate-limit — are NOT cached so the store is
    // retried on the next search rather than stuck "check manually" until restart.
    val platform = platformCacheMutex.withLock { platformCache[baseUrl] } ?: run {
        val detected = KNOWN_PLATFORMS[baseUrl] ?: detectPlatform(client, baseUrl)
        if (detected != Platform.UNKNOWN && detected != Platform.UNREACHABLE) {
            platformCacheMutex.withLock { platformCache[baseUrl] = detected }
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

    val searcher: suspend (HttpClient, String, String, Int) -> List<SearchResult> = when (platform) {
        Platform.SHOPIFY      -> ::searchShopify
        Platform.WOOCOMMERCE  -> ::searchWooCommerce
        Platform.WC_STORE_API -> ::searchWcStoreApi
        Platform.OPENCART     -> ::searchOpenCart
        Platform.BIGCOMMERCE  -> ::searchBigCommerce
        Platform.PRESTASHOP   -> ::searchPrestaShop
        Platform.WARREN_API   -> ::searchWarrenApi
        Platform.UNTAPPED_API -> ::searchUntappedPotential
        else                  -> { _, _, _, _ -> emptyList() }
    }

    // 2 concurrent card-processing lanes per store, with a random jitter before each.
    // Actual HTTP request pacing is handled by the per-host rate limiter in the client.
    val sem = Semaphore(2)
    coroutineScope {
        cards.map { card ->
            async {
                sem.withPermit {
                    delay((500L..2000L).random())
                    val qty = cardQuantities[card] ?: 1
                    val rows = try {
                        val hits = withRetry(
                            baseUrl,
                            onCfBlocked = { url ->
                                runCatching {
                                    recordApiError(storeName, url, "BACKOFF", "Cloudflare rate-limit (429) -- store skipped for the rest of this search")
                                }
                                onCfBlocked?.invoke(url, cards.size)
                            },
                        ) { searcher(client, baseUrl, card, qty) }
                        if (hits.isEmpty()) listOf(SearchResult(
                            store = storeName, card = card, title = null,
                            priceZar = null, available = null, url = baseUrl, note = "not stocked",
                        ))
                        else hits.map { it.copy(store = storeName) }
                    } catch (e: Exception) {
                        // The Cloudflare-block case is already logged once above, via onCfBlocked --
                        // don't double-log it here for every card that fast-fails against a store
                        // already known to be blocked this search.
                        if (e !is CloudflareBlockedException) {
                            val msg = e.message?.take(120) ?: e::class.simpleName ?: "unknown error"
                            val isNew = errorLogMutex.withLock { loggedApiErrors.add(storeName to msg) }
                            if (isNew) {
                                val kind = if (errorNote(e) == "[timeout]") "TIMEOUT" else "ERROR"
                                runCatching { recordApiError(storeName, baseUrl, kind, msg) }
                            }
                        }
                        listOf(SearchResult(
                            store = storeName, card = card, title = null,
                            priceZar = null, available = null, url = baseUrl,
                            note = errorNote(e),
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
    // Called (on an IO thread) when a store is rate-limited by Cloudflare. Receives the store name.
    onStoreCfBlocked: ((String) -> Unit)? = null,
    // Pass a long-lived BrowserBackedSearcher from the ViewModel so the browser session (Playwright
    // on desktop, WebView on Android) survives between search clicks. If null, and no
    // [createBrowserSearcher] factory is supplied either, browser-backed stores are skipped.
    sharedBrowserSearcher: BrowserBackedSearcher? = null,
    // Platform-specific factory for a temporary browser searcher when no shared instance is
    // supplied (desktop passes `{ parallelism -> BrowserSearcher(parallelism) }`). Keeps this
    // shared orchestrator free of any compile-time dependency on a concrete Playwright/WebView type.
    createBrowserSearcher: ((parallelism: Int) -> BrowserBackedSearcher)? = null,
    // Requested quantity per card; see checkStore's cardQuantities doc.
    cardQuantities: Map<String, Int> = emptyMap(),
) {
    cfBlockedMutex.withLock { cfBlockedStores.clear() }
    errorLogMutex.withLock { loggedApiErrors.clear() }

    // Determine the base tier from total card-store pairs: < 300 = tier 1, >= 300 = tier 2.
    // This base applies to every store; per-store DB history can only raise it, never lower it.
    // BROWSER stores are excluded — they manage their own concurrency.
    val totalSearches = cards.size * stores.size
    val isLargeSearch = totalSearches >= 300
    val baseTier = if (isLargeSearch) 2 else 1

    val cfRules = loadActiveCfThrottleRules()
    val hostProfiles: Map<String, ThrottleProfile> = stores.values.flatMap { baseUrl ->
        val platform = KNOWN_PLATFORMS[baseUrl] ?: Platform.SHOPIFY
        if (platform == Platform.BROWSER || platform == Platform.UNKNOWN || platform == Platform.UNREACHABLE)
            return@flatMap emptyList()
        // UNTAPPED_API's actual requests go to a shared Supabase host, not the store's own
        // domain — throttle that host directly rather than the (never-requested) store host.
        val host = if (platform == Platform.UNTAPPED_API) UNTAPPED_SUPABASE_HOST
                   else extractHost(baseUrl) ?: return@flatMap emptyList()
        val rule = cfRules[baseUrl]
        val tier = if (rule == null) baseTier
                   else if (isLargeSearch) maxOf(baseTier, rule.tierLarge)
                   else maxOf(baseTier, rule.tierSmall)
        // Cart-mutation bucket ("$host#cart") escalates with the same per-store tier as browsing
        // — see ThrottleProfile.cartMutationForTier.
        listOf(
            host to ThrottleProfile.forTierAndPlatform(tier, platform),
            "$host#cart" to ThrottleProfile.cartMutationForTier(tier),
        )
    }.toMap()

    val rateLimiter = PerHostRateLimiter(hostProfiles)
    val client = buildHttpClient(rateLimiter)

    val hasBrowserStores = stores.values.any { KNOWN_PLATFORMS[it] == Platform.BROWSER }
    val localBrowserSearcher = if (hasBrowserStores && sharedBrowserSearcher == null) {
        val parallelism = (cards.size / 3 + 1).coerceIn(1, 3)
        createBrowserSearcher?.invoke(parallelism)
    } else null
    val effectiveBrowserSearcher = sharedBrowserSearcher ?: localBrowserSearcher

    try {
        coroutineScope {
            stores.map { (name, base) ->
                async(Dispatchers.IO) {
                    checkStore(
                        client, name, base, cards, effectiveBrowserSearcher,
                        onProgress, onResults, onStoreComplete,
                        onCfBlocked = { url, cardCount ->
                            runCatching { recordCfThrottleBlock(url, cardCount, isLargeSearch) }
                            onStoreCfBlocked?.invoke(name)
                        },
                        cardQuantities = cardQuantities,
                    )
                }
            }.awaitAll()
        }
    } finally {
        client.close()
        localBrowserSearcher?.close()
    }
}
