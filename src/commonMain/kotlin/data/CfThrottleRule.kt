package data

/**
 * Per-store persisted Cloudflare rate-limit escalation, read/written by `engine.SearchEngine`.
 *
 * Three independent buckets -- one per [SearchCategory] -- so a 429 on a LARGE search escalates
 * only that store's LARGE-category governance on future searches, without penalising a later SMALL
 * search against the same store (and vice versa).
 */
data class CfThrottleRule(
    val baseUrl: String,
    val tierSmall: Int,   // applied when the search classifies as SearchCategory.SMALL
    val tierMedium: Int,  // applied when the search classifies as SearchCategory.MEDIUM
    val tierLarge: Int,   // applied when the search classifies as SearchCategory.LARGE
) {
    fun tierFor(category: SearchCategory): Int = when (category) {
        SearchCategory.SMALL -> tierSmall
        SearchCategory.MEDIUM -> tierMedium
        SearchCategory.LARGE -> tierLarge
    }
}

/**
 * Loads every store's active throttle rule. Desktop delegates to `AppDatabase` (Exposed/H2);
 * Android delegates to its own SQLDelight-backed store.
 */
expect fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule>

/** Records a 429 hit for [baseUrl] so future searches in the same [category] escalate that bucket. */
expect fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, category: SearchCategory)

private const val MAX_CF_TIER = 3
private const val BASE_TIER_SMALL = 1
private const val BASE_TIER_MEDIUM = 2
private const val BASE_TIER_LARGE = 3 // already the max tier -- a first LARGE-bucket hit is a numeric no-op

/**
 * Pure escalation rule shared by the desktop (`AppDatabase.recordCfBlock`) and Android
 * (`CfThrottleStore.android.kt`) implementations of [recordCfThrottleBlock] -- computes the next
 * tier for all three buckets given which [category] just got blocked. Only the matching bucket ever
 * escalates (by +1, capped at [MAX_CF_TIER]) and only when [isNewEvent] (outside the 2-hour cooldown,
 * or this is the very first hit for this store, in which case the `existing*` defaults are the
 * pre-block bases: small=1, medium=2, large=3). The other two buckets are always carried through
 * unchanged. Kept here (not duplicated per-platform) so both implementations can never drift, and so
 * this logic is unit-testable without standing up either platform's database.
 */
fun escalateCfTiers(
    category: SearchCategory,
    isNewEvent: Boolean,
    existingSmall: Int = BASE_TIER_SMALL,
    existingMedium: Int = BASE_TIER_MEDIUM,
    existingLarge: Int = BASE_TIER_LARGE,
): Triple<Int, Int, Int> {
    val newSmall  = if (category == SearchCategory.SMALL  && isNewEvent) minOf(MAX_CF_TIER, existingSmall + 1)  else existingSmall
    val newMedium = if (category == SearchCategory.MEDIUM && isNewEvent) minOf(MAX_CF_TIER, existingMedium + 1) else existingMedium
    val newLarge  = if (category == SearchCategory.LARGE  && isNewEvent) minOf(MAX_CF_TIER, existingLarge + 1)  else existingLarge
    return Triple(newSmall, newMedium, newLarge)
}
