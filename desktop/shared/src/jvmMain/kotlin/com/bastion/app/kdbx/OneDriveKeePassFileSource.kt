package com.bastion.app.kdbx

import com.bastion.app.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/**
 * OneDrive 上的 KDBX 文件源（桌面版，Graph REST）。
 *
 * 与安卓版 [OneDriveKeePassFileSource] 职责一致，但认证改为 [authTokenProvider] 回调
 * （由 OneDriveBrowserAuth 提供 access token），不再依赖 MSAL。
 *
 * 覆盖：stat / read / write（分片上传）/ listChildren / testConnection。
 * 冲突检测：写入时带 etag（If-Match），远端被改动则失败，交由同步引擎处理三方合并。
 */
class OneDriveKeePassFileSource(
    private val authTokenProvider: suspend () -> String,
    private val driveId: String? = null,
    private val itemId: String? = null,
    private val remotePath: String? = null,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : KeePassFileSource {

    private val tag = "OneDriveKeePassFileSource"
    // encodeDefaults=true：保证值等于默认值的属性也参与序列化。
    // 否则 UploadSessionRequestDto() 会退化为 "{}"，createUploadSession 的
    // conflictBehavior=replace 发不出去，Graph 按默认 rename 处理，>4MB 重复上传
    // 会生成 "file 1.kdbx" 副本而非覆盖。勿改回默认值（有回归测试守护）。
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private const val JSON_MEDIA = "application/json; charset=utf-8"
        private const val OCTET_MEDIA = "application/octet-stream"
        private const val CHUNK_SIZE = 4 * 1024 * 1024L // 4MB 分片
        private const val MAX_CHUNK_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
    }

    // ==================== DTO ====================

    @Serializable
    private data class DriveItemDto(
        val id: String = "",
        val name: String = "",
        val size: Long? = null,
        @SerialName("eTag") val eTag: String? = null,
        @SerialName("cTag") val cTag: String? = null,
        @SerialName("lastModifiedDateTime") val lastModifiedDateTime: String? = null,
        val folder: FolderFacetDto? = null,
        val file: FileFacetDto? = null,
        @SerialName("parentReference") val parentReference: ParentReferenceDto? = null,
        val error: ErrorDto? = null
    )

    @Serializable
    private data class FolderFacetDto(val childCount: Int? = null)

    @Serializable
    private data class FileFacetDto(val mimeType: String? = null)

    @Serializable
    private data class ParentReferenceDto(val driveId: String? = null, val path: String? = null)

    @Serializable
    private data class ChildrenResponseDto(
        val value: List<DriveItemDto> = emptyList(),
        @SerialName("@odata.nextLink") val nextLink: String? = null
    )

    @Serializable
    internal data class UploadSessionRequestDto(
        val item: UploadSessionItemDto = UploadSessionItemDto()
    )

    @Serializable
    internal data class UploadSessionItemDto(
        @SerialName("@microsoft.graph.conflictBehavior") val conflictBehavior: String = "replace"
    )

    @Serializable
    private data class UploadSessionResponseDto(
        @SerialName("uploadUrl") val uploadUrl: String = ""
    )

    @Serializable
    private data class ErrorDto(val message: ErrorMessageDto? = null)

    @Serializable
    private data class ErrorMessageDto(val value: String? = null)

    // ==================== KeePassFileSource ====================

    override suspend fun stat(): FileSourceStat = withContext(Dispatchers.IO) {
        val token = authTokenProvider()
        val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")
        val response = getJson(token, url) ?: return@withContext FileSourceStat()

        FileSourceStat(
            versionToken = response.cTag,
            etag = response.eTag,
            lastModified = response.lastModifiedDateTime?.let(::parseMs),
            sizeBytes = response.size,
            remoteId = response.id,
            driveId = response.parentReference?.driveId,
            isDirectory = response.folder != null,
            displayName = response.name
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val token = authTokenProvider()
        val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")
        val contentUrl = "$url/content"

        val request = Request.Builder()
            .url(contentUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneDrive read failed: ${response.code}")
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun write(bytes: ByteArray, expectedVersion: String?): FileSourceWriteResult =
        withContext(Dispatchers.IO) {
            val token = authTokenProvider()
            val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")

            // 大文件用上传会话分片；小文件直接 PUT
            if (bytes.size > CHUNK_SIZE) {
                uploadViaSession(url, token, bytes)
            } else {
                val request = Request.Builder()
                    .url("$url/content")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", OCTET_MEDIA)
                    .apply { expectedVersion?.let { addHeader("If-Match", it) } }
                    .put(bytes.toRequestBody(OCTET_MEDIA.toMediaType()))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 412) {
                            throw conflictWithRemoteVersion(
                                "远端文件已被修改（412 Precondition Failed），本地 expectedVersion 过期"
                            )
                        }
                        throw IOException("OneDrive write failed: ${response.code} ${response.body?.string().orEmpty()}")
                    }
                    val dto = parseItemOrNull(response.body?.string())
                    FileSourceWriteResult(
                        etag = dto?.eTag,
                        lastModified = dto?.lastModifiedDateTime?.let(::parseMs),
                        remoteId = dto?.id,
                        driveId = dto?.parentReference?.driveId
                    )
                }
            }
        }

    override suspend fun listChildren(): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val token = authTokenProvider()
        val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")
        val childrenUrl = "$url/children"

        val request = Request.Builder()
            .url(childrenUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneDrive listChildren failed: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ChildrenResponseDto>(body)
            parsed.value.map { item ->
                FileSourceEntry(
                    id = item.id,
                    name = item.name,
                    path = item.parentReference?.path?.plus("/${item.name}") ?: item.name,
                    isDirectory = item.folder != null,
                    versionToken = item.cTag,
                    lastModified = item.lastModifiedDateTime?.let(::parseMs),
                    sizeBytes = item.size
                )
            }
        }
    }

    override suspend fun createFile(name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val token = authTokenProvider()
        val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")
        val createUrl = "$url/children/$name/content"

        val request = Request.Builder()
            .url(createUrl)
            .addHeader("Authorization", "Bearer $token")
            .put(ByteArray(0).toRequestBody(OCTET_MEDIA.toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneDrive createFile failed: ${response.code}")
            }
            val dto = parseItemOrNull(response.body?.string())
            FileSourceEntry(
                id = dto?.id,
                name = name,
                path = dto?.parentReference?.path?.plus("/$name") ?: name,
                isDirectory = false,
                versionToken = dto?.cTag,
                lastModified = dto?.lastModifiedDateTime?.let(::parseMs),
                sizeBytes = dto?.size
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        val token = authTokenProvider()
        val url = itemUrl() ?: throw IOException("OneDrive 未配置 itemId/remotePath")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneDrive connection test failed: ${response.code}")
            }
        }
    }

    // ==================== 内部 ====================

    private suspend fun getJson(token: String, url: String): DriveItemDto? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.w(tag, "GET $url failed: ${response.code}")
                return null
            }
            return json.decodeFromString<DriveItemDto>(response.body?.string().orEmpty())
        }
    }

    private suspend fun uploadViaSession(url: String, token: String, bytes: ByteArray): FileSourceWriteResult {
        // 1. 创建上传会话（conflictBehavior=replace：同名文件覆盖而非改名副本）
        val sessionRequest = Request.Builder()
            .url("$url/createUploadSession")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", JSON_MEDIA)
            .post(json.encodeToString(UploadSessionRequestDto()).toRequestBody(JSON_MEDIA.toMediaType()))
            .build()

        val uploadUrl = okHttpClient.newCall(sessionRequest).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 412) {
                    throw conflictWithRemoteVersion(
                        "远端文件已被修改（412 Precondition Failed），无法创建覆盖上传会话"
                    )
                }
                throw IOException("Create upload session failed: ${response.code} ${response.body?.string().orEmpty()}")
            }
            json.decodeFromString<UploadSessionResponseDto>(response.body?.string().orEmpty()).uploadUrl
        }

        // 2. 分片上传（严格失败检查：任一非 2xx 即抛，offset 仅在成功后前进）
        var offset = 0L
        var finalResponse: String? = null
        try {
            while (offset < bytes.size) {
                val chunkLen = minOf(CHUNK_SIZE, bytes.size - offset)
                val chunk = bytes.copyOfRange(offset.toInt(), (offset + chunkLen).toInt())
                val last = offset + chunkLen >= bytes.size
                val contentRange = "bytes ${offset}-${offset + chunkLen - 1}/${bytes.size}"

                val putRequest = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Content-Length", chunk.size.toString())
                    .addHeader("Content-Range", contentRange)
                    .put(chunk.toRequestBody(OCTET_MEDIA.toMediaType()))
                    .build()

                val result = putChunkWithRetry(putRequest, contentRange, last)
                if (last) finalResponse = result
                offset += chunkLen
            }
        } catch (e: Exception) {
            // 取消上传会话（尽力而为），然后原样抛出
            runCatching { cancelUploadSession(uploadUrl, token) }
            throw e
        }

        // 3. 最后分片成功后，响应体必须包含最终 DriveItem
        val body = finalResponse?.trim().orEmpty()
        if (body.isEmpty()) {
            throw IOException("Upload completed but final response body was empty")
        }
        val dto = parseItemOrNull(body)
            ?: throw IOException("Upload completed but final response could not be parsed")

        return FileSourceWriteResult(
            etag = dto.eTag,
            lastModified = dto.lastModifiedDateTime?.let(::parseMs),
            remoteId = dto.id,
            driveId = dto.parentReference?.driveId
        )
    }

    /**
     * 上传单个分片；网络/5xx 对同一 range 重试，412 冲突直接抛出，其余失败抛 IOException。
     */
    private suspend fun putChunkWithRetry(
        putRequest: Request,
        contentRange: String,
        last: Boolean
    ): String? {
        var attempt = 0
        while (true) {
            attempt++
            val response = okHttpClient.newCall(putRequest).execute()
            response.use { r ->
                if (r.isSuccessful) {
                    return if (last) r.body?.string() else null
                }
                if (r.code == 412) {
                    throw conflictWithRemoteVersion(
                        "远端文件已被修改（412 Precondition Failed）@ range $contentRange"
                    )
                }
                if (attempt >= MAX_CHUNK_RETRIES) {
                    throw IOException(
                        "Chunk upload failed after $attempt attempts @ range $contentRange: " +
                            "${r.code} ${r.body?.string().orEmpty()}"
                    )
                }
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /** 取消上传会话（DELETE uploadUrl），尽力而为。 */
    private suspend fun cancelUploadSession(uploadUrl: String, token: String) {
        runCatching {
            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            okHttpClient.newCall(request).execute().close()
        }
    }

    private fun parseItemOrNull(body: String?): DriveItemDto? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString<DriveItemDto>(body)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to parse DriveItem: ${e.message}")
            null
        }
    }

    private fun itemUrl(): String? {
        val path = remotePath?.trim('/')
        return when {
            itemId != null -> "$GRAPH_BASE/drives/${driveId ?: "me/drive"}/items/$itemId"
            !path.isNullOrBlank() -> "$GRAPH_BASE/drives/${driveId ?: "me/drive"}/root:$path"
            else -> null
        }
    }

    private fun parseMs(iso: String): Long {
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    /**
     * 构造冲突异常并尽力附带远端当前版本标识（etag/cTag）。
     * stat() 失败时 [OneDriveConflictException.remoteVersion] 为 null，不影响冲突上报。
     */
    private suspend fun conflictWithRemoteVersion(message: String): OneDriveConflictException {
        val remoteStat = try { stat() } catch (_: Exception) { null }
        val remote = remoteStat?.etag ?: remoteStat?.versionToken
        return OneDriveConflictException(message, remoteVersion = remote)
    }
}

/**
 * OneDrive 写入冲突异常：远端文件已被修改（412 Precondition Failed），
 * 本地持有的 expectedVersion 已过期。由同步引擎捕获后映射为 [OneDriveSyncResult.Conflict]。
 *
 * @param remoteVersion 冲突时远端当前的版本标识（etag/cTag），best-effort 获取；
 *                      获取失败时为 null。供同步引擎填充 [OneDriveSyncResult.Conflict.remoteVersion]。
 */
class OneDriveConflictException(
    message: String,
    val remoteVersion: String? = null,
    cause: Throwable? = null
) : IOException(message, cause)
