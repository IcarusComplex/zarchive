package network

import android.content.Intent
import android.net.Uri
import androidapp.ZArchiveApplication
import androidx.browser.customtabs.CustomTabsIntent
import co.za.zarchive.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android OAuth flow: Chrome Custom Tabs (not a WebView -- Google disallows embedded-WebView OAuth)
 * to the consent screen, redirected back into the app via a custom URI scheme caught by the
 * dedicated `OAuthRedirectActivity` (see AndroidManifest.xml). [PendingOAuthRedirect] is the bridge
 * between that separate Activity and this suspended call.
 */
actual class GoogleOAuthFlow actual constructor() {
    actual val clientId: String =
        if (BuildConfig.DEBUG) GoogleAuthConfig.ANDROID_CLIENT_ID_DEBUG else GoogleAuthConfig.ANDROID_CLIENT_ID_RELEASE

    actual suspend fun authenticate(scope: String): GoogleAuthResult? {
        // Matches the intent-filter's android:scheme="${applicationId}" android:host="oauth2redirect"
        // -- debug (".debug" application-id suffix) and release therefore never collide.
        val redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect"
        val pkce = generatePkce()
        val authUrl = buildGoogleAuthUrl(clientId, redirectUri, scope, pkce)

        val deferred = CompletableDeferred<Uri?>()
        PendingOAuthRedirect.deferred = deferred
        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(ZArchiveApplication.appContext, Uri.parse(authUrl))
        }

        val resultUri = withTimeoutOrNull(180_000) { deferred.await() }
        PendingOAuthRedirect.deferred = null
        val code = resultUri?.getQueryParameter("code") ?: return null
        return GoogleAuthResult(code, pkce.verifier, redirectUri)
    }
}

/** Set for the duration of one in-flight [GoogleOAuthFlow.authenticate] call; null otherwise. */
object PendingOAuthRedirect {
    var deferred: CompletableDeferred<Uri?>? = null
}
