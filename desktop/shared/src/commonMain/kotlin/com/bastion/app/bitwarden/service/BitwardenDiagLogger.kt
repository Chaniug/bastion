package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.platform.PathProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bitwarden 登录诊断持久化日志（桌面版）。
 *
 * 与安卓版职责一致：把登录诊断链路持久化到文件，便于排查。
 * 依赖注入路径改为 [PathProvider]，无 BuildConfig/Context。
 */
object BitwardenDiagLogger {

    private const val LOG_DIR_NAME = "bitwarden_logs"
    private const val LOG_FILE_NAME = "bitwarden_diag_v1.log"
    private const val MAX_LOG_FILE_BYTES = 1024 * 1024L
    private const val ROTATE_KEEP_LINES = 4000

    private val fileLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bastion-bitwarden-diag").apply { isDaemon = true }
    }
    @Volatile
    private var persistentLogFile: File? = null

    fun initialize() {
        if (persistentLogFile != null) return
        synchronized(fileLock) {
            if (persistentLogFile != null) return
            runCatchingObserved {
                val logDir = File(PathProvider.dataDir, LOG_DIR_NAME)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                val file = File(logDir, LOG_FILE_NAME)
                persistentLogFile = file
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val header = buildString {
                    appendLine("=== Bastion Bitwarden Diag Session ===")
                    appendLine("session_start=$time")
                    appendLine("app_version=BastionDesktop")
                    appendLine("platform=jvm")
                    appendLine("===")
                }
                file.appendText(header)
            }
        }
    }

    fun append(rawLine: String) {
        val file = persistentLogFile ?: return
        val line = rawLine.trimEnd()
        writeExecutor.execute {
            val sanitizedLine = sanitize(line)
            synchronized(fileLock) {
                runCatchingObserved {
                    if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                        rotate(file)
                    }
                    file.appendText(sanitizedLine + "\n")
                }
            }
        }
    }

    fun exportPersistedLogs(maxEntries: Int = 2000): String {
        val file = persistentLogFile ?: return ""
        if (!file.exists()) return ""
        return synchronized(fileLock) {
            runCatchingObserved {
                file.readLines()
                    .takeLast(maxEntries.coerceAtLeast(1))
                    .joinToString(separator = "\n")
            }.getOrDefault("")
        }
    }

    fun clear() {
        synchronized(fileLock) {
            runCatchingObserved {
                persistentLogFile?.let { file ->
                    if (file.exists()) {
                        file.writeText("")
                    }
                }
            }
        }
    }

    private fun rotate(file: File) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val tail = runCatchingObserved {
            file.readLines().takeLast(ROTATE_KEEP_LINES)
        }.getOrElse { emptyList() }

        val header = "=== bitwarden diag log rotated at $time ==="
        val output = buildString {
            appendLine(header)
            tail.forEach { appendLine(it) }
        }
        file.writeText(output)
    }

    private fun sanitize(text: String): String {
        return text
            .replace(
                Regex("(password|pwd|passwd|passwordhash|hash)[\"'\\s]*[:=][\"'\\s]*[A-Za-z0-9+/=]{8,}", RegexOption.IGNORE_CASE),
                "$1=***"
            )
            .replace(
                Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"),
                "***@***.com"
            )
            .replace(Regex("\\b[A-Za-z0-9]{28,}\\b"), "***TOKEN***")
            .replace(Regex("[A-Za-z0-9+/]{40,}={0,2}"), "***TOKEN***")
    }
}
