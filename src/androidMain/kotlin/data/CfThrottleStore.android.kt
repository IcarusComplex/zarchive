package data

// Mirrors AppDatabase.kt's loadActiveCfRules()/recordCfBlock() exactly -- both delegate the actual
// tier escalation to the shared escalateCfTiers() (data/CfThrottleRule.kt) so the two platforms
// can never drift apart.
private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

actual fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule> =
    queries.selectAllThrottleRules().executeAsList().associate { row ->
        row.base_url to CfThrottleRule(
            baseUrl = row.base_url,
            tierSmall = row.tier.toInt(),
            tierMedium = row.tier_medium.toInt(),
            tierLarge = row.tier_large.toInt(),
        )
    }

actual fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, category: SearchCategory) {
    val now = System.currentTimeMillis()
    val twoHoursMs = 2L * 60 * 60 * 1_000

    val existing = queries.selectThrottleRule(baseUrl).executeAsOneOrNull()
    val isNewEvent = existing == null || (now - existing.last_hit_at) > twoHoursMs
    val (newSmall, newMedium, newLarge) = if (existing == null) escalateCfTiers(category, isNewEvent)
        else escalateCfTiers(
            category, isNewEvent,
            existing.tier.toInt(), existing.tier_medium.toInt(), existing.tier_large.toInt(),
        )

    if (existing == null) {
        queries.insertThrottleRule(
            base_url = baseUrl,
            card_threshold = 300,
            tier = newSmall.toLong(),
            tier_medium = newMedium.toLong(),
            tier_large = newLarge.toLong(),
            last_hit_at = now,
            last_hit_cards = cardCount.toLong(),
        )
    } else {
        queries.updateThrottleRule(
            tier = newSmall.toLong(),
            tier_medium = newMedium.toLong(),
            tier_large = newLarge.toLong(),
            last_hit_at = now,
            last_hit_cards = cardCount.toLong(),
            base_url = baseUrl,
        )
    }
}
