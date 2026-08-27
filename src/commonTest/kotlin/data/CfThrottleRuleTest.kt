package data

import kotlin.test.*

// Tests the pure escalation rule shared by AppDatabase.recordCfBlock (desktop) and
// CfThrottleStore.android.kt -- no database needed, see escalateCfTiers's doc comment.
class CfThrottleRuleTest {

    @Test fun `first-ever hit in each bucket escalates from that bucket's base`() {
        assertEquals(Triple(2, 2, 3), escalateCfTiers(SearchCategory.SMALL, isNewEvent = true))
        assertEquals(Triple(1, 3, 3), escalateCfTiers(SearchCategory.MEDIUM, isNewEvent = true))
        // Large's base (3) is already the max tier -- a first LARGE hit is a numeric no-op.
        assertEquals(Triple(1, 2, 3), escalateCfTiers(SearchCategory.LARGE, isNewEvent = true))
    }

    @Test fun `only the matching bucket escalates, the other two are untouched`() {
        val (small, medium, large) = escalateCfTiers(
            SearchCategory.SMALL, isNewEvent = true,
            existingSmall = 1, existingMedium = 2, existingLarge = 3,
        )
        assertEquals(2, small)
        assertEquals(2, medium) // unchanged
        assertEquals(3, large)  // unchanged
    }

    @Test fun `a stale existing state on an unrelated bucket is never touched by a different category`() {
        // Small was already escalated to 2 by an earlier block; a LARGE-category block now must
        // not reset or otherwise touch it.
        val (small, medium, large) = escalateCfTiers(
            SearchCategory.LARGE, isNewEvent = true,
            existingSmall = 2, existingMedium = 2, existingLarge = 3,
        )
        assertEquals(2, small)
        assertEquals(2, medium)
        assertEquals(3, large)
    }

    @Test fun `outside a new event, nothing escalates even for the matching bucket`() {
        // isNewEvent=false models "within the 2-hour cooldown of the last hit."
        val (small, medium, large) = escalateCfTiers(
            SearchCategory.MEDIUM, isNewEvent = false,
            existingSmall = 1, existingMedium = 2, existingLarge = 3,
        )
        assertEquals(1, small)
        assertEquals(2, medium)
        assertEquals(3, large)
    }

    @Test fun `escalation is capped at tier 3`() {
        val (_, medium, _) = escalateCfTiers(
            SearchCategory.MEDIUM, isNewEvent = true,
            existingSmall = 1, existingMedium = 3, existingLarge = 3,
        )
        assertEquals(3, medium)
    }

    @Test fun `tierFor returns the bucket matching the category`() {
        val rule = CfThrottleRule(baseUrl = "https://example.com", tierSmall = 1, tierMedium = 2, tierLarge = 3)
        assertEquals(1, rule.tierFor(SearchCategory.SMALL))
        assertEquals(2, rule.tierFor(SearchCategory.MEDIUM))
        assertEquals(3, rule.tierFor(SearchCategory.LARGE))
    }
}
