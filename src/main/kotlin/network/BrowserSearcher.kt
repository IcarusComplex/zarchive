package network

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
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

private val IS_DEBUG = System.getProperty("mtg.debug") == "true"

// Debug dumps go to ~/zarchive-debug/ — only active during `gradlew run`
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

class BrowserSearcher(private val autoOpenLuckshack: Boolean = false) : AutoCloseable {
    // Playwright is not thread-safe — all calls must stay on one dedicated thread
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playwright-thread").also { it.isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var pw: Playwright? = null
    private var browser: Browser? = null
    private var ctx: BrowserContext? = null

    private fun launchRealBrowser(): Browser {
        val baseArgs = listOf("--disable-blink-features=AutomationControlled")
        // Headless — The Warren is JS-token-protected, not Cloudflare-gated, so no visible
        // window or user interaction is needed; it runs silently in the background.
        val opts = BrowserType.LaunchOptions().setHeadless(true).setArgs(baseArgs)
        // Try real Chrome, then Edge, then fall back to bundled Chromium
        for (channel in listOf("chrome", "msedge")) {
            runCatching {
                return pw!!.chromium().launch(opts.setChannel(channel))
            }
        }
        println("[BrowserSearcher] No Chrome/Edge found — using bundled Chromium")
        return pw!!.chromium().launch(BrowserType.LaunchOptions()
            .setHeadless(true)
            .setArgs(baseArgs + listOf("--no-sandbox", "--disable-dev-shm-usage")))
    }

    private fun init() {
        if (pw != null) return
        pw = Playwright.create()
        browser = launchRealBrowser()
        val ctxOptions = Browser.NewContextOptions()
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
        ctx = browser!!.newContext(ctxOptions)
        // Stealth: mask automation signals that Cloudflare checks
        ctx!!.addInitScript("""
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
    }

    suspend fun search(base: String, card: String): List<SearchResult> {
        // Luckshack without auto-open is a pure placeholder — skip the Playwright executor
        // entirely so it doesn't queue behind long Warren searches.
        if ("luckshack" in base && !autoOpenLuckshack) {
            return luckshackPlaceholder(card)
        }
        return withContext(dispatcher) {
            init()
            when {
                "luckshack" in base -> searchLuckshack(card)
                "thewarren" in base -> searchWarren(card)
                else                -> emptyList()
            }
        }
    }

    // ── Luckshack (Cloudflare-protected OpenCart) ──────────────────────────

    private fun luckshackPlaceholder(card: String): List<SearchResult> {
        val encoded = URLEncoder.encode(card, "UTF-8")
        return listOf(SearchResult(
            store = "", card = card,
            title = "Click to search Luckshack",
            priceZar = null, available = null,
            url = "https://luckshack.co.za/index.php?route=product/asearch&search=$encoded",
            note = "Opens in browser",
        ))
    }

    // Only called when autoOpenLuckshack == true (runs on the Playwright executor thread)
    private fun searchLuckshack(card: String): List<SearchResult> {
        val encoded = URLEncoder.encode(card, "UTF-8")
        val searchUrl = "https://luckshack.co.za/index.php?route=product/asearch&search=$encoded"
        try {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI(searchUrl))
            }
        } catch (_: Exception) {}
        return listOf(SearchResult(
            store = "", card = card,
            title = "Opened Luckshack search in browser",
            priceZar = null, available = null,
            url = searchUrl,
            note = "Opens in browser",
        ))
    }

    // ── The Warren (JS-token-protected custom API) ─────────────────────────

    private fun searchWarren(card: String): List<SearchResult> {
        val encoded = URLEncoder.encode(card, "UTF-8")
        // f=1 MTG, t=1 singles, c=3 category, sc=7 subcategory, is=1 in-stock
        val searchPageUrl = "https://thewarren.co.za/search?f=1&q=$encoded&t=1&c=3&sc=7&is=1&o=0"
        val page = ctx!!.newPage()
        return try {
            page.navigate(searchPageUrl, Page.NavigateOptions()
                .setTimeout(30_000.0)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED))

            // Wait for product links to appear in the rendered DOM
            runCatching {
                page.waitForSelector(
                    "a[href*='/mtg?id=']",
                    Page.WaitForSelectorOptions().setTimeout(20_000.0)
                )
            }

            // Let any late-loading settle
            runCatching {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                    Page.WaitForLoadStateOptions().setTimeout(8_000.0))
            }

            val html = page.content()
            val results = parseWarrenHtml(html, card)
            debugDump(
                store = "warren",
                card = card,
                html = html,
                summary = buildString {
                    appendLine("URL navigated: $searchPageUrl")
                    appendLine("Final URL:     ${page.url()}")
                    appendLine("Results parsed: ${results.size}")
                    results.forEach { appendLine("  -> ${it.title}  R${it.priceZar}  available=${it.available}") }
                    if (results.isEmpty()) appendLine("  (none — check .html to see what rendered)")
                }
            )
            results
        } catch (e: Exception) {
            debugDump("warren", card, "", "EXCEPTION: ${e.message}")
            emptyList()
        } finally {
            page.close()
        }
    }

    private fun parseWarrenHtml(html: String, card: String): List<SearchResult> {
        val soup = Jsoup.parse(html)
        // Warren product links all use /mtg?id=N
        return soup.select("a[href*='/mtg?id=']").mapNotNull { a ->
            // Title is in the nearest text-bearing descendant
            val title = (a.selectFirst("[class*='name'], [class*='title'], [class*='Name'], [class*='Title']")
                ?: a).text().trim().ifEmpty { return@mapNotNull null }
            if (!isRelevant(card, title)) return@mapNotNull null
            val priceEl = a.selectFirst("[class*='price'], [class*='Price']")
                ?: a.parent()?.selectFirst("[class*='price'], [class*='Price']")
            val href = a.attr("href").let {
                if (it.startsWith("http")) it else "https://thewarren.co.za$it"
            }
            SearchResult(
                store = "", card = card, title = title,
                priceZar = parsePrice(priceEl?.text() ?: ""),
                available = true,
                url = href,
                note = "In stock",
            )
        }.distinctBy { it.url }
    }

    private fun parseWarrenJson(body: String, card: String): List<SearchResult> {
        val data = try {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return data.mapNotNull { p ->
            val obj = p.jsonObject
            val title = (obj["product_name"] ?: obj["name"])
                ?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            if (!isRelevant(card, title)) return@mapNotNull null
            val price = parsePrice(obj["price"]?.jsonPrimitive?.contentOrNull ?: "")
                ?: obj["actual_price"]?.jsonPrimitive?.doubleOrNull
            val qty = (obj["stock_available"] ?: obj["quantity"])
                ?.jsonPrimitive?.intOrNull ?: 0
            val available = qty > 0
            val path = (obj["url"] ?: obj["slug"])?.jsonPrimitive?.contentOrNull
            val productId = obj["product_id"]?.jsonPrimitive?.intOrNull
            val productUrl = when {
                path?.startsWith("http") == true -> path
                path != null                     -> "https://thewarren.co.za$path"
                productId != null                -> "https://thewarren.co.za/mtg?id=$productId"
                else                             -> "https://thewarren.co.za"
            }
            SearchResult(
                store = "",
                card = card,
                title = title,
                priceZar = price,
                available = available,
                url = productUrl,
                note = if (available) "In stock" else "Out of stock",
            )
        }
    }

    override fun close() {
        executor.submit {
            runCatching { ctx?.close() }
            runCatching { browser?.close() }
            runCatching { pw?.close() }
        }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        executor.shutdown()
        dispatcher.close()
    }
}
