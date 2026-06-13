package data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParsePriceTest {

    private fun assertPrice(expected: Double, input: String) {
        val result = parsePrice(input)
        assertNotNull(result, "Expected a price from \"$input\" but got null")
        assertEquals(expected, result!!, 0.001)
    }

    // ── Standard dot-decimal (R30.00) ────────────────────────────────────────

    @Test fun `dot decimal whole rands`() = assertPrice(30.0, "R30.00")
    @Test fun `dot decimal fractional cents`() = assertPrice(29.99, "R29.99")
    @Test fun `dot decimal large amount`() = assertPrice(3000.0, "R3,000.00")
    @Test fun `dot decimal very large`() = assertPrice(15000.0, "R15,000.00")

    // ── European comma-decimal (R30,00) — the SA ZAR locale ─────────────────

    @Test fun `euro decimal basic`() = assertPrice(30.0, "R30,00")
    @Test fun `euro decimal with space thousands`() = assertPrice(1500.0, "R1 500,00")
    @Test fun `euro decimal large`() = assertPrice(2500.0, "R2 500,00")
    @Test fun `euro decimal three digit`() = assertPrice(750.0, "R750,00")

    // ── Whole number (no decimal) ────────────────────────────────────────────

    @Test fun `whole number no cents`() = assertPrice(120.0, "R120")
    @Test fun `whole number large`() = assertPrice(1000.0, "R1000")

    // ── Space after R ────────────────────────────────────────────────────────

    @Test fun `space after R dot decimal`() = assertPrice(75.0, "R 75.00")
    @Test fun `space after R euro decimal`() = assertPrice(75.0, "R 75,00")

    // ── Embedded in surrounding text ─────────────────────────────────────────

    @Test fun `price embedded in text`() = assertPrice(99.0, "In stock R99,00")
    @Test fun `price with label prefix`() = assertPrice(45.0, "Price: R45,00")
    @Test fun `price in html-stripped text`() = assertPrice(199.0, "R199,00 Add to cart")

    // ── Null cases ────────────────────────────────────────────────────────────

    @Test fun `null on no price in text`() = assertNull(parsePrice("Out of Stock"))
    @Test fun `null on empty string`() = assertNull(parsePrice(""))
    @Test fun `null on plain text`() = assertNull(parsePrice("Lightning Bolt"))
    @Test fun `null on whitespace only`() = assertNull(parsePrice("   "))
}
