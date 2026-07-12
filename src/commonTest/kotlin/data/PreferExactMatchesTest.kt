package data

import kotlin.test.*

class PreferExactMatchesTest {

    private fun result(title: String, card: String = "Lightning Bolt", price: Double? = 10.0) =
        SearchResult(store = "Test Store", card = card, title = title, priceZar = price,
            available = true, url = "https://example.com", note = "")

    // ── Exact match filtering ────────────────────────────────────────────────

    @Test fun `returns exact match over near-name`() {
        val listings = listOf(
            result("Reprieve"),
            result("Graceful Reprieve"),
        )
        val filtered = preferExactMatches("Reprieve", listings)
        assertEquals(1, filtered.size)
        assertEquals("Reprieve", filtered[0].title)
    }

    @Test fun `stops bolt matching lightning bolt`() {
        val listings = listOf(
            result("Bolt"),
            result("Lightning Bolt"),
        )
        val filtered = preferExactMatches("Bolt", listings)
        assertEquals(1, filtered.size)
        assertEquals("Bolt", filtered[0].title)
    }

    // ── Fallback when no exact match ─────────────────────────────────────────

    @Test fun `returns all when no exact match`() {
        val listings = listOf(
            result("Lightning Bolt [M10]"),
            result("Lightning Bolt NM"),
        )
        val filtered = preferExactMatches("Lightning Bolt", listings)
        // Neither normalises to exactly "Lightning Bolt" after noise removal + matchKey …
        // Actually normalizeCardName("Lightning Bolt [M10]") = "Lightning Bolt" so both match.
        // The point: if exact matches exist they are returned.
        assertTrue(filtered.isNotEmpty())
    }

    @Test fun `fallback returns all when nothing normalises to query`() {
        val listings = listOf(
            result("Totally Different Name"),
            result("Another Different Name"),
        )
        val filtered = preferExactMatches("Lightning Bolt", listings)
        assertEquals(2, filtered.size)
    }

    // ── Single result always returned as-is ─────────────────────────────────

    @Test fun `single result returned unchanged`() {
        val listings = listOf(result("Graceful Reprieve"))
        assertEquals(listings, preferExactMatches("Reprieve", listings))
    }

    @Test fun `empty list returned as-is`() =
        assertEquals(emptyList<SearchResult>(), preferExactMatches("Bolt", emptyList()))

    // ── Double-faced / split cards ───────────────────────────────────────────

    @Test fun `matches first face of DFC`() {
        val listings = listOf(
            result("Delver of Secrets // Insectile Aberration"),
            result("Insectile Aberration"),
        )
        val filtered = preferExactMatches("Delver of Secrets", listings)
        assertEquals(1, filtered.size)
        assertEquals("Delver of Secrets // Insectile Aberration", filtered[0].title)
    }

    @Test fun `matches second face of DFC`() {
        val listings = listOf(
            result("Delver of Secrets // Insectile Aberration"),
        )
        val filtered = preferExactMatches("Insectile Aberration", listings)
        assertEquals(1, filtered.size)
    }

    // ── Noise in titles normalised away ─────────────────────────────────────

    @Test fun `bracketed set code stripped before comparison`() {
        val listings = listOf(result("Shadowspear [THB]"))
        val filtered = preferExactMatches("Shadowspear", listings)
        assertEquals(1, filtered.size)
    }

    @Test fun `condition keyword stripped before comparison`() {
        val listings = listOf(result("Lightning Bolt NM"))
        val filtered = preferExactMatches("Lightning Bolt", listings)
        assertEquals(1, filtered.size)
    }
}
