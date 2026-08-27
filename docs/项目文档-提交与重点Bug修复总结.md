# Bastion 项目文档 · 提交与重点 Bug 修复总结

> 维护约定：开发在 `dev` 分支，验证（GitHub Actions + 荣耀 Android 17 真机）通过后合并 `main`。
> 本文档为内部交接总览，对外用户文档请见 `BastionDocs/` 文档站（VitePress，含中/英/日/俄/越）。
> 最后整理：2026-08-27

---

## 1. 项目简介

**Bastion** 是一款开源、本地优先的密码管理器（Android，Kotlin/Compose），管理密码、2FA 验证码与加密便签，数据默认完全留在本地设备。

- 仓库：`https://github.com/Chaniug/bastion`
- 多后端架构：**KDBX**（本地 KeePass 文件）、**Bitwarden**（含自建 Vaultwarden 同步）、**BastionLocal**（纯本地库）。
- 桌面端：`desktop/`（Compose Multiplatform，独立构建，物理隔离于 `Bastion/` 安卓模块）。

## 2. 技术栈与运行环境

| 项 | 版本/说明 |
|---|---|
| 语言/UI | Kotlin 2.2 + Jetpack Compose（Material 3） |
| 构建 | AGP 9.1.1 / Gradle 9.3.1 |
| 目标平台 | `compileSdk`/`targetSdk` = Android 17（API 37），`minSdk` = 26 |
| 桌面端 | Kotlin Multiplatform + Compose for Desktop + SQLDelight |
| 真机验收 | 荣耀 MagicOS / Android 17 |
| CI | GitHub Actions：`Android CI debug`（lint + assembleDebug + 单测）、`CodeQL Advanced`（仅扫 `dev`）、`Desktop-Build`（桌面端，仅 `dev`） |

## 3. 分支与 CI 约定（重要）

- 所有改动先落 `dev`，CI 双绿（Android + CodeQL）后由维护者下令合 `main`。
- `main.yml` / `codeql.yml` 触发分支已收窄为仅 `[dev]`，合并 `main` 不再重复构建。
- `deploy-pages.yml`、`Desktop-Build.yml` 同样仅触发 `dev`。
- 推送注意：`gh-proxy.com` 镜像 + GitHub API 真实直连 IP；限流时优先用 `git ls-remote` / shields.io badge 绕开（user `26280126` 共享额度易耗尽）。

---

## 4. 提交历史归纳（按主题）

### 4.1 Bitwarden 同步（最长演进线，见 `bitwarden同步与密码库生态.md`）
- 性能基线：同步写库包单事务 + OkHttp 快失败（`3eb0187f`）。
- 增量尝试：`sinceRevisionDate` 增量同步 + 节流（`9aee0c97`）；后被证实 **Vaultwarden 忽略增量游标**，等于空转。
- 提速核心：**上传与全量下载解耦**（`10228d6` → 合 `ce539395`），新建条目走 `POST /ciphers` 轻量路径。
- 轻量预检：`GET /accounts/revision-date` 比对跳过全量 + 60s 防抖（`349daef7`）。
- 卡死根治（2026-08-27 大修复，合 `aff292c1`）：诊断日志补全 → 401 自动恢复 + 超时收紧 + 主界面错误提示 + 上传埋点 → **passkeyMerge 全量逐条 GET 根因修复**（`f647ba90`）。

### 4.2 Passkey
- 绑定型 passkey **合并进密码 cipher 的 `login.fido2Credentials`**（`d692acaa` 等，`1bef7fc9` 合 main）。
- 历史独立 `[Passkey]` cipher 同步时自动迁移合并（`f0fd1d03`）。
- 创建页"移动到分类"卡片点击失效修复（`870dc244`）。
- Passkey 登录/选择器 UI 与文案优化（`841cec65`）。

### 4.3 自动填充（见 `自动填充与浏览器兼容.md`）
- AutofillPicker UI 重构（MD3 选择器替代系统原生列表）。
- WebView 认证回灌半填充修复（Edge + GitHub 只填密码不填账号，`0472eaf1`）。
- Via 系统 WebView 密码框回填、京东搜索栏误弹、OTP 剪贴板等专项修复。
- 条目字段兼容性 P0–P2（monica 遗留绑定读取、Bitwarden/KeePass 应用绑定互通，`45dd526c`）。

### 4.4 架构与性能（见 `架构与路线图.md`）
- **Phase A**：移除 MDBX 自研引擎，落地 KDBX + Bitwarden + BastionLocal 三后端（`69c9f8b5`）。
- **Phase B**：代码治理、守卫测试脆弱性治理、PasswordViewModel 拆分（4162→3472 行）。
- **Phase C**：运行时性能优化（AutofillConfigCache 预加载、热路径 runBlocking 替换等）。
- **Phase D / targetSdk 37**：AGP 9 + Gradle 9 跨代升级，拉到 Android 17。
- PC 端 Phase 3（SQLDelight 持久化）、Phase 4（SyncScheduler 接线）已完成。

### 4.5 其他
- 清理冗余与过期设置、精简死代码（`eb89f35d`）。
- Bastion Plus 全链路移除（`安卓端BastionPlus全链路移除记录.md`）。
- WebDAV 原子条件写入（If-Match）+ 密钥文件内部副本与指纹（`bfa33298`）。
- CodeQL Action v3→v4 升级（`42fff881`）。

---

## 5. 重点 Bug 修复总结

| # | 现象（真机） | 根因 | 修复 | 关键提交 |
|---|---|---|---|---|
| 1 | 已登录点同步"很慢/感觉没同步"，退出重登正常 | 连接池复用被反代静默关闭的空闲连接，挂到 60s 超时；且 `passkeyMerge` 每次同步对 vault 全部条目逐条 `GET /ciphers/{id}` 检查 legacy 字段（216 条量级）卡死 | OkHttp 超时 60s→30s + 连接池 5min→2min + `pingInterval`；`passkeyMerge` 加 vault 级完成标记 + 15s 超时保护，跳过已处理 | `f647ba90` `9bdba9a8` |
| 2 | 同步中途令牌过期 → 整次失败、从头重试雪崩 | 仅按 `accessTokenExpiresAt` 预判刷新一次，无反应式 401 恢复 | 新增 OkHttp `Authenticator`：`refreshForHost` 按 host 刷新 token 并重试一次（防死循环） | `9bdba9a8`（类型在 `1907eba6` 修正） |
| 3 | 同步失败主 Vault 界面无任何提示 | `ShowError` 事件仅在 `SendScreen` 被消费，主界面静默 | `SimpleMainScreen` 用 `when` 消费 `ShowError` 弹 Toast | `9bdba9a8` |
| 4 | 新建条目"一直在同步中"传不上去 | 上传前强制整库下载；Vaultwarden 无增量，整库下载主导耗时 | 上传与下载解耦：新建条目走 `POST /ciphers` 轻量路径，全量仅作后台 reconcile | `10228d6` `ce539395` |
| 5 | 自建服务器每次同步都全量偏慢 | Vaultwarden 忽略 `sinceRevisionDate` | 客户端先 `GET /accounts/revision-date` 比对，一致则跳过整库 pull + 60s 防抖 | `349daef7` |
| 6 | Bitwarden 锁/解锁入口是无效操作 | `forceLock` 只清内存缓存，本地密文由 app 级 MDK 加密，与 Bitwarden 锁正交 | 移除无效锁/解锁 UI，统一依赖 Bastion app 锁 | `6b86427c` |
| 7 | 绑定型 passkey 同步后在服务器显示为独立 `[Passkey]` 条目 | 创建的是独立 login cipher，未塞进密码 cipher 的 `fido2Credentials` | 合并进密码 cipher（基于 GET baseline 去重合并，禁盲 PUT）+ 历史独立 cipher 自动迁移 | `d692acaa` `f0fd1d03` |
| 8 | Edge 登录 Discord 只填用户名、密码空白 | 跨字段填充顺序/目标角色判定问题 | 参照 Bitwarden 填充设计修正填充链 | `Edge浏览器Discord填充半填充Bug修复计划` |
| 9 | Via 系统 WebView 密码框填不进 | dataset 回填在系统 WebView 内核下不可靠 | 解析器保留密码候选 + 直填路径修正 | `Via系统WebView密码框回填不可靠修复计划` |
| 10 | OneDrive 登录 `invalid_request: redirect_uri` | Azure 门户 redirect URI 配置与代码不一致 | 修正 Azure 应用注册 / 回环地址（或自注册 Azure 应用） | `onedrive-login-bug-diagnosis` |
| 11 | 清空所有数据失效 + TOTP 双显示/消失 | K2 suspend lambda 跨文件解析缺陷 + 重复 TOTP 合并逻辑 | 修复 lambda 作用域、收敛 TOTP 去重 | `83de32e0` `d4003067` |

---

## 6. 当前状态与未决项

- **已稳定**：Bitwarden 同步卡死系列（#1–#5）已随 `aff292c1` 合 main，CI 在 `dev` 双绿。
- **已知边界**：passkey 解绑场景不会自动从密码 cipher 移除旧 credential；多设备并发追加 passkey 极短覆盖窗口（已缓解）。
- **待续方向**：本地数据库统一同步架构重构（三条同步通路归一）、KEEPASS 独立客户端化、桌面端 Phase 5 三 Tab UI 完善。

## 7. 文档索引（本仓库仅保留以下 5 篇内部交接文档）

1. `项目文档-提交与重点Bug修复总结.md`（本篇）
2. `架构与路线图.md`
3. `bitwarden同步与密码库生态.md`
4. `自动填充与浏览器兼容.md`
5. `android端优化与杂项.md`

> 其余历史交接笔记（原 `docs/` 50 篇 + `Bastion/docs/` 38 篇）已合并精简进上述 5 篇；对外文档以 `BastionDocs/` 为准。
