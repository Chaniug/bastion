package com.bastion.app.bitwarden.api

import com.bastion.app.logging.runCatchingObserved
import kotlinx.serialization.json.Json
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.bastion.app.data.bitwarden.BitwardenVault
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Bitwarden API 客户端工厂
 * 
 * 创建针对不同服务器端点的 API 客户端
 * 支持官方服务和自托管服务
 */
object BitwardenApiFactory {
    
    private const val TAG = "BitwardenApiFactory"
    
    // 官方服务端点（US）
    const val OFFICIAL_VAULT_URL = "https://vault.bitwarden.com"
    const val OFFICIAL_IDENTITY_URL = "https://identity.bitwarden.com"
    const val OFFICIAL_API_URL = "https://api.bitwarden.com"

    // 官方服务端点（EU）
    const val OFFICIAL_EU_VAULT_URL = "https://vault.bitwarden.eu"
    const val OFFICIAL_EU_IDENTITY_URL = "https://identity.bitwarden.eu"
    const val OFFICIAL_EU_API_URL = "https://api.bitwarden.eu"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * 401 自动恢复回调。由 [BitwardenRepository]（持有 vault/refresh token）注册。
     * [refreshForHost] 为阻塞式：内部自行切到 IO 线程执行刷新网络请求，返回新 access token，失败返回 null。
     * 这样 OkHttp 层对任意 Bitwarden 调用（同步/附件/Send 等）返回的 401 都能自动恢复，
     * 不再只依赖 [BitwardenRepository.sync] 入口按 accessTokenExpiresAt 预判刷新。
     */
    interface BitwardenTokenRefresher {
        fun refreshForHost(host: String): String?
    }

    @Volatile
    private var tokenRefresher: BitwardenTokenRefresher? = null

    fun setTokenRefresher(refresher: BitwardenTokenRefresher?) {
        tokenRefresher = refresher
    }

    /**
     * 全局 401 自动恢复拦截器：任意 Bitwarden 请求返回 401 → 回调刷新 token → 用新 token 重试一次。
     * OkHttp 对同一响应链只会调用一次（priorResponse 非空即已重试过），天然防死循环。
     */
    private val tokenAuthenticator = Authenticator { route, response ->
        if (response.priorResponse != null) return@Authenticator null
        val host = route?.address?.url?.host ?: return@Authenticator null
        val newToken = tokenRefresher?.refreshForHost(host) ?: return@Authenticator null
        // OkHttp4 Authenticator.authenticate(route, response) 返回「带新凭证的 Request」（签名 Request?），
        // OkHttp 内部会自动用该 Request 重新发起请求；不能返回 Response（签名不匹配，编译报错）。
        response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
    
    enum class HeaderProfile {
        MONICA_DEFAULT,
        KEYGUARD_FALLBACK
    }

    private data class HeaderSpec(
        val majorVersion: String,
        val fullVersion: String
    )

    // Bastion 当前默认请求指纹
    private const val MONICA_CHROME_MAJOR_VERSION = "131"
    private const val MONICA_CHROME_FULL_VERSION = "$MONICA_CHROME_MAJOR_VERSION.0.6778.140"
    // Keyguard 当前使用的请求指纹
    private const val KEYGUARD_CHROME_MAJOR_VERSION = "126"
    private const val KEYGUARD_CHROME_FULL_VERSION = "$KEYGUARD_CHROME_MAJOR_VERSION.0.6478.114"
    
    /**
     * 创建 OkHttp 客户端
     * 
     * @param enableLogging 是否启用日志 (仅用于调试)
     */
    fun createOkHttpClient(
        enableLogging: Boolean = false,
        refererUrl: String? = null,
        headerProfile: HeaderProfile = HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null
    ): OkHttpClient {
        val headerSpec = getHeaderSpec(headerProfile)
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // 收紧读/写超时：自建服务器经反代时，复用的空闲连接可能被服务端静默关闭（半开连接），
            // 原 60s 会让请求一直挂到读超时（用户感知"很慢很慢"）。降到 30s 让死连接更快失败，
            // 配合下面 retryOnConnectionFailure 立即重试新连接。
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 显式连接池：自建服务器高 RTT 下避免每次 sync 重建 TCP/TLS；
            // pingInterval 启用 HTTP/2 keepalive，VPN/自签 CA 抖动时快速发现死连接；
            // keepAliveDuration 从 5min 降到 2min，缩短空闲连接存活，降低复用已被反代关闭的连接的概率
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .pingInterval(30, TimeUnit.SECONDS)
            .authenticator(tokenAuthenticator)
            // 添加 Keyguard 使用的 Cloudflare 绕过 headers
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                
                builder.header("User-Agent", buildUserAgent(headerSpec.fullVersion))
                builder.header("Keyguard-Client", "1")
                builder.header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                builder.header("Sec-Ch-Ua", """"Not.A/Brand";v="8", "Chromium";v="${headerSpec.majorVersion}"""")

                builder.header("Sec-Ch-Ua-Mobile", "?0")
                builder.header("Sec-Ch-Ua-Platform", "Linux")
                // Bitwarden 服务端根据客户端版本决定是否返回 Type 5 (SSH Key) 等新类型数据
                // 不声明版本时服务端会降级为 Type 1 并丢弃 sshKey 字段
                builder.header("Bitwarden-Client-Name", "desktop")
                builder.header("Bitwarden-Client-Version", "2025.1.0")
                if (isRefererApplied(headerProfile, refererUrl)) {
                    builder.header("referer", ensureTrailingSlash(refererUrl!!.trim()))
                }
                
                chain.proceed(builder.build())
            }
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }

        configureTls(builder, tlsConfig)
        return builder.build()
    }

    fun headerProfileName(profile: HeaderProfile): String = when (profile) {
        HeaderProfile.MONICA_DEFAULT -> "bastion_default"
        HeaderProfile.KEYGUARD_FALLBACK -> "keyguard_fallback"
    }

    fun headerProfileUserAgentVersion(profile: HeaderProfile): String {
        val spec = getHeaderSpec(profile)
        return "Chrome/${spec.fullVersion}"
    }

    fun isRefererApplied(profile: HeaderProfile, refererUrl: String?): Boolean {
        val normalized = refererUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val officialUs = isOfficialServer(normalized)
        val officialEu = isOfficialEuServer(normalized)
        // keyguard 对官方服务器不发 referer，只对自建服务器发
        if (officialUs || officialEu) return false
        return true
    }
    
    /**
     * 创建 Identity API 客户端 (认证)
     * 
     * @param baseUrl Identity 服务端点
     * @param okHttpClient 可选的自定义 OkHttp 客户端
     */
    fun createIdentityApi(
        baseUrl: String = OFFICIAL_IDENTITY_URL,
        okHttpClient: OkHttpClient = createOkHttpClient()
    ): BitwardenIdentityApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        
        return retrofit.create(BitwardenIdentityApi::class.java)
    }
    
    /**
     * 创建 Vault API 客户端 (数据操作)
     * 
     * @param baseUrl API 服务端点
     * @param okHttpClient 可选的自定义 OkHttp 客户端
     */
    fun createVaultApi(
        baseUrl: String = OFFICIAL_API_URL,
        okHttpClient: OkHttpClient = createOkHttpClient()
    ): BitwardenVaultApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        
        return retrofit.create(BitwardenVaultApi::class.java)
    }
    
    /**
     * 从 Vault URL 推断其他端点 URL (用于自托管服务)
     * 
     * 自托管服务通常使用以下结构:
     * - Vault: https://your-domain.com
     * - Identity: https://your-domain.com/identity
     * - API: https://your-domain.com/api
     */
    fun inferServerUrls(vaultUrl: String): ServerUrls {
        val normalizedUrl = vaultUrl.trimEnd('/')

        return when {
            isOfficialEuServer(normalizedUrl) -> {
                ServerUrls(
                    vault = OFFICIAL_EU_VAULT_URL,
                    identity = OFFICIAL_EU_IDENTITY_URL,
                    api = OFFICIAL_EU_API_URL
                )
            }

            isOfficialServer(normalizedUrl) -> {
            ServerUrls(
                vault = OFFICIAL_VAULT_URL,
                identity = OFFICIAL_IDENTITY_URL,
                api = OFFICIAL_API_URL
            )
            }

            else -> {
            ServerUrls(
                vault = normalizedUrl,
                identity = "$normalizedUrl/identity",
                api = "$normalizedUrl/api"
            )
            }
        }
    }
    
    /**
     * 检查是否为官方服务
     *
     * 使用严格的域名后缀匹配，避免自建 Vaultwarden 的 URL 中偶然包含 "bitwarden" 子串
     * （例如路径、子域名等）被误判为官方服务器。
     */
    fun isOfficialServer(url: String): Boolean {
        val normalized = url.lowercase().trimEnd('/')
        if (normalized == OFFICIAL_VAULT_URL.lowercase()) return true
        // 严格匹配：域名部分以 bitwarden.com 结尾（不是 URL 任意位置 contains）
        val host = runCatchingObserved { java.net.URI(normalized).host }.getOrNull() ?: return false
        return host == "bitwarden.com" || host.endsWith(".bitwarden.com")
    }

    /**
     * 检查是否为官方 EU 服务
     */
    fun isOfficialEuServer(url: String): Boolean {
        val normalized = url.lowercase().trimEnd('/')
        if (normalized == OFFICIAL_EU_VAULT_URL.lowercase()) return true
        val host = runCatchingObserved { java.net.URI(normalized).host }.getOrNull() ?: return false
        return host == "bitwarden.eu" || host.endsWith(".bitwarden.eu")
    }
    
    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun buildUserAgent(chromeFullVersion: String): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFullVersion Safari/537.36"
    }

    private fun getHeaderSpec(profile: HeaderProfile): HeaderSpec {
        return when (profile) {
            HeaderProfile.MONICA_DEFAULT -> HeaderSpec(
                majorVersion = MONICA_CHROME_MAJOR_VERSION,
                fullVersion = MONICA_CHROME_FULL_VERSION
            )

            HeaderProfile.KEYGUARD_FALLBACK -> HeaderSpec(
                majorVersion = KEYGUARD_CHROME_MAJOR_VERSION,
                fullVersion = KEYGUARD_CHROME_FULL_VERSION
            )
        }
    }

    private fun configureTls(
        builder: OkHttpClient.Builder,
        tlsConfig: BitwardenTlsConfig?
    ) {
        if (tlsConfig == null || tlsConfig.isEmpty()) return

        val trustManager = buildTrustManager(tlsConfig.caCertificatePem)
        val keyManagers = buildClientKeyManagers(
            enabled = tlsConfig.mtlsEnabled,
            pkcs12Base64 = tlsConfig.clientCertPkcs12Base64,
            password = tlsConfig.clientCertPassword
        )

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
    }

    private fun buildTrustManager(caCertificatePem: String?): X509TrustManager {
        val systemTrustManager = systemDefaultTrustManager()
        if (caCertificatePem.isNullOrBlank()) return systemTrustManager

        val customTrustManager = customCaTrustManager(caCertificatePem)
        return CompositeX509TrustManager(listOf(systemTrustManager, customTrustManager))
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun customCaTrustManager(caCertificatePem: String): X509TrustManager {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = certificateFactory.generateCertificates(
            ByteArrayInputStream(caCertificatePem.toByteArray(Charsets.UTF_8))
        )

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        certificates.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("ca_$index", cert)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun buildClientKeyManagers(
        enabled: Boolean,
        pkcs12Base64: String?,
        password: String?
    ): Array<KeyManager>? {
        if (!enabled) return null
        if (pkcs12Base64.isNullOrBlank()) return null

        val passwordChars = password.orEmpty().toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        val certBytes = Base64.decode(pkcs12Base64, Base64.DEFAULT)
        keyStore.load(ByteArrayInputStream(certBytes), passwordChars)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, passwordChars)
        return kmf.keyManagers
    }

    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastError: Exception? = null
            delegates.forEach { manager ->
                try {
                    manager.checkClientTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No trust manager accepted client certificate")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastError: Exception? = null
            delegates.forEach { manager ->
                try {
                    manager.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No trust manager accepted server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates
                .flatMap { it.acceptedIssuers.toList() }
                .distinctBy { it.subjectX500Principal.name + it.serialNumber }
                .toTypedArray()
        }
    }
    
    /**
     * 服务器 URL 配置
     */
    data class ServerUrls(
        val vault: String,
        val identity: String,
        val api: String
    )
}

/**
 * Bitwarden API 客户端管理器
 * 
 * 管理多个 Vault 的 API 客户端实例
 */
class BitwardenApiManager {

    // 缓存 API 客户端实例。带容量上限的 LRU，避免 OkHttpClient / Retrofit 实例
    // （各自持有连接池与线程池）长期累积导致内存增长（Android 17 内存治理）。
    private val okHttpClientCache = boundedLruMap<String, OkHttpClient>(8)
    private val identityApiCache = boundedLruMap<String, BitwardenIdentityApi>(16)
    private val vaultApiCache = boundedLruMap<String, BitwardenVaultApi>(16)

    /**
     * 获取 Identity API 客户端
     */
    fun getIdentityApi(
        identityUrl: String,
        refererUrl: String? = null,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null
    ): BitwardenIdentityApi {
        val cacheKey = "${identityUrl.trimEnd('/')}|${refererUrl?.trim().orEmpty()}|${headerProfile.name}|${tlsConfig?.cacheFingerprint().orEmpty()}"
        return identityApiCache.getOrPut(cacheKey) {
            BitwardenApiFactory.createIdentityApi(
                baseUrl = identityUrl,
                okHttpClient = getOrCreateOkHttpClient(refererUrl, headerProfile, tlsConfig)
            )
        }
    }
    
    /**
     * 获取 Vault API 客户端
     */
    fun getVaultApi(
        apiUrl: String,
        refererUrl: String? = null,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        tlsConfig: BitwardenTlsConfig? = null
    ): BitwardenVaultApi {
        val cacheKey = "${apiUrl.trimEnd('/')}|${refererUrl?.trim().orEmpty()}|${headerProfile.name}|${tlsConfig?.cacheFingerprint().orEmpty()}"
        return vaultApiCache.getOrPut(cacheKey) {
            BitwardenApiFactory.createVaultApi(
                baseUrl = apiUrl,
                okHttpClient = getOrCreateOkHttpClient(refererUrl, headerProfile, tlsConfig)
            )
        }
    }

    fun getVaultApi(
        vault: BitwardenVault,
        headerProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT
    ): BitwardenVaultApi {
        val tlsConfig = BitwardenTlsConfig(
            certificateAlias = vault.tlsCertificateAlias,
            caCertificatePem = vault.tlsCaCertificatePem,
            mtlsEnabled = vault.tlsMtlsEnabled,
            clientCertPkcs12Base64 = vault.tlsClientCertPkcs12Base64,
            clientCertPassword = vault.tlsEncryptedClientCertPassword
        )
        return getVaultApi(
            apiUrl = vault.apiUrl,
            refererUrl = vault.serverUrl,
            headerProfile = headerProfile,
            tlsConfig = tlsConfig
        )
    }
    
    /**
     * 获取与指定 vault 关联的 OkHttpClient。
     * 用于附件下载等需要直接 HTTP 访问的场景。
     */
    fun getOkHttpClient(vault: BitwardenVault): OkHttpClient {
        val tlsConfig = BitwardenTlsConfig(
            certificateAlias = vault.tlsCertificateAlias,
            caCertificatePem = vault.tlsCaCertificatePem,
            mtlsEnabled = vault.tlsMtlsEnabled,
            clientCertPkcs12Base64 = vault.tlsClientCertPkcs12Base64,
            clientCertPassword = vault.tlsEncryptedClientCertPassword
        )
        return getOrCreateOkHttpClient(
            refererUrl = vault.serverUrl,
            headerProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
            tlsConfig = tlsConfig
        )
    }

    /**
     * 清除缓存的客户端
     */
    fun clearCache() {
        okHttpClientCache.clear()
        identityApiCache.clear()
        vaultApiCache.clear()
    }

    private companion object {
        /**
         * 创建带容量上限、按访问顺序(access-order)淘汰的线程安全 LRU Map。
         * 条目数超过 [maxEntries] 时自动移除最久未访问的条目，
         * 确保 OkHttpClient / Retrofit 实例（含连接池、线程池）可被驱逐，防止内存无限增长。
         */
        private inline fun <K, V> boundedLruMap(maxEntries: Int): MutableMap<K, V> {
            val backing = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                    return size > maxEntries
                }
            }
            return Collections.synchronizedMap(backing)
        }
    }

    private fun getOrCreateOkHttpClient(
        refererUrl: String?,
        headerProfile: BitwardenApiFactory.HeaderProfile,
        tlsConfig: BitwardenTlsConfig?
    ): OkHttpClient {
        val cacheKey = "${headerProfile.name}|${refererUrl?.trim().orEmpty()}|${tlsConfig?.cacheFingerprint().orEmpty()}"
        return okHttpClientCache.getOrPut(cacheKey) {
            BitwardenApiFactory.createOkHttpClient(
                enableLogging = false,
                refererUrl = refererUrl,
                headerProfile = headerProfile,
                tlsConfig = tlsConfig
            )
        }
    }
}
