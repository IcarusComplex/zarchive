package network

import data.Platform
import data.SearchResult
import data.isRelevant
import data.parsePrice
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val IS_DEBUG = System.getProperty("mtg.debug") == "true"
private val DEBUG_DIR by lazy {
    java.io.File(System.getProperty("user.home"), "zarchive-debug").also { it.mkdirs() }
}

class CloudflareBlockedException : Exception("Cloudflare challenge — store skipped")

private suspend fun checkStatus(response: HttpResponse) {
    when (response.status.value) {
        429 -> {
            if (IS_DEBUG) dump429(response)
            throw CloudflareBlockedException()
        }
        !in 200..299 -> throw Exception("HTTP ${response.status.value}")
    }
}

private suspend fun dump429(response: HttpResponse) {
    runCatching {
        val req = response.call.request
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss_SSS"))
        val host = req.url.host.replace(Regex("[^a-zA-Z0-9]"), "_")
        val body = runCatching { response.bodyAsText() }.getOrDefault("[body unreadable]")

        val text = buildString {
            appendLine("=== REQUEST ===")
            appendLine("${req.method.value} ${req.url}")
            appendLine()
            req.headers.forEach { key, values -> values.forEach { v -> appendLine("$key: $v") } }
            appendLine()
            appendLine("=== RESPONSE ===")
            appendLine("HTTP ${response.status.value} ${response.status.description}")
            appendLine()
            response.headers.forEach { key, values -> values.forEach { v -> appendLine("$key: $v") } }
            appendLine()
            appendLine("--- Body ---")
            appendLine(body)
        }

        val file = DEBUG_DIR.resolve("429_${host}_$ts.txt")
        file.writeText(text)
        println("[DEBUG] 429 dump → ${file.absolutePath}")
    }.onFailure { e -> println("[DEBUG] Failed to write 429 dump: ${e.message}") }
}

private fun resolveUrl(base: String, path: String): String {
    if (path.startsWith("http")) return path
    val baseUri = URI(base)
    return baseUri.resolve(path).toString()
}

suspend fun detectPlatform(client: HttpClient, base: String): Platform {
    return try {
        val r = client.get("$base/products.json?limit=1")
        if (r.status == HttpStatusCode.OK && "products" in r.bodyAsText().take(200)) {
            return Platform.SHOPIFY
        }
        detectFromHomepage(client, base)
    } catch (_: Exception) {
        detectFromHomepage(client, base)
    }
}

private suspend fun detectFromHomepage(client: HttpClient, base: String): Platform {
    return try {
        val body = client.get(base).bodyAsText().lowercase()
        when {
            "woocommerce" in body || "wp-content" in body -> Platform.WOOCOMMERCE
            "route=product" in body || "opencart" in body -> Platform.OPENCART
            "cdn.shopify" in body || "myshopify" in body  -> Platform.SHOPIFY
            else                                           -> Platform.UNKNOWN
        }
    } catch (_: Exception) {
        Platform.UNREACHABLE
    }
}

suspend fun searchShopify(client: HttpClient, base: String, card: String): List<SearchResult> = coroutineScope {
    val url = "$base/search/suggest.json"
    val response = client.get(url) {
        parameter("q", card)
        parameter("resources[type]", "product")
        parameter("resources[limit]", "10")
    }
    checkStatus(response)
    val body = response.bodyAsText()

    val products = try {
        Json.parseToJsonElement(body)
            .jsonObject["resources"]
            ?.jsonObject?.get("results")
            ?.jsonObject?.get("products")
            ?.jsonArray ?: return@coroutineScope emptyList()
    } catch (_: Exception) {
        return@coroutineScope emptyList()
    }

    // Pre-filter by relevance before making extra requests
    data class Candidate(val title: String, val relUrl: String, val suggestPrice: Double?, val suggestAvailable: Boolean?)
    val candidates = products.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        Candidate(
            title = title,
            relUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
            suggestPrice = obj["price"]?.jsonPrimitive?.doubleOrNull,
            suggestAvailable = obj["available"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    // Shopify suggest API only returns the minimum variant price. Fetch /products/{handle}.js
    // for each result to get the default (first) variant price and the first available variant's
    // cart ID. Availability for the result row comes from the suggest API (product-level).
    // .js endpoint is used (not .json) because .json omits per-variant available fields.
    val sem = Semaphore(3)
    candidates.map { c ->
        async(Dispatchers.IO) {
            sem.withPermit {
                val handle = c.relUrl.removePrefix("/products/").substringBefore("?").trim('/')
                val variant = if (handle.isNotBlank())
                    shopifyFirstVariant(client, base, handle)
                else null

                val price = variant?.price ?: c.suggestPrice
                val available = c.suggestAvailable  // suggest API availability is authoritative
                SearchResult(
                    store = "",
                    card = card,
                    title = c.title,
                    priceZar = price,
                    available = available,
                    url = resolveUrl(base, c.relUrl),
                    note = availabilityNote(available),
                    variantId = variant?.id,
                )
            }
        }
    }.awaitAll()
}

private data class ShopifyVariant(val price: Double, val id: Long?)

/**
 * Fetches /products/{handle}.js and returns the display price + the best cart variant ID.
 *
 * Price: variants[0] — what Shopify defaults to on the product page (typically NM non-foil).
 * Cart ID: first variant where available==true, so the cart permalink doesn't bounce as
 * out-of-stock. Falls back to variants[0].id when no variant has available info.
 * Availability is NOT returned here — the suggest API value is used by the caller.
 *
 * .js endpoint (not .json) is used because it includes per-variant "available" fields;
 * the public .json endpoint omits them entirely.
 */
private suspend fun shopifyFirstVariant(
    client: HttpClient,
    base: String,
    handle: String,
): ShopifyVariant? {
    return try {
        val body = client.get("$base/products/$handle.js").bodyAsText()
        val variantObjs = Json.parseToJsonElement(body)
            .jsonObject["variants"]?.jsonArray
            ?.mapNotNull { it.jsonObject } ?: return null
        val first = variantObjs.firstOrNull() ?: return null
        // .js prices are in minor currency units (cents): 8500 = R85.00
        val price = (first["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null) / 100.0
        val cartId = variantObjs
            .firstOrNull { it["available"]?.jsonPrimitive?.booleanOrNull == true }
            ?.get("id")?.jsonPrimitive?.longOrNull
            ?: first["id"]?.jsonPrimitive?.longOrNull
        ShopifyVariant(price, cartId)
    } catch (_: Exception) {
        null
    }
}

suspend fun searchWooCommerce(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/?s=$encoded&post_type=product"
    val resp = client.get(url)
    checkStatus(resp)
    val body = resp.bodyAsText()

    val soup = Jsoup.parse(body)

    // WooCommerce redirects directly to the product page when there is exactly one result.
    // Detect via the canonical URL tag — WooCommerce always sets it to the actual page URL,
    // so /product/ in canonical means we landed on a product detail page, not a results list.
    // (Can't use li.product count: product detail pages also include a related-products list.)
    val canonicalUrl = soup.selectFirst("link[rel=canonical]")?.attr("href") ?: ""
    if ("/product/" in canonicalUrl) {
        val title = soup.selectFirst("h1.product_title")?.text()?.trim() ?: return emptyList()
        if (!isRelevant(card, title)) return emptyList()
        val priceEl = soup.selectFirst(".price ins") ?: soup.selectFirst(".price")
        // Scope stock check to the main product summary — related-products lower on the page
        // also have .stock elements which would give a false out-of-stock reading.
        val summaryEl = soup.selectFirst("div.summary, .entry-summary") ?: soup
        val stockEl = summaryEl.selectFirst(".stock")
        val outOfStock = stockEl?.hasClass("out-of-stock") == true
        val available = !outOfStock
        val productId = summaryEl.selectFirst("[data-product_id]")?.attr("data-product_id")?.toLongOrNull()
            ?: soup.selectFirst("[data-product_id]")?.attr("data-product_id")?.toLongOrNull()
        val productUrl = soup.selectFirst("link[rel=canonical]")?.attr("href") ?: url
        return listOf(SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = parsePrice(priceEl?.text() ?: ""),
            available = available,
            url = productUrl,
            note = availabilityNote(available),
            variantId = productId,
        ))
    }

    return soup.select("li.product").mapNotNull { li ->
        val a = li.selectFirst("a.woocommerce-LoopProduct-link, a")
        val titleEl = li.selectFirst(".woocommerce-loop-product__title, h2, h3")
        // Prefer the sale price (ins) over the full .price text which includes strikethrough original
        val priceEl = li.selectFirst(".price ins") ?: li.selectFirst(".price")
        val title = titleEl?.text()?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        val outOfStock = li.selectFirst(".out-of-stock, .outofstock") != null ||
            li.classNames().any { it.contains("outofstock") }
        val available = !outOfStock
        // Try the add-to-cart button attribute first; fall back to the post-{ID} class
        // that WooCommerce always adds to every li.product (works even when the theme
        // omits the button from search result listings).
        val productId = li.selectFirst("[data-product_id]")
            ?.attr("data-product_id")?.toLongOrNull()
            ?: li.classNames().firstOrNull { it.matches(Regex("post-\\d+")) }
                ?.removePrefix("post-")?.toLongOrNull()
        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = parsePrice(priceEl?.text() ?: ""),
            available = available,
            url = a?.attr("href")?.takeIf { it.isNotEmpty() } ?: url,
            note = availabilityNote(available),
            variantId = productId,
        )
    }
}

// WooCommerce Blocks Store API — works on WC stores that have Gutenberg blocks enabled.
// Returns JSON with prices in minor currency units (divide by 10^currency_minor_unit).
suspend fun searchWcStoreApi(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/wp-json/wc/store/v1/products?search=$encoded&per_page=10"
    val resp = client.get(url) {
        header(HttpHeaders.Accept, "application/json")
    }
    checkStatus(resp)
    val body = resp.bodyAsText()

    val products = try {
        Json.parseToJsonElement(body).jsonArray
    } catch (_: Exception) {
        return emptyList()
    }

    return products.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        val prices = obj["prices"]?.jsonObject
        val minorUnit = prices?.get("currency_minor_unit")?.jsonPrimitive?.intOrNull ?: 2
        val rawPrice = prices?.get("price")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val price = rawPrice?.let { it.toDouble() / Math.pow(10.0, minorUnit.toDouble()) }
        val permalink = obj["permalink"]?.jsonPrimitive?.contentOrNull ?: "$base/"
        val productId = obj["id"]?.jsonPrimitive?.longOrNull
        // WC Store API only returns purchasable/in-stock products by default
        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = price,
            available = true,
            url = permalink,
            note = availabilityNote(true),
            variantId = productId,
        )
    }
}

suspend fun searchOpenCart(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    // Try asearch first (used by stores like Luckshack), then standard search
    for (route in listOf("product/asearch", "product/search")) {
        val url = "$base/index.php?route=$route&search=$encoded"
        val results = try {
            val resp = client.get(url)
            checkStatus(resp)
            val body = resp.bodyAsText()
            val soup = Jsoup.parse(body)
            soup.select(".product-thumb, .product-layout").mapNotNull { div ->
                val a = div.selectFirst("h4 a, .caption a") ?: return@mapNotNull null
                val title = a.text().trim()
                if (!isRelevant(card, title)) return@mapNotNull null
                val priceEl = div.selectFirst(".price, .price-new")
                SearchResult(
                    store = "",
                    card = card,
                    title = title,
                    priceZar = parsePrice(priceEl?.text() ?: ""),
                    available = null,
                    url = a.attr("href").takeIf { it.isNotEmpty() } ?: url,
                    note = availabilityNote(null),
                )
            }
        } catch (e: CloudflareBlockedException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (results.isNotEmpty()) return results
    }
    return emptyList()
}

suspend fun searchBigCommerce(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/search.php?search_query=$encoded"
    val resp = client.get(url)
    checkStatus(resp)
    val body = resp.bodyAsText()
    val soup = Jsoup.parse(body)
    return soup.select("li.product, article.card").mapNotNull { el ->
        val a = el.selectFirst(".card-title a, h4 a, h3 a") ?: return@mapNotNull null
        val title = a.text().trim()
        if (!isRelevant(card, title)) return@mapNotNull null
        // Use data attributes to target the actual price, not hidden RRP/non-sale spans
        val priceEl = el.selectFirst("[data-product-price-with-tax], [data-product-price-without-tax]")
            ?: el.selectFirst(".price--withTax, .price--withoutTax")
        val outOfStock = el.selectFirst(".button--outofstock, [data-button-type='out-of-stock']") != null
        val available = !outOfStock
        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = parsePrice(priceEl?.text() ?: ""),
            available = available,
            url = a.attr("href").let { if (it.startsWith("http")) it else "$base$it" },
            note = availabilityNote(available),
        )
    }
}

suspend fun searchPrestaShop(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/search?controller=search&search_query=$encoded"
    val resp = client.get(url)
    checkStatus(resp)
    val body = resp.bodyAsText()
    val soup = Jsoup.parse(body)
    return soup.select("article.product-miniature, .js-product-miniature").mapNotNull { el ->
        val a = el.selectFirst(".product-title a, h3 a, h2 a") ?: return@mapNotNull null
        val title = a.text().trim()
        if (!isRelevant(card, title)) return@mapNotNull null

        // AI Fest uses .market-rands with a bare number price; standard PS uses .price with R prefix
        val price: Double? = run {
            val marketEl = el.selectFirst(".market-rands")
            if (marketEl != null) {
                marketEl.text().trim().replace(",", "").toDoubleOrNull()
            } else {
                parsePrice(el.selectFirst(".price, .product-price")?.text() ?: "")
            }
        }

        // AI Fest shows .product-in-stock when marketplace stock is available;
        // the PrestaShop-native out_of_stock flag is unreliable for marketplace stores.
        val inStockDiv = el.selectFirst(".product-in-stock")
        val qtyEl = el.selectFirst(".product-qty b, .product-qty strong")
        val explicitOutOfStock = el.selectFirst(".product-unavailable, .out-of-stock") != null
        val available: Boolean = when {
            inStockDiv != null                     -> true
            qtyEl?.text()?.trim()?.toIntOrNull()?.let { it > 0 } == true -> true
            explicitOutOfStock                     -> false
            else                                   -> true  // appeared in results → assume listable
        }

        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = price,
            available = available,
            url = a.attr("href").takeIf { it.isNotEmpty() } ?: url,
            note = availabilityNote(available),
        )
    }
}

// This API endpoint requires an auth token that The Warren does not expose publicly.
// Calls always return 401. The Warren is therefore searched via BrowserSearcher (Playwright headless),
// which handles the JS token exchange. Do not attempt to fall back to this function.
suspend fun searchWarrenApi(client: HttpClient, base: String, card: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/openapi/1.0.0fe/client/product/search?" +
        "limit=20&offset=0&search_term=$encoded&in_stock=0" +
        "&on_special=0&preorder=0&cmc_filter=e" +
        "&white=0&blue=0&black=0&red=0&green=0&multicoloured=0&colourless=0" +
        "&is_foil=0&is_token=0&is_art=0" +
        "&swu_cost_filter=e&swu_power_filter=e&swu_hp_filter=e&swu_is_foil=0"
    val body = client.get(url) {
        header("Referer", "$base/search?f=1&q=${encoded}&is=1&o=0")
    }.bodyAsText()

    val data = try {
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
    } catch (_: Exception) {
        return emptyList()
    }

    return data.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        val priceStr = obj["price"]?.jsonPrimitive?.contentOrNull ?: ""
        val price = parsePrice(priceStr) ?: obj["actual_price"]?.jsonPrimitive?.doubleOrNull
        val qty = obj["stock_available"]?.jsonPrimitive?.intOrNull ?: 0
        val available = qty > 0
        val slug = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
        val productUrl = if (slug.startsWith("http")) slug else "$base$slug"
        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = price,
            available = available,
            url = productUrl,
            note = availabilityNote(available),
        )
    }
}

private fun availabilityNote(available: Boolean?) = when (available) {
    true  -> "In stock"
    false -> "Out of stock"
    null  -> "Stock unknown"
}
