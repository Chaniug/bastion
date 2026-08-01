# B.2.3 守卫测试校验工具

Wave 1b 踩坑后沉淀的两段校验脚本，供后续 Wave（1b 续 / 1c / 2 / 3）及其他接力 agent 复用。

## validate_regex.py

忠实复刻 Kotlin 字符串转义（`\\`→`\`、`\"`→`"`、`\$`→`$`）后，用 Python `re` 逐个校验守卫断言：
- `assertTrue` 的正则/子串必须命中当前源码（零信号丢失）；
- `assertFalse` 必须不命中（保留"牙齿"）。

> 关键：早期用非贪婪 `"..."` 提取正则，遇到体内嵌转义引号 `\"` 会截断提取并**静默跳过**断言 → 误报全绿。本脚本按"未被 `\` 转义的引号"作为串边界，正确处理内嵌 `\"`。

用法：

```bash
python3 validate_regex.py \
  Bastion/app/src/test/java/com/bastion/app/utils/WebDavBillingAddressBackupGuardTest.kt \
  Bastion/app/src/test/java/com/bastion/app/utils/WebDavSecurityStorageGuardTest.kt
```

## scan_bare_template.py

预检：仅扫 `Regex("...")` 内部，标记"偶数反斜杠 + `$` 后接标识符"的真·裸模板写法
（会触发 Kotlin `Unresolved reference` 编译失败）。花括号形式 `\\$\\{...\\}` 已多次 CI 验证安全，不报。

用法：

```bash
python3 scan_bare_template.py            # 扫整个 app/src/test 树
python3 scan_bare_template.py 某文件.kt  # 仅扫单个文件
```

## 转义铁律（正则里匹配字面 `$`）

| 源码形式 | 测试文件应写 | Kotlin 解析后 | 正则含义 |
| --- | --- | --- | --- |
| `"$folderKey/..."` | `"\\\$folderKey/..."` | `\$folderKey/...` | 字面 `$folderKey` |
| `"${x.y}"` | `"\\$\\{x.y\\}"` | `$\{x.y\}` | 字面 `${x.y}` |

写成 `\\$identifier`（两反斜杠）会被 Kotlin 当 `$identifier` 模板变量 → 编译失败。
