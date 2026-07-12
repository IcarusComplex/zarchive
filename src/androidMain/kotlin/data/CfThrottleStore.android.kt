package data

// Stub until Phase 5 wires up the Android SQLDelight database. No persisted throttle escalation
// on Android yet — every search starts at the base tier, same as a desktop install with no history.
actual fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule> = emptyMap()

actual fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, isLargeSearch: Boolean) {
    // no-op until Phase 5
}
