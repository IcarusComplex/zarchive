package network

/**
 * Drives the whole interactive Google sign-in round-trip, from opening the consent UI through to
 * a redeemed access + refresh token pair. The two platforms' mechanisms are different enough
 * (desktop: RFC 8252 loopback browser flow + manual PKCE code exchange; Android: Google Play
 * Services' Authorization API, since Google fully blocks custom-URI-scheme redirects for Android
 * OAuth clients -- "Error 400: invalid_request / Custom URI scheme is not enabled", found against
 * a real device) that forcing them through a shared intermediate "code" shape isn't worth it; each
 * actual does its own full authorize-then-exchange internally and just hands back tokens.
 */
expect class GoogleOAuthFlow() {
    /**
     * Client id/secret to use when later refreshing an access token from the stored refresh
     * token -- NOT necessarily the same client that ran the interactive consent screen. On
     * Android, the refresh token is issued to the "Web application" client passed as
     * requestOfflineAccess()'s serverClientId, not the "Android" client (which gets no secret and
     * can't appear in a server-side token exchange at all) -- so refreshes must use the Web
     * client's credentials, not the Android client's.
     */
    val refreshClientId: String
    val refreshClientSecret: String?

    /**
     * Runs the interactive consent flow for [scope] and returns the resulting tokens, or null if
     * the user cancels, it times out, or the exchange fails.
     */
    suspend fun authenticate(scope: String): GoogleTokens?
}
