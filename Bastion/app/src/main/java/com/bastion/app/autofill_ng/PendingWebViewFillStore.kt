package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.util.Base64
import java.io.File

/**
 * WebView 回填兜底存储：当 Android Autofill Framework 的 Dataset 在 WebView（如 Via 浏览器）
 * 中因虚拟 autofillId 不稳定而无法回填时，凭据选择改由无障碍服务通过
 * ACTION_SET_TEXT 直接写入聚焦节点（Bitwarden 同款做法）。
 *
 * 数据流：
 *  - 写入方：主进程(:autofill) 的 AutofillCipherCallbackActivity，在解密凭据后 stash。
 *  - 消费方：独立进程(:accessibility) 的无障碍服务，焦点回到目标 App 后 consume 并填充。
 *
 * 跨进程：同 UID 下用 filesDir 文件共享，写入临时文件 + 原子 rename，避免读到半写内容。
 * 凭据可能含任意字符（含换行），故 username/password 以 Base64 存储，避免行格式错位。
 */
object PendingWebViewFillStore {
    private const val MAX_AGE_MS = 15_000L
    private const val FILE_NAME = "bastion_pending_webview_fill_v1"
    private const val FILE_NAME_TMP = "bastion_pending_webview_fill_v1.tmp"

    data class PendingFill(
        val packageName: String,
        val username: String,
        val password: String,
        val updatedAt: Long,
    )

    @Volatile
    private var appContext: Context? = null
    private val fileLock = Any()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun stash(packageName: String, username: String, password: String) {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return
        val snap = PendingFill(pkg, username, password, System.currentTimeMillis())
        writeToFile(snap)
    }

    /**
     * 若存在且未过期、且包名匹配，则返回并清除；否则返回 null。
     * 过期项由下次 stash 直接覆盖，无需单独清理。
     */
    fun consume(packageName: String): PendingFill? {
        val snap = readFromFile() ?: return null
        if (!snap.packageName.equals(packageName.trim(), ignoreCase = true)) return null
        if (System.currentTimeMillis() - snap.updatedAt > MAX_AGE_MS) return null
        clear()
        return snap
    }

    fun clear() {
        val ctx = appContext ?: return
        synchronized(fileLock) {
            runCatchingObserved { File(ctx.filesDir, FILE_NAME).delete() }
            runCatchingObserved { File(ctx.filesDir, FILE_NAME_TMP).delete() }
        }
    }

    private fun writeToFile(snap: PendingFill) {
        val ctx = appContext ?: return
        synchronized(fileLock) {
            runCatchingObserved {
                val dir = ctx.filesDir
                val target = File(dir, FILE_NAME)
                val tmp = File(dir, FILE_NAME_TMP)
                val payload = buildString {
                    appendLine(Base64.encodeToString(snap.packageName.toByteArray(), Base64.NO_WRAP))
                    appendLine(Base64.encodeToString(snap.username.toByteArray(), Base64.NO_WRAP))
                    appendLine(Base64.encodeToString(snap.password.toByteArray(), Base64.NO_WRAP))
                    append(snap.updatedAt.toString())
                }
                tmp.writeText(payload)
                if (!tmp.renameTo(target)) {
                    target.writeText(payload)
                }
                runCatchingObserved { tmp.delete() }
            }
        }
    }

    private fun readFromFile(): PendingFill? {
        val ctx = appContext ?: return null
        return synchronized(fileLock) {
            runCatchingObserved {
                val file = File(ctx.filesDir, FILE_NAME)
                if (!file.exists()) return@runCatchingObserved null
                val lines = file.readLines()
                if (lines.size < 4) return@runCatchingObserved null
                val pkg = String(Base64.decode(lines[0], Base64.NO_WRAP))
                val user = String(Base64.decode(lines[1], Base64.NO_WRAP))
                val pass = String(Base64.decode(lines[2], Base64.NO_WRAP))
                val ts = lines[3].trim().toLongOrNull() ?: return@runCatchingObserved null
                if (pkg.isBlank()) return@runCatchingObserved null
                PendingFill(pkg, user, pass, ts)
            }.getOrNull()
        }
    }
}
