package network

import androidapp.ZArchiveApplication
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android OAuth flow via Google Play Services' Authorization API -- NOT a Custom Tabs + custom-
 * URI-scheme redirect (the original design), because Google fully blocks custom-scheme redirects
 * for Android OAuth clients ("Error 400: invalid_request -- Custom URI scheme is not enabled for
 * your Android client", found against a real device; per Google's own docs this is a hard policy,
 * not a togglable setting: "Custom URI schemes are no longer supported on Android and Chrome apps
 * due to the risk of app impersonation"). This is Google's actually-supported native-app path.
 *
 * [refreshClientId]/[refreshClientSecret] are the "Web application" client passed as
 * requestOfflineAccess()'s serverClientId -- it's never used for a redirect (no hosting/domain
 * involved), only as an identifier so Play Services knows which client is allowed to redeem the
 * resulting serverAuthCode. The app redeems that code itself, directly against Google's token
 * endpoint, since there's no backend server.
 */
actual class GoogleOAuthFlow actual constructor() {
    actual val refreshClientId: String = GoogleAuthConfig.WEB_CLIENT_ID
    actual val refreshClientSecret: String? = GoogleAuthConfig.WEB_CLIENT_SECRET

    actual suspend fun authenticate(scope: String): GoogleTokens? {
        val context = ZArchiveApplication.appContext
        val scopes = scope.split(" ").filter { it.isNotBlank() }.map { Scope(it) }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .requestOfflineAccess(refreshClientId, true)
            .build()
        val authClient = Identity.getAuthorizationClient(context)

        val initial: AuthorizationResult = suspendCancellableCoroutine<AuthorizationResult?> { cont ->
            authClient.authorize(request)
                .addOnSuccessListener { result -> cont.resumeWith(Result.success(result)) }
                .addOnFailureListener { cont.resumeWith(Result.success(null)) }
        } ?: return null

        // Already-granted case (e.g. re-authenticating after a token was cleared but consent was
        // never revoked) skips the UI entirely; otherwise launch the consent screen Play Services
        // handed back a PendingIntent for, via the launcher MainActivity registered.
        val authResult: AuthorizationResult = if (initial.hasResolution()) {
            val pendingIntent = initial.pendingIntent ?: return null
            val launcher = AndroidAuthorizationBridge.launcher ?: return null
            val deferred = CompletableDeferred<ActivityResult>()
            AndroidAuthorizationBridge.pendingDeferred = deferred
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            val activityResult: ActivityResult = withTimeoutOrNull(180_000) { deferred.await() } ?: return null
            AndroidAuthorizationBridge.pendingDeferred = null
            runCatching { authClient.getAuthorizationResultFromIntent(activityResult.data) }.getOrNull() ?: return null
        } else {
            initial
        }

        val serverAuthCode = authResult.serverAuthCode ?: return null
        return exchangeGoogleServerAuthCode(refreshClientId, requireNotNull(refreshClientSecret), serverAuthCode)
    }
}

/** Bridges MainActivity's registered ActivityResultLauncher to this suspended flow. */
object AndroidAuthorizationBridge {
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    var pendingDeferred: CompletableDeferred<ActivityResult>? = null
}
