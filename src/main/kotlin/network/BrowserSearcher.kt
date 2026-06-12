package network

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import data.SearchResult
import data.isRelevant
import data.parsePrice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
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

        fun init() {
            if (pw != null) return
            pw = Playwright.create()
            browser = launchBrowser(pw!!)
            ctx = createContext(browser!!)
        }

        fun close() {
            executor.submit {
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
            if ("thewarren" in base) searchWarren(card, lane.ctx!!) else emptyList()
        }
    }

    // ── Browser / context setup (called once per lane on its own thread) ───────

    private fun launchBrowser(pw: Playwright): Browser {
        val args = listOf("--disable-blink-features=AutomationControlled")
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

    private fun searchWarren(card: String, ctx: BrowserContext): List<SearchResult> {
        val encoded = URLEncoder.encode(card, "UTF-8")
        val url = "https://thewarren.co.za/search?f=1&q=$encoded&t=1&c=3&sc=7&is=1&o=0"
        val page = ctx.newPage()
        return try {
            page.navigate(url, Page.NavigateOptions()
                .setTimeout(30_000.0)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
            runCatching {
                page.waitForSelector("a[href*='/mtg?id=']",
                    Page.WaitForSelectorOptions().setTimeout(20_000.0))
            }
            runCatching {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                    Page.WaitForLoadStateOptions().setTimeout(2_500.0))
            }
            val html = page.content()
            val results = parseWarrenHtml(html, card)
            debugDump("warren", card, html, buildString {
                appendLine("URL: $url"); appendLine("Final: ${page.url()}")
                appendLine("Results: ${results.size}")
                results.forEach { appendLine("  ${it.title}  R${it.priceZar}  available=${it.available}") }
                if (results.isEmpty()) appendLine("  (none — check .html)")
            })
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
        return soup.select("a[href*='/mtg?id=']").mapNotNull { a ->
            val title = (a.selectFirst("[class*='name'],[class*='title'],[class*='Name'],[class*='Title']")
                ?: a).text().trim().ifEmpty { return@mapNotNull null }
            if (!isRelevant(card, title)) return@mapNotNull null
            val priceEl = a.selectFirst("[class*='price'],[class*='Price']")
                ?: a.parent()?.selectFirst("[class*='price'],[class*='Price']")
            val href = a.attr("href").let {
                if (it.startsWith("http")) it else "https://thewarren.co.za$it"
            }
            SearchResult(
                store = "", card = card, title = title,
                priceZar = parsePrice(priceEl?.text() ?: ""),
                available = true, url = href, note = "In stock",
            )
        }.distinctBy { it.url }
    }

    override fun close() = lanes.forEach { it.close() }
}
