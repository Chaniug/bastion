# 安卓端 PluralsCandidate 细化方案（P3-d）

> 状态：**方案待确认**（未实施）
> 分支流程：dev → `Android CI debug` → 真机验收 → 合 main
> 关联：`docs/安卓端UI优化计划.md`（lint 清理，已全部完成）、`docs/安卓端界面视觉优化计划.md`（P4 视觉打磨，B1/B2 已完成）

## 1. 背景与目的

lint 报告 **PluralsCandidate 89 处**：字符串里 `%d`/`%1$d` 后跟英文名词（如 `%d passwords`、`%1$d items`），英语需要单复数变化（"1 password" vs "2 passwords"）。当前用普通 `<string>` 无法区分单复数，英文环境下文案不地道。

**目标**：把这 89 处数量文案改成 `<plurals>` 资源，让英文正确单复数化；中文（values-zh）无语法复数，保持单一形式。

## 2. 现状清单（89 处，全部来自 `lint-baseline.xml`）

按语义分组：

| 组 | 示例 | 条数 |
|----|------|------|
| 删除/批量操作确认 | `batch_delete_message`（Delete %d items?）、`batch_delete_passwords_message` 等 | ~12 |
| 成功/结果提示 | `import_data_success_normal`、`batch_stack_success`、`deleted_items` 等 | ~18 |
| 统计/列表计数 | `passwords_count`、`common_count_items`、`security_issue_simple_count_subtitle` 等 | ~20 |
| 回收站/时间线 | `trash_day_format`、`timeline_deleted_items_count`、`timeline_restore_snapshot_result` 等 | ~12 |
| 去重/维护引擎 | `dedup_engine_cluster_count`、`quick_database_maintenance_stats_desc` 等 | ~10 |
| 导入/WebDAV/Keepass | `webdav_restore_summary_part_passwords`、`import_data_kdbx_import_success_count` 等 | ~12 |
| 其他 | `length_chars`、`passkey_used_count`、`custom_icon_load_more` 等 | ~5 |

完整 89 条 string 名在 `lint-baseline.xml`（id=PluralsCandidate）可查，实施时逐条核对。

## 3. 改造方案

### 3.1 资源层（strings.xml → plurals）

把形如 `%d passwords` 的字符串改成：

```xml
<!-- 改前 -->
<string name="passwords_count">%d passwords</string>
<!-- 改后 -->
<plurals name="passwords_count">
    <item quantity="one">%d password</item>
    <item quantity="other">%d passwords</item>
</plurals>
```

- **格式串保留占位**：`%d` / `%1$d` 原样保留（多参数如 `%1$d of %2$d` 也保留）。
- **values-zh**：中文无复数语法，统一写：
  ```xml
  <plurals name="passwords_count">
      <item quantity="other">%d 个密码</item>
  </plurals>
  ```
  （中文本就靠量词，无需区分 one/other；Android 在 zh 环境下始终取 `other`。）

### 3.2 调用点改造（核心风险点）

所有 `getString(R.string.X, n)` → `resources.getQuantityString(R.plurals.X, n, n)`：

```kotlin
// 改前
context.getString(R.string.passwords_count, count)
// 改后
context.resources.getQuantityString(R.plurals.passwords_count, count, count)
```

- **必须找到全部调用点**：每个 string 名用 grep 全仓搜 `R.string.xxx`，逐个改成 `R.plurals.xxx`。
- **编译期兜底**：改资源为 `<plurals>` 后，`R.string.xxx` 引用会**编译报错**（这正是安全网）——凡漏改的调用点 CI 会直接红，不会产生运行时错文案。
- 个别调用点可能在 `stringResource(...)`（Compose）里：改为 `pluralStringResource(R.plurals.X, count, count)`。

### 3.3 特殊项处理（需人工判断，不盲目改）

| 情况 | 例子 | 处理 |
|------|------|------|
| 多个 `%d` 且非首个名词主导 | `%1$d of %2$d types selected`、`%1$d groups · %2$d items` | 数量复数以**首个数量对应名词**为准；无法确定时**跳过**，保持 string，人工确认 |
| 千分位 `%,d` | `Breached %,d times` | 保持 `%,d` 占位，`quantity` 判断用数量值 |
| 复数位于句中 | `Enabled · auto-clear in %1$d days` | 改成 plurals，`one`/`other` 只影响天数词 |
| 百分比/比率 | `Required permissions: %1$d / %2$d granted` | **跳过**（比率不是复数语义，lint 误报） |

### 3.4 分批建议

- **批次 1（纯计数、风险低，~50 处）**：`passwords_count`、`selected_items`、`deleted_items`、批量删除确认、导入成功等**单一数量、语义清晰**的。
- **批次 2（句内复数，~30 处）**：`trash_day_format`、`timeline_*`、`webdav_restore_summary_*` 等。
- **批次 3（多参数/特殊，~9 处）**：人工逐条判断，跳过歧义项。

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 漏改调用点 | 编译失败（`R.string.xxx` 不存在） | CI 编译兜底，必红必修；逐 string 全仓 grep |
| 单复数语义判断错误 | 英文文案怪（如 "1 passwords"） | 真机英文/中英混排验收；逐条读上下文 |
| values-zh 缺 plurals | 中文取不到值 | zh 文件同步改 `<plurals>`（only other） |
| 多 `%d` 字符串误改 | 复数判断错误 | 特殊项人工判断，歧义跳过 |

## 5. 验证策略

1. 每批推 dev → `Android CI debug`（编译闸门，漏改调用点必红）。
2. 真机（荣耀 Android 17，可切英文）：触发批量删除、导入、回收站清空、WebDAV 恢复等，肉眼确认 "1 item" / "2 items" 文案。
3. 中文环境：确认文案与改前一致（only other 形式）。

## 6. 待确认

- [ ] 用户确认本方案（特别是「特殊项：比率/多 %d 跳过」的取舍）
- [ ] 批次 1 实施
- [ ] 批次 2 实施
- [ ] 批次 3 实施（人工逐条）
- [ ] 每批真机验收后合 main
