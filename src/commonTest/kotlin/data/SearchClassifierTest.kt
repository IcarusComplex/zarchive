package data

import kotlin.test.*

class SearchClassifierTest {

    private fun cards(n: Int): List<String> = (1..n).map { "Card $it" }

    // Not present in KNOWN_PLATFORMS -> defaults to Platform.SHOPIFY, same as runSearch does for
    // every store not explicitly pinned (the majority of this app's stores).
    private val shopifyStore = mapOf("Fake Shopify" to "https://fake-shopify.example.com")

    @Test fun `classify is SMALL below the weight threshold`() {
        val estimate = SearchClassifier.classify(cards(50), emptyMap(), shopifyStore)
        assertEquals(SearchCategory.SMALL, estimate.category)
    }

    @Test fun `classify is MEDIUM at a moderate weight`() {
        // Big enough card*store volume alone to cross into MEDIUM without any quantity probing.
        val threeShopifyStores = mapOf(
            "A" to "https://a-shopify.example.com",
            "B" to "https://b-shopify.example.com",
            "C" to "https://c-shopify.example.com",
        )
        val estimate = SearchClassifier.classify(cards(200), emptyMap(), threeShopifyStores)
        assertEquals(SearchCategory.MEDIUM, estimate.category)
    }

    @Test fun `classify is LARGE at a high weight`() {
        val threeShopifyStores = mapOf(
            "A" to "https://a-shopify.example.com",
            "B" to "https://b-shopify.example.com",
            "C" to "https://c-shopify.example.com",
        )
        val estimate = SearchClassifier.classify(cards(600), emptyMap(), threeShopifyStores)
        assertEquals(SearchCategory.LARGE, estimate.category)
    }

    @Test fun `BROWSER-platform stores contribute zero weight`() {
        // thewarren.co.za is pinned to Platform.BROWSER in KNOWN_PLATFORMS -- Playwright-driven,
        // never touches the per-host Ktor rate limiter, so it must never push a search out of SMALL
        // on its own no matter how many cards are searched.
        val warrenOnly = mapOf("The Warren" to "https://thewarren.co.za")
        val estimate = SearchClassifier.classify(cards(1000), emptyMap(), warrenOnly)
        assertEquals(0.0, estimate.totalWeight)
        assertEquals(SearchCategory.SMALL, estimate.category)
    }

    @Test fun `probe weight only applies to cards with qty greater than 1`() {
        val qty1 = SearchClassifier.classify(listOf("Bolt"), mapOf("Bolt" to 1), shopifyStore)
        val qty2 = SearchClassifier.classify(listOf("Bolt"), mapOf("Bolt" to 2), shopifyStore)
        assertTrue(qty2.totalWeight > qty1.totalWeight)
    }

    @Test fun `probe weight does not apply on a non-probing platform`() {
        // Battle Wizards is pinned to Platform.BIGCOMMERCE, which never sends a cart-mutation
        // stock probe (see ThrottleProfile/Searchers.kt) -- qty > 1 must not add extra weight there.
        val bigCommerceStore = mapOf("Battle Wizards" to "https://www.battlewizards.co.za")
        val qty1 = SearchClassifier.classify(listOf("Bolt"), mapOf("Bolt" to 1), bigCommerceStore)
        val qty2 = SearchClassifier.classify(listOf("Bolt"), mapOf("Bolt" to 30), bigCommerceStore)
        assertEquals(qty1.totalWeight, qty2.totalWeight)
    }
}
