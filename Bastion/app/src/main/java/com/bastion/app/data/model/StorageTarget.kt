package com.bastion.app.data.model

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem

sealed interface StorageTarget {
    val stableKey: String

    data class BastionLocal(val categoryId: Long?) : StorageTarget {
        override val stableKey: String = "local:${categoryId ?: "root"}"
    }

    data class KeePass(
        val databaseId: Long,
        val groupPath: String?
    ) : StorageTarget {
        override val stableKey: String = "keepass:$databaseId:${groupPath.orEmpty()}"
    }

    data class Bitwarden(
        val vaultId: Long,
        val folderId: String?
    ) : StorageTarget {
        override val stableKey: String = "bitwarden:$vaultId:${folderId.orEmpty()}"
    }

}

fun StorageTarget.storageScopeKey(): String = when (this) {
    is StorageTarget.BastionLocal -> "local"
    is StorageTarget.KeePass -> "keepass:$databaseId"
    is StorageTarget.Bitwarden -> "bitwarden:$vaultId"
}

fun StorageTarget.uncategorizedPeer(): StorageTarget = when (this) {
    is StorageTarget.BastionLocal -> StorageTarget.BastionLocal(null)
    is StorageTarget.KeePass -> StorageTarget.KeePass(databaseId, null)
    is StorageTarget.Bitwarden -> StorageTarget.Bitwarden(vaultId, null)
}

fun StorageTarget.isUncategorizedTarget(): Boolean = when (this) {
    is StorageTarget.BastionLocal -> categoryId == null
    is StorageTarget.KeePass -> groupPath.isNullOrBlank()
    is StorageTarget.Bitwarden -> folderId.isNullOrBlank()
}

fun List<StorageTarget>.normalizedStorageTargets(
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null)
): List<StorageTarget> = distinctBy(StorageTarget::stableKey).ifEmpty { listOf(defaultTarget) }

fun List<StorageTarget>.withStorageTargetSelected(
    target: StorageTarget,
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null)
): List<StorageTarget> {
    val current = normalizedStorageTargets(defaultTarget)
    if (current.any { it.stableKey == target.stableKey }) return current
    val withoutImplicitFallback = if (target.isUncategorizedTarget()) {
        current
    } else {
        current.filterNot {
            it.storageScopeKey() == target.storageScopeKey() && it.isUncategorizedTarget()
        }
    }
    return (withoutImplicitFallback + target).normalizedStorageTargets(defaultTarget)
}

fun List<StorageTarget>.withoutStorageTarget(
    target: StorageTarget,
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null)
): List<StorageTarget> {
    val current = normalizedStorageTargets(defaultTarget)
    val remaining = current.filterNot { it.stableKey == target.stableKey }
    if (remaining.size == current.size) return current
    val hasSameScopeTarget = remaining.any { it.storageScopeKey() == target.storageScopeKey() }
    val fallback = target.uncategorizedPeer()
    val next = when {
        hasSameScopeTarget -> remaining
        fallback.stableKey != target.stableKey -> remaining + fallback
        else -> remaining
    }
    return next.normalizedStorageTargets(defaultTarget)
}

fun PasswordEntry.toStorageTarget(): StorageTarget = when {
    bitwardenVaultId != null -> StorageTarget.Bitwarden(
        vaultId = bitwardenVaultId,
        folderId = bitwardenFolderId
    )
    keepassDatabaseId != null -> StorageTarget.KeePass(
        databaseId = keepassDatabaseId,
        groupPath = keepassGroupPath
    )
    else -> StorageTarget.BastionLocal(categoryId = categoryId)
}

fun SecureItem.toStorageTarget(): StorageTarget = when {
    bitwardenVaultId != null -> StorageTarget.Bitwarden(
        vaultId = bitwardenVaultId,
        folderId = bitwardenFolderId
    )
    keepassDatabaseId != null -> StorageTarget.KeePass(
        databaseId = keepassDatabaseId,
        groupPath = keepassGroupPath
    )
    else -> StorageTarget.BastionLocal(categoryId = categoryId)
}

fun StorageTarget.applyToPasswordEntry(
    entry: PasswordEntry,
    replicaGroupId: String? = entry.replicaGroupId
): PasswordEntry {
    return when (this) {
        is StorageTarget.BastionLocal -> entry.copy(
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.KeePass -> entry.copy(
            categoryId = null,
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.Bitwarden -> entry.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
    }
}

fun StorageTarget.applyToSecureItem(
    item: SecureItem,
    replicaGroupId: String? = item.replicaGroupId
): SecureItem {
    return when (this) {
        is StorageTarget.BastionLocal -> item.copy(
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "NONE",
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.KeePass -> item.copy(
            categoryId = null,
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "NONE",
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.Bitwarden -> item.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "PENDING",
            replicaGroupId = replicaGroupId
        )
    }
}
