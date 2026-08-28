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
 * This holistic weight is what [runSearch][engine] uses to pick a per-request delay
 * (`SizeScaledThrottle`) and drives `SearchCategory`, which separately governs the cross-store
 * start stagger (`SearchEngine.kt`'s `StaggerProfile`) and the pre-search explainer dialog.
 *
 * Weights are 1 per (card, store) for plain browsing -- reset from earlier, higher per-platform
 * guesses (Aug 2026, live direction): a plain card search is "one search per store," so 40 cards x
 * 20 stores is weight 800, not inflated by counting a platform's own internal request fan-out (e.g.
 * Shopify's candidate handle.js fetches) on top. With `SMALL_MAX`=500/`MEDIUM_MAX`=1500, a full
 * 19-store roster with no quantity probing lands MEDIUM around 26 cards and LARGE around 79 cards.
 */
object SearchClassifier {
    // Estimated Ktor-level HTTP requests per (card, store) pair for ordinary browsing.
    // BROWSER (Playwright/WebView) stores never touch the per-host rate limiter -- 0 weight.
    // Reset to 1 per store, per explicit direction (Aug 2026): a plain card search is "one search
    // per store" at the level this weight should reason about -- 40 cards x 20 stores is 800 search
    // calls, not inflated by counting Shopify's own internal candidate-fetch fan-out on top.
    private fun browsingWeight(platform: Platform): Double = when (platform) {
        Platform.SHOPIFY, Platform.WOOCOMMERCE, Platform.WC_STORE_API, Platform.BIGCOMMERCE,
        Platform.PRESTASHOP, Platform.UNTAPPED_API, Platform.OPENCART, Platform.WARREN_API -> 1.0
        Platform.BROWSER, Platform.UNKNOWN, Platform.UNREACHABLE -> 0.0
    }

    // Extra weight added only for a card whose requested quantity > 1, at a platform that actually
    // probes stock via a cart-mutation endpoint (see CART_MUTATION_ATTR usages in Searchers.kt).
    // Treated as platform-agnostic (~7/store) rather than finely differentiated by request count --
    // this weight now drives an actual delay directly (SizeScaledThrottle), not just a coarse
    // 3-bucket tier, so under-estimating it is the unsafe direction of error (too little delay) while
    // over-estimating is merely "waits a bit longer than strictly needed." Shopify's lower candidate
    // cap doesn't mean each probe is cheaper to Cloudflare, just that there are fewer of them --
    // recalibrate downward per-platform only once real testing confirms it's safe to.
    private fun probeWeight(platform: Platform): Double = when (platform) {
        Platform.SHOPIFY, Platform.WOOCOMMERCE, Platform.WC_STORE_API -> 7.0
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
