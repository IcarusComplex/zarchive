package data

/**
 * How strictly a search should be governed (concurrency, delay, cross-store start stagger -- see
 * `engine.SearchEngine`). Replaces the old two-part `isLargeSearch: Boolean` + separate
 * `mayProbeStock: Boolean` split with one holistic classification computed once per search.
 */
enum class SearchCategory { SMALL, MEDIUM, LARGE }

data class SearchWeightEstimate(val totalWeight: Double, val category: SearchCategory)

/**
 * Estimates how much HTTP request volume a search will actually generate -- not just
 * `cards.size * stores.size`, but weighted by platform (Shopify makes several requests per card;
 * WooCommerce/BigCommerce make roughly one) and by whether quantity-probing will add cart-mutation
 * requests on top (only for cards with `qty > 1`, only on platforms that actually probe stock).
 *
 * This holistic weight is what [runSearch][engine] uses to pick a base governance tier (SMALL=1,
 * MEDIUM=2, LARGE=3) and a cross-store start stagger -- see `SearchEngine.kt`'s `StaggerProfile`.
 *
 * Thresholds are a documented starting estimate (same posture as `ThrottleProfile`'s constants),
 * not empirically tuned -- confirmed live (Aug 2026): a full 19-store roster with no quantity
 * probing lands MEDIUM around 12 cards and LARGE around 35 cards; the historical "17-card + one 30x"
 * incident that triggered wide rate-limiting lands in MEDIUM. Revisit against real trace-log data.
 */
object SearchClassifier {
    // Estimated Ktor-level HTTP requests per (card, store) pair for ordinary browsing.
    // BROWSER (Playwright/WebView) stores never touch the per-host rate limiter -- 0 weight.
    private fun browsingWeight(platform: Platform): Double = when (platform) {
        Platform.SHOPIFY -> 3.0                                  // suggest.json + ~2 candidate handle.js fetches
        Platform.WOOCOMMERCE, Platform.WC_STORE_API -> 1.3       // usually 1 request, sometimes +1 product page
        Platform.BIGCOMMERCE -> 1.0
        Platform.PRESTASHOP -> 1.5                                // search + product-page fetch for cart token
        Platform.UNTAPPED_API, Platform.OPENCART, Platform.WARREN_API -> 1.0
        Platform.BROWSER, Platform.UNKNOWN, Platform.UNREACHABLE -> 0.0
    }

    // Extra weight added only for a card whose requested quantity > 1, at a platform that actually
    // probes stock via a cart-mutation endpoint (see CART_MUTATION_ATTR usages in Searchers.kt).
    // Treated as platform-agnostic (~5/store) rather than finely differentiated by request count --
    // this weight now drives an actual delay directly (SizeScaledThrottle), not just a coarse
    // 3-bucket tier, so under-estimating it is the unsafe direction of error (too little delay) while
    // over-estimating is merely "waits a bit longer than strictly needed." Shopify's lower candidate
    // cap doesn't mean each probe is cheaper to Cloudflare, just that there are fewer of them --
    // recalibrate downward per-platform only once real testing confirms it's safe to.
    private fun probeWeight(platform: Platform): Double = when (platform) {
        Platform.SHOPIFY, Platform.WOOCOMMERCE, Platform.WC_STORE_API -> 5.0
        else -> 0.0
    }

    fun classify(
        cards: List<String>,
        cardQuantities: Map<String, Int>,
        stores: Map<String, String>,
    ): SearchWeightEstimate {
        var weight = 0.0
        for (baseUrl in stores.values) {
            val platform = KNOWN_PLATFORMS[baseUrl] ?: Platform.SHOPIFY
            val bw = browsingWeight(platform)
            val pw = probeWeight(platform)
            if (bw == 0.0 && pw == 0.0) continue
            for (card in cards) {
                weight += bw
                if ((cardQuantities[card] ?: 1) > 1) weight += pw
            }
        }
        val category = when {
            weight < SMALL_MAX -> SearchCategory.SMALL
            weight < MEDIUM_MAX -> SearchCategory.MEDIUM
            else -> SearchCategory.LARGE
        }
        return SearchWeightEstimate(weight, category)
    }

    private const val SMALL_MAX = 500.0
    private const val MEDIUM_MAX = 1500.0
}
