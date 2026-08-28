package engine

import data.SearchCategory
import kotlin.test.*

// SizeScaledThrottle replaces the old tier/CfThrottleRule escalation system (Aug 2026): the delay
// is derived from a search's SearchCategory plus whether it includes any quantity probing, applied
// unconditionally from a store's first request. See its doc comment in SearchEngine.kt for the live
// evidence and reasoning behind the two-scale split.
class SizeScaledThrottleTest {

    @Test fun `no-quantities scale is strictly less than the with-quantities scale, per category`() {
        for (category in SearchCategory.entries) {
            val plain = SizeScaledThrottle.delayFor(category, hasQuantities = false)
            val withQty = SizeScaledThrottle.delayFor(category, hasQuantities = true)
            assertTrue(plain < withQty, "$category: expected plain ($plain) < withQty ($withQty)")
        }
    }

    @Test fun `no-quantities delays increase with category`() {
        assertEquals(500L, SizeScaledThrottle.delayFor(SearchCategory.SMALL, hasQuantities = false))
        assertEquals(1_500L, SizeScaledThrottle.delayFor(SearchCategory.MEDIUM, hasQuantities = false))
        assertEquals(4_000L, SizeScaledThrottle.delayFor(SearchCategory.LARGE, hasQuantities = false))
    }

    @Test fun `with-quantities delays increase with category`() {
        assertEquals(1_000L, SizeScaledThrottle.delayFor(SearchCategory.SMALL, hasQuantities = true))
        assertEquals(3_000L, SizeScaledThrottle.delayFor(SearchCategory.MEDIUM, hasQuantities = true))
        assertEquals(7_000L, SizeScaledThrottle.delayFor(SearchCategory.LARGE, hasQuantities = true))
    }

    @Test fun `profileFor always serializes to a single in-flight request`() {
        val profile = SizeScaledThrottle.profileFor(SearchCategory.LARGE, hasQuantities = true)
        assertEquals(1, profile.maxConcurrent)
        assertEquals(7_000L, profile.minDelayMs)
    }
}
