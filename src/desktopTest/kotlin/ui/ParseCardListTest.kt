package ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParseCardListTest {

    private fun parse(input: String, ignore: Boolean = false) =
        SearchViewModel.parseCardList(input, ignore)

    // ── Basic input ──────────────────────────────────────────────────────────

    @Test fun `single card name`() = assertEquals(listOf("Lightning Bolt"), parse("Lightning Bolt"))
    @Test fun `two cards on separate lines`() =
        assertEquals(listOf("Lightning Bolt", "Thoughtseize"), parse("Lightning Bolt\nThoughtseize"))
    @Test fun `trims leading and trailing whitespace`() =
        assertEquals(listOf("Lightning Bolt"), parse("  Lightning Bolt  "))
    @Test fun `empty input returns empty list`() = assertTrue(parse("").isEmpty())
    @Test fun `whitespace-only input returns empty list`() = assertTrue(parse("   \n  \n").isEmpty())
    @Test fun `blank lines between cards are ignored`() =
        assertEquals(listOf("Bolt", "Bolt 2"), parse("Bolt\n\nBolt 2"))

    // ── Quantity prefix stripping ────────────────────────────────────────────

    @Test fun `strips 1 space prefix`() = assertEquals(listOf("Shadowspear"), parse("1 Shadowspear"))
    @Test fun `strips 4x prefix lowercase`() = assertEquals(listOf("Lightning Bolt"), parse("4x Lightning Bolt"))
    @Test fun `strips 4X prefix uppercase`() = assertEquals(listOf("Lightning Bolt"), parse("4X Lightning Bolt"))
    @Test fun `strips 2x prefix`() = assertEquals(listOf("Thoughtseize"), parse("2x Thoughtseize"))
    @Test fun `strips 10 space prefix`() = assertEquals(listOf("Island"), parse("10 Island", false))
    @Test fun `does not strip non-quantity number prefix`() =
        // "1984" is a card name, not a quantity prefix — number must be followed by space or xX+space
        assertEquals(listOf("1984"), parse("1984"))

    // ── Decklist sections and comments ──────────────────────────────────────

    @Test fun `section header line ignored`() =
        assertEquals(listOf("Lightning Bolt"), parse("[Creatures]\n4 Lightning Bolt"))
    @Test fun `multiple section headers ignored`() =
        assertEquals(listOf("Bolt", "Spear"), parse("[Creatures]\n1 Bolt\n[Artifacts]\n1 Spear"))
    @Test fun `comment line ignored`() =
        assertEquals(listOf("Bolt"), parse("# this is a comment\nBolt"))
    @Test fun `comment and section mixed`() =
        assertEquals(listOf("Thoughtseize"), parse("# Sideboard\n[Side]\n1x Thoughtseize"))

    // ── Deduplication ────────────────────────────────────────────────────────

    @Test fun `duplicate card names deduplicated`() =
        assertEquals(listOf("Lightning Bolt"), parse("Lightning Bolt\nLightning Bolt"))
    @Test fun `quantity duplicate deduplicated`() =
        assertEquals(listOf("Lightning Bolt"), parse("4x Lightning Bolt\n2 Lightning Bolt"))

    // ── Basic land filtering ─────────────────────────────────────────────────

    @Test fun `plains stripped when ignore on`() =
        assertEquals(listOf("Lightning Bolt"), parse("Plains\nLightning Bolt", ignore = true))
    @Test fun `island stripped when ignore on`() =
        assertEquals(emptyList<String>(), parse("Island", ignore = true))
    @Test fun `all five basics stripped`() =
        assertEquals(emptyList<String>(), parse("Plains\nIsland\nSwamp\nMountain\nForest", ignore = true))
    @Test fun `wastes stripped when ignore on`() =
        assertEquals(emptyList<String>(), parse("Wastes", ignore = true))
    @Test fun `snow-covered plains stripped`() =
        assertEquals(listOf("Bolt"), parse("Snow-Covered Plains\nBolt", ignore = true))
    @Test fun `snow-covered island stripped`() =
        assertEquals(emptyList<String>(), parse("Snow-Covered Island", ignore = true))
    @Test fun `basics kept when ignore off`() =
        assertEquals(listOf("Plains", "Lightning Bolt"), parse("Plains\nLightning Bolt", ignore = false))
    @Test fun `basic land case insensitive`() =
        assertEquals(emptyList<String>(), parse("PLAINS", ignore = true))

    // ── parseCardQuantities ──────────────────────────────────────────────────

    private fun quantities(input: String, ignore: Boolean = false) =
        SearchViewModel.parseCardQuantities(input, ignore)

    @Test fun `quantity prefix parsed`() =
        assertEquals(mapOf("Lightning Bolt" to 4), quantities("4x Lightning Bolt"))

    @Test fun `no prefix defaults to quantity 1`() =
        assertEquals(mapOf("Lightning Bolt" to 1), quantities("Lightning Bolt"))

    @Test fun `duplicate lines take the max quantity, not a sum`() =
        assertEquals(mapOf("Lightning Bolt" to 4), quantities("4x Lightning Bolt\n2 Lightning Bolt"))

    @Test fun `a bare mention alongside an explicit quantity does not inflate it`() =
        // Regression: "Hare Apparent" + "30 Hare Apparent" must resolve to 30, not 31 --
        // a leftover bare mention shouldn't count as "one more copy" on top of an explicit qty.
        assertEquals(mapOf("Hare Apparent" to 30), quantities("Hare Apparent\n30 Hare Apparent"))

    @Test fun `two bare duplicate lines still count as one`() =
        assertEquals(mapOf("Lightning Bolt" to 1), quantities("Lightning Bolt\nLightning Bolt"))

    @Test fun `basic land filtering applies before quantity aggregation`() =
        assertEquals(mapOf("Lightning Bolt" to 1), quantities("4x Plains\nLightning Bolt", ignore = true))

    @Test fun `section headers and comments ignored for quantities too`() =
        assertEquals(mapOf("Bolt" to 1, "Spear" to 2), quantities("[Creatures]\n1 Bolt\n# note\n2x Spear"))

    @Test fun `parseCardList and parseCardQuantities never drift apart`() {
        val fixtures = listOf(
            "Lightning Bolt",
            "4x Lightning Bolt\n2 Lightning Bolt",
            "[Creatures]\n1 Bolt\n# note\n2x Spear",
            "Plains\nIsland\nSwamp\nMountain\nForest\nLightning Bolt",
            "",
        )
        for (input in fixtures) {
            for (ignore in listOf(false, true)) {
                assertEquals(parse(input, ignore).toSet(), quantities(input, ignore).keys, "mismatch for: $input (ignore=$ignore)")
            }
        }
    }
}
