package engine

import kotlin.test.*

// SizeScaledThrottle replaces the old tier/CfThrottleRule escalation system (Aug 2026): the delay
// is derived directly from a search's estimated request weight, applied unconditionally from a
// store's first request. See its doc comment in SearchEngine.kt for the live evidence behind this.
class SizeScaledThrottleTest {

    @Test fun `weight below the first breakpoint uses that breakpoint's delay`() {
        assertEquals(1_000L, SizeScaledThrottle.delayForWeight(0.0))
        assertEquals(1_000L, SizeScaledThrottle.delayForWeight(1.0))
    }

    @Test fun `weight exactly at a breakpoint uses it`() {
        assertEquals(4_000L, SizeScaledThrottle.delayForWeight(500.0))
        assertEquals(7_000L, SizeScaledThrottle.delayForWeight(1500.0))
    }

    @Test fun `weight between two breakpoints uses the lower one`() {
        assertEquals(1_000L, SizeScaledThrottle.delayForWeight(499.9))
        assertEquals(4_000L, SizeScaledThrottle.delayForWeight(1499.9))
    }

    @Test fun `a large weight still resolves to the top breakpoint`() {
        assertEquals(7_000L, SizeScaledThrottle.delayForWeight(50_000.0))
    }

    @Test fun `profileForWeight always serializes to a single in-flight request`() {
        val profile = SizeScaledThrottle.profileForWeight(4_942.1)
        assertEquals(1, profile.maxConcurrent)
        assertEquals(7_000L, profile.minDelayMs)
    }
}
