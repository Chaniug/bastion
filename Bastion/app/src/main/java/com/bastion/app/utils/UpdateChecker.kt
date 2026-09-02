package com.bastion.app.utils

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 更新渠道：正式版（latest release）/ 预览版（滚动 preview prerelease 的 Debug APK）。 */
enum class UpdateChannel {
    STABLE,
    PREVIEW
}

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String?,
    val releaseUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val releaseNotes: String?,
    val isUpdateAvailable: Boolean
)

data class UpdateDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long
) {
    val hasTotal: Boolean
        get() = totalBytes > 0L

    val fraction: Float
        get() = if (hasTotal) bytesRead.toFloat() / totalBytes.toFloat() else 0f
}

object UpdateChecker {
    /** 项目主页（设置页「版本与更新」对外展示） */
    const val PROJECT_URL = "https://github.com/Chaniug/bastion"
    /** Release 列表页（下载地址入口） */
    const val RELEASES_PAGE_URL = "https://github.com/Chaniug/bastion/releases"

    private const val RELEASE_API_URL =
        "https://api.github.com/repos/Chaniug/bastion/releases/latest"
    private const val RELEASE_LIST_API_URL =
        "https://api.github.com/repos/Chaniug/bastion/releases?per_page=15"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 统一入口。
     * - STABLE：对比 latest release 的语义化版本号。
     * - PREVIEW：滚动 preview prerelease 的 Debug APK，用「附件上传时间(epoch 秒) > 当前 versionCode」
     *   判断是否有新构建（CI 的 versionCode 即构建时刻的 Unix 时间戳）。
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        currentVersionCode: Long,
        channel: UpdateChannel
    ): Result<UpdateCheckResult> = when (channel) {
        UpdateChannel.STABLE -> checkLatestRelease(currentVersion)
        UpdateChannel.PREVIEW -> checkLatestPreview(currentVersion, currentVersionCode)
    }

    suspend fun checkLatestRelease(currentVersion: String): Result<UpdateCheckResult> =
        withContext(Dispatchers.IO) {
            runCatchingObserved {
                val request = Request.Builder()
                    .url(RELEASE_API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Bastion-Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("GitHub Releases request failed: HTTP ${response.code}")
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        throw IOException("GitHub Releases response is empty")
                    }

                    val release = json.decodeFromString(GitHubRelease.serializer(), body)
                    val latestVersion = release.tagName.trim()
                    val apkAsset = release.apkAsset()
                    UpdateCheckResult(
                        currentVersion = currentVersion,
                        latestVersion = latestVersion,
                        releaseName = release.name?.takeIf { it.isNotBlank() },
                        releaseUrl = release.htmlUrl,
                        apkAssetName = apkAsset?.name,
                        apkDownloadUrl = apkAsset?.downloadUrl,
                        releaseNotes = release.body?.takeIf { it.isNotBlank() },
                        isUpdateAvailable = compareVersionTags(latestVersion, currentVersion) > 0
                    )
                }
            }
        }

    private suspend fun checkLatestPreview(
        currentVersion: String,
        currentVersionCode: Long
    ): Result<UpdateCheckResult> =
        withContext(Dispatchers.IO) {
            runCatchingObserved {
                val request = Request.Builder()
                    .url(RELEASE_LIST_API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Bastion-Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("GitHub Releases request failed: HTTP ${response.code}")
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        throw IOException("GitHub Releases response is empty")
                    }

                    val releases = json.decodeFromString<List<GitHubRelease>>(body)
                    val preview = releases.firstOrNull { it.prerelease }
                        ?: throw IOException("No preview release found")

                    val apkAsset = preview.debugApkAsset()
                        ?: throw IOException("Preview release has no APK asset")
                    val buildEpochSeconds = previewBuildEpochSeconds(apkAsset.updatedAt)
                    val isUpdateAvailable = buildEpochSeconds == null || buildEpochSeconds > currentVersionCode
                    val buildLabel = buildEpochSeconds?.let { formatBuildEpoch(it) }

                    UpdateCheckResult(
                        currentVersion = currentVersion,
                        latestVersion = buildLabel?.let { "build $it" } ?: preview.tagName,
                        releaseName = preview.name?.takeIf { it.isNotBlank() } ?: "开发预览版",
                        releaseUrl = preview.htmlUrl,
                        apkAssetName = apkAsset.name,
                        apkDownloadUrl = apkAsset.downloadUrl,
                        releaseNotes = buildLabel?.let { "预览构建时间：$it" },
                        isUpdateAvailable = isUpdateAvailable
                    )
                }
            }
        }

    /** GitHub 附件的 updated_at 为 ISO-8601 UTC 时间，解析为 epoch 秒。 */
    private fun previewBuildEpochSeconds(assetUpdatedAt: String?): Long? =
        assetUpdatedAt?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
        }

    private fun formatBuildEpoch(epochSeconds: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochSecond(epochSeconds))

    suspend fun downloadApk(
        downloadUrl: String,
        outputDir: File,
        outputName: String,
        onProgress: suspend (UpdateDownloadProgress) -> Unit = {}
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatchingObserved {
                outputDir.mkdirs()
                outputDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
                    ?.forEach { it.delete() }

                val safeName = outputName
                    .ifBlank { "Bastion-update.apk" }
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
                val outputFile = File(outputDir, safeName)

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "Bastion-Android")
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("APK download failed: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw IOException("APK download response is empty")
                    val totalBytes = body.contentLength()
                    outputFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead = 0L
                            var lastProgressAt = 0L
                            onProgress(UpdateDownloadProgress(bytesRead, totalBytes))
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                bytesRead += read
                                val now = System.currentTimeMillis()
                                if (
                                    totalBytes > 0L && bytesRead >= totalBytes ||
                                    now - lastProgressAt >= 250L
                                ) {
                                    lastProgressAt = now
                                    onProgress(UpdateDownloadProgress(bytesRead, totalBytes))
                                }
                            }
                        }
                    }
                }

                if (outputFile.length() <= 0L) {
                    throw IOException("Downloaded APK is empty")
                }
                outputFile
            }
        }

    fun validateDownloadedApk(context: Context, apkFile: File): Result<Unit> =
        runCatchingObserved {
            val packageManager = context.packageManager
            val downloadedPackage = packageManager.getArchivePackageInfo(apkFile)
                ?: throw IOException("Downloaded APK package info is unreadable")
            val installedPackage = packageManager.getInstalledPackageInfo(context.packageName)

            if (downloadedPackage.packageName != context.packageName) {
                throw IOException("Downloaded APK package name does not match Bastion")
            }

            val downloadedDigests = downloadedPackage.signingCertificateDigests()
            val installedDigests = installedPackage.signingCertificateDigests()
            if (downloadedDigests.isEmpty() || installedDigests.isEmpty()) {
                throw IOException("Downloaded APK signing certificate is unreadable")
            }

            val hasMatchingSigner = downloadedDigests.any { downloaded ->
                installedDigests.any { installed -> downloaded.contentEquals(installed) }
            }
            if (!hasMatchingSigner) {
                throw IOException("Downloaded APK signature does not match installed Bastion")
            }
        }

    fun compareVersionTags(candidate: String, current: String): Int {
        val candidateParts = candidate.semanticVersionParts()
        val currentParts = current.semanticVersionParts()
        if (candidateParts.isEmpty() || currentParts.isEmpty()) {
            return candidate.trim().compareTo(current.trim(), ignoreCase = true)
        }

        val maxSize = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) {
                return candidatePart.compareTo(currentPart)
            }
        }
        return 0
    }

    private fun String.semanticVersionParts(): List<Int> {
        val semanticMatch = Regex("""(?i)v?(\d+)\.(\d+)\.(\d+)""").find(this)
        if (semanticMatch != null) {
            return semanticMatch.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        }

        return Regex("\\d+")
            .findAll(this)
            .take(3)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getArchivePackageInfo(apkFile: File): PackageInfo? =
        getPackageArchiveInfo(apkFile.absolutePath, signatureQueryFlags())

    @Suppress("DEPRECATION")
    private fun PackageManager.getInstalledPackageInfo(packageName: String): PackageInfo =
        getPackageInfo(packageName, signatureQueryFlags())

    private fun signatureQueryFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun PackageInfo.signingCertificateDigests(): List<ByteArray> =
        signaturesForVerification().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        }

    private fun PackageInfo.signaturesForVerification(): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptyArray()
            if (info.hasMultipleSigners()) {
                info.apkContentsSigners ?: emptyArray()
            } else {
                info.signingCertificateHistory ?: info.apkContentsSigners ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            signatures ?: emptyArray()
        }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList()
) {
    fun apkAsset(): GitHubReleaseAsset? =
        assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType?.equals("application/vnd.android.package-archive", ignoreCase = true) == true
        }

    /** 预览渠道优先取 Debug APK（arm64），退回任意 APK。 */
    fun debugApkAsset(): GitHubReleaseAsset? =
        assets.firstOrNull { it.name.endsWith("-debug.apk", ignoreCase = true) }
            ?: apkAsset()
}

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
