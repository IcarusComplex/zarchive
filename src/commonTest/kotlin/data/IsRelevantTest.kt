package data

import kotlin.test.*

class IsRelevantTest {

    // ── Basic whole-word matching ────────────────────────────────────────────

    @Test fun `exact title match`() = assertTrue(isRelevant("Lightning Bolt", "Lightning Bolt"))
    @Test fun `title with extra words`() = assertTrue(isRelevant("Bolt", "Lightning Bolt NM"))
    @Test fun `multi-word query all present`() =
        assertTrue(isRelevant("Lightning Bolt", "Lightning Bolt [M10]"))

    // ── Whole-word enforcement (the big one) ─────────────────────────────────

    @Test fun `hop does not match hope`() =
        assertFalse(isRelevant("Hop to It", "Hope Thief"))
    // "reprieve" IS a whole word within "Graceful Reprieve" so isRelevant returns true —
    // near-name filtering (Reprieve vs Graceful Reprieve) is preferExactMatches's job.
    @Test fun `reprieve matches graceful reprieve as whole word`() =
        assertTrue(isRelevant("Reprieve", "Graceful Reprieve NM"))
    @Test fun `bolt does not match thunderbolt`() =
        assertFalse(isRelevant("Bolt", "Thunderbolt Wyvern"))
    @Test fun `elf does not match itself`() =
        assertFalse(isRelevant("Elf", "Itself"))

    // ── Short words (≤2 chars) skipped — they don't need to match ───────────

    @Test fun `short words in query ignored`() =
        assertTrue(isRelevant("Go for the Throat", "Go for the Throat"))
    @Test fun `query with only short words returns false`() =
        assertFalse(isRelevant("It", "It"))   // single 2-char word → empty significant words

    // ── NON_SINGLE_RE rejection ──────────────────────────────────────────────

    @Test fun `binder rejected`() =
        assertFalse(isRelevant("Season of the Burrow", "Ultimate Guard Zipfolio Season of the Burrow"))
    @Test fun `deck box rejected`() =
        assertFalse(isRelevant("Shadowspear", "Deck Box Shadowspear Design"))
    @Test fun `booster box rejected`() =
        assertFalse(isRelevant("Thoughtseize", "Booster Box Theros Thoughtseize Pack"))
    @Test fun `sleeves rejected`() =
        assertFalse(isRelevant("Lightning Bolt", "Card Sleeves Lightning Bolt Art"))
    @Test fun `playmat rejected`() =
        assertFalse(isRelevant("Jace", "Playmat Jace the Mind Sculptor"))
    @Test fun `prerelease kit rejected`() =
        assertFalse(isRelevant("Shadowspear", "Prerelease Kit Shadowspear promo"))

    // ── Mixed case ───────────────────────────────────────────────────────────

    @Test fun `case insensitive match`() =
        assertTrue(isRelevant("lightning bolt", "LIGHTNING BOLT"))

    // ── Query words must match the card name, not a bracketed/appended set name ─────
    // Regression: "Mystic Gate" (a land) falsely matched "Imoen, Mystic Trickster
    // [Commander Legends: Battle for Baldur's Gate]" because "gate" is a whole word inside
    // the *set name* suffix, not the card name. isRelevant must strip that suffix first.
    @Test fun `query word only present in bracketed set name is rejected`() =
        assertFalse(isRelevant(
            "Mystic Gate",
            "Imoen, Mystic Trickster [Commander Legends: Battle for Baldur's Gate]",
        ))
    @Test fun `query word only present in dash set-name suffix is rejected`() =
        assertFalse(isRelevant("Sunken Ruins", "Some Other Card - Sunken Ruins Anthology"))
    @Test fun `real card with matching bracketed set still matches`() =
        assertTrue(isRelevant("Mystic Gate", "Mystic Gate [Kaldheim]"))
}
