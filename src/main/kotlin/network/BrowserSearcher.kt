package network

import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitUntilState
import data.SearchResult
import data.isRelevant
import data.parsePrice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val IS_DEBUG = System.getProperty("mtg.debug") == "true"

private val DEBUG_DIR by lazy {
    File(System.getProperty("user.home"), "zarchive-debug").also { it.mkdirs() }
}


private fun debugDump(store: String, card: String, html: String, summary: String) {
    if (!IS_DEBUG) return
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
    val slug = "${store}_${card.replace("[^a-zA-Z0-9]".toRegex(), "_")}_$ts"
    DEBUG_DIR.resolve("$slug.html").writeText(html)
    DEBUG_DIR.resolve("$slug.txt").writeText(summary)
    println("[DEBUG] ${DEBUG_DIR.absolutePath}/$slug.txt")
}

/**
 * Runs up to [parallelism] headless Chromium instances in parallel, each on its own dedicated
 * thread (Playwright's Java API is not thread-safe). Cards are distributed round-robin so N
 * Warren searches happen simultaneously instead of serially.
 */
class BrowserSearcher(private val parallelism: Int = 1) : AutoCloseable {

    private inner class Lane(id: Int) {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "playwright-$id").also { it.isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        var pw: Playwright? = null
        var browser: Browser? = null
        var ctx: BrowserContext? = null
        // Persistent page kept on thewarren.co.za — avoids re-navigating for each card search.
        // Warren results come from an API (evaluate/fetch), not from the page HTML.
        var warrenPage: Page? = null

        fun init() {
            if (pw != null) return
            pw = Playwright.create()
            browser = launchBrowser(pw!!)
            ctx = createContext(browser!!)
        }

        fun initWarrenPage() {
            if (warrenPage != null) return
            warrenPage = ctx!!.newPage()
            warrenPage!!.navigate("https://thewarren.co.za",
                Page.NavigateOptions().setTimeout(30_000.0).setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
        }

        fun close() {
            executor.submit {
                runCatching { warrenPage?.close() }
                runCatching { ctx?.close() }
                runCatching { browser?.close() }
                runCatching { pw?.close() }
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            executor.shutdown()
            dispatcher.close()
        }
    }

    private val lanes = (1..parallelism.coerceAtLeast(1)).map { Lane(it) }
    private val robin = AtomicInteger(0)

    suspend fun search(base: String, card: String): List<SearchResult> {
        val lane = lanes[robin.getAndIncrement() % lanes.size]
        return withContext(lane.dispatcher) {
            lane.init()
            if ("thewarren" in base) {
                lane.initWarrenPage()
                searchWarren(card, lane.warrenPage!!)
            } else emptyList()
        }
    }

    // ── Browser / context setup (called once per lane on its own thread) ───────

    private fun launchBrowser(pw: Playwright): Browser {
        // The real Chrome/Edge channel's "new" headless mode still briefly creates a real OS
        // window before backgrounding itself, flashing a blank panel on screen at launch (two
        // of them, one per lane). Chrome removed the old headless mode (--headless=old) in
        // v132, so it can no longer be suppressed outright — instead push the window fully
        // off any real monitor so the flash is never on-screen.
        val args = listOf(
            "--disable-blink-features=AutomationControlled",
            "--window-position=-32000,-32000",
            "--window-size=1,1",
        )
        val opts = BrowserType.LaunchOptions().setHeadless(true).setArgs(args)
        for (channel in listOf("chrome", "msedge")) {
            runCatching { return pw.chromium().launch(opts.setChannel(channel)) }
        }
        println("[BrowserSearcher] No Chrome/Edge found — using bundled Chromium")
        return pw.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
                .setArgs(args + listOf("--no-sandbox", "--disable-dev-shm-usage"))
        )
    }

    private fun createContext(browser: Browser): BrowserContext {
        val ctx = browser.newContext(
            Browser.NewContextOptions()
                .setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .setViewportSize(1280, 800)
                .setExtraHTTPHeaders(mapOf(
                    "sec-ch-ua"          to "\"Google Chrome\";v=\"124\",\"Chromium\";v=\"124\",\"Not-A.Brand\";v=\"99\"",
                    "sec-ch-ua-mobile"   to "?0",
                    "sec-ch-ua-platform" to "\"Windows\"",
                    "accept-language"    to "en-ZA,en;q=0.9",
                ))
        )
        ctx.addInitScript("""
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            window.chrome = {
                app: {}, runtime: {}, loadTimes: function(){}, csi: function(){},
                isEqualCurrentId: function(){}, getURL: function(){}
            };
            Object.defineProperty(navigator, 'plugins', {
                get: () => [
                    Object.assign(document.createElement('object'), {
                        name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer',
                        description: 'Portable Document Format'
                    }),
                    Object.assign(document.createElement('object'), {
                        name: 'Native Client', filename: 'internal-nacl-plugin',
                        description: ''
                    })
                ]
            });
            Object.defineProperty(navigator, 'languages', { get: () => ['en-ZA', 'en'] });
            const _query = navigator.permissions.query.bind(navigator.permissions);
            navigator.permissions.query = (p) =>
                p.name === 'notifications'
                    ? Promise.resolve({ state: Notification.permission })
                    : _query(p);
        """)
        return ctx
    }

    // ── The Warren ─────────────────────────────────────────────────────────────

    // The Warren's /openapi/.../product/search endpoint requires a server-side session
    // state that only the React app initialises — bare HTTP requests always return empty.
    // Strategy: navigate to the search URL, intercept the React app's first API call via
    // waitForResponse (gives us JSON with prices immediately), then fetch remaining pages
    // via page.evaluate(fetch(...)) since the session is now properly initialised.
    private fun searchWarren(card: String, page: Page): List<SearchResult> {
        val encoded = URLEncoder.encode(card, "UTF-8")
        val searchUrl = "https://thewarren.co.za/search?f=1&q=$encoded&t=1&c=3&sc=7&is=1&o=0"
        return try {
            val resp = page.waitForResponse("**/product/search**") {
                page.navigate(searchUrl, Page.NavigateOptions()
                    .setTimeout(30_000.0)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
            }
            val firstJson = resp.text()
            debugDump("warren", card, firstJson, "card=$card  page1")
            val results = parseWarrenApiJson(firstJson, card).toMutableList()

            // Each item carries total_records. If more pages exist, fetch them via
            // page.evaluate now that the session is properly initialised by the navigation.
            val total = Json.parseToJsonElement(firstJson).jsonObject["data"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("total_records")
                ?.jsonPrimitive?.intOrNull ?: 0
            val pageSize = 20
            if (total > pageSize) {
                var offset = pageSize
                while (offset < total) {
                    val apiPath = "/openapi/1.0.0fe/client/product/search" +
                        "?limit=$pageSize&offset=$offset&search_term=$encoded" +
                        "&in_stock=1&cmc_filter=e&white=0&blue=0&black=0&red=0&green=0" +
                        "&multicoloured=0&colourless=0&is_foil=0&is_token=0&is_art=0" +
                        "&swu_cost_filter=e&swu_power_filter=e&swu_hp_filter=e&swu_is_foil=0"
                    val moreJson = page.evaluate("""
                        async () => {
                            const r = await fetch('$apiPath');
                            return r.text();
                        }
                    """.trimIndent()) as? String ?: break
                    results.addAll(parseWarrenApiJson(moreJson, card))
                    offset += pageSize
                }
            }
            results.distinctBy { it.url }
        } catch (e: Exception) {
            debugDump("warren", card, "", "EXCEPTION: ${e.message}")
            emptyList()
        }
    }

    private fun parseWarrenApiJson(json: String, card: String): List<SearchResult> {
        return try {
            val arr = Json.parseToJsonElement(json).jsonObject["data"]?.jsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["product_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@mapNotNull null
                if (!isRelevant(card, title)) return@mapNotNull null
                val available = (obj["stock_available"]?.jsonPrimitive?.intOrNull ?: 1) > 0
                // Prefer discount_price when set, fall back to actual_price (both are numeric ZAR)
                val priceRaw = obj["discount_price"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
                    ?: obj["actual_price"]?.jsonPrimitive?.doubleOrNull
                val productId  = obj["product_id"]?.jsonPrimitive?.longOrNull
                val variantId  = obj["mtg_product_variant_id"]?.jsonPrimitive?.longOrNull
                SearchResult(
                    store = "", card = card, title = title,
                    priceZar = priceRaw,
                    available = available,
                    url = "https://thewarren.co.za/mtg?id=$productId",
                    note = if (available) "In stock" else "Out of stock",
                    variantId = variantId,
                )
            }
        } catch (e: Exception) {
            debugDump("warren", card, json, "JSON PARSE ERROR: ${e.message}")
            emptyList()
        }
    }

    override fun close() = lanes.forEach { it.close() }
}
