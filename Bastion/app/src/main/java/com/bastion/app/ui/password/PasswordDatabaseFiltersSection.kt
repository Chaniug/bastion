package com.bastion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.writeOperationAvailability
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.ui.components.BastionExpressiveFilterChip
import com.bastion.app.viewmodel.CategoryFilter

internal data class PasswordDatabaseFiltersSectionParams(
    val currentFilter: CategoryFilter,
    val keepassDatabases: List<LocalKeePassDatabase>,
    val bitwardenVaults: List<BitwardenVault>,
    val onSelectFilter: (CategoryFilter) -> Unit
)

/**
 * 数据库筛选区块。在方案 A 中由顶部 Tab 驱动展开：选中"数据库"Tab 时
 * [expanded] 为 true 显示内容，其余 Tab 时仅保留静态标题。不再提供独立的
 * 折叠箭头（与 Tab 语义冲突、且原先被 expandedOverride 盖住导致点击无效）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordDatabaseFiltersSection(
    params: PasswordDatabaseFiltersSectionParams,
    modifier: Modifier = Modifier,
    expanded: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.category_selection_menu_databases),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(120)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(100))
        ) {
            DatabaseFilterChips(params = params)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DatabaseFilterChips(params: PasswordDatabaseFiltersSectionParams) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BastionExpressiveFilterChip(
            selected = params.currentFilter is CategoryFilter.All,
            onClick = { params.onSelectFilter(CategoryFilter.All) },
            label = stringResource(R.string.category_all),
            leadingIcon = Icons.Default.List
        )
        BastionExpressiveFilterChip(
            selected = params.currentFilter.isBastionDatabaseFilter(),
            onClick = { params.onSelectFilter(CategoryFilter.Local) },
            label = stringResource(R.string.category_selection_menu_local_database),
            leadingIcon = Icons.Default.Smartphone
        )
        params.keepassDatabases.forEach { database ->
            BastionExpressiveFilterChip(
                selected = params.currentFilter.isKeePassDatabaseFilter(database.id),
                onClick = { params.onSelectFilter(CategoryFilter.KeePassDatabase(database.id)) },
                label = database.name,
                leadingIcon = Icons.Default.Key,
                statusDotColor = if (database.writeOperationAvailability().canOperate) {
                    StorageHealthyGreen
                } else {
                    null
                }
            )
        }
        params.bitwardenVaults.forEach { vault ->
            BastionExpressiveFilterChip(
                selected = params.currentFilter.isBitwardenVaultFilter(vault.id),
                onClick = { params.onSelectFilter(CategoryFilter.BitwardenVault(vault.id)) },
                label = vault.email.ifBlank { "Bitwarden" },
                leadingIcon = Icons.Default.CloudSync,
                statusDotColor = if (vault.hasHealthyConnection()) StorageHealthyGreen else null
            )
        }
    }
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
