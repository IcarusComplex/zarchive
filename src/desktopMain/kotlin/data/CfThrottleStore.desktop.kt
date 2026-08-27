package data

actual fun loadActiveCfThrottleRules(): Map<String, CfThrottleRule> = AppDatabase.loadActiveCfRules()

actual fun recordCfThrottleBlock(baseUrl: String, cardCount: Int, category: SearchCategory) =
    AppDatabase.recordCfBlock(baseUrl, cardCount, category)
