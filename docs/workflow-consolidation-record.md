# CI Workflow 合并与收窄记录

> 状态：已完成并推送到 dev（提交见 git log）。
> 关联：用户询问 `.github/workflows/` 中是否有冗余/不常用/反复触发的 yml 可合并。

## 决策（用户确认）
1. 删除 `Android-Preview.yml`，把其 PR 校验能力并入 `main.yml`（dev 主链路）。
2. `deploy-pages.yml`、`Desktop-Build.yml` 触发分支由 `[main, dev]` 收窄为仅 `[dev]`。
3. 与 `codeql.yml`、`main.yml` 的 dev-only 策略保持一致：main 由 dev 合并，不在 main 上重复检测/构建。

## 合并前重叠点（已消除）
- `main.yml` 与 `Android-Preview.yml` 都在 PR 时 `assembleDebug` 校验 → 重复构建。
- 两者都向 `preview` tag 发布预览 Release（main.yml=dev push 发 debug；Android-Preview=手动 dispatch 发 release）→ 抢同一 tag 的潜在冲突。

## 合并后状态
| 文件 | 触发（push / PR） | 职责 |
|---|---|---|
| main.yml | dev / dev | Android 主链路：PR 校验 + dev push 自动发 debug 预览包 + 单测基线闸门 |
| codeql.yml | dev / dev + 每周一 | 静态安全扫描 |
| Android-Release.yml | tag `v*` + 手动 | Stable 正式发布（独立，不动） |
| deploy-pages.yml | dev(push, pages/**) | 文档站 Pages（独立） |
| Desktop-Build.yml | dev / dev (desktop/**) | 桌面版（独立） |

## 注意点
- 删除 Android-Preview.yml 后，PR→main 不再单独校验（与「dev 校验、main 只合并」策略一致）。
- deploy-pages 改为仅 dev 触发后，文档站会在 dev 改 `pages/**` 时即部署，早于合入 main；符合用户「main 不重复检测」的偏好。
- `preview` Release 现在唯一来源是 main.yml 在 dev push 时的 debug 预览包，不再有冲突。
