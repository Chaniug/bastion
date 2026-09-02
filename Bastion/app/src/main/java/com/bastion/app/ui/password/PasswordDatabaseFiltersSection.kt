package com.bastion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.writeOperationAvailability
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.viewmodel.CategoryFilter

/** 各存储来源的密码条数统计（全部 / 本地 Bastion / 每个库）。 */
internal data class PasswordStorageCounts(
    val total: Int,
    val bastionLocal: Int,
    val perKeePassDatabase: Map<Long, Int>,
    val perBitwardenVault: Map<Long, Int>
)

internal data class PasswordDatabaseFiltersSectionParams(
    val currentFilter: CategoryFilter,
    val keepassDatabases: List<LocalKeePassDatabase>,
    val bitwardenVaults: List<BitwardenVault>,
    val onSelectFilter: (CategoryFilter) -> Unit,
    /** 为 null 时不显示条数徽标（调用方未提供统计）。 */
    val entryCounts: PasswordStorageCounts? = null
)

/**
 * 数据库筛选区块。在方案 A 中由顶部 Tab 驱动展开：选中"数据库"Tab 时
 * [expanded] 为 true 显示内容，其余 Tab 时仅保留静态标题。
 *
 * 布局为统计行式（图标 + 名称 + 条数徽标）：分类筛选移至横排后本区块空间充裕，
 * 行式排版比原先的 All/Bastion/Bitwarden 三个矮 chip 更统一，且能展示每个库的密码数。
 */
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
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(120)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(100))
        ) {
            DatabaseFilterRows(params = params)
        }
    }
}

@Composable
private fun DatabaseFilterRows(params: PasswordDatabaseFiltersSectionParams) {
    val counts = params.entryCounts
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DatabaseStatRow(
            icon = Icons.Default.List,
            title = stringResource(R.string.category_all),
            count = counts?.total,
            selected = params.currentFilter is CategoryFilter.All,
            onClick = { params.onSelectFilter(CategoryFilter.All) }
        )
        DatabaseStatRow(
            icon = Icons.Default.Smartphone,
            title = stringResource(R.string.category_selection_menu_local_database),
            count = counts?.bastionLocal,
            selected = params.currentFilter.isBastionDatabaseFilter(),
            onClick = { params.onSelectFilter(CategoryFilter.Local) }
        )
        params.keepassDatabases.forEach { database ->
            DatabaseStatRow(
                icon = Icons.Default.Key,
                title = database.name,
                count = counts?.perKeePassDatabase?.get(database.id),
                selected = params.currentFilter.isKeePassDatabaseFilter(database.id),
                onClick = { params.onSelectFilter(CategoryFilter.KeePassDatabase(database.id)) },
                statusDotColor = if (database.writeOperationAvailability().canOperate) {
                    StorageHealthyGreen
                } else {
                    null
                }
            )
        }
        params.bitwardenVaults.forEach { vault ->
            DatabaseStatRow(
                icon = Icons.Default.CloudSync,
                title = vault.email.ifBlank { "Bitwarden" },
                count = counts?.perBitwardenVault?.get(vault.id),
                selected = params.currentFilter.isBitwardenVaultFilter(vault.id),
                onClick = { params.onSelectFilter(CategoryFilter.BitwardenVault(vault.id)) },
                statusDotColor = if (vault.hasHealthyConnection()) StorageHealthyGreen else null
            )
        }
    }
}

@Composable
private fun DatabaseStatRow(
    icon: ImageVector,
    title: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusDotColor: Color? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (statusDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusDotColor, CircleShape)
                )
            }
            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        text = stringResource(R.string.database_entry_count, count),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
