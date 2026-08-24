# Passkey 创建页"移动到分类"卡片点击失效 — 修复计划

## 1. 现象（用户实机反馈）

- **页面**：`PasskeyCreateActivity` 创建通行密钥确认页（用户口语"保存通行密钥"）
- **位置**：下方"移动到分类 / 仅 Bastion 本地存储 / 无分类" ElevatedCard
- **症状**：点不动，分类选择 bottom sheet 不弹出
- **截图**：移动到分类卡片视觉完整，下方有大块空白（bottomBar 视觉区）

## 2. 代码现状

`PasskeyCreateActivity.kt:1469-1520`：

```kotlin
ElevatedCard(
    onClick = { showStoragePicker = true },
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), ...) {
        // 文件夹图标 + 标题/副标题 + 右侧 UnfoldMore 图标
    }
}
```

- `onClick` 写法本身**正确**（ElevatedCard 带 onClick 的重载）
- `showStoragePicker` 状态、`UnifiedMoveToCategoryBottomSheet` 调用链都正常
- `PasskeyCreateScreen` 外层 `Column.verticalScroll(rememberScrollState())` 也正常

## 3. 根因（最可能）

1. **样式与项目主流不一致**：项目内带 onClick 的可点击卡片**主流用 `Surface(onClick=...)`**，仅 `ExportOptionCard` 与本处用 `ElevatedCard(onClick)`。`ElevatedCard(onClick)` 没有 `@OptIn(ExperimentalMaterial3Api::class)`，运行时虽能编译，但在 Compose 1.6+ 某些场景下 onClick 内部 clickable modifier 不一定可靠附加（具体与 Compose 版本相关）。
2. **点击区域视觉模糊**：卡片下方到 bottomBar 有大段空白，用户可能误把空白当卡片扩展区触摸。
3. **缺少 ripple 视觉反馈**：用户按下去没看到明显涟漪反馈，怀疑"点不动"。

## 4. 修复方案

| 方案 | 改动 | 风险 | 收益 |
|---|---|---|---|
| **A（最小推荐）** | `ElevatedCard` → `Surface(onClick, shape, color, tonalElevation=1.dp)`，统一项目风格；加 `Modifier.heightIn(min = 72.dp)` 保最小触摸高度 | 低（API 同形替换） | 中（提高点击可靠性 + 与项目其他可点击卡片一致） |
| **B（增强）** | A + 改右侧图标 `Icons.Default.UnfoldMore` → `Icons.AutoMirrored.Filled.KeyboardArrowDown`（更符合"打开选择器"语义）；加 `Modifier.semantics { role = Role.Button }` 改善无障碍 | 低 | 中（视觉更明显，无障碍更友好） |
| **C（彻底重做）** | B + 把卡片改成完整的 `ListItem(headline + supporting + trailing = Icon)` Material3 组件，加 `Modifier.clickable`，加 `InteractionSource` 显式 ripple | 中 | 高（语义最清晰、ripple 视觉最强，但改动量较大） |

## 5. 涉及文件

- `Bastion/app/src/main/java/com/bastion/app/passkey/PasskeyCreateActivity.kt` line 1469-1520（主改）
- `strings.xml` 视方案 B/C 可能调整 `R.string.move_to_category` 附近文案（不改）
- 不改 `UnifiedMoveToCategoryBottomSheet.kt`、不改 `build.gradle` Compose 版本

## 6. 验证方式

1. dev 分支构建 APK，真机（荣耀 Android 17）测试：
   - 点"移动到分类"卡片：应弹出 UnifiedMoveToCategoryBottomSheet
   - 点空白区域：应无反应（属正常）
   - 长按卡片：应显示 ripple 涟漪
2. CI（CodeQL + main.yml）全绿后合 main
3. 回归测试创建流程：选 KeePass / Bitwarden / Bastion 分类三类目标，确认 `onCategorySelected` 回调正常

## 7. 风险与回滚

- 风险：低（仅样式改写，不改业务逻辑）
- 回滚：单 commit revert 即可，无数据库 schema / 协议变更

## 8. 等待用户确认

按规范 5 重点改动先确认，本计划已落到 docs/，待用户选定方案 A/B/C 后在 dev 上实施。
