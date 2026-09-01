package com.bastion.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bastion.app.R

/**
 * 一项可勾选的快速筛选条目。labelRes 是显示文本（已为多语言字符串资源）；
 * icon 是该项的前置图标（沿用密码页 chip 横排的图标，便于用户识别）。
 */
data class VaultQuickFilterMenuItem(
    val key: String,
    val labelRes: Int,
    val icon: ImageVector,
    val selected: Boolean,
    val onToggle: (Boolean) -> Unit
)

/**
 * 「点密码页标题弹出的快速筛选菜单」。
 *
 * 设计要点：
 *  - 多选 toggle 语义：和原 chip 横排完全一致，关闭后切回横排不丢状态。
 *  - DropdownMenu 由调用方控制 expanded / onDismissRequest（与 ExpressiveTopBar 的 onTitleClick 配对）。
 *  - 排序：调用方传入顺序（建议按「高频在前、类型在后」排）。
 *  - 与 EntryTypeChip 的不同点：EntryTypeChip 是单选切换条目类型（用于添加/编辑页），
 *    这里强调「筛选叠加」，所以保留 Check + 多选。
 *
 * 实验功能：受 AppSettings.experimentalCollapsedQuickFilters 控制。默认关闭，
 * 关闭时 PasswordListScrollableContent 仍渲染 chip 横排；打开后由
 * PasswordListTopSection 挂出本组件，同时 chip 横排隐藏。
 */
@Composable
fun VaultQuickFiltersMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<VaultQuickFilterMenuItem>,
    modifier: Modifier = Modifier,
    headerLabel: String? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.large
    ) {
        if (headerLabel != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items.forEach { item ->
            val label = stringResource(item.labelRes)
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (item.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                },
                text = {
                    Text(
                        text = label,
                        color = if (item.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                trailingIcon = if (item.selected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                onClick = {
                    // 切换后保持菜单打开，便于连续勾选多个；
                    // 用户点外部或外部主动调 onDismissRequest 才关闭。
                    item.onToggle(!item.selected)
                }
            )
        }
    }
}