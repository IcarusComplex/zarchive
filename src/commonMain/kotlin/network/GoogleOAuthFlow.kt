package network

/** Result of a completed interactive consent round-trip, ready for [exchangeGoogleAuthCode]. */
data class GoogleAuthResult(val code: String, val codeVerifier: String, val redirectUri: String)

/**
 * Drives the interactive part of Google sign-in: opening the consent screen and capturing the
 * redirect. Desktop uses a transient RFC 8252 loopback HTTP listener; Android uses Chrome Custom
 * Tabs + a dedicated redirect `Activity`. Token exchange/refresh itself is platform-agnostic (see
 * `GoogleAuthService.kt`) and deliberately kept out of this class.
 */
expect class GoogleOAuthFlow() {
    /** The OAuth client id registered for this platform/build-type. */
    val clientId: String

    /** Non-null only for the Desktop app client type -- Android clients are issued no secret. */
    val clientSecret: String?

    /**
     * Opens the consent screen for [scope] and suspends until the redirect arrives (or the user
     * cancels / it times out), returning null in the latter cases.
     */
    suspend fun authenticate(scope: String): GoogleAuthResult?
}
