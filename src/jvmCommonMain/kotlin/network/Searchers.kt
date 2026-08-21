package network

import data.Platform
import data.PlatformPaths
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
private val DEBUG_DIR by lazy { PlatformPaths.debugDumpDir }

// Matches WooCommerce's core low-stock-threshold wording, e.g. "Only 2 left in stock".
private val WOOCOMMERCE_STOCK_QTY_RE = Regex("""(?i)only\s+(\d+)\s+left""")

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
    data class Candidate(val title: String, val relUrl: String, val suggestPrice: Double?, val suggestAvailable: Boolean?, val setHint: String?)
    val candidates = products.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        // The suggest API returns a "body" field with the product's HTML description.
        // Stores with structured TCG singles data include a "Set:" label — parse two known formats:
        //   D20 Battleground: <table><tr><td>Set:</td><td>SET NAME</td></tr></table>
        //   Wzrd TCG:         <b>Set:</b> Set Name (CODE)<br>
        val bodyHtml = obj["body"]?.jsonPrimitive?.contentOrNull
        val bodyHint = bodyHtml?.let { html ->
            val soup = Jsoup.parse(html)
            soup.select("tr")
                .firstOrNull { tr -> tr.select("td").firstOrNull()?.text()?.trim() == "Set:" }
                ?.select("td")?.getOrNull(1)?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: soup.select("b").firstOrNull { it.text().trim() == "Set:" }
                ?.nextSibling()?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        // Untapped Potential TCG (and possibly other Shopify TCG themes) tags each product with
        // a "Set:<value>" entry — either a bare set name ("Set:Lorwyn Eclipsed") or a
        // "CODE-collectorNumber" SKU ("Set:TDM-358"). Either form is handled downstream by
        // CardImageService (findCode() for names, PAREN_SET_CODE_RE for CODE-number SKUs).
        val tagsHint = obj["tags"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.firstOrNull { it.startsWith("Set:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?.takeIf { it.isNotBlank() }
        val setHint = bodyHint ?: tagsHint
        Candidate(
            title = title,
            relUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
            suggestPrice = obj["price"]?.jsonPrimitive?.doubleOrNull,
            suggestAvailable = obj["available"]?.jsonPrimitive?.booleanOrNull,
            setHint = setHint,
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
                    setHint = variant?.setHint ?: c.setHint,
                    stockQty = variant?.stockQty,
                )
            }
        }
    }.awaitAll()
}

private data class ShopifyVariant(val price: Double, val id: Long?, val setHint: String? = null, val stockQty: Int? = null)

/**
 * Fetches /products/{handle}.js and returns the display price, the best cart variant ID,
 * and an optional set name when the store exposes a "Set" variant option (e.g. Wzrd TCG).
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
        val productObj = Json.parseToJsonElement(body).jsonObject
        val variantObjs = productObj["variants"]?.jsonArray
            ?.mapNotNull { it.jsonObject } ?: return null
        val first = variantObjs.firstOrNull() ?: return null
        // Use first available variant for price — matches what Shopify shows on the product page.
        // variants[0] may be an out-of-stock condition (e.g. MP) cheaper than the available NM copy.
        val firstAvailable = variantObjs.firstOrNull { it["available"]?.jsonPrimitive?.booleanOrNull == true }
        val display = firstAvailable ?: first
        // .js prices are in minor currency units (cents): 8500 = R85.00
        val price = (display["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null) / 100.0
        val cartId = firstAvailable?.get("id")?.jsonPrimitive?.longOrNull
            ?: first["id"]?.jsonPrimitive?.longOrNull
        // Some stores (e.g. Wzrd TCG) expose a "Set" option whose value is the full set name.
        val setPosition = productObj["options"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull.equals("Set", ignoreCase = true) }
            ?.get("position")?.jsonPrimitive?.intOrNull
        val setHint = setPosition?.let { pos ->
            display["option$pos"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        val stockQty = cartId?.let { probeShopifyStock(client, base, it) }
        ShopifyVariant(price, cartId, setHint, stockQty)
    } catch (_: Exception) {
        null
    }
}

// Shopify's public .js/.json product endpoints never expose real inventory counts (deliberate
// anti-scraping policy) -- but the storefront's own /cart/add.js endpoint reveals it indirectly:
// requesting more than what's in stock either succeeds and silently caps the added quantity, or
// (observed live, Aug 2026) rejects with HTTP 422 whose message states the real capped amount,
// e.g. "Only 3 items were added to your cart due to availability." A genuinely sold-out variant
// instead returns "...is already sold out." with no number. This is documented Shopify behavior
// (their own community forums describe it), not an exploit -- it just creates a harmless
// anonymous, abandoned cart per check; this app's Ktor client doesn't persist cookies, so there's
// no cross-request cart-state accumulation to worry about.
private const val SHOPIFY_PROBE_QTY = 20
private val SHOPIFY_CAPPED_QTY_RE = Regex("""(?i)only\s+(\d+)\s+items?\s+(?:was|were)\s+added""")
private val SHOPIFY_SOLD_OUT_RE = Regex("""(?i)sold out""")

private suspend fun probeShopifyStock(client: HttpClient, base: String, variantId: Long): Int? {
    return try {
        val resp = client.post("$base/cart/add.js") {
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"id":$variantId,"quantity":$SHOPIFY_PROBE_QTY}]}""")
        }
        val body = resp.bodyAsText()
        when (resp.status.value) {
            200 -> {
                val qty = Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("quantity")?.jsonPrimitive?.intOrNull
                // Hitting our own probe ceiling exactly means we can't tell real stock from a
                // backorder-allowed variant -- treat as unknown/unlimited, not a false "20".
                qty?.takeIf { it < SHOPIFY_PROBE_QTY }
            }
            422 -> {
                SHOPIFY_CAPPED_QTY_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (SHOPIFY_SOLD_OUT_RE.containsMatchIn(body)) 0 else null
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

// WooCommerce's own add-to-cart AJAX endpoint (/?wc-ajax=add_to_cart, form-encoded product_id +
// quantity) silently rejects a request once the quantity exceeds real stock -- but unlike
// Shopify's /cart/add.js, the failure response is a bare {"error":true,...} with no number telling
// us what the real cap is (confirmed live, Aug 2026: qty=3 succeeded, qty=4 failed, for a listing
// whose real stock was exactly 3 -- the exact bug this fixes, where an unknown-stock listing was
// treated as able to supply an entire 28-card request). A success response is a "fragments" JSON
// object (mini-cart HTML), so `"error":true` absent is the success signal.
//
// Since failure carries no number, pinning down the exact cap needs a small binary search rather
// than Shopify's one-shot read. To keep the common case (plentiful/untracked stock) cheap, a
// single probe at WC_PROBE_CEILING is tried first -- only a listing that actually fails that
// probe (i.e. is meaningfully limited) pays for the ~5 extra requests to locate the exact boundary.
private const val WC_PROBE_CEILING = 20

private suspend fun wcAddToCartSucceeds(client: HttpClient, base: String, productId: Long, qty: Int): Boolean {
    return try {
        val resp = client.post("$base/?wc-ajax=add_to_cart") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("product_id=$productId&quantity=$qty")
        }
        resp.status.value == 200 && "\"error\":true" !in resp.bodyAsText()
    } catch (_: Exception) {
        false
    }
}

private suspend fun probeWooCommerceStock(client: HttpClient, base: String, productId: Long): Int? {
    // Plentiful/untracked stock -- treat as unknown/unlimited, same convention as everywhere else.
    if (wcAddToCartSucceeds(client, base, productId, WC_PROBE_CEILING)) return null
    var lowSucceeds = 0
    var highFails = WC_PROBE_CEILING
    while (highFails - lowSucceeds > 1) {
        val mid = (lowSucceeds + highFails) / 2
        if (wcAddToCartSucceeds(client, base, productId, mid)) lowSucceeds = mid else highFails = mid
    }
    return lowSucceeds.takeIf { it > 0 }
}

suspend fun searchWooCommerce(client: HttpClient, base: String, card: String): List<SearchResult> = coroutineScope {
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
        val title = soup.selectFirst("h1.product_title")?.text()?.trim() ?: return@coroutineScope emptyList()
        if (!isRelevant(card, title)) return@coroutineScope emptyList()
        val priceEl = soup.selectFirst(".price ins") ?: soup.selectFirst(".price")
        // Scope stock check to the main product summary — related-products lower on the page
        // also have .stock elements which would give a false out-of-stock reading.
        val summaryEl = soup.selectFirst("div.summary, .entry-summary") ?: soup
        val stockEl = summaryEl.selectFirst(".stock")
        val outOfStock = stockEl?.hasClass("out-of-stock") == true
        val available = !outOfStock
        val productId = summaryEl.selectFirst("[data-product_id]")?.attr("data-product_id")?.toLongOrNull()
            ?: soup.selectFirst("[data-product_id]")?.attr("data-product_id")?.toLongOrNull()
        // WooCommerce's core low-stock-threshold message renders as e.g. "Only 2 left in stock" in
        // this same element -- free when present, but only shows up near the threshold. Otherwise
        // (or if that text isn't there), fall back to the wc-ajax probe for a real number.
        val stockQty = WOOCOMMERCE_STOCK_QTY_RE.find(stockEl?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: if (available && productId != null) probeWooCommerceStock(client, base, productId) else null
        val productUrl = soup.selectFirst("link[rel=canonical]")?.attr("href") ?: url
        // Some TCG-singles WC themes put "Rarity | #Number | Set Name" in the short description.
        val shortDesc = summaryEl.selectFirst(".woocommerce-product-details__short-description")?.text()?.trim()
        val setHint = if (shortDesc != null && shortDesc.count { it == '|' } >= 2)
            shortDesc.substringAfterLast("|").trim().takeIf { it.length >= 4 }
        else null
        return@coroutineScope listOf(SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = parsePrice(priceEl?.text() ?: ""),
            available = available,
            url = productUrl,
            note = availabilityNote(available),
            variantId = productId,
            setHint = setHint,
            stockQty = stockQty,
        ))
    }

    data class Candidate(
        val title: String, val price: Double?, val available: Boolean,
        val url: String, val productId: Long?, val setHint: String?,
    )
    val candidates = soup.select("li.product").mapNotNull { li ->
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
        // Some TCG-singles WC themes include a short description in listing tiles.
        val shortDesc = li.selectFirst(".woocommerce-product-details__short-description, .short-description")?.text()?.trim()
        val setHint = if (shortDesc != null && shortDesc.count { it == '|' } >= 2)
            shortDesc.substringAfterLast("|").trim().takeIf { it.length >= 4 }
        else null
        Candidate(title, parsePrice(priceEl?.text() ?: ""), available, a?.attr("href")?.takeIf { it.isNotEmpty() } ?: url, productId, setHint)
    }

    // The results-grid tiles have no stock element at all (confirmed live) -- the wc-ajax probe
    // is the only source of a real number here, not just a low-stock-threshold bonus.
    val sem = Semaphore(3)
    candidates.map { c ->
        async(Dispatchers.IO) {
            sem.withPermit {
                val stockQty = if (c.available && c.productId != null) probeWooCommerceStock(client, base, c.productId) else null
                SearchResult(
                    store = "",
                    card = card,
                    title = c.title,
                    priceZar = c.price,
                    available = c.available,
                    url = c.url,
                    note = availabilityNote(c.available),
                    variantId = c.productId,
                    setHint = c.setHint,
                    stockQty = stockQty,
                )
            }
        }
    }.awaitAll()
}

// WooCommerce Blocks Store API — works on WC stores that have Gutenberg blocks enabled.
// Returns JSON with prices in minor currency units (divide by 10^currency_minor_unit).
// For variable products we fetch each product page to extract the first in-stock variation ID
// from the form's data-product_variations attribute — the parent product ID alone won't add
// the correct variant to the cart.
suspend fun searchWcStoreApi(client: HttpClient, base: String, card: String): List<SearchResult> = coroutineScope {
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
        return@coroutineScope emptyList()
    }

    data class Candidate(val title: String, val permalink: String, val price: Double?, val productId: Long?, val setHint: String?)
    val candidates = products.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        val prices = obj["prices"]?.jsonObject
        val minorUnit = prices?.get("currency_minor_unit")?.jsonPrimitive?.intOrNull ?: 2
        val rawPrice = prices?.get("price")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val price = rawPrice?.let { it.toDouble() / Math.pow(10.0, minorUnit.toDouble()) }
        val permalink = obj["permalink"]?.jsonPrimitive?.contentOrNull ?: "$base/"
        val productId = obj["id"]?.jsonPrimitive?.longOrNull
        // short_description often contains "Rarity | #Number | Set Name" — take the last segment
        // when ≥ 2 pipes are present. Short plain text (≤ 60 chars) is used as-is. Long flavor/
        // mechanic text (no pipes, > 60 chars) is ignored — the SKU fallback covers it.
        val shortDescHtml = obj["short_description"]?.jsonPrimitive?.contentOrNull
        val descSetHint = shortDescHtml?.let { html ->
            val text = Jsoup.parse(html).text().trim()
            when {
                text.count { it == '|' } >= 2 -> text.substringAfterLast("|").trim().takeIf { it.length >= 4 }
                text.length in 4..60 -> text
                else -> null
            }
        }
        // SKU is often "CODE-NUMBER" (e.g. "RVR-347") — CardImageService resolves it to a set
        // code via PAREN_SET_CODE_RE + isKnownCode when descSetHint is unavailable.
        val sku = obj["sku"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        Candidate(title, permalink, price, productId, descSetHint ?: sku)
    }

    val sem = Semaphore(3)
    candidates.map { c ->
        async(Dispatchers.IO) {
            sem.withPermit {
                // Fetch the product page: get the variation ID and, as a fallback, the set hint
                // from the page HTML (covers stores where the set isn't in short_description).
                val (variantId, pageSetHint) = resolveWcProductPage(client, c.permalink)
                SearchResult(
                    store = "",
                    card = card,
                    title = c.title,
                    priceZar = c.price,
                    available = true,
                    url = c.permalink,
                    note = availabilityNote(true),
                    variantId = variantId ?: c.productId,
                    setHint = c.setHint ?: pageSetHint,
                )
            }
        }
    }.awaitAll()
}

/**
 * Fetches a WooCommerce product page and returns:
 *  - The first in-stock variation ID (null for simple/non-variable products).
 *  - A set hint extracted from the short-description area or product-attributes table.
 *
 * The set hint extraction covers two common TCG-singles patterns:
 *   - "Rarity | #N | Set Name" short description → last | segment (requires ≥ 2 pipes).
 *   - A short plain-text short description like "Magic 2013" (≤ 60 chars, no pipes).
 *   - A product-attributes table row whose label contains "set" or "edition".
 */
private suspend fun resolveWcProductPage(client: HttpClient, productUrl: String): Pair<Long?, String?> {
    return try {
        val body = client.get(productUrl).bodyAsText()
        val soup = Jsoup.parse(body)

        // Variation ID
        val variationsJson = soup.selectFirst("form.variations_form[data-product_variations]")
            ?.attr("data-product_variations")
        val variationId = variationsJson?.let {
            val variations = runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull()
                ?: return@let null
            val picked = variations.firstOrNull { v ->
                v.jsonObject["is_in_stock"]?.jsonPrimitive?.booleanOrNull == true
            } ?: variations.firstOrNull()
            picked?.jsonObject?.get("variation_id")?.jsonPrimitive?.longOrNull
        }

        // Set hint — try short-description area first, then product-attributes table
        val shortDesc = soup
            .selectFirst(".woocommerce-product-details__short-description, .short-description")
            ?.text()?.trim()
        val setHint = when {
            shortDesc == null -> null
            shortDesc.count { it == '|' } >= 2 ->
                shortDesc.substringAfterLast("|").trim().takeIf { it.length >= 4 }
            shortDesc.length in 4..60 -> shortDesc   // "Magic 2013", "The Lost Caverns of Ixalan", etc.
            else -> null
        } ?: soup.select("table.woocommerce-product-attributes tr").firstNotNullOfOrNull { row ->
            val label = row.selectFirst("th")?.text()?.trim() ?: return@firstNotNullOfOrNull null
            if (label.contains("set", ignoreCase = true) || label.contains("edition", ignoreCase = true))
                row.selectFirst("td")?.text()?.trim()?.takeIf { it.length >= 4 }
            else null
        }

        variationId to setHint
    } catch (_: Exception) {
        null to null
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

suspend fun searchBigCommerce(client: HttpClient, base: String, card: String): List<SearchResult> = coroutineScope {
    // Quoting the query forces BigCommerce to match the exact phrase rather than individual
    // keywords — without quotes "Dark Ritual" returns 500+ unrelated results (cards from
    // "The Dark" set, cards with "Ritual" in the name, etc.) with the actual card buried.
    val encoded = java.net.URLEncoder.encode("\"$card\"", "UTF-8")
    val url = "$base/search.php?search_query=$encoded"
    val resp = client.get(url)
    checkStatus(resp)
    val body = resp.bodyAsText()
    val soup = Jsoup.parse(body)
    // BigCommerce Stencil structure: ul.productGrid > li.product > article.card
    // Selecting both "li.product, article.card" would match each product twice since article
    // is nested inside li. Use li.product as the single outer container.
    data class Candidate(val title: String, val price: Double?, val available: Boolean, val productUrl: String, val productId: Long?)
    val candidates = soup.select("li.product").mapNotNull { el ->
        val a = el.selectFirst(".card-title a, h4 a, h3 a") ?: return@mapNotNull null
        val title = a.text().trim()
        if (!isRelevant(card, title)) return@mapNotNull null
        // Use data attributes to target the actual price, not hidden RRP/non-sale spans
        val priceEl = el.selectFirst("[data-product-price-with-tax], [data-product-price-without-tax]")
            ?: el.selectFirst(".price--withTax, .price--withoutTax")
        val outOfStock = el.selectFirst(".button--outofstock, [data-button-type='out-of-stock']") != null
        val available = !outOfStock
        // Extract product ID from the add-to-cart link href or the quick-view data attribute
        val cartHref = el.selectFirst("a[href*='action=add'][href*='product_id=']")?.attr("href") ?: ""
        val productId = Regex("product_id=(\\d+)").find(cartHref)?.groupValues?.get(1)?.toLongOrNull()
            ?: el.selectFirst("[data-product-id]")?.attr("data-product-id")?.toLongOrNull()
        val productUrl = a.attr("href").let { if (it.startsWith("http")) it else "$base$it" }
        Candidate(title, parsePrice(priceEl?.text() ?: ""), available, productUrl, productId)
    }.distinctBy { it.productUrl }

    // The search grid has no stock count — fetch each in-stock candidate's product page for it.
    val sem = Semaphore(3)
    candidates.map { c ->
        async(Dispatchers.IO) {
            sem.withPermit {
                val stockQty = if (c.available) resolveBigCommerceStock(client, c.productUrl) else null
                SearchResult(
                    store = "",
                    card = card,
                    title = c.title,
                    priceZar = c.price,
                    available = c.available,
                    url = c.productUrl,
                    note = availabilityNote(c.available),
                    variantId = c.productId,
                    stockQty = stockQty,
                )
            }
        }
    }.awaitAll()
}

// Matches the "available_to_sell" field in the product page's embedded BCData JS blob (the
// count actually purchasable now — distinct from raw on-hand stock, which could include
// held/reserved units). Falls back to the rendered "Current Stock:" HTML span if that JS blob
// isn't present for some reason.
private val BC_AVAILABLE_TO_SELL_RE = Regex(""""available_to_sell"\s*:\s*(\d+)""")
private val BC_DATA_PRODUCT_STOCK_RE = Regex("""data-product-stock[^>]*>\s*(\d+)\s*<""")

private suspend fun resolveBigCommerceStock(client: HttpClient, productUrl: String): Int? {
    return try {
        val body = client.get(productUrl).bodyAsText()
        BC_AVAILABLE_TO_SELL_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: BC_DATA_PRODUCT_STOCK_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()
    } catch (_: Exception) {
        null
    }
}

// PrestaShop has two types of products on AI Fest:
// - Regular products: listing HTML has input.product_page_product_id with the cart ID
// - Marketplace products: listing HTML only has data-id-product (catalog ID); the real
//   cartable product ID is in a separate input.product_page_product_id on the product page
// We prefer the listing form ID, fall back to a product-page fetch, then to data-id-product.
suspend fun searchPrestaShop(client: HttpClient, base: String, card: String): List<SearchResult> = coroutineScope {
    val encoded = java.net.URLEncoder.encode(card, "UTF-8")
    val url = "$base/search?controller=search&search_query=$encoded"
    val resp = client.get(url)
    checkStatus(resp)
    val body = resp.bodyAsText()
    val soup = Jsoup.parse(body)
    // PrestaShop embeds a site-wide static token in every page as a JS variable.
    // It's an MD5 of server-side constants — same for all users and sessions.
    val staticToken = Regex("""var static_token\s*=\s*"([a-f0-9]{32})"""")
        .find(body)?.groupValues?.get(1)

    data class Candidate(
        val title: String, val productUrl: String, val price: Double?,
        val available: Boolean, val listingId: Long?, val stockQty: Int?,
    )
    val candidates = soup.select("article.product-miniature, .js-product-miniature").mapNotNull { el ->
        val a = el.selectFirst(".product-title a, h3 a, h2 a") ?: return@mapNotNull null
        val title = a.text().trim()
        if (!isRelevant(card, title)) return@mapNotNull null

        val price: Double? = run {
            val marketEl = el.selectFirst(".market-rands")
            if (marketEl != null) marketEl.text().trim().replace(",", "").toDoubleOrNull()
            else parsePrice(el.selectFirst(".price, .product-price")?.text() ?: "")
        }

        val inStockDiv = el.selectFirst(".product-in-stock")
        val qtyEl = el.selectFirst(".product-qty b, .product-qty strong")
        val qty = qtyEl?.text()?.trim()?.toIntOrNull()
        val explicitOutOfStock = el.selectFirst(".product-unavailable, .out-of-stock") != null
        val available: Boolean = when {
            inStockDiv != null -> true
            qty?.let { it > 0 } == true -> true
            explicitOutOfStock -> false
            else -> true
        }

        // Prefer the form's product_page_product_id (correct for both regular and marketplace).
        // data-id-product is the catalog ID and may differ from the cartable product ID.
        val listingId = el.selectFirst("input.product_page_product_id")?.attr("value")?.toLongOrNull()
            ?: el.attr("data-id-product").toLongOrNull()
            ?: el.selectFirst("[data-id-product]")?.attr("data-id-product")?.toLongOrNull()
        Candidate(title, a.attr("href").takeIf { it.isNotEmpty() } ?: url, price, available, listingId, qty?.takeIf { it > 0 })
    }

    val sem = Semaphore(3)
    candidates.map { c ->
        async(Dispatchers.IO) {
            sem.withPermit {
                // Fetch the product page to get the definitive cart product ID.
                // Marketplace items don't expose product_page_product_id in search listings,
                // so the product page is the only reliable source.
                val cartId = resolvePrestaShopCartId(client, c.productUrl) ?: c.listingId
                SearchResult(
                    store = "",
                    card = card,
                    title = c.title,
                    priceZar = c.price,
                    available = c.available,
                    url = c.productUrl,
                    note = availabilityNote(c.available),
                    variantId = cartId,
                    cartToken = staticToken,
                    stockQty = c.stockQty,
                )
            }
        }
    }.awaitAll()
}

private suspend fun resolvePrestaShopCartId(client: HttpClient, productUrl: String): Long? {
    return try {
        val body = client.get(productUrl).bodyAsText()
        Jsoup.parse(body).selectFirst("input.product_page_product_id")
            ?.attr("value")?.toLongOrNull()
    } catch (_: Exception) {
        null
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

// Untapped Potential TCG migrated off Shopify to a custom storefront (Aug 2026) backed by a
// Supabase RPC endpoint. The apikey below is Supabase's "publishable" key — it's embedded in the
// storefront's own client-side JS bundle (visible to any site visitor via devtools) and is
// deliberately public, not a privileged/secret token.
const val UNTAPPED_SUPABASE_HOST = "lflljzsbxgcemvrlaavo.supabase.co"
private const val UNTAPPED_SUPABASE_URL =
    "https://$UNTAPPED_SUPABASE_HOST/rest/v1/rpc/storefront_products_page_v1"
private const val UNTAPPED_API_KEY = "sb_publishable_x_to7XANoonahQHDQmcw6w_PlLa3aNr"

// The search RPC does fuzzy/trigram matching rather than a strict substring search, so it can
// return a lot of loosely-related noise (isRelevant filters it back down) but total_count reflects
// genuine matches, not a hard cap. p_sort only accepts "newest" — other values (e.g. "relevance")
// return a Postgres error ("Sort is invalid").
private val UNTAPPED_SET_CODE_RE = Regex(""" - ([A-Za-z0-9]{2,6}) #\d+$""")

suspend fun searchUntappedPotential(client: HttpClient, base: String, card: String): List<SearchResult> {
    val payload = buildJsonObject {
        put("p_category", "mtg")
        put("p_filters", buildJsonObject {
            put("price_min_cents", JsonNull)
            put("price_max_cents", JsonNull)
            put("wishlist_only", false)
        })
        put("p_page", 1)
        put("p_page_size", 30)
        put("p_search", card)
        put("p_sort", "newest")
        put("p_type", "singles")
        put("p_type_exclude", false)
        put("p_wishlist_product_ids", buildJsonArray {})
    }
    val resp = client.post(UNTAPPED_SUPABASE_URL) {
        header("apikey", UNTAPPED_API_KEY)
        header(HttpHeaders.Authorization, "Bearer $UNTAPPED_API_KEY")
        header("content-profile", "public")
        contentType(ContentType.Application.Json)
        setBody(payload.toString())
    }
    checkStatus(resp)
    val body = resp.bodyAsText()

    val items = try {
        Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray ?: return emptyList()
    } catch (_: Exception) {
        return emptyList()
    }

    return items.mapNotNull { p ->
        val obj = p.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
        if (!isRelevant(card, title)) return@mapNotNull null
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

        // Each product is already a single specific condition/finish combo (unlike Shopify's
        // multi-variant products), but pick the default variant defensively.
        val variants = obj["variants"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val variant = variants.firstOrNull { it["is_default"]?.jsonPrimitive?.booleanOrNull == true }
            ?: variants.firstOrNull()
        val stock = variant?.get("stock")?.jsonPrimitive?.intOrNull ?: 0
        val available = stock > 0

        // effective_price_cents is the discounted price actually charged (site-wide 5% off at
        // time of writing); price_cents/original_price_cents is the pre-discount price.
        val priceCents = obj["effective_price_cents"]?.jsonPrimitive?.longOrNull
            ?: variant?.get("effective_price_cents")?.jsonPrimitive?.longOrNull
        val price = priceCents?.let { it / 100.0 }

        // Titles are consistently "Card Name - CODE #123" — a much more reliable set code than
        // the payload's own "set_code" field, which is sometimes a real code and sometimes the
        // full set name (and occasionally neither, for non-Scryfall custom/proxy listings).
        val setHint = UNTAPPED_SET_CODE_RE.find(title)?.groupValues?.get(1)
            ?: obj["set_code"]?.jsonPrimitive?.contentOrNull

        SearchResult(
            store = "",
            card = card,
            title = title,
            priceZar = price,
            available = available,
            url = "$base/product/$slug",
            note = availabilityNote(available),
            setHint = setHint,
            stockQty = stock.takeIf { it > 0 },
        )
    }
}

private fun availabilityNote(available: Boolean?) = when (available) {
    true  -> "In stock"
    false -> "Out of stock"
    null  -> "Stock unknown"
}
