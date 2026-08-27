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
        // ── TEMPORARY EXPERIMENT (requested live, Aug 2026) ─────────────────────────────────
        // Forces every request -- browsing AND cart-mutation probes, every tier, every platform --
        // to a single in-flight request per host with a flat 20s delay between requests. Goal:
        // isolate "pacing" from "IP reputation" as the cause of today's rate limits by proving/
        // disproving whether pure pacing, taken to an extreme, avoids Cloudflare blocks even on a
        // heavily-tested IP. Not a permanent design -- flip EXTREME_SLOW_MODE back to false (or
        // delete this block) once the experiment concludes; the normal tiered profiles below are
        // left untouched so reverting is a one-line change.
        private const val EXTREME_SLOW_MODE = true
        private val EXTREME_PROFILE = ThrottleProfile(maxConcurrent = 1, minDelayMs = 20_000L)

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

        fun forTier(tier: Int): ThrottleProfile {
            if (EXTREME_SLOW_MODE) return EXTREME_PROFILE
            return when (tier) {
                1    -> TIER1
                2    -> TIER2
                else -> TIER3
            }
        }

        // Cart-mutation pacing escalates with a store's own CfThrottleRule history exactly like
        // forTier does for browsing -- a store that has already 429'd (Knightly Gaming, Underworld
        // Connections, confirmed Aug 2026) gets slower cart probes on future searches specifically,
        // instead of every Shopify store sharing one flat 2s delay regardless of track record.
        fun cartMutationForTier(tier: Int): ThrottleProfile {
            if (EXTREME_SLOW_MODE) return EXTREME_PROFILE
            return when (tier) {
                1    -> CART_MUTATION
                2    -> ThrottleProfile(maxConcurrent = 1, minDelayMs = 3_500L)
                else -> ThrottleProfile(maxConcurrent = 1, minDelayMs = 5_000L)
            }
        }

        // Non-Shopify platforms make far fewer HTTP requests per card and are rarely
        // rate-limited — use lighter profiles to keep large searches reasonably fast.
        fun forTierAndPlatform(tier: Int, platform: Platform): ThrottleProfile {
            if (EXTREME_SLOW_MODE) return EXTREME_PROFILE
            return when (platform) {
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
}

// Set on a request's attributes by a searcher (e.g. probeShopifyStock, wcAddToCartSucceeds) to
// mark it as a cart-mutation stock probe -- see ThrottleProfile.CART_MUTATION and its use in
// buildHttpClient's send interceptor below.
val CART_MUTATION_ATTR: AttributeKey<Boolean> = AttributeKey("cartMutationProbe")

/**
 * Delays a store's first-ever request by `index * stepMs + jitter`, spreading many stores' first
 * contact out over time instead of every store firing its first request within the same ~1-2s
 * window (see `runSearch`'s per-card jitter, which only staggers *within* a store). Confirmed live
 * (Aug 2026): a dozen independent Shopify zones all showed a Cloudflare Managed Challenge within
 * the same second at search launch on a fresh IP -- a cross-domain "spray" of first contacts to many
 * independent zones in a couple seconds is itself a plausible bot signal, unaddressed by any
 * per-host pacing since it's a *cross*-host pattern. SMALL searches get no stagger at all -- the
 * common case (most searches) stays exactly as fast as before this existed.
 */
data class StaggerProfile(val stepMs: Long, val jitterMs: LongRange) {
    companion object {
        val NONE   = StaggerProfile(0L, 0L..0L)
        val MEDIUM = StaggerProfile(400L, 0L..300L)   // ~19 stores spread across ~7.6s
        val LARGE  = StaggerProfile(900L, 0L..500L)   // ~19 stores spread across ~17s

        fun forCategory(category: SearchCategory): StaggerProfile = when (category) {
            SearchCategory.SMALL  -> NONE
            SearchCategory.MEDIUM -> MEDIUM
            SearchCategory.LARGE  -> LARGE
        }
    }
}

// ── Per-host rate limiter ──────────────────────────────────────────────────────

/**
 * Installed into the Ktor client via HttpSend.intercept so that every client.get() —
 * including nested handle.js / product-page fetches inside a single card search —
 * is throttled per domain automatically.
 *
 * [hostProfiles] maps domain names (e.g. "greedygold.co.za") to their browsing throttle profile.
 * [cartProfiles] maps the same domains to their (much stricter) cart-mutation stock-probe profile,
 * for hosts where probing is possible (Shopify/WooCommerce). Hosts not in [hostProfiles] use
 * ThrottleProfile.NONE (no delay, no semaphore).
 *
 * Browsing and cart-mutation requests to the SAME host share ONE semaphore/queue, not two
 * independent ones. Confirmed live (Aug 2026, full request-level trace log): several stores got
 * Cloudflare-blocked on a plain suggest.json/product.js GET, not a cart probe -- each lane
 * individually paced "politely," but their *combined* rate to the same domain exceeded the site's
 * real limit (observed: blocks landed consistently around 40-50 total requests/minute, well under
 * what browsing+cart running as two independent "polite" lanes could add up to). Whatever's
 * protecting these stores evidently tracks total request volume to the domain, not per-endpoint
 * volume, so our own budget has to be combined too.
 *
 * The mutex only guards the (fast) get-or-create of a host's semaphore — the actual
 * withPermit/delay/block() execution runs outside the lock, so per-host (and cross-host)
 * concurrency is unaffected, same as the ConcurrentHashMap.getOrPut this replaces.
 */
class PerHostRateLimiter(
    private val hostProfiles: Map<String, ThrottleProfile>,
    private val cartProfiles: Map<String, ThrottleProfile> = emptyMap(),
) {
    private val mutex = Mutex()
    private val semaphores = mutableMapOf<String, Semaphore>()

    // The longest single pre-request delay any host's profile configures this search -- exposed for
    // diagnostics/testing. NOT used to size any Ktor-level timeout (an earlier version of this fix
    // tried that and was wrong -- see withThrottle's own doc comment for why: real queueing time
    // under sustained demand isn't bounded by a single delay value, so no fixed formula based on it
    // can work as a request-timeout budget).
    val maxDelayMs: Long =
        (hostProfiles.values.asSequence() + cartProfiles.values.asSequence())
            .map { it.minDelayMs }.maxOrNull() ?: 0L

    suspend fun <T> withThrottle(host: String, isCartMutation: Boolean, block: suspend () -> T): T {
        val profile = if (isCartMutation) cartProfiles[host] ?: ThrottleProfile.CART_MUTATION
                      else hostProfiles[host] ?: ThrottleProfile.NONE
        if (profile === ThrottleProfile.NONE) return block()
        val key = host
        // A host with any cart-mutation traffic must serialize to exactly 1 in-flight request of
        // EITHER type -- that's what makes the combined budget real instead of nominal. Hosts that
        // never do cart mutations keep their own browsing profile's concurrency (2-3 for light
        // platforms/tiers) since there's no combined-lane risk for them.
        val sem = mutex.withLock {
            semaphores.getOrPut(key) {
                val cap = if (cartProfiles.containsKey(host)) 1 else profile.maxConcurrent
                Semaphore(cap)
            }
        }
        val queuedAt = System.currentTimeMillis()
        traceLog(
            "throttle",
            "$key: waiting for permit (isCartMutation=$isCartMutation, available=${sem.availablePermits}, minDelayMs=${profile.minDelayMs})",
        )
        return sem.withPermit {
            val acquiredAt = System.currentTimeMillis()
            traceLog("throttle", "$key: permit acquired after ${acquiredAt - queuedAt}ms wait, delaying ${profile.minDelayMs}ms")
            delay(profile.minDelayMs)
            val startedAt = System.currentTimeMillis()
            traceLog("throttle", "$key: executing request (isCartMutation=$isCartMutation)")
            try {
                // Bounds only the actual network attempt, separately from Ktor's own client-level
                // HttpTimeout (which starts its clock from the original client.get()/post() call --
                // i.e. BEFORE this function's own queueing wait and delay() above). Confirmed live
                // (Aug 2026, 20s extreme-pacing experiment): under sustained multi-lane demand, a
                // host's semaphore queue backs up (confirmed earlier in the same investigation --
                // permit-acquire waits climbing past a minute), and that queueing time counts against
                // ANY fixed client-level requestTimeoutMillis regardless of its value -- no fixed
                // number can outrun an unbounded queue. Wrapping just block() here decouples "how
                // long did our own pacing make this wait" from "how long do we give the real network
                // call" -- which is the only correct place to bound the latter.
                withTimeout(NETWORK_CALL_TIMEOUT_MS) { block() }.also {
                    traceLog("throttle", "$key: request completed in ${System.currentTimeMillis() - startedAt}ms")
                }
            } catch (e: Exception) {
                traceLog("throttle", "$key: request failed after ${System.currentTimeMillis() - startedAt}ms (${e::class.simpleName}: ${e.message})")
                throw e
            }
        }
    }

    companion object {
        // Generous budget for one actual network attempt (post-queueing, post-pacing-delay) --
        // covers a slow SA store server plus TLS handshake with real headroom, independent of
        // however long the request sat waiting for its turn above. Matches socketTimeoutMillis in
        // buildHttpClient -- raised from 30s after confirming live (Aug 2026) that The Hidden
        // Realm's search endpoint genuinely takes up to ~26s to respond on requests that do succeed.
        private const val NETWORK_CALL_TIMEOUT_MS = 60_000L
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
            // Ktor's own request timeout clock starts from the original client.get()/post() call --
            // BEFORE our HttpSend interceptor's own queueing wait + pacing delay run (see
            // PerHostRateLimiter.withThrottle) -- so it can't be sized off any single delay value:
            // under sustained demand a host's semaphore queue can back up well past that (confirmed
            // live, Aug 2026 -- see withThrottle's doc comment). This is now just a generous, static
            // backstop that should essentially never fire; the actual per-network-attempt bound lives
            // in withThrottle's own withTimeout(NETWORK_CALL_TIMEOUT_MS) around block(), which is
            // correctly scoped to start only once the real send begins.
            requestTimeoutMillis = 30 * 60 * 1000L // 30 minutes
            connectTimeoutMillis = 12_000
            // Never explicitly set before -- OkHttp's own default read timeout (10s) was silently
            // governing instead. Confirmed live (Aug 2026): The Hidden Realm's WooCommerce Store API
            // search endpoint is genuinely slow and inconsistent -- real observed response times of
            // 11.6s and 25.7s on requests that DID succeed, some others never returning within 30s
            // at all. Set generously (matches PerHostRateLimiter.NETWORK_CALL_TIMEOUT_MS) so a
            // slow-but-real server gets a real chance to finish rather than being cut off by a
            // default nobody chose, separate from connectTimeoutMillis (TCP handshake only) and
            // requestTimeoutMillis (overall clock, effectively unbounded above).
            socketTimeoutMillis = 60_000
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
    // CART_MUTATION_ATTR (stock-probing cart/add.js and wc-ajax=add_to_cart calls) get a much
    // stricter per-request delay, but share the SAME per-host queue as browsing requests -- see
    // PerHostRateLimiter's doc comment for why a combined budget matters.
    client.plugin(HttpSend).intercept { request ->
        val host = request.url.host
        val isCartMutation = request.attributes.getOrNull(CART_MUTATION_ATTR) == true
        rateLimiter.withThrottle(host, isCartMutation) { execute(request) }
    }
    return client
}

// ── Retry / CF-block handling ──────────────────────────────────────────────────

/**
 * Runs [block] with up to 2 retries for transient errors.
 * On a [CloudflareBlockedException]: marks the store blocked for this session,
 * invokes [onCfBlocked] with the request URL + full response detail that got the 429 (so the
 * caller can persist the event to the DB) -- only on the block that actually flips the store from
 * unblocked to blocked, not on every concurrently-running card that races into this catch before
 * the shared cfBlockedStores state updates -- and rethrows.
 */
private suspend fun <T> withRetry(
    baseUrl: String,
    onCfBlocked: ((baseUrl: String, requestUrl: String?, detail: String?) -> Unit)? = null,
    block: suspend () -> T,
): T {
    if (cfBlockedMutex.withLock { baseUrl in cfBlockedStores }) {
        traceLog("retry", "$baseUrl: fast-fail, already blocked this search")
        throw CloudflareBlockedException()
    }

    var lastError: Exception? = null
    for (attempt in 0..2) {
        if (attempt > 0) {
            traceLog("retry", "$baseUrl: attempt $attempt after ${RETRY_DELAYS_MS[attempt - 1]}ms delay (previous: ${lastError?.let { "${it::class.simpleName}: ${it.message}" }})")
            delay(RETRY_DELAYS_MS[attempt - 1])
        }
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            val isNewBlock = cfBlockedMutex.withLock { cfBlockedStores.add(baseUrl) }
            traceLog("retry", "$baseUrl: Cloudflare block on ${e.url} (isNewBlock=$isNewBlock)")
            if (isNewBlock) onCfBlocked?.invoke(baseUrl, e.url, e.detail)
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
                    traceLog("card", "$storeName: \"$card\" lane acquired (available=${sem.availablePermits})")
                    val laneStartedAt = System.currentTimeMillis()
                    delay((500L..2000L).random())
                    val qty = cardQuantities[card] ?: 1
                    val rows = try {
                        val hits = withRetry(
                            baseUrl,
                            onCfBlocked = { url, requestUrl, detail ->
                                runCatching {
                                    recordApiError(
                                        storeName,
                                        requestUrl ?: url,
                                        "BACKOFF",
                                        "Cloudflare rate-limit (429) on ${requestUrl ?: "unknown request"} -- store skipped for the rest of this search",
                                        detail,
                                    )
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
                                val detail = if (e is HttpStatusException) e.detail else null
                                runCatching { recordApiError(storeName, baseUrl, kind, msg, detail) }
                            }
                        }
                        listOf(SearchResult(
                            store = storeName, card = card, title = null,
                            priceZar = null, available = null, url = baseUrl,
                            note = errorNote(e),
                        ))
                    }
                    traceLog("card", "$storeName: \"$card\" lane done in ${System.currentTimeMillis() - laneStartedAt}ms -- ${rows.size} row(s)")
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

    // Holistic classification (see SearchClassifier): replaces the old isLargeSearch/baseTier +
    // separate mayProbeStock split with one weighted request-volume estimate that already accounts
    // for quantity-probing's extra cost, not a bolted-on boolean checked after the fact. Drives both
    // the base governance tier below and the cross-store start stagger further down.
    val estimate = SearchClassifier.classify(cards, cardQuantities, stores)
    val category = estimate.category
    val baseTier = when (category) {
        SearchCategory.SMALL  -> 1
        SearchCategory.MEDIUM -> 2
        SearchCategory.LARGE  -> 3
    }

    // Only searches that actually request more than 1 copy of something ever trigger a
    // cart-mutation stock probe (see checkStore's qty computation) -- the overwhelmingly common
    // plain search (every card at qty=1) never sends one, so it should keep its normal browsing
    // concurrency rather than being pre-emptively serialized "just in case." This answers a
    // narrower question than `category` (whether a host needs a combined budget at all, vs. how
    // strict governance should be overall) -- kept separate on purpose, not folded together.
    val mayProbeStock = cardQuantities.values.any { it > 1 }

    traceReset(
        "runSearch: ${cards.size} card(s) x ${stores.size} store(s), weight=${estimate.totalWeight}, " +
            "category=$category, baseTier=$baseTier, mayProbeStock=$mayProbeStock",
    )

    val cfRules = loadActiveCfThrottleRules()
    val hostProfiles = mutableMapOf<String, ThrottleProfile>()
    val cartProfiles = mutableMapOf<String, ThrottleProfile>()
    for (baseUrl in stores.values) {
        val platform = KNOWN_PLATFORMS[baseUrl] ?: Platform.SHOPIFY
        if (platform == Platform.BROWSER || platform == Platform.UNKNOWN || platform == Platform.UNREACHABLE)
            continue
        // UNTAPPED_API's actual requests go to a shared Supabase host, not the store's own
        // domain — throttle that host directly rather than the (never-requested) store host.
        val host = if (platform == Platform.UNTAPPED_API) UNTAPPED_SUPABASE_HOST
                   else extractHost(baseUrl) ?: continue
        val rule = cfRules[baseUrl]
        val tier = if (rule == null) baseTier else maxOf(baseTier, rule.tierFor(category))
        hostProfiles[host] = ThrottleProfile.forTierAndPlatform(tier, platform)
        // Only Shopify/WooCommerce ever send a cart-mutation stock probe (see CART_MUTATION_ATTR
        // usages in Searchers.kt), and only when this search actually has a qty>1 card somewhere.
        // Only give those hosts a cart profile in that case -- a host with a cart profile entry
        // shares one combined, fully-serialized budget across browsing and cart-mutation traffic
        // (see PerHostRateLimiter), which is the safety this search needs but a plain qty=1 search
        // (the common case, never sends a probe at all) shouldn't pay for.
        if (mayProbeStock && (platform == Platform.SHOPIFY || platform == Platform.WOOCOMMERCE)) {
            cartProfiles[host] = ThrottleProfile.cartMutationForTier(tier)
        }
    }
    for ((host, profile) in hostProfiles) {
        traceLog("setup", "$host: browsing=$profile cart=${cartProfiles[host]}")
    }

    val rateLimiter = PerHostRateLimiter(hostProfiles, cartProfiles)
    val client = buildHttpClient(rateLimiter)

    val hasBrowserStores = stores.values.any { KNOWN_PLATFORMS[it] == Platform.BROWSER }
    val localBrowserSearcher = if (hasBrowserStores && sharedBrowserSearcher == null) {
        val parallelism = (cards.size / 3 + 1).coerceIn(1, 3)
        createBrowserSearcher?.invoke(parallelism)
    } else null
    val effectiveBrowserSearcher = sharedBrowserSearcher ?: localBrowserSearcher

    // Cross-store start stagger (see StaggerProfile) -- shuffled so the same physical store isn't
    // always launched first/last across searches.
    val stagger = StaggerProfile.forCategory(category)
    val staggeredStores = stores.entries.toList().shuffled()

    try {
        coroutineScope {
            staggeredStores.mapIndexed { index, entry ->
                val (name, base) = entry
                async(Dispatchers.IO) {
                    if (stagger.stepMs > 0) {
                        delay(index * stagger.stepMs + stagger.jitterMs.random())
                    }
                    checkStore(
                        client, name, base, cards, effectiveBrowserSearcher,
                        onProgress, onResults, onStoreComplete,
                        onCfBlocked = { url, cardCount ->
                            runCatching { recordCfThrottleBlock(url, cardCount, category) }
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
