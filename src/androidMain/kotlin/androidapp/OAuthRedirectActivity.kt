package androidapp

import android.app.Activity
import android.os.Bundle
import network.PendingOAuthRedirect

/**
 * Invisible catcher for the Google OAuth redirect (see AndroidManifest.xml's intent-filter on this
 * Activity and `network.GoogleOAuthFlow.android.kt`). Hands the redirect Uri back to whichever
 * `GoogleOAuthFlow.authenticate()` call is currently suspended waiting for it, then closes itself
 * immediately -- it never shows any UI.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PendingOAuthRedirect.deferred?.complete(intent?.data)
        finish()
    }
}
