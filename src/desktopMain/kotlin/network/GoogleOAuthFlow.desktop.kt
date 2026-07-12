package network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ui.PlatformActions
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Desktop installed-app OAuth flow (RFC 8252): opens the system browser to Google's consent screen
 * and catches the redirect with a transient, loopback-only `HttpServer` (JDK built-in, no new
 * dependency). The server binds an ephemeral port, handles exactly one request, and is torn down
 * immediately after -- it is never a long-lived listening service.
 */
actual class GoogleOAuthFlow actual constructor() {
    actual val clientId: String = GoogleAuthConfig.DESKTOP_CLIENT_ID
    actual val clientSecret: String? = GoogleAuthConfig.DESKTOP_CLIENT_SECRET

    actual suspend fun authenticate(scope: String): GoogleAuthResult? {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectUri = "http://127.0.0.1:${server.address.port}/oauth2redirect"
        val pkce = generatePkce()
        val codeDeferred = CompletableDeferred<String?>()

        server.createContext("/oauth2redirect") { exchange ->
            val params = parseQueryParams(exchange.requestURI.query.orEmpty())
            val code = params["code"]
            val html = if (code != null)
                "<html><body>Signed in to ZArchive. You can close this window.</body></html>"
            else
                "<html><body>Sign-in failed or was cancelled. You can close this window.</body></html>"
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            codeDeferred.complete(code)
        }
        server.start()

        return try {
            PlatformActions().openUrl(buildGoogleAuthUrl(clientId, redirectUri, scope, pkce))
            val code = withTimeoutOrNull(120_000) { codeDeferred.await() }
            code?.let { GoogleAuthResult(it, pkce.verifier, redirectUri) }
        } finally {
            server.stop(0)
        }
    }
}

private fun parseQueryParams(query: String): Map<String, String> =
    query.split("&").filter { it.isNotEmpty() }.associate { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) pair to ""
        else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
    }
