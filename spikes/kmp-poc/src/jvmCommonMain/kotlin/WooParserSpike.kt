package co.za.zarchive.poc

import org.jsoup.Jsoup

/**
 * Phase 0A spike: verbatim-adapted port of the `li.product` parsing branch of
 * network/Searchers.kt's searchWooCommerce (desktop project, lines ~280-313), using the exact same
 * Jsoup selector surface (.select, .selectFirst, .text, .attr, .classNames), running against a real
 * HTML fixture captured live from https://shop.dracoti.co.za (a genuine WooCommerce store already
 * in ZArchive's STORES list), embedded below since it's a small excerpt (~5.5KB, 3 listings).
 */

data class ParsedListing(
    val title: String,
    val priceText: String,
    val available: Boolean,
    val productId: Long?,
    val url: String,
)

fun parseWooListingSnippet(html: String): List<ParsedListing> {
    val soup = Jsoup.parse(html)
    return soup.select("li.product").mapNotNull { li ->
        val a = li.selectFirst("a.woocommerce-LoopProduct-link, a")
        val titleEl = li.selectFirst(".woocommerce-loop-product__title, h2, h3")
        val priceEl = li.selectFirst(".price ins") ?: li.selectFirst(".price")
        val title = titleEl?.text()?.trim() ?: return@mapNotNull null
        val outOfStock = li.selectFirst(".out-of-stock, .outofstock") != null ||
            li.classNames().any { it.contains("outofstock") }
        val available = !outOfStock
        val productId = li.selectFirst("[data-product_id]")
            ?.attr("data-product_id")?.toLongOrNull()
            ?: li.classNames().firstOrNull { it.matches(Regex("post-\\d+")) }
                ?.removePrefix("post-")?.toLongOrNull()
        ParsedListing(
            title = title,
            priceText = priceEl?.text()?.trim() ?: "",
            available = available,
            productId = productId,
            url = a?.attr("href")?.takeIf { it.isNotEmpty() } ?: "",
        )
    }
}

// Real HTML captured live via curl from https://shop.dracoti.co.za/?s=lightning&post_type=product
// on 2026-07-11. Truncated to a ~5.5KB slice containing 3 li.product entries.
val REAL_DRACOTI_FIXTURE = """
<ul class="products columns-4">
<li class="sales-flash-overlay woocommerce-text-align-center woocommerce-image-align-center do-quantity-buttons product type-product post-518057 status-publish first instock product_cat-card-crypt-dracotis-vault-coldsnap product_cat-cspfoils product_cat-csprare product_tag-cspcreature product_tag-csprare product_tag-cspred has-post-thumbnail taxable shipping-taxable purchasable product-type-simple">
	<a href="https://shop.dracoti.co.za/product/lightning-serpent-foil/" class="woocommerce-LoopProduct-link woocommerce-loop-product__link"><div class="wc-product-image"><div class="inside-wc-product-image"><img width="223" height="310" src="https://shop.dracoti.co.za/wp-content/uploads/2018/10/lightning-serpent1-1.jpeg" class="attachment-woocommerce_thumbnail size-woocommerce_thumbnail" alt="Lightning Serpent" decoding="async" fetchpriority="high" /></div></div><h2 class="woocommerce-loop-product__title">Lightning Serpent (FOIL)</h2>
	<span class="price"><span class="woocommerce-Price-amount amount"><bdi><span class="woocommerce-Price-currencySymbol">&#82;</span>255.00</bdi></span></span>
</a><a href="/?s=lightning&#038;post_type=product&#038;add-to-cart=518057" aria-describedby="woocommerce_loop_add_to_cart_link_describedby_518057" data-quantity="1" class="button product_type_simple add_to_cart_button ajax_add_to_cart" data-product_id="518057" data-product_sku="738244" aria-label="Add to cart: &ldquo;Lightning Serpent (FOIL)&rdquo;" rel="nofollow" data-success_message="&ldquo;Lightning Serpent (FOIL)&rdquo; has been added to your cart" role="button">Add to cart</a>	<span id="woocommerce_loop_add_to_cart_link_describedby_518057" class="screen-reader-text">
			</span>
</li>
<li class="sales-flash-overlay woocommerce-text-align-center woocommerce-image-align-center do-quantity-buttons product type-product post-517974 status-publish instock product_cat-core-set-2019 product_cat-core-set-2019-core-set-2019-singles-m19common product_tag-m19common product_tag-m19instant product_tag-m19red has-post-thumbnail taxable shipping-taxable purchasable product-type-simple">
	<a href="https://shop.dracoti.co.za/product/radiating-lightning-welcome-deck/" class="woocommerce-LoopProduct-link woocommerce-loop-product__link"><div class="wc-product-image"><div class="inside-wc-product-image"><img width="300" height="418" src="https://shop.dracoti.co.za/wp-content/uploads/2026/07/m19-313-radiating-lightning-300x418.png" class="attachment-woocommerce_thumbnail size-woocommerce_thumbnail" alt="Radiating Lightning (Welcome Deck)" decoding="async" /></div></div><h2 class="woocommerce-loop-product__title">Radiating Lightning (Welcome Deck)</h2>
	<span class="price"><span class="woocommerce-Price-amount amount"><bdi><span class="woocommerce-Price-currencySymbol">&#82;</span>12.50</bdi></span></span>
</a><a href="/?s=lightning&#038;post_type=product&#038;add-to-cart=517974" aria-describedby="woocommerce_loop_add_to_cart_link_describedby_517974" data-quantity="1" class="button product_type_simple add_to_cart_button ajax_add_to_cart" data-product_id="517974" data-product_sku="738206" aria-label="Add to cart" rel="nofollow" role="button">Add to cart</a>	<span id="woocommerce_loop_add_to_cart_link_describedby_517974" class="screen-reader-text">
			</span>
</li>
<li class="sales-flash-overlay woocommerce-text-align-center woocommerce-image-align-center do-quantity-buttons product type-product post-516270 status-publish instock product_cat-msh product_cat-msh-common product_cat-mshfoil product_tag-mshcommon product_tag-mshinstant product_tag-mshred has-post-thumbnail taxable shipping-taxable purchasable product-type-simple">
	<a href="https://shop.dracoti.co.za/product/lightning-strike-foil-8/" class="woocommerce-LoopProduct-link woocommerce-loop-product__link"><div class="wc-product-image"><div class="inside-wc-product-image"><img width="300" height="418" src="https://shop.dracoti.co.za/wp-content/uploads/2026/06/msh-142-lightning-strike-300x418.jpeg" class="attachment-woocommerce_thumbnail size-woocommerce_thumbnail" alt="Lightning Strike (FOIL)" decoding="async" /></div></div><h2 class="woocommerce-loop-product__title">Lightning Strike (FOIL)</h2>
	<span class="price"><span class="woocommerce-Price-amount amount"><bdi><span class="woocommerce-Price-currencySymbol">&#82;</span>5.00</bdi></span></span>
</a><a href="/?s=lightning&#038;post_type=product&#038;add-to-cart=516270" aria-describedby="woocommerce_loop_add_to_cart_link_describedby_516270" data-quantity="1" class="button product_type_simple add_to_cart_button ajax_add_to_cart" data-product_id="516270" data-product_sku="737409" aria-label="Add to cart" rel="nofollow" role="button">Add to cart</a>
</li>
</ul>
""".trimIndent()

fun runJsoupSpike(): String {
    val results = parseWooListingSnippet(REAL_DRACOTI_FIXTURE)
    if (results.size != 3) return "FAIL: expected 3 listings, got ${results.size}"
    val summary = results.joinToString("; ") { "${it.title} (R${it.priceText}, id=${it.productId}, avail=${it.available})" }
    return "OK (${results.size} parsed): $summary"
}
