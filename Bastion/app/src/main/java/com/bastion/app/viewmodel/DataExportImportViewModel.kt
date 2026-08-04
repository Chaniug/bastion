package com.bastion.app.viewmodel

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bastion.app.R
import com.bastion.app.data.BackupReport
import com.bastion.app.data.ItemType
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.data.SecureItem
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.CustomField
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.model.TotpData
import com.bastion.app.repository.SecureItemRepository
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.repository.CustomFieldRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.utils.BackupRestoreApplier
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.ImportedPasswordSnapshot
import com.bastion.app.utils.OperationLogger
import com.bastion.app.utils.PasswordImportDuplicateResolver
import com.bastion.app.util.DataExportImportManager
import com.bastion.app.util.TotpDataResolver
import com.bastion.app.util.TotpUriParser
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.bitwarden.export.BitwardenJsonExporter
import com.bastion.app.utils.BackupContentScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据导入导出ViewModel
 */
class DataExportImportViewModel(
    private val secureItemRepository: SecureItemRepository,
    private val passwordRepository: PasswordRepository,
    private val context: Context
) : ViewModel() {

    private data class ImportedAuthenticatorDraft(
        val authenticatorKey: String,
        val totpData: TotpData?
    )

    private val exportManager = DataExportImportManager(context)
    private val bitwardenExportJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val securityManager by lazy { SecurityManager(context) }
    private val customFieldRepository by lazy {
        CustomFieldRepository(PasswordDatabase.getDatabase(context).customFieldDao())
    }

    private fun parseStoredTotpData(itemData: String, fallbackIssuer: String = ""): TotpData? {
        return TotpDataResolver.parseStoredItemData(
            itemData = itemData,
            fallbackIssuer = fallbackIssuer,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        )
    }

    private fun parseStoredTotpData(item: SecureItem): TotpData? {
        return parseStoredTotpData(item.itemData, item.title)
    }

    private fun encodeTotpDataPreservingStorageShape(originalItemData: String, data: TotpData): String {
        val plainJson = Json.encodeToString(data)
        return if (securityManager.looksLikeBastionCiphertext(originalItemData)) {
            securityManager.encryptDataLegacyCompat(plainJson)
        } else {
            plainJson
        }
    }

    private fun encodeSecureItemDataForLocalStorage(itemType: ItemType, itemData: String): String {
        if (itemData.isBlank()) return itemData
        if (
            itemType != ItemType.TOTP &&
            itemType != ItemType.BANK_CARD &&
            itemType != ItemType.DOCUMENT
        ) {
            return itemData
        }
        if (securityManager.looksLikeBastionCiphertext(itemData)) {
            return itemData
        }
        return securityManager.encryptDataLegacyCompat(itemData)
    }

    private fun validatePlainZipFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("导出的备份文件为空")
        }
        ZipFile(file).use { zip ->
            if (!zip.entries().hasMoreElements()) {
                throw IOException("导出的ZIP备份没有内容")
            }
        }
    }

    private fun validatePlainZipStream(input: InputStream) {
        ZipInputStream(input).use { zip ->
            if (zip.nextEntry == null) {
                throw IOException("写入后的ZIP备份没有内容")
            }
        }
    }

    private fun openExportOutputStream(uri: Uri): OutputStream {
        val resolver = context.contentResolver
        val modes = listOf("wt", "rwt", "w")
        var lastError: Throwable? = null
        modes.forEach { mode ->
            try {
                val stream = resolver.openOutputStream(uri, mode)
                if (stream != null) {
                    return stream
                }
            } catch (error: Throwable) {
                lastError = error
                android.util.Log.w("DataExport", "openOutputStream($mode) failed: ${error.message}")
            }
        }
        try {
            val stream = resolver.openOutputStream(uri)
            if (stream != null) {
                return stream
            }
        } catch (error: Throwable) {
            lastError = error
            android.util.Log.w("DataExport", "openOutputStream(default) failed: ${error.message}")
        }
        throw IOException("无法打开导出文件", lastError)
    }

    private suspend fun copyZipFileToOutputUri(zipFile: File, outputUri: Uri): Long = withContext(Dispatchers.IO) {
        validatePlainZipFile(zipFile)
        val expectedBytes = zipFile.length()
        val copiedBytes = openExportOutputStream(outputUri).use { output ->
            zipFile.inputStream().use { input ->
                input.copyTo(output)
            }.also {
                output.flush()
            }
        }
        if (copiedBytes <= 0L) {
            throw IOException("导出文件写入为空")
        }
        if (expectedBytes > 0L && copiedBytes != expectedBytes) {
            throw IOException("导出文件写入不完整：$copiedBytes/$expectedBytes")
        }
        context.contentResolver.openInputStream(outputUri)?.use(::validatePlainZipStream)
            ?: throw IOException("无法校验导出的ZIP文件")
        copiedBytes
    }

    private suspend fun copyPlainFileToOutputUri(
        sourceFile: File,
        outputUri: Uri,
        validateOutput: ((InputStream) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            throw IOException("导出文件为空")
        }
        val expectedBytes = sourceFile.length()
        val copiedBytes = openExportOutputStream(outputUri).use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }.also {
                output.flush()
            }
        }
        if (copiedBytes <= 0L) {
            throw IOException("导出文件写入为空")
        }
        if (expectedBytes > 0L && copiedBytes != expectedBytes) {
            throw IOException("导出文件写入不完整：$copiedBytes/$expectedBytes")
        }
        validateOutput?.let { validator ->
            context.contentResolver.openInputStream(outputUri)?.use(validator)
                ?: throw IOException("无法校验导出的文件")
        }
        copiedBytes
    }

    private fun zipBackupExportMessage(report: BackupReport): String {
        return "成功导出备份，包含 ${report.successItems.passwords} 个密码和 ${report.successItems.images} 张图片"
    }


    /**
     * 导入数据
     */
    suspend fun importData(
        inputUri: Uri,
        formatHint: DataExportImportManager.CsvFormat? = null,
        passwordKeyboardTagHandling: DataExportImportManager.PasswordKeyboardTagHandling =
            DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
    ): Result<Int> {
        return try {
            // 导入数据
            val result = exportManager.importData(
                inputUri = inputUri,
                formatHint = formatHint,
                passwordKeyboardTagHandling = passwordKeyboardTagHandling
            )
            
            result.fold(
                onSuccess = { items ->
                    android.util.Log.d("DataImport", "ViewModel收到 ${items.size} 条导入数据")
                    var count = 0
                    var errorCount = 0
                    var skippedCount = 0
                    
                    // Separate items into Passwords and Others
                    val passwordItems = items.filter { ItemType.valueOf(it.itemType) == ItemType.PASSWORD }
                    val otherItems = items.filter { ItemType.valueOf(it.itemType) != ItemType.PASSWORD }
                    
                    val passwordIdMap = mutableMapOf<Long, Long>() // Old ID -> New ID (or existing ID)
                    
                    // 1. Process Passwords First
                    passwordItems.forEach { exportItem ->
                        try {
                            // PASSWORD类型存入PasswordEntry表
                            val passwordData = parsePasswordData(exportItem.itemData)
                            val website = passwordData["website"] ?: ""
                            val username = passwordData["username"] ?: ""
                            val importedPassword = passwordData["password"].orEmpty()
                            val storedPassword = prepareImportedPasswordForStorage(importedPassword)
                            val importedAuthenticator = parseImportedAuthenticatorDraft(
                                rawAuthenticator = exportItem.importedAuthenticatorKey,
                                fallbackTitle = exportItem.title,
                                fallbackWebsite = website,
                                fallbackUsername = username
                            )
                            val originalId = exportItem.id
                            
                            // 来自 Bastion 自身导出的 CSV，直接恢复，跳过重复检测
                            val existingEntry = if (exportItem.isFromAppExport) {
                                null
                            } else {
                                PasswordImportDuplicateResolver.findMatchingEntry(
                                    passwordRepository = passwordRepository,
                                    securityManager = securityManager,
                                    snapshot = ImportedPasswordSnapshot(
                                        title = exportItem.title,
                                        username = username,
                                        website = website,
                                        password = importedPassword,
                                        notes = exportItem.notes,
                                        email = passwordData["email"] ?: "",
                                        phone = passwordData["phone"] ?: "",
                                        authenticatorKey = importedAuthenticator?.authenticatorKey.orEmpty()
                                    ),
                                    localOnly = false
                                )
                            }
                            
                            if (existingEntry == null) {
                                val passwordEntry = PasswordEntry(
                                    id = 0, // 让数据库自动生成新ID
                                    title = exportItem.title,
                                    website = website,
                                    username = username,
                                    password = storedPassword,
                                    notes = exportItem.notes,
                                    email = passwordData["email"] ?: "",
                                    phone = passwordData["phone"] ?: "",
                                    categoryId = exportItem.categoryId,
                                    isFavorite = exportItem.isFavorite,
                                    createdAt = Date(exportItem.createdAt),
                                    updatedAt = Date(exportItem.updatedAt),
                                    keepassDatabaseId = exportItem.keepassDatabaseId,
                                    keepassGroupPath = exportItem.keepassGroupPath,
                                    authenticatorKey = importedAuthenticator?.authenticatorKey.orEmpty(),
                                    bitwardenVaultId = exportItem.bitwardenVaultId,
                                    bitwardenFolderId = exportItem.bitwardenFolderId
                                )
                                val newId = passwordRepository.insertPasswordEntry(passwordEntry)
                                if (originalId > 0 && newId > 0) {
                                    passwordIdMap[originalId] = newId
                                }
                                if (newId > 0 && exportItem.importedCustomFields.isNotEmpty()) {
                                    saveImportedCustomFields(newId, exportItem.importedCustomFields)
                                }
                                if (newId > 0 && importedAuthenticator != null) {
                                    saveImportedAuthenticator(newId, exportItem, importedAuthenticator)
                                }
                                android.util.Log.d("DataImport", "成功插入到PasswordEntry表")
                                count++
                            } else {
                                android.util.Log.d("DataImport", "跳过重复密码")
                                if (originalId > 0) {
                                    passwordIdMap[originalId] = existingEntry.id
                                }
                                skippedCount++
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("DataImport", "插入密码失败: ${e.message}", e)
                        }
                    }
                    
                    // 2. Process Other Items (SecureItems) and update bindings
                    otherItems.forEach { exportItem ->
                        try {
                            android.util.Log.d("DataImport", "处理项类型: ${exportItem.itemType}")
                            val itemType = ItemType.valueOf(exportItem.itemType)
                            
                            // 其他类型存入SecureItem表
                            // 使用智能重复检测：根据类型比较不同的唯一标识字段
                            val existingItem = secureItemRepository.findDuplicateSecureItem(
                                itemType,
                                exportItem.itemData,
                                exportItem.title
                            )
                            val isDuplicate = existingItem != null
                            
                            if (!isDuplicate) {
                                var itemData = exportItem.itemData
                                
                                // Update TOTP binding if applicable
                                if (itemType == ItemType.TOTP) {
                                    try {
                                        val totpData = parseStoredTotpData(itemData, exportItem.title)
                                            ?: throw IllegalArgumentException("Unable to parse TOTP data")
                                        val originalBoundId = totpData.boundPasswordId
                                        if (originalBoundId != null && originalBoundId > 0) {
                                            val newBoundId = passwordIdMap[originalBoundId]
                                            if (newBoundId != null) {
                                                val updatedTotpData = totpData.copy(boundPasswordId = newBoundId)
                                                itemData = encodeTotpDataPreservingStorageShape(itemData, updatedTotpData)
                                                android.util.Log.d("DataImport", "Updated TOTP boundPasswordId from $originalBoundId to $newBoundId")
                                            } else {
                                                android.util.Log.w("DataImport", "Could not map password ID for TOTP: oldId=$originalBoundId")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("DataImport", "Failed to parse/update TOTP data: ${e.message}")
                                    }
                                }
                                
                                val secureItem = SecureItem(
                                    id = 0, // 让数据库自动生成新ID
                                    itemType = itemType,
                                    title = exportItem.title,
                                    itemData = encodeSecureItemDataForLocalStorage(itemType, itemData),
                                    notes = exportItem.notes,
                                    isFavorite = exportItem.isFavorite,
                                    imagePaths = exportItem.imagePaths,
                                    createdAt = Date(exportItem.createdAt),
                                    updatedAt = Date(exportItem.updatedAt),
                                    categoryId = exportItem.categoryId,
                                    keepassDatabaseId = exportItem.keepassDatabaseId,
                                    keepassGroupPath = exportItem.keepassGroupPath,
                                    bitwardenVaultId = exportItem.bitwardenVaultId,
                                    bitwardenFolderId = exportItem.bitwardenFolderId
                                )
                                secureItemRepository.insertItem(secureItem)
                                android.util.Log.d("DataImport", "成功插入到SecureItem表")
                                count++
                            } else {
                                if (itemType == ItemType.NOTE) {
                                    val merged = mergeImportedNoteTagsIfNeeded(
                                        existingItem = existingItem,
                                        incoming = exportItem
                                    )
                                    if (merged) {
                                        android.util.Log.d("DataImport", "重复笔记已合并标签")
                                        count++
                                    } else {
                                        android.util.Log.d("DataImport", "跳过重复项")
                                        skippedCount++
                                    }
                                } else {
                                    android.util.Log.d("DataImport", "跳过重复项")
                                    skippedCount++
                                }
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("DataImport", "插入数据库失败: ${e.message}", e)
                        }
                    }
                    
                    android.util.Log.d("DataImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                    logImportSummary(
                        source = formatHint?.name ?: "CSV_AUTO",
                        importedCount = count,
                        skippedCount = skippedCount,
                        failedCount = errorCount
                    )
                    Result.success(count)
                },
                onFailure = { error ->
                    android.util.Log.e("DataImport", "导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导入KeePass CSV文件
     */
    suspend fun importKeePassCsv(inputUri: Uri): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.KEEPASS_PASSWORD)
    }

    /**
     * 导入Bitwarden CSV文件
     */
    suspend fun importBitwardenCsv(inputUri: Uri): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.BITWARDEN_PASSWORD)
    }

    /**
     * 导入 Proton Pass CSV 文件
     */
    suspend fun importProtonPassCsv(inputUri: Uri): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD)
    }

    /**
     * 导入Chrome CSV文件
     */
    suspend fun importChromeCsv(inputUri: Uri): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.CHROME_PASSWORD)
    }

    /**
     * 导入密码键盘软件 CSV 文件
     */
    suspend fun importPasswordKeyboardCsv(
        inputUri: Uri,
        tagHandling: DataExportImportManager.PasswordKeyboardTagHandling =
            DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
    ): Result<Int> {
        return importData(
            inputUri = inputUri,
            formatHint = DataExportImportManager.CsvFormat.PASSWORD_KEYBOARD,
            passwordKeyboardTagHandling = tagHandling
        )
    }

    private suspend fun saveImportedCustomFields(
        entryId: Long,
        importedFields: List<DataExportImportManager.ImportedCustomField>
    ) {
        val normalized = importedFields
            .map {
                it.copy(
                    title = it.title.trim(),
                    value = it.value.trim()
                )
            }
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.title.lowercase() to it.value }

        if (normalized.isEmpty()) return

        val customFields = normalized.mapIndexed { index, field ->
            CustomField(
                id = 0,
                entryId = entryId,
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }

        try {
            customFieldRepository.insertFields(customFields)
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "保存导入自定义字段失败: entryId=$entryId", e)
        }
    }

    private fun parseImportedAuthenticatorDraft(
        rawAuthenticator: String?,
        fallbackTitle: String,
        fallbackWebsite: String,
        fallbackUsername: String
    ): ImportedAuthenticatorDraft? {
        val raw = rawAuthenticator?.trim().orEmpty()
        if (raw.isBlank()) return null

        val fallbackIssuer = fallbackWebsite.ifBlank { fallbackTitle }
        val fallbackAccount = fallbackUsername.ifBlank { fallbackTitle }

        if (raw.contains("://")) {
            val parsed = TotpUriParser.parseUri(raw)?.totpData
            if (parsed == null) {
                android.util.Log.w("DataImport", "密码键盘验证器 URI 无法解析，已仅保留到密码验证器字段")
                return ImportedAuthenticatorDraft(
                    authenticatorKey = raw,
                    totpData = null
                )
            }

            val normalizedSecret = normalizeTotpSecret(parsed.secret)
            if (normalizedSecret.isBlank()) return null

            return ImportedAuthenticatorDraft(
                authenticatorKey = normalizedSecret,
                totpData = parsed.copy(
                    secret = normalizedSecret,
                    issuer = parsed.issuer.ifBlank { fallbackIssuer },
                    accountName = parsed.accountName.ifBlank { fallbackAccount }
                )
            )
        }

        val normalizedSecret = normalizeTotpSecret(raw)
        if (normalizedSecret.isBlank()) return null

        return ImportedAuthenticatorDraft(
            authenticatorKey = normalizedSecret,
            totpData = TotpData(
                secret = normalizedSecret,
                issuer = fallbackIssuer,
                accountName = fallbackAccount
            )
        )
    }

    private suspend fun saveImportedAuthenticator(
        entryId: Long,
        exportItem: DataExportImportManager.ExportItem,
        importedAuthenticator: ImportedAuthenticatorDraft
    ) {
        val baseTotpData = importedAuthenticator.totpData ?: return
        val totpData = baseTotpData.copy(
            boundPasswordId = entryId,
            categoryId = exportItem.categoryId,
            keepassDatabaseId = exportItem.keepassDatabaseId
        )
        val itemData = Json.encodeToString(totpData)
        val title = buildImportedAuthenticatorTitle(exportItem.title, totpData)

        try {
            val existingItem = secureItemRepository.findDuplicateSecureItem(
                itemType = ItemType.TOTP,
                itemData = itemData,
                title = title
            )
            if (existingItem != null) {
                return
            }

            val secureItem = SecureItem(
                id = 0,
                itemType = ItemType.TOTP,
                title = title,
                itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                notes = "",
                isFavorite = exportItem.isFavorite,
                imagePaths = "",
                createdAt = Date(exportItem.createdAt),
                updatedAt = Date(exportItem.updatedAt),
                categoryId = exportItem.categoryId,
                keepassDatabaseId = exportItem.keepassDatabaseId,
                keepassGroupPath = exportItem.keepassGroupPath,
                bitwardenVaultId = exportItem.bitwardenVaultId,
                bitwardenFolderId = exportItem.bitwardenFolderId
            )
            secureItemRepository.insertItem(secureItem)
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "保存导入验证器失败: entryId=$entryId", e)
        }
    }

    private fun buildImportedAuthenticatorTitle(passwordTitle: String, totpData: TotpData): String {
        val issuer = totpData.issuer.trim()
        val account = totpData.accountName.trim()
        return when {
            issuer.isNotBlank() && account.isNotBlank() -> "$issuer: $account"
            issuer.isNotBlank() -> issuer
            account.isNotBlank() -> account
            else -> passwordTitle
        }
    }

    private fun normalizeTotpSecret(secret: String): String {
        return secret
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .uppercase(Locale.ROOT)
    }

    private suspend fun mergeImportedNoteTagsIfNeeded(
        existingItem: SecureItem,
        incoming: DataExportImportManager.ExportItem
    ): Boolean {
        val incomingDecoded = NoteContentCodec.decode(
            itemData = incoming.itemData,
            fallbackNotes = incoming.notes
        )
        val incomingTags = normalizeNoteTags(incomingDecoded.tags)
        if (incomingTags.isEmpty()) return false

        val existingDecoded = NoteContentCodec.decode(
            itemData = existingItem.itemData,
            fallbackNotes = existingItem.notes
        )
        val existingTags = normalizeNoteTags(existingDecoded.tags)
        val mergedTags = normalizeNoteTags(existingTags + incomingTags)

        if (mergedTags == existingTags) {
            return false
        }

        val encoded = NoteContentCodec.encode(
            content = existingDecoded.content,
            tags = mergedTags,
            isMarkdown = existingDecoded.isMarkdown
        )

        secureItemRepository.updateItem(
            existingItem.copy(
                itemData = encoded.first,
                notes = encoded.second,
                updatedAt = Date()
            )
        )
        return true
    }

    private fun normalizeNoteTags(tags: List<String>): List<String> {
        return tags.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }
    
    /**
     * 解析密码数据字符串
     * 格式: username:xxx;password:xxx;email:xxx;url:xxx
     */
    private fun parsePasswordData(data: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        data.split(";").forEach { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }

    private fun prepareImportedPasswordForStorage(password: String): String {
        if (password.isBlank()) return password
        if (isBastionEncryptedValue(password)) return password
        return securityManager.encryptDataLegacyCompat(password)
    }

    private fun isBastionEncryptedValue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("MDK|") ||
            trimmed.startsWith("V2|") ||
            trimmed.startsWith("C2|")
    }

    /**
     * 导入Aegis JSON文件
     */
    suspend fun importAegisJson(inputUri: Uri): Result<Int> {
        return try {
            // 首先检查是否为加密文件
            val isEncryptedResult = exportManager.isEncryptedAegisFile(inputUri)
            if (isEncryptedResult.getOrDefault(false)) {
                // 如果是加密文件，返回错误提示
                Result.failure(Exception("不支持导入加密的Aegis文件，请选择未加密的JSON文件"))
            } else {
                // 处理未加密的Aegis文件
                val result = exportManager.importAegisJson(inputUri)
                
                result.fold(
                    onSuccess = { entries ->
                        android.util.Log.d("AegisImport", "ViewModel收到 ${entries.size} 条Aegis条目")
                        var count = 0
                        var errorCount = 0
                        var skippedCount = 0
                        
                        entries.forEach { aegisEntry ->
                            try {
                                // 检查是否已存在相同的条目（基于issuer和name）
                                val existingItems = secureItemRepository.getItemsByType(ItemType.TOTP).first()
                                val isDuplicate = existingItems.any { item ->
                                    try {
                                        val totpData = parseStoredTotpData(item) ?: return@any false
                                        totpData.issuer == aegisEntry.issuer && totpData.accountName == aegisEntry.name
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                                
                                if (isDuplicate) {
                                    android.util.Log.d("AegisImport", "跳过重复条目")
                                    skippedCount++
                                } else {
                                    // 创建新的TOTP条目
                                    val totpData = TotpData(
                                        secret = aegisEntry.secret,
                                        issuer = aegisEntry.issuer,
                                        accountName = aegisEntry.name,
                                        period = aegisEntry.period,
                                        digits = aegisEntry.digits,
                                        algorithm = aegisEntry.algorithm
                                    )
                                    
                                    val itemData = Json.encodeToString(totpData)
                                    val title = if (aegisEntry.issuer.isNotBlank()) {
                                        "${aegisEntry.issuer}: ${aegisEntry.name}"
                                    } else {
                                        aegisEntry.name
                                    }
                                    
                                    val secureItem = SecureItem(
                                        id = 0,
                                        itemType = ItemType.TOTP,
                                        title = title,
                                        itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                                        notes = aegisEntry.note,
                                        isFavorite = false,
                                        imagePaths = "",
                                        createdAt = Date(),
                                        updatedAt = Date()
                                    )
                                    
                                    secureItemRepository.insertItem(secureItem)
                                    count++
                                    android.util.Log.d("AegisImport", "成功插入TOTP条目")
                                }
                            } catch (e: Exception) {
                                errorCount++
                                android.util.Log.e("AegisImport", "插入数据库失败: ${e.message}", e)
                            }
                        }
                        
                        android.util.Log.d("AegisImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                        logImportSummary(
                            source = "AEGIS_JSON",
                            importedCount = count,
                            skippedCount = skippedCount,
                            failedCount = errorCount
                        )
                        Result.success(count)
                    },
                    onFailure = { error ->
                        android.util.Log.e("AegisImport", "导入失败: ${error.message}", error)
                        Result.failure(error)
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AegisImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导入加密的Aegis JSON文件
     */
    suspend fun importEncryptedAegisJson(inputUri: Uri, password: String): Result<Int> {
        return try {
            val result = exportManager.importEncryptedAegisJson(inputUri, password)
            
            result.fold(
                onSuccess = { entries ->
                    android.util.Log.d("EncryptedAegisImport", "ViewModel收到 ${entries.size} 条Aegis条目")
                    var count = 0
                    var errorCount = 0
                    var skippedCount = 0
                    
                    entries.forEach { aegisEntry ->
                        try {
                            // 检查是否已存在相同的条目（基于issuer和name）
                            val existingItems = secureItemRepository.getItemsByType(ItemType.TOTP).first()
                            val isDuplicate = existingItems.any { item ->
                                try {
                                    val totpData = parseStoredTotpData(item) ?: return@any false
                                    totpData.issuer == aegisEntry.issuer && totpData.accountName == aegisEntry.name
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            
                            if (isDuplicate) {
                                android.util.Log.d("EncryptedAegisImport", "跳过重复条目")
                                skippedCount++
                            } else {
                                // 创建新的TOTP条目
                                val totpData = TotpData(
                                    secret = aegisEntry.secret,
                                    issuer = aegisEntry.issuer,
                                    accountName = aegisEntry.name,
                                    period = aegisEntry.period,
                                    digits = aegisEntry.digits,
                                    algorithm = aegisEntry.algorithm
                                )
                                
                                val itemData = Json.encodeToString(totpData)
                                val title = if (aegisEntry.issuer.isNotBlank()) {
                                    "${aegisEntry.issuer}: ${aegisEntry.name}"
                                } else {
                                    aegisEntry.name
                                }
                                
                                val secureItem = SecureItem(
                                    id = 0,
                                    itemType = ItemType.TOTP,
                                    title = title,
                                    itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                                    notes = aegisEntry.note,
                                    isFavorite = false,
                                    imagePaths = "",
                                    createdAt = Date(),
                                    updatedAt = Date()
                                )
                                
                                secureItemRepository.insertItem(secureItem)
                                count++
                                android.util.Log.d("EncryptedAegisImport", "成功插入TOTP条目")
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("EncryptedAegisImport", "插入数据库失败: ${e.message}", e)
                        }
                    }
                    
                    android.util.Log.d("EncryptedAegisImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                    logImportSummary(
                        source = "AEGIS_JSON_ENCRYPTED",
                        importedCount = count,
                        skippedCount = skippedCount,
                        failedCount = errorCount
                    )
                    Result.success(count)
                },
                onFailure = { error ->
                    android.util.Log.e("EncryptedAegisImport", "导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("EncryptedAegisImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导出完整备份 (ZIP格式，WebDAV兼容)
     * @param outputUri 导出文件的URI
     * @param preferences 备份偏好设置（选择要导出的内容类型）
     */
    suspend fun prepareZipBackup(preferences: com.bastion.app.data.BackupPreferences = com.bastion.app.data.BackupPreferences()): Result<Pair<File, String>> {
        return try {
            val webDavHelper = com.bastion.app.utils.WebDavHelper(context)

            // 获取所有数据
            val passwordEntries = passwordRepository.getAllPasswordEntries().first()
            val secureItems = secureItemRepository.getAllItems().first()
            val exportedPasswords = passwordEntries.map { entry ->
                val exportedPassword = runCatchingObserved { securityManager.decryptData(entry.password) }
                    .getOrElse { error ->
                        android.util.Log.w(
                            "DataExport",
                            "Failed to decrypt password for ZIP export: ${entry.title} (${error.message})"
                        )
                        entry.password
                    }
                entry.copy(password = exportedPassword)
            }
            
            // 创建ZIP备份，使用传入的偏好设置
            val result = webDavHelper.createBackupZip(
                passwords = exportedPasswords,
                secureItems = secureItems,
                preferences = preferences,
                contentScope = BackupContentScope.ALL_OFFLINE,
                allowBackupEncryption = false
            )

            result.fold(
                onSuccess = { pair ->
                    val (zipFile, report) = pair
                    try {
                        if (!report.success) {
                            // 如果有失败项，但还是生成了文件，可能需要警告用户
                            // 这里我们记录警告但继续导出
                            android.util.Log.w("DataExport", "Backup report has failures: ${report.failedItems.size}")
                        }

                        validatePlainZipFile(zipFile)
                        Result.success(zipFile to zipBackupExportMessage(report))
                    } catch (error: Throwable) {
                        zipFile.delete()
                        Result.failure(error)
                    }
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DataExport", "创建ZIP失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun writePreparedZipBackup(
        outputUri: Uri,
        zipFile: File,
        successMessage: String
    ): Result<String> {
        return try {
            copyZipFileToOutputUri(zipFile, outputUri)
            Result.success(successMessage)
        } catch (e: Exception) {
            android.util.Log.e("DataExport", "写入ZIP失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun exportZipBackup(outputUri: Uri, preferences: com.bastion.app.data.BackupPreferences = com.bastion.app.data.BackupPreferences()): Result<String> {
        var preparedFile: File? = null
        return try {
            val (zipFile, message) = prepareZipBackup(preferences).getOrThrow()
            preparedFile = zipFile
            writePreparedZipBackup(outputUri, zipFile, message)
        } catch (e: Exception) {
            android.util.Log.e("DataExport", "导出ZIP失败: ${e.message}", e)
            Result.failure(e)
        } finally {
            preparedFile?.delete()
        }
    }

    /**
     * 导出 Bitwarden 兼容的明文 JSON（可被 Bitwarden 官方导入，或供 bw2keepass 转换）。
     * @param outputUri 导出文件 URI
     */
    suspend fun exportBitwardenJson(outputUri: Uri): Result<String> {
        return try {
            val passwordEntries = passwordRepository.getAllPasswordEntries().first()
            val secureItems = secureItemRepository.getAllItems().first()
            val categories = passwordRepository.getAllCategories().first()

            val plain = BitwardenJsonExporter(securityManager, categories)
                .buildPlainExport(passwordEntries, secureItems)
            val jsonString = bitwardenExportJson.encodeToString(plain)

            writeTextToUri(jsonString, outputUri)
            Result.success(context.getString(R.string.export_success_bitwarden_json))
        } catch (e: Exception) {
            android.util.Log.e("DataExport", "导出 Bitwarden JSON 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导出 Bitwarden 兼容的加密 JSON（密码保护信封，结构与 bw2keepass 对称）。
     * @param outputUri 导出文件 URI
     * @param exportPassword 导出密码（用于加密整个导出）
     */
    suspend fun exportEncryptedBitwardenJson(
        outputUri: Uri,
        exportPassword: String
    ): Result<String> {
        return try {
            val passwordEntries = passwordRepository.getAllPasswordEntries().first()
            val secureItems = secureItemRepository.getAllItems().first()
            val categories = passwordRepository.getAllCategories().first()

            val exporter = BitwardenJsonExporter(securityManager, categories)
            val plain = exporter.buildPlainExport(passwordEntries, secureItems)
            val encrypted = exporter.encryptExport(plain, exportPassword)
            val jsonString = bitwardenExportJson.encodeToString(encrypted)

            writeTextToUri(jsonString, outputUri)
            Result.success(context.getString(R.string.export_success_bitwarden_encrypted_json))
        } catch (e: Exception) {
            android.util.Log.e("DataExport", "导出加密 Bitwarden JSON 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun writeTextToUri(text: String, uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("无法写入导出文件")
        }
    }

    /**
     * 导入完整备份 (ZIP格式)
     * @param inputUri 用户选择的ZIP文件URI
     */
    suspend fun importZipBackup(inputUri: Uri, decryptPassword: String? = null): Result<Int> {
        return try {
            val webDavHelper = com.bastion.app.utils.WebDavHelper(context)
            
            // 1. 将Uri内容复制到临时文件
            val tempFile = java.io.File(context.cacheDir, "import_temp_${System.nanoTime()}.zip")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(Exception("无法读取选定的文件"))
            
            try {
                // 2. 调用 restoreFromBackupFile 解析备份
                val result = webDavHelper.restoreFromBackupFile(tempFile, decryptPassword)
                
                result.fold(
                    onSuccess = { restoreResult ->
                        val stats = BackupRestoreApplier.applyRestoreResult(
                            context = context,
                            restoreResult = restoreResult,
                            passwordRepository = passwordRepository,
                            secureItemRepository = secureItemRepository,
                            localOnlyDedup = true,
                            logTag = "DataImport"
                        )
                        logImportSummary(
                            source = "ZIP_BACKUP",
                            importedCount = stats.totalImported()
                        )
                        Result.success(stats.totalImported())
                    },
                    onFailure = { error ->
                        // 如果是密码错误，抛出特定的异常以便UI处理
                        if (error is com.bastion.app.utils.WebDavHelper.PasswordRequiredException) {
                            return Result.failure(error)
                        }
                        Result.failure(error)
                    }
                )
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "导入ZIP失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ==================== Stratum Auth Import ====================
    
    suspend fun isStratumFileEncrypted(inputUri: Uri): Boolean {
        return exportManager.isStratumFileEncrypted(inputUri).getOrDefault(false)
    }
    
    suspend fun importStratum(inputUri: Uri, password: String? = null): Result<Int> {
        return try {
            val fileType = exportManager.detectStratumFileType(inputUri).getOrNull()
                ?: return Result.failure(Exception("Cannot detect file type"))
            val entriesResult = when (fileType) {
                com.bastion.app.util.StratumDecryptor.StratumFileType.MODERN_ENCRYPTED,
                com.bastion.app.util.StratumDecryptor.StratumFileType.LEGACY_ENCRYPTED -> {
                    if (password.isNullOrEmpty()) return Result.failure(Exception("Password required"))
                    exportManager.importEncryptedStratum(inputUri, password)
                }
                com.bastion.app.util.StratumDecryptor.StratumFileType.UNENCRYPTED -> exportManager.importStratumJson(inputUri)
                com.bastion.app.util.StratumDecryptor.StratumFileType.NOT_STRATUM -> {
                    val txtResult = exportManager.importStratumTxt(inputUri)
                    if (txtResult.isSuccess) {
                        txtResult
                    } else {
                        exportManager.importStratumHtml(inputUri)
                    }
                }
            }
            entriesResult.fold(
                onSuccess = { list -> insertTotpEntries(list) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) { Result.failure(e) }
    }
    
    suspend fun importStratumTxt(inputUri: Uri): Result<Int> {
        return try {
            exportManager.importStratumTxt(inputUri).fold(onSuccess = { insertTotpEntries(it) }, onFailure = { Result.failure(it) })
        } catch (e: Exception) { Result.failure(e) }
    }
    
    suspend fun importStratumHtml(inputUri: Uri): Result<Int> {
        return try {
            exportManager.importStratumHtml(inputUri).fold(onSuccess = { insertTotpEntries(it) }, onFailure = { Result.failure(it) })
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private suspend fun insertTotpEntries(entries: List<DataExportImportManager.AegisEntry>): Result<Int> {
        var count = 0
        var skippedCount = 0
        var failedCount = 0
        val existingItems = secureItemRepository.getAllItems().first().filter { it.itemType == ItemType.TOTP }
        val existingSecrets = existingItems.mapNotNull { parseStoredTotpData(it)?.secret }.toSet()
        for (entry in entries) {
            try {
                if (entry.secret in existingSecrets) {
                    skippedCount++
                    continue
                }
                val totpData = TotpData(secret = entry.secret, issuer = entry.issuer, accountName = entry.name, digits = entry.digits, period = entry.period, algorithm = entry.algorithm)
                val itemData = Json.encodeToString(totpData)
                val item = SecureItem(id = 0, itemType = ItemType.TOTP, title = entry.issuer.ifBlank { entry.name }, itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData), notes = entry.note, isFavorite = false, imagePaths = "", createdAt = Date(), updatedAt = Date(), categoryId = null, keepassDatabaseId = null, keepassGroupPath = null, bitwardenVaultId = null, bitwardenFolderId = null)
                secureItemRepository.insertItem(item)
                count++
            } catch (e: Exception) {
                failedCount++
            }
        }
        logImportSummary(
            source = "STRATUM_TOTP",
            importedCount = count,
            skippedCount = skippedCount,
            failedCount = failedCount
        )
        return Result.success(count)
    }

    private fun logImportSummary(
        source: String,
        importedCount: Int,
        skippedCount: Int = 0,
        failedCount: Int = 0
    ) {
        if (importedCount <= 0 && skippedCount <= 0 && failedCount <= 0) return

        val details = mutableListOf(
            FieldChange(
                fieldName = context.getString(R.string.import_data),
                oldValue = source,
                newValue = source
            ),
            FieldChange(
                fieldName = "Imported",
                oldValue = "0",
                newValue = importedCount.toString()
            )
        )
        if (skippedCount > 0) {
            details += FieldChange(
                fieldName = "Skipped",
                oldValue = "0",
                newValue = skippedCount.toString()
            )
        }
        if (failedCount > 0) {
            details += FieldChange(
                fieldName = "Failed",
                oldValue = "0",
                newValue = failedCount.toString()
            )
        }

        OperationLogger.logCreate(
            itemType = OperationLogItemType.CATEGORY,
            itemId = System.currentTimeMillis(),
            itemTitle = "${context.getString(R.string.import_data)} · $source",
            details = details
        )
    }

}
