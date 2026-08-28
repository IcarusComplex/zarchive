package engine

import kotlin.test.*

// Regression test for a real bug (Aug 2026): buildHttpClient's Ktor request timeout was a fixed
// constant, independent of PerHostRateLimiter's own throttle delays. Ktor's HttpTimeout clock starts
// before the HttpSend interceptor's pre-request delay runs, so once a throttle delay reached (or
// exceeded) the fixed timeout, every single request failed instantly with "Request timeout has
// expired" -- confirmed live when an experimental 20s per-request delay was set against the
// then-fixed 20s request timeout. maxDelayMs exists so the timeout can never fall out of sync with
// whatever delays are actually configured.
class PerHostRateLimiterTest {

    @Test fun `maxDelayMs is zero when no profiles are configured`() {
        val limiter = PerHostRateLimiter(emptyMap())
        assertEquals(0L, limiter.maxDelayMs)
    }

    @Test fun `maxDelayMs reflects the slowest browsing profile`() {
        val limiter = PerHostRateLimiter(
            hostProfiles = mapOf(
                "a.example.com" to ThrottleProfile(1, 800L),
                "b.example.com" to ThrottleProfile(1, 5_000L),
            ),
        )
        assertEquals(5_000L, limiter.maxDelayMs)
    }

}
