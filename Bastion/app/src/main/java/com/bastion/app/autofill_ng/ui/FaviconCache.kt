package com.bastion.app.autofill_ng.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.SSLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Favicon cache for fetching and storing website icons.
 */
object FaviconCache {
    private const val TAG = "FaviconCache"
    private const val CACHE_DIR_NAME = "favicons"
    private const val MAX_MEMORY_CACHE_BYTES_KB = 4 * 1024 // ~4MB，以 KB 计
    private const val MAX_DISK_CACHE_FILES = 600 // favicon 磁盘缓存文件数上限
    private const val MAX_DISK_CACHE_MB = 24 // favicon 磁盘缓存体积上限（MB）
    private val pruneCounter = AtomicInteger(0) // 节流：每 25 次写入触发一次清理

    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_MEMORY_CACHE_BYTES_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    /**
     * Get icon for domain.
     * This function should be called from a coroutine.
     *
     * 多源顺序尝试：站点直连 /favicon.ico -> DuckDuckGo -> Google S2。
     * 任一源成功即返回并写入缓存；全部失败返回 null（最终落到首字母头像兜底）。
     */
    suspend fun getIcon(context: Context, url: String): ImageBitmap? {
        val domain = getDomainFromUrl(url) ?: return null
        val cacheKey = hashString(domain)

        // 1. Check memory cache
        memoryCache.get(cacheKey)?.let {
            return it
        }

        // 2. Check disk cache
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val cacheFile = File(cacheDir, "$cacheKey.webp")
        if (cacheFile.exists()) {
            try {
                val bitmap = decodeFaviconSampled(cacheFile, MAX_FAVICON_DIM)
                if (bitmap != null) {
                    val imageBitmap = bitmap.asImageBitmap()
                    memoryCache.put(cacheKey, imageBitmap)
                    return imageBitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading favicon disk cache: ${e.message}")
            }
        }

        return withContext(Dispatchers.IO) {
            memoryCache.get(cacheKey)?.let { cached ->
                return@withContext cached
            }

            // 3. 多源顺序尝试，第一个成功即返回
            for (candidate in buildFaviconCandidates(domain)) {
                val bitmap = fetchFaviconFromUrl(candidate, connectTimeoutMs = 3000, readTimeoutMs = 3500)
                if (bitmap != null) {
                    try {
                        val out = FileOutputStream(cacheFile)
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                        out.flush()
                        out.close()
                        pruneFaviconDiskCacheIfNeeded(cacheDir)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error saving favicon disk cache: ${e.message}")
                    }
                    val imageBitmap = bitmap.asImageBitmap()
                    memoryCache.put(cacheKey, imageBitmap)
                    return@withContext imageBitmap
                }
            }
            null
        }
    }

    /**
     * 磁盘 favicon 缓存清理：每 25 次写入触发一次，按修改时间删除最旧文件，
     * 直到文件数与总大小回落到阈值的 70%，防止下载缓存目录长期无限膨胀。
     * 注意：仅清理自动下载的 favicon；用户上传覆盖图在 password_icons/，属于用户数据，不在此清理。
     */
    private fun pruneFaviconDiskCacheIfNeeded(cacheDir: File) {
        if (pruneCounter.incrementAndGet() % 25 != 0) return
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val maxBytes = MAX_DISK_CACHE_MB * 1024L * 1024L
        val totalBytes = files.sumOf { it.length() }
        if (files.size <= MAX_DISK_CACHE_FILES && totalBytes <= maxBytes) return

        val sorted = files.sortedBy { it.lastModified() }
        var remaining = files.size
        var used = totalBytes
        val targetCount = (MAX_DISK_CACHE_FILES * 0.7).toInt()
        val targetBytes = (maxBytes * 0.7).toLong()
        for (f in sorted) {
            if (remaining <= targetCount && used <= targetBytes) break
            if (f.delete()) {
                remaining -= 1
                used -= f.length()
            }
        }
    }

    /**
     * 按优先级生成 favicon 候选源。
     * 站点直连 /favicon.ico 不依赖任何第三方服务（国内可用且最快）；
     * DuckDuckGo 作次选兜底；Google S2 作海外兜底。
     */
    private fun buildFaviconCandidates(domain: String): List<String> = listOf(
        "https://$domain/favicon.ico",
        "https://icons.duckduckgo.com/ip3/$domain.ico",
        "https://www.google.com/s2/favicons?domain=$domain&sz=64",
        "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    )

    /**
     * 从单个 URL 拉取 favicon 位图；任何网络异常都返回 null，由调用方尝试下一个源。
     */
    private const val MAX_FAVICON_DIM = 128

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > maxDim || h > maxDim) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun decodeFaviconSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeFaviconStreamSampled(stream: java.io.InputStream, maxDim: Int): Bitmap? {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun fetchFaviconFromUrl(
        faviconUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Bitmap? {
        return try {
            val connection = URL(faviconUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { stream ->
                        decodeFaviconStreamSampled(stream, MAX_FAVICON_DIM)
                    }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            // Composable left composition; expected during fast list updates/navigation.
            throw e
        } catch (e: Exception) {
            val commonNetworkIssue = e is UnknownHostException ||
                e is SocketTimeoutException ||
                e is SSLException ||
                e is IOException
            if (commonNetworkIssue) {
                Log.w(TAG, "Favicon source skipped for $faviconUrl: ${e.javaClass.simpleName}")
            } else {
                Log.w("FaviconCache", "Unexpected favicon fetch failure for $faviconUrl", e)
            }
            null
        }
    }

    private fun getDomainFromUrl(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = java.net.URI(url)
            val domain = uri.host
            if (domain != null) {
                return domain.removePrefix("www.")
            }
            // Fallback for URLs without scheme
            val simpleUrl = if (url.startsWith("http")) url else "http://$url"
            java.net.URI(simpleUrl).host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Composable to load website favicon.
 *
 * @param url Website URL
 * @param enabled Whether icon fetching is enabled
 */
@Composable
fun rememberFavicon(url: String, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, enabled) {
        if (!enabled || url.isBlank()) return@LaunchedEffect

        // 首次加载失败时自动做短重试，避免用户必须手动关开开关触发第二次请求。
        val maxAttempts = 3
        repeat(maxAttempts) { index ->
            val loadedIcon = FaviconCache.getIcon(context, url)
            if (loadedIcon != null) {
                icon = loadedIcon
                return@LaunchedEffect
            }
            if (index < maxAttempts - 1) {
                delay((index + 1) * 600L)
            }
        }
    }

    // If enabled is false, we should still return the cached icon if available?
    // User requested: "if icon switch is off, do not load icons anymore. if cached, keep it?"
    // Requirement: "if closed, do not load icon anymore"
    // Requirement 2: "get once and cache" - handled by Cache logic
    // Requirement 3: "if closed, won't clean cache for next time usage" - handled by persistent disk cache
    
    // If enabled is false, we simply don't trigger the fetch. 
    // However, if we already have it in state (from previous render when enabled was true), 
    // allow showing it? Or hide it?
    // "如果关闭了带图标的卡片开关将不会再加载图标" -> If switch is OFF, just don't load.
    // Use case implies: Switch OFF -> Do not show icons on UI.
    // So if !enabled, return null.
    
    if (!enabled) return null
    return icon
}



