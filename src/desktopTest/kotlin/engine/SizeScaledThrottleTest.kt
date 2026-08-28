package engine

import data.SearchCategory
import kotlin.test.*

// SizeScaledThrottle replaces the old tier/CfThrottleRule escalation system (Aug 2026): the delay
// is derived from a search's SearchCategory plus whether it includes any quantity probing, applied
// unconditionally from a store's first request. See its doc comment in SearchEngine.kt for the live
// evidence and reasoning behind the two-scale split (currently set equal, per a live experiment
// testing whether maxConcurrent=1 alone is enough to make probing safe at the plain-search pace).
class SizeScaledThrottleTest {

    @Test fun `no-quantities and with-quantities scales currently match, per category`() {
        for (category in SearchCategory.entries) {
            val plain = SizeScaledThrottle.delayFor(category, hasQuantities = false)
            val withQty = SizeScaledThrottle.delayFor(category, hasQuantities = true)
            assertEquals(plain, withQty, "$category: expected the two scales to currently match")
        }
    }

    @Test fun `no-quantities delays increase with category`() {
        assertEquals(500L, SizeScaledThrottle.delayFor(SearchCategory.SMALL, hasQuantities = false))
        assertEquals(1_500L, SizeScaledThrottle.delayFor(SearchCategory.MEDIUM, hasQuantities = false))
        assertEquals(4_000L, SizeScaledThrottle.delayFor(SearchCategory.LARGE, hasQuantities = false))
    }

    @Test fun `with-quantities delays increase with category`() {
        assertEquals(500L, SizeScaledThrottle.delayFor(SearchCategory.SMALL, hasQuantities = true))
        assertEquals(1_500L, SizeScaledThrottle.delayFor(SearchCategory.MEDIUM, hasQuantities = true))
        assertEquals(4_000L, SizeScaledThrottle.delayFor(SearchCategory.LARGE, hasQuantities = true))
    }

    @Test fun `profileFor always serializes to a single in-flight request`() {
        val profile = SizeScaledThrottle.profileFor(SearchCategory.LARGE, hasQuantities = true)
        assertEquals(1, profile.maxConcurrent)
        assertEquals(4_000L, profile.minDelayMs)
    }
}
