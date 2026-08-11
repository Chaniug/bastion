package com.bastion.app.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import com.bastion.app.platform.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 桌面版 KDBX 数据库服务（精简）。
 *
 * 核心能力：
 * 1. 打开本地 .kdbx 文件（主密码 / 密钥文件）
 * 2. 读取条目（列表 + 分组）
 * 3. 编辑条目 / 新建条目
 * 4. 保存（整文件重写，KDBX4）
 *
 * kotpass 仅支持 JVM，因此本类位于 jvmMain 源集。
 */
class KeePassKdbxService {

    private val TAG = "KeePassKdbxService"

    companion object {
        val cipherProviders: List<CipherProvider> = buildList {
            addAll(BaseCiphers.entries)
            add(TwofishCipher)
        }
    }

    // ==================== 打开 ====================

    /**
     * 打开 KDBX 文件并返回数据库会话。
     */
    fun open(
        file: File,
        password: String,
        keyFileBytes: ByteArray? = null
    ): OpenedDatabase {
        val credentials = buildCredentials(password, keyFileBytes)
        val bytes = file.readBytes()
        return try {
            val database = KeePassDatabase.decode(
                ByteArrayInputStream(bytes),
                credentials,
                cipherProviders = cipherProviders
            )
            OpenedDatabase(
                file = file,
                database = database,
                password = password,
                keyFileBytes = keyFileBytes
            )
        } catch (e: Exception) {
            Logger.e(TAG, "KDBX decode failed: ${file.absolutePath}", e)
            throw KeePassOpenException("无法打开数据库：${e.message}", e)
        }
    }

    /**
     * 新建一个 KDBX4 数据库（AES-256），写入文件并返回会话。
     */
    fun create(
        file: File,
        password: String
    ): OpenedDatabase {
        val credentials = buildCredentials(password, null)
        val fresh = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(),
            credentials = credentials
        )
        val bytes = encodeDatabase(fresh)
        file.writeBytes(bytes)

        // 重新 decode 以建立正确的会话（header/KDF 已初始化）
        return open(file, password, null)
    }

    // ==================== 读取 ====================

    /** 列出所有分组（含路径与条目数）。 */
    fun listGroups(db: KeePassDatabase): List<KeePassGroupInfo> {
        val result = mutableListOf<KeePassGroupInfo>()
        walkGroups(db.content.group, "", result)
        return result
    }

    /** 列出全部条目（扁平）。 */
    fun listEntries(db: KeePassDatabase): List<KeePassEntryData> {
        val result = mutableListOf<KeePassEntryData>()
        walkEntries(db.content.group, result)
        return result
    }

    /** 列出指定分组路径下的条目。 */
    fun listEntriesInGroup(db: KeePassDatabase, groupPath: String): List<KeePassEntryData> {
        if (groupPath.isBlank() || groupPath == "Root") return listEntries(db)
        val group = findGroupByPath(db.content.group, groupPath) ?: return emptyList()
        return collectEntries(group)
    }

    // ==================== 编辑 ====================

    /**
     * 新增或更新条目并保存。通过 uuid 定位已有条目；新条目插入目标分组。
     */
    fun saveEntry(
        opened: OpenedDatabase,
        entry: KeePassEntryData,
        targetGroupPath: String = "Root"
    ): Boolean {
        var root = opened.database.content.group
        val existing = findEntryByUuid(root, entry.uuid)
        val fields = buildEntryFields(entry)

        root = if (existing != null) {
            replaceEntry(root, existing.uuid, fields)
        } else {
            val target = findGroupByPath(root, targetGroupPath) ?: root
            insertEntryIntoGroup(root, target, entry.uuid, fields)
        }

        val updated = opened.database.modifyParentGroup { root }
        val bytes = encodeDatabase(updated)
        opened.file.writeBytes(bytes)
        opened.database = updated
        return true
    }

    /** 删除条目并保存。 */
    fun deleteEntry(opened: OpenedDatabase, uuid: String): Boolean {
        val uuidObj = UUID.fromString(uuid)
        val root = removeEntryByUuid(opened.database.content.group, uuidObj)
        val updated = opened.database.modifyParentGroup { root }
        val bytes = encodeDatabase(updated)
        opened.file.writeBytes(bytes)
        opened.database = updated
        return true
    }

    /** 新建分组并保存。 */
    fun addGroup(opened: OpenedDatabase, parentPath: String, groupName: String): Boolean {
        var root = opened.database.content.group
        val parent = findGroupByPath(root, parentPath) ?: root
        val newGroup = Group(
            uuid = UUID.randomUUID(),
            name = groupName,
            groups = emptyList(),
            entries = emptyList()
        )
        root = addGroupToGroup(root, parent.uuid, newGroup)
        val updated = opened.database.modifyParentGroup { root }
        val bytes = encodeDatabase(updated)
        opened.file.writeBytes(bytes)
        opened.database = updated
        return true
    }

    // ==================== 内部 ====================

    private fun buildCredentials(password: String, keyFileBytes: ByteArray?): Credentials {
        return if (keyFileBytes == null) {
            Credentials.from(EncryptedValue.fromString(password))
        } else {
            Credentials.from(EncryptedValue.fromString(password), keyFileBytes)
        }
    }

    /**
     * 将数据库重新编码为 KDBX 字节流（用于上传/导出，与保存使用相同的 cipherProviders）。
     */
    fun exportBytes(db: KeePassDatabase): ByteArray = encodeDatabase(db)

    private fun encodeDatabase(db: KeePassDatabase): ByteArray {
        return ByteArrayOutputStream().use { output ->
            db.encode(output, cipherProviders = cipherProviders)
            output.toByteArray()
        }
    }

    private fun walkGroups(group: Group, prefix: String, out: MutableList<KeePassGroupInfo>) {
        val path = if (prefix.isEmpty()) group.name else "$prefix/${group.name}"
        out.add(KeePassGroupInfo(path = path, entryCount = group.entries.size))
        group.groups.forEach { walkGroups(it, path, out) }
    }

    private fun walkEntries(group: Group, out: MutableList<KeePassEntryData>) {
        out.addAll(collectEntries(group))
        group.groups.forEach { walkEntries(it, out) }
    }

    private fun collectEntries(group: Group): List<KeePassEntryData> {
        return group.entries.map { entry ->
            KeePassEntryData(
                uuid = entry.uuid.toString(),
                title = readField(entry, "Title"),
                username = readField(entry, "UserName"),
                password = readField(entry, "Password"),
                url = readField(entry, "URL"),
                notes = readField(entry, "Notes")
            )
        }
    }

    private fun readField(entry: Entry, name: String): String {
        val value = entry.fields[name] ?: return ""
        return value.content
    }

    private fun buildEntryFields(entry: KeePassEntryData): EntryFields {
        return EntryFields(
            mapOf(
                "Title" to EntryValue.Plain(entry.title),
                "UserName" to EntryValue.Plain(entry.username),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(entry.password)),
                "URL" to EntryValue.Plain(entry.url),
                "Notes" to EntryValue.Plain(entry.notes)
            )
        )
    }

    private fun findGroupByPath(root: Group, path: String): Group? {
        val segments = path.split('/').filter { it.isNotBlank() }
        var current = root
        for (seg in segments) {
            val next = current.groups.firstOrNull { it.name == seg } ?: return null
            current = next
        }
        return current
    }

    private fun findEntryByUuid(root: Group, uuid: String): Entry? {
        if (uuid.isBlank()) return null
        val uuidObj = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
        fun search(g: Group): Entry? {
            g.entries.firstOrNull { it.uuid == uuidObj }?.let { return it }
            g.groups.forEach { search(it)?.let { return it } }
            return null
        }
        return search(root)
    }

    private fun replaceEntry(root: Group, uuid: UUID, fields: EntryFields): Group {
        fun replace(g: Group): Group {
            val newEntries = g.entries.map { e ->
                if (e.uuid == uuid) e.copy(fields = fields) else e
            }
            return g.copy(entries = newEntries, groups = g.groups.map { replace(it) })
        }
        return replace(root)
    }

    private fun insertEntryIntoGroup(root: Group, targetGroup: Group, uuid: String, fields: EntryFields): Group {
        val newEntry = Entry(uuid = UUID.fromString(uuid), fields = fields)
        fun insert(g: Group): Group {
            if (g === targetGroup) {
                return g.copy(entries = g.entries + newEntry)
            }
            return g.copy(groups = g.groups.map { insert(it) })
        }
        return insert(root)
    }

    private fun removeEntryByUuid(root: Group, uuid: UUID): Group {
        fun remove(g: Group): Group {
            val newEntries = g.entries.filterNot { it.uuid == uuid }
            return g.copy(entries = newEntries, groups = g.groups.map { remove(it) })
        }
        return remove(root)
    }

    private fun addGroupToGroup(root: Group, parentUuid: UUID, newGroup: Group): Group {
        fun add(g: Group): Group {
            if (g.uuid == parentUuid) {
                return g.copy(groups = g.groups + newGroup)
            }
            return g.copy(groups = g.groups.map { add(it) })
        }
        return add(root)
    }
}

/** 打开的数据库会话句柄。 */
class OpenedDatabase(
    val file: File,
    var database: KeePassDatabase,
    val password: String,
    val keyFileBytes: ByteArray?
)

/** 分组信息（用于树状展示）。 */
data class KeePassGroupInfo(
    val path: String,
    val entryCount: Int
)

/** 条目数据（UI 层使用）。 */
data class KeePassEntryData(
    val uuid: String = UUID.randomUUID().toString(),
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = ""
)

/** 打开失败异常。 */
class KeePassOpenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
