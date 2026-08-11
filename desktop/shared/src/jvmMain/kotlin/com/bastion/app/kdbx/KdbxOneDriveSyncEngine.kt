package com.bastion.app.kdbx

import com.bastion.app.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * KDBX ↔ OneDrive 远端同步引擎（桌面版）。
 *
 * 能力：
 * 1. 上传：本地 [OpenedDatabase] 重新编码为 KDBX4 字节 → 通过 [KeePassFileSource.write] 上传远端
 * 2. 下载：远端 read() 字节 → 先写临时文件并 open 校验 → 覆盖本地 → 返回重新打开的新会话
 * 3. 冲突检测：上传带 [expectedVersion]（If-Match），远端被改动时 [OneDriveConflictException] 抛出，
 *    引擎捕获后返回 [OneDriveSyncResult.Conflict]
 *
 * 所有网络 / IO 调用均在 [Dispatchers.IO] 执行。
 */
class KdbxOneDriveSyncEngine(
    private val service: KeePassKdbxService = KeePassKdbxService()
) {

    private val tag = "KdbxOneDriveSyncEngine"

    /**
     * 上传本地库到远端。
     *
     * @param opened 当前打开的本地数据库会话
     * @param source OneDrive 文件源（由调用方构造，认证由 authTokenProvider 提供）
     * @param expectedVersion 本地记录的远端 etag；传入时启用 If-Match 冲突检测，
     *                        远端被修改则返回 [OneDriveSyncResult.Conflict]；传 null 则直接覆盖
     */
    suspend fun upload(
        opened: OpenedDatabase,
        source: KeePassFileSource,
        expectedVersion: String? = null
    ): OneDriveSyncResult = withContext(Dispatchers.IO) {
        try {
            val bytes = service.exportBytes(opened.database)
            Logger.i(tag, "re-encoded ${bytes.size} bytes for upload, expectedVersion=${expectedVersion ?: "none"}")
            val writeResult = source.write(bytes, expectedVersion = expectedVersion)
            val remoteEtag = writeResult.etag ?: writeResult.versionToken
            Logger.i(tag, "upload success: ${bytes.size} bytes, etag=$remoteEtag")
            OneDriveSyncResult.Success(
                remoteEtag = remoteEtag,
                bytesWritten = bytes.size
            )
        } catch (e: OneDriveConflictException) {
            Logger.w(tag, "upload conflict: ${e.message}")
            OneDriveSyncResult.Conflict(
                localVersion = expectedVersion,
                remoteVersion = e.remoteVersion
            )
        } catch (e: Exception) {
            Logger.e(tag, "upload failed", e)
            OneDriveSyncResult.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 从远端下载并覆盖本地文件，成功后重新打开数据库返回新会话。
     *
     * 流程：stat() 检查远端是否存在 → read() 取字节 → 写入临时文件并用密码 open 校验
     * （避免把本地库覆盖成无法打开的状态）→ 校验通过才覆盖本地文件。
     *
     * @param source OneDrive 文件源
     * @param localFile 本地 KDBX 文件（覆盖目标）
     * @param password 数据库主密码
     * @param keyFileBytes 密钥文件内容（可选）
     */
    suspend fun download(
        source: KeePassFileSource,
        localFile: File,
        password: String,
        keyFileBytes: ByteArray? = null
    ): OneDriveDownloadResult = withContext(Dispatchers.IO) {
        try {
            val stat = source.stat()
            val remoteExists = stat.remoteId != null || stat.displayName != null ||
                stat.etag != null || stat.versionToken != null
            if (!remoteExists) {
                Logger.w(tag, "remote file missing: ${localFile.name}")
                return@withContext OneDriveDownloadResult.RemoteMissing
            }

            val bytes = source.read()
            if (bytes.isEmpty()) {
                return@withContext OneDriveDownloadResult.Error("远端文件为空")
            }

            val tempFile = File(localFile.parentFile, ".${localFile.name}.synctmp")
            tempFile.writeBytes(bytes)
            try {
                val tempOpened = service.open(tempFile, password, keyFileBytes)
                // 校验通过 → 覆盖本地文件，并构造指向本地文件的新会话
                localFile.writeBytes(bytes)
                val opened = OpenedDatabase(
                    file = localFile,
                    database = tempOpened.database,
                    password = password,
                    keyFileBytes = keyFileBytes
                )
                val remoteEtag = stat.etag ?: stat.versionToken
                Logger.i(tag, "download success: ${bytes.size} bytes, etag=$remoteEtag")
                OneDriveDownloadResult.Success(
                    opened = opened,
                    remoteEtag = remoteEtag,
                    bytesDownloaded = bytes.size
                )
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Logger.e(tag, "download failed", e)
            OneDriveDownloadResult.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 检查远端文件是否存在，并返回其 etag（供 UI 展示远端版本）。
     */
    suspend fun checkRemote(source: KeePassFileSource): OneDriveSyncResult = withContext(Dispatchers.IO) {
        try {
            val stat = source.stat()
            val remoteExists = stat.remoteId != null || stat.displayName != null ||
                stat.etag != null || stat.versionToken != null
            if (!remoteExists) {
                Logger.w(tag, "checkRemote: remote file missing")
                OneDriveSyncResult.RemoteMissing
            } else {
                OneDriveSyncResult.Success(
                    remoteEtag = stat.etag ?: stat.versionToken,
                    bytesWritten = 0
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "checkRemote failed", e)
            OneDriveSyncResult.Error(e.message ?: "未知错误")
        }
    }
}

/** 上传 / 检查结果模型。 */
sealed class OneDriveSyncResult {
    /** 上传成功；[remoteEtag] 为远端新版本标识，[bytesWritten] 为写入字节数。 */
    data class Success(
        val remoteEtag: String?,
        val bytesWritten: Int
    ) : OneDriveSyncResult()

    /** 远端已被修改，写入被拒（If-Match 412）。 */
    data class Conflict(
        val localVersion: String?,
        val remoteVersion: String?
    ) : OneDriveSyncResult()

    /** 操作失败（网络 / IO / 解码等）。 */
    data class Error(
        val message: String
    ) : OneDriveSyncResult()

    /** 远端文件不存在。 */
    data object RemoteMissing : OneDriveSyncResult()
}

/** 下载结果模型；成功时携带重新打开的新会话。 */
sealed class OneDriveDownloadResult {
    /** 下载成功；[opened] 为指向本地文件的新会话。 */
    data class Success(
        val opened: OpenedDatabase,
        val remoteEtag: String?,
        val bytesDownloaded: Int
    ) : OneDriveDownloadResult()

    /** 下载失败。 */
    data class Error(
        val message: String
    ) : OneDriveDownloadResult()

    /** 远端文件不存在。 */
    data object RemoteMissing : OneDriveDownloadResult()
}
