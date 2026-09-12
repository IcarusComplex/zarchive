package data

import kotlin.test.*

/**
 * [cardSummaryFacts] backs the card-summary panel's "found / pending" counts and each entry's
 * status chip. It was extracted so the panel computes it once per results change instead of once
 * per card per recomposition -- these tests pin the behaviour it took over.
 */
class CardSummaryFactsTest {

    private fun result(
        card: String,
        title: String?,
        available: Boolean? = true,
        store: String = "Test Store",
    ) = SearchResult(store = store, card = card, title = title, priceZar = 10.0,
        available = available, url = "https://example.com/$title", note = "")

    @Test fun `in-stock card reports hasInStock`() {
        val facts = cardSummaryFacts(
            listOf("Lightning Bolt"),
            listOf(result("Lightning Bolt", "Lightning Bolt", available = true)),
            includePartialMatches = false,
        )
        val f = facts.getValue("Lightning Bolt")
        assertTrue(f.hasInStock)
        assertFalse(f.hasOutOfStock)
        assertFalse(f.pending)
    }

    @Test fun `only out-of-stock listings report hasOutOfStock`() {
        val facts = cardSummaryFacts(
            listOf("Lightning Bolt"),
            listOf(result("Lightning Bolt", "Lightning Bolt", available = false)),
            includePartialMatches = false,
        )
        val f = facts.getValue("Lightning Bolt")
        assertFalse(f.hasInStock)
        assertTrue(f.hasOutOfStock)
    }

    @Test fun `card with no results yet is pending`() {
        val facts = cardSummaryFacts(listOf("Lightning Bolt"), emptyList(), includePartialMatches = false)
        val f = facts.getValue("Lightning Bolt")
        assertTrue(f.pending)
        assertFalse(f.hasInStock)
        assertFalse(f.hasOutOfStock)
        assertTrue(f.titles.isEmpty())
    }

    @Test fun `a title-less error row counts as answered, not pending`() {
        val facts = cardSummaryFacts(
            listOf("Lightning Bolt"),
            listOf(result("Lightning Bolt", null, available = null)),
            includePartialMatches = false,
        )
        assertFalse(facts.getValue("Lightning Bolt").pending)
    }

    @Test fun `titles are narrowed to exact matches`() {
        val facts = cardSummaryFacts(
            listOf("Reprieve"),
            listOf(
                result("Reprieve", "Graceful Reprieve"),
                result("Reprieve", "Reprieve"),
            ),
            includePartialMatches = false,
        )
        assertEquals(listOf("Reprieve"), facts.getValue("Reprieve").titles)
    }

    @Test fun `includePartialMatches keeps near-name listings`() {
        val facts = cardSummaryFacts(
            listOf("Reprieve"),
            listOf(
                result("Reprieve", "Graceful Reprieve"),
                result("Reprieve", "Reprieve"),
            ),
            includePartialMatches = true,
        )
        assertEquals(listOf("Graceful Reprieve", "Reprieve"), facts.getValue("Reprieve").titles)
    }

    @Test fun `an out-of-stock exact match does not mask an in-stock one`() {
        val facts = cardSummaryFacts(
            listOf("Lightning Bolt"),
            listOf(
                result("Lightning Bolt", "Lightning Bolt", available = false, store = "A"),
                result("Lightning Bolt", "Lightning Bolt", available = true, store = "B"),
            ),
            includePartialMatches = false,
        )
        val f = facts.getValue("Lightning Bolt")
        assertTrue(f.hasInStock)
        assertFalse(f.hasOutOfStock)
    }

    @Test fun `every requested card gets an entry`() {
        val facts = cardSummaryFacts(
            listOf("Lightning Bolt", "Counterspell"),
            listOf(result("Lightning Bolt", "Lightning Bolt")),
            includePartialMatches = false,
        )
        assertEquals(setOf("Lightning Bolt", "Counterspell"), facts.keys)
    }
}
