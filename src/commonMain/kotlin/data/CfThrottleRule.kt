package data

/** Per-store persisted Cloudflare rate-limit escalation, read/written by `engine.SearchEngine`. */
data class CfThrottleRule(
    val baseUrl: String,
    val tierSmall: Int,  // applied when cards*stores < 300
    val tierLarge: Int,  // applied when cards*stores >= 300
)

/**
 * Loads every store's active throttle rule. Desktop delegates to `AppDatabase` (Exposed/H2,
 * unchanged); Android is a no-op stub until Phase 5 wires up its own local database.
 */
expect fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule>

/** Records a 429 hit for [baseUrl] so future searches escalate that store's throttle tier. */
expect fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, isLargeSearch: Boolean)
