package com.bastion.app.repository

// 从 MdbxVaultStore.kt 抽出的纯定义（行为保持型重构第一步）。
// 这些为顶层声明，原文件内对它们的引用因同包可见而无需改动 import。
// 常量可见性由 private 放宽至 internal，供同模块（含原文件）访问，行为不变。

internal const val MDBX_SCHEMA_FORMAT_VERSION = "MDBX-1"
internal const val MDBX_LEGACY_DRAFT_FORMAT_VERSION = "MDBX-1-DRAFT"
internal const val MDBX_OFFICIAL_RELEASE_LABEL = "MDBX-1.0"
internal const val MDBX_ANDROID_CAPABILITY_FLAGS =
    "android-official-1.0,sky-portable,tiga-selectable,legacy-test-compatible"

data class MdbxVaultDiagnostics(
    val databaseId: Long,
    val filePath: String?,
    val fileExists: Boolean,
    val fileSizeBytes: Long,
    val isReadable: Boolean,
    val currentDeviceId: String? = null,
    val unavailableReason: String? = null,
    val formatVersion: String? = null,
    val releaseLabel: String? = null,
    val capabilityFlags: String? = null,
    val defaultTigaMode: String? = null,
    val integrityOk: Boolean = false,
    val integrityMessage: String? = null,
    val unresolvedConflictCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val commitCount: Int = 0,
    val tombstoneCount: Int = 0,
    val branchCount: Int = 0,
    val deviceCount: Int = 0,
    val snapshotCount: Int = 0,
    val folderCount: Int = 0,
    val indexedObjectCount: Int = 0,
    val entryCount: Int = 0,
    val deletedEntryCount: Int = 0,
    val attachmentCount: Int = 0,
    val externalAttachmentCount: Int = 0,
    val originalAttachmentBytes: Long = 0,
    val storedAttachmentBytes: Long = 0,
    val danglingParentCount: Int = 0,
    val danglingBranchHeadCount: Int = 0,
    val danglingDeviceHeadCount: Int = 0,
    val attachmentChunkMismatchCount: Int = 0,
    val lastSyncStatus: String,
    val lastSyncError: String? = null
) {
    val structuralIssueCount: Int
        get() = danglingParentCount + danglingBranchHeadCount +
            danglingDeviceHeadCount + attachmentChunkMismatchCount

    val healthIssueCount: Int
        get() = (if (!integrityOk) 1 else 0) + structuralIssueCount +
            (if (!isReadable) 1 else 0)
}

data class MdbxConflictSummary(
    val conflictId: String,
    val objectType: String,
    val objectId: String,
    val baseCommitId: String,
    val localCommitId: String,
    val incomingCommitId: String,
    val conflictingFields: String,
    val createdAt: String,
    val localTitle: String? = null,
    val incomingTitle: String? = null,
    val localPayloadPreview: String? = null,
    val incomingPayloadPreview: String? = null
)
