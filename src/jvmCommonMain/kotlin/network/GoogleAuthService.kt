package network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val authJson = Json { ignoreUnknownKeys = true }

/** RFC 7636 PKCE pair -- generated fresh for every login attempt, never persisted. */
data class Pkce(val verifier: String, val challenge: String)

fun generatePkce(): Pkce {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return Pkce(verifier, challenge)
}

/** Builds the Google consent-screen URL the system browser is opened to. */
fun buildGoogleAuthUrl(clientId: String, redirectUri: String, scope: String, pkce: Pkce): String {
    val params = listOf(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to scope,
        "code_challenge" to pkce.challenge,
        "code_challenge_method" to "S256",
        // Ensures a refresh_token is issued even if this Google account previously granted consent.
        "access_type" to "offline",
        "prompt" to "consent",
    ).joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
    return "https://accounts.google.com/o/oauth2/v2/auth?$params"
}

data class GoogleTokens(
    val accessToken: String,
    /** Only present on the initial code exchange -- Google omits it on ordinary refreshes. */
    val refreshToken: String?,
    /** Epoch millis; refresh a little early to avoid racing expiry mid-request. */
    val expiresAt: Long,
)

/** Exchanges an authorization code (from the redirect) for an access + refresh token pair. */
suspend fun exchangeGoogleAuthCode(
    clientId: String,
    clientSecret: String?,
    redirectUri: String,
    code: String,
    codeVerifier: String,
): GoogleTokens? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("code", code)
                append("client_id", clientId)
                if (clientSecret != null) append("client_secret", clientSecret)
                append("redirect_uri", redirectUri)
                append("grant_type", "authorization_code")
                append("code_verifier", codeVerifier)
            },
        )
        parseTokenResponse(resp)
    }.also { client.close() }.getOrNull()
}

/** Exchanges a stored refresh token for a fresh access token (refresh_token is not re-issued). */
suspend fun refreshGoogleAccessToken(
    clientId: String,
    clientSecret: String?,
    refreshToken: String,
): GoogleTokens? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                if (clientSecret != null) append("client_secret", clientSecret)
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            },
        )
        parseTokenResponse(resp)
    }.also { client.close() }.getOrNull()
}

/** Best-effort revoke on disconnect -- failures are swallowed, the local token is cleared regardless. */
suspend fun revokeGoogleToken(token: String) {
    val client = HttpClient(OkHttp)
    runCatching {
        client.submitForm(
            url = "https://oauth2.googleapis.com/revoke",
            formParameters = Parameters.build { append("token", token) },
        )
    }
    client.close()
}

/** Fetches the connected Google account's email for display in the sync-status UI. Best-effort. */
suspend fun fetchGoogleAccountEmail(accessToken: String): String? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
            bearerAuth(accessToken)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        authJson.parseToJsonElement(resp.bodyAsText()).jsonObject["email"]?.jsonPrimitive?.content
    }.also { client.close() }.getOrNull()
}

private suspend fun parseTokenResponse(resp: HttpResponse): GoogleTokens? {
    if (!resp.status.isSuccess()) return null
    val body = authJson.parseToJsonElement(resp.bodyAsText()).jsonObject
    val accessToken = body["access_token"]?.jsonPrimitive?.content ?: return null
    val expiresIn = body["expires_in"]?.jsonPrimitive?.long ?: 3600L
    val refreshToken = body["refresh_token"]?.jsonPrimitive?.content
    // Refresh 60s before actual expiry to leave headroom for the Drive call that follows.
    val expiresAt = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(0) * 1000
    return GoogleTokens(accessToken, refreshToken, expiresAt)
}
