package com.bastion.app.viewmodel

sealed class CategoryFilter {
    object All : CategoryFilter()
    object Archived : CategoryFilter()
    object Local : CategoryFilter() // Pure local view (Bastion)
    object LocalOnly : CategoryFilter() // Local entries that have no matching item in Bitwarden
    object Starred : CategoryFilter()
    object Uncategorized : CategoryFilter()
    object LocalStarred : CategoryFilter()
    object LocalUncategorized : CategoryFilter()
    data class Custom(val categoryId: Long) : CategoryFilter()
    data class KeePassDatabase(val databaseId: Long) : CategoryFilter()
    data class KeePassGroupFilter(val databaseId: Long, val groupPath: String) : CategoryFilter()
    data class KeePassDatabaseStarred(val databaseId: Long) : CategoryFilter()
    data class KeePassDatabaseUncategorized(val databaseId: Long) : CategoryFilter()
    data class BitwardenVault(val vaultId: Long) : CategoryFilter()
    data class BitwardenFolderFilter(val folderId: String, val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultStarred(val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultUncategorized(val vaultId: Long) : CategoryFilter()
}

internal class PasswordArchiveFilterController {
    private var returnFilter: CategoryFilter? = null

    fun open(currentFilter: CategoryFilter): CategoryFilter {
        if (currentFilter !is CategoryFilter.Archived) {
            returnFilter = currentFilter
        }
        return CategoryFilter.Archived
    }

    fun close(): CategoryFilter {
        val target = returnFilter ?: CategoryFilter.All
        returnFilter = null
        return target
    }

    fun clear() {
        returnFilter = null
    }
}
