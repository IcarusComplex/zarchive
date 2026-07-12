package data

// Mirrors AppDatabase.kt's loadActiveCfRules()/recordCfBlock() escalation logic exactly (two
// independent tiers — small-bucket for cards*stores < 300, large-bucket for >= 300 — each
// escalating at most once per 2-hour window, max tier 3).
private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

actual fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule> =
    queries.selectAllThrottleRules().executeAsList().associate { row ->
        row.base_url to CfThrottleRule(
            baseUrl = row.base_url,
            tierSmall = row.tier.toInt(),
            tierLarge = row.tier_large.toInt(),
        )
    }

actual fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, isLargeSearch: Boolean) {
    val now = System.currentTimeMillis()
    val twoHoursMs = 2L * 60 * 60 * 1_000

    val existing = queries.selectThrottleRule(baseUrl).executeAsOneOrNull()
    if (existing == null) {
        queries.insertThrottleRule(
            base_url = baseUrl,
            card_threshold = 300,
            tier = (if (isLargeSearch) 1 else 2).toLong(), // tierSmall
            tier_large = (if (isLargeSearch) 3 else 2).toLong(), // tierLarge
            last_hit_at = now,
            last_hit_cards = cardCount.toLong(),
        )
    } else {
        val isNewEvent = (now - existing.last_hit_at) > twoHoursMs
        val storedSmall = existing.tier.toInt()
        val storedLarge = existing.tier_large.toInt()
        val newSmall = if (!isLargeSearch && isNewEvent) minOf(3, storedSmall + 1) else storedSmall
        val newLarge = if (isLargeSearch && isNewEvent) minOf(3, storedLarge + 1) else storedLarge

        queries.updateThrottleRule(
            tier = newSmall.toLong(),
            tier_large = newLarge.toLong(),
            last_hit_at = now,
            last_hit_cards = cardCount.toLong(),
            base_url = baseUrl,
        )
    }
}
