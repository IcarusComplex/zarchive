package data

actual fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule> = AppDatabase.loadActiveCfRules()

actual fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, isLargeSearch: Boolean) =
    AppDatabase.recordCfBlock(baseUrl, cardCount, isLargeSearch)
