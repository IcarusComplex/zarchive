package ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Savings
import androidx.compose.ui.graphics.vector.ImageVector
import data.DEFAULT_DELIVERY_ZAR

// Moved out of ui/App.kt (Phase 4) so SearchViewModel (now shared) can reference it — ImageVector/
// Icons.Default.* are Compose Multiplatform common APIs, not desktop-specific, so this needed no
// further abstraction beyond moving the file.
enum class OrderStrategy(val label: String, val icon: ImageVector, val blurb: String) {
    CHEAPEST("Cheapest total", Icons.Default.Savings,
        "Lowest possible spend — each card from whichever store is cheapest, even if that means more parcels."),
    BALANCED("Balanced", Icons.Default.LocalShipping,
        "Lowest all-in cost — counts about R${DEFAULT_DELIVERY_ZAR.toInt()} delivery per store on top of card prices, " +
            "so a cheaper card only wins if it beats the extra parcel it adds."),
    FEWEST("Fewest packages", Icons.Default.Inventory2,
        "Fewest orders to place — the smallest set of stores that covers everything in stock, price aside."),
}
