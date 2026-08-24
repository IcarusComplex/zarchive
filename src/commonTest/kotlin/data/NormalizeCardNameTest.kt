package data

import kotlin.test.*

class NormalizeCardNameTest {

    // ── Set code / expansion tags ────────────────────────────────────────────

    @Test fun `strips bracketed set code`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt [M10]"))
    @Test fun `strips bracketed set name`() =
        assertEquals("Thoughtseize", normalizeCardName("Thoughtseize [Theros]"))
    @Test fun `strips paren set code`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear (THB)"))
    @Test fun `strips paren promo label`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear (Promo)"))

    // ── " - Set Name" suffix ─────────────────────────────────────────────────

    @Test fun `strips dash suffix`() =
        assertEquals("Thoughtseize", normalizeCardName("Thoughtseize - Theros"))
    @Test fun `strips dash suffix with extra words`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt - Magic 2010"))

    // ── Condition and treatment keywords ────────────────────────────────────

    @Test fun `strips NM condition`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt NM"))
    @Test fun `strips foil keyword`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear Foil"))
    @Test fun `strips extended art`() =
        assertEquals("Thoughtseize", normalizeCardName("Thoughtseize Extended Art"))
    @Test fun `strips showcase`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt Showcase"))
    @Test fun `strips borderless`() =
        assertEquals("Thoughtseize", normalizeCardName("Thoughtseize Borderless"))
    @Test fun `strips prerelease`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear Prerelease"))

    // ── Trailing collector number ────────────────────────────────────────────

    @Test fun `strips trailing collector number`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt 123"))
    @Test fun `strips short trailing number`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear 15"))

    // ── Clean names are unchanged ────────────────────────────────────────────

    @Test fun `clean name unchanged`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt"))
    @Test fun `apostrophe preserved`() =
        assertEquals("Jace's Ingenuity", normalizeCardName("Jace's Ingenuity"))
    @Test fun `hyphenated name preserved`() =
        assertEquals("Will-o'-the-Wisp", normalizeCardName("Will-o'-the-Wisp"))

    // ── Combined messy titles ────────────────────────────────────────────────

    @Test fun `set code and condition combined`() =
        assertEquals("Lightning Bolt", normalizeCardName("Lightning Bolt [M10] NM"))
    @Test fun `paren and dash combined`() =
        assertEquals("Shadowspear", normalizeCardName("Shadowspear (THB) - Theros"))

    // ── Double-faced / double-sided token names ─────────────────────────────

    @Test fun `preserves double-faced separator while cleaning both faces`() =
        assertEquals(
            "Rabbit // Splash Lasher",
            normalizeCardName("Rabbit // Splash Lasher Double-Sided Token [Bloomburrow Tokens]"),
        )
    @Test fun `strips double-sided token wording with no separator`() =
        assertEquals("Elephant", normalizeCardName("Elephant Double-Sided Token [Set]"))
    @Test fun `double-faced spell card separator preserved`() =
        assertEquals("Fire // Ice", normalizeCardName("Fire // Ice [Dominaria Remastered]"))
}
