package com.bastion.desktop.platform

import com.bastion.app.platform.Logger
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OneDrive 浏览器授权（桌面版 PKCE）。
 *
 * 替代安卓的 MSAL：
 * 1. 生成 PKCE code_verifier / code_challenge
 * 2. 打开系统浏览器跳转 Microsoft 登录
 * 3. 本地 localhost 回环端口接收授权码
 * 4. 换 token（access + refresh），刷新令牌
 *
 * 前置条件：Azure 应用注册需允许公共客户端流程，并注册 http://localhost:{port} 重定向。
 */
class OneDriveBrowserAuth(
    private val clientId: String = ONE_DRIVE_CLIENT_ID,
    private val port: Int = LOOPBACK_PORT,
    private val scopes: String = "offline_access User.Read Files.ReadWrite"
) {

    private val tag = "OneDriveBrowserAuth"
    private val okHttp = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    companion object {
        /** 默认 client_id（需在 Azure 注册为公共客户端）。 */
        const val ONE_DRIVE_CLIENT_ID = "51306f8c-de1c-41da-8ae0-df00d1e830cb"
        const val LOOPBACK_PORT = 52525
        private const val AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String = "",
        val refresh_token: String? = null,
        val expires_in: Long = 0,
        val token_type: String = "",
        val scope: String = "",
        val id_token: String? = null,
        val error: String? = null,
        val error_description: String? = null
    )

    @Serializable
    data class OneDriveSession(
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAt: Long,
        val userEmail: String
    )

    /**
     * 交互式登录：打开浏览器 → 等待回环回调 → 换 token。
     * 阻塞直到授权完成或超时。
     */
    fun login(): OneDriveSession {
        val verifier = generateCodeVerifier()
        val challenge = codeChallenge(verifier)
        val state = randomState()

        val authUrl = buildAuthUrl(challenge, state)
        Logger.i(tag, "Opening browser for OneDrive auth: $authUrl")

        val code = launchBrowserAndAwaitCode(authUrl, state)
        Logger.i(tag, "Authorization code received")

        return exchangeCode(code, verifier)
    }

    /**
     * 用 refresh_token 刷新 access token。
     */
    fun refreshAccessToken(refreshToken: String): OneDriveSession {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", scopes)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(form)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        okHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OneDriveAuthException("Token refresh failed: ${response.code()} - $body")
            }
            val parsed = json.decodeFromString<TokenResponse>(body)
            return sessionFromToken(parsed)
        }
    }

    // ==================== PKCE ====================

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomState(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun buildAuthUrl(challenge: String, state: String): String {
        return buildString {
            append(AUTHORIZE_URL)
            append("?client_id=").append(urlEncode(clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(urlEncode(redirectUri()))
            append("&scope=").append(urlEncode(scopes))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
            append("&prompt=select_account")
        }
    }

    private fun redirectUri(): String = "http://localhost:$port"

    // ==================== 回环回调 ====================

    private fun launchBrowserAndAwaitCode(authUrl: String, expectedState: String): String {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(authUrl))
        } else {
            throw OneDriveAuthException("系统不支持自动打开浏览器，请手动访问：$authUrl")
        }

        val server = ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            val parts = requestLine.split(" ")
            val pathAndQuery = parts.getOrNull(1) ?: ""

            // 解析 code 与 state
            val query = pathAndQuery.substringAfter("?", "").substringBefore(" ")
            val params = query.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
            }.toMap()

            val code = params["code"] ?: run {
                respond(socket, "授权失败：未收到授权码（${params["error"] ?: "unknown"}）")
                throw OneDriveAuthException("Authorization failed: ${params["error_description"] ?: params["error"] ?: "no code"}")
            }
            val state = params["state"]
            if (state != expectedState) {
                respond(socket, "授权失败：state 不匹配")
                throw OneDriveAuthException("CSRF state mismatch")
            }

            respond(socket, "登录成功！您可以关闭此窗口并返回 Bastion Desktop。")
            socket.close()
            code
        } finally {
            server.close()
        }
    }

    private fun respond(socket: java.net.Socket, message: String) {
        val html = "<html><body style='font-family:sans-serif;margin:40px'><h2>Bastion Desktop</h2><p>$message</p></body></html>"
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=UTF-8\r\n")
            append("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
            append(html)
        }
        socket.getOutputStream().use { it.write(response.toByteArray(Charsets.UTF_8)); it.flush() }
    }

    // ==================== 换 token ====================

    private fun exchangeCode(code: String, verifier: String): OneDriveSession {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri())
            .add("code_verifier", verifier)
            .add("scope", scopes)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(form)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        okHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OneDriveAuthException("Token exchange failed: ${response.code()} - $body")
            }
            val parsed = json.decodeFromString<TokenResponse>(body)
            sessionFromToken(parsed)
        }
    }

    private fun sessionFromToken(token: TokenResponse): OneDriveSession {
        if (token.access_token.isBlank()) {
            throw OneDriveAuthException("No access token in response")
        }
        val refresh = token.refresh_token ?: throw OneDriveAuthException("No refresh token in response")
        return OneDriveSession(
            accessToken = token.access_token,
            refreshToken = refresh,
            accessTokenExpiresAt = System.currentTimeMillis() + token.expires_in * 1000,
            userEmail = parseEmailFromIdToken(token.id_token)
        )
    }

    private fun parseEmailFromIdToken(idToken: String?): String {
        if (idToken.isNullOrBlank()) return ""
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return ""
            val jsonStr = String(Base64.getUrlDecoder().decode(payload))
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            obj["email"]?.jsonPrimitive?.contentOrNull ?: obj["preferred_username"]?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

/** OneDrive 授权异常。 */
class OneDriveAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
