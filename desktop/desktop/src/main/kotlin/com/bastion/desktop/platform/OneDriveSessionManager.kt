package com.bastion.desktop.platform

import com.bastion.app.platform.PathProvider
import com.bastion.app.security.DesktopCryptoManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * OneDrive 会话持久化 + 自动刷新：
 * - 登录后把 refresh token 加密落盘（%APPDATA%/BastionDesktop/onedrive_session.json）
 * - 提供 authTokenProvider：access token 过期时自动用 refresh token 刷新
 */
class OneDriveSessionManager(
    private val cryptoManager: DesktopCryptoManager,
    private val auth: OneDriveBrowserAuth = OneDriveBrowserAuth()
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionFile = File(PathProvider.resolve("onedrive_session.json"))

    @Serializable
    private data class StoredSession(
        val encryptedRefreshToken: String,
        val userEmail: String,
        val savedAt: Long
    )

    @Volatile
    private var cached: OneDriveBrowserAuth.OneDriveSession? = null

    /** 保存会话（refresh token 加密存储）。 */
    fun saveSession(session: OneDriveBrowserAuth.OneDriveSession) {
        cached = session
        val stored = StoredSession(
            encryptedRefreshToken = cryptoManager.encryptString(session.refreshToken),
            userEmail = session.userEmail,
            savedAt = System.currentTimeMillis()
        )
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeText(json.encodeToString(stored))
    }

    /** 读取已保存会话；无则返回 null。 */
    fun loadSession(): OneDriveBrowserAuth.OneDriveSession? {
        cached?.let { return it }
        if (!sessionFile.exists()) return null
        return try {
            val stored = json.decodeFromString<StoredSession>(sessionFile.readText())
            val refresh = cryptoManager.decryptString(stored.encryptedRefreshToken)
            OneDriveBrowserAuth.OneDriveSession(
                accessToken = "",
                refreshToken = refresh,
                accessTokenExpiresAt = 0L,
                userEmail = stored.userEmail
            ).also { cached = it }
        } catch (e: Exception) {
            null
        }
    }

    /** 当前已登录账号邮箱；未登录返回 null。 */
    fun currentUserEmail(): String? = loadSession()?.userEmail?.takeIf { it.isNotBlank() }

    /** 是否已登录。 */
    fun isLoggedIn(): Boolean = loadSession() != null

    /** 清除会话。 */
    fun clearSession() {
        cached = null
        sessionFile.delete()
    }

    /**
     * 提供有效 access token；过期时自动刷新。
     * 未登录时抛出 [OneDriveAuthException]。
     */
    suspend fun tokenProvider(): String {
        val session = loadSession() ?: throw OneDriveAuthException("OneDrive 未登录")
        val now = System.currentTimeMillis()

        // 有效期内直接返回
        if (session.accessToken.isNotBlank() && session.accessTokenExpiresAt > now + 60_000) {
            return session.accessToken
        }

        // 需要刷新
        val refreshed = auth.refreshAccessToken(session.refreshToken)
        saveSession(refreshed)
        return refreshed.accessToken
    }
}
