package com.bastion.app.data

import java.util.Date

/**
 * 密码条目列表展示用的投影 POJO（架构升级 C.3）。
 *
 * 仅包含列表卡片 / 搜索结果所需的轻量列，刻意排除以下大字段，以降低大 vault 首屏查询与内存开销：
 * - `password`（加密，通常较大）
 * - `sshKeyData` / `wifiMetadata` / `passkeyBindings`（JSON 元数据）
 * - `creditCardNumber` / `creditCardCVV` / `creditCardHolder` / `creditCardExpiry`（加密支付信息）
 * - Phase7 PII：`email` / `phone` / `addressLine` / `city` / `state` / `zipCode` / `country`
 * - `ssoRefEntryId` / `replicaGroupId` / `boundNoteId`（列表展示不依赖）
 *
 * 列表点击进详情时，仍按 [PasswordEntry.id] 走 `getPasswordEntryById` 取完整实体。
 * 全 `val` 不可变，为 C.6 的 `@Immutable` 标注做准备。
 *
 * 投影列必须与列表 UI 实际读取字段保持一致（见 [com.bastion.app.ui.password.PasswordEntryCard]
 * 与 [com.bastion.app.ui.password.resolvePasswordCardDisplayLines]）：
 * id / title / website / username / notes / updatedAt / createdAt / isFavorite / isGroupCover /
 * appPackageName / appName / categoryId / keepassDatabaseId / authenticatorKey / loginType /
 * ssoProvider / customIconType / customIconValue / bitwardenVaultId / bitwardenLocalModified /
 * isDeleted / isArchived / sortOrder。
 */
data class PasswordEntryListItem(
    val id: Long,
    val title: String,
    val website: String,
    val username: String,
    val notes: String,
    val updatedAt: Date,
    val createdAt: Date,
    val isFavorite: Boolean,
    val isGroupCover: Boolean,
    val appPackageName: String,
    val appName: String,
    val categoryId: Long?,
    val keepassDatabaseId: Long?,
    val authenticatorKey: String,
    val loginType: String,
    val ssoProvider: String,
    val customIconType: String,
    val customIconValue: String?,
    val bitwardenVaultId: Long?,
    val bitwardenLocalModified: Boolean,
    val isDeleted: Boolean,
    val isArchived: Boolean,
    val sortOrder: Int
)
