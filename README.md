# monicpass

<div align="center">

**中文** | [English](README_EN.md)

<img src="image/themepng.png" alt="monicpass App Icon" width="360" />

**基于 Monica 的二次开发独立分支 —— 本地优先密码管理，持续优化**

[![Release](https://img.shields.io/github/v/release/Chaniug/monicpass?style=flat-square)](https://github.com/Chaniug/monicpass/releases)
[![Downloads](https://img.shields.io/github/downloads/Chaniug/monicpass/total?style=flat-square)](https://github.com/Chaniug/monicpass/releases)
[![Last Commit](https://img.shields.io/github/last-commit/Chaniug/monicpass?style=flat-square)](https://github.com/Chaniug/monicpass/commits)

</div>

---

## 关于 monicpass

monicpass 是 [Monica Pass](https://github.com/Monica-Pass/Monica) 的独立二次开发分支（fork），在保持上游核心能力的基础上进行针对性优化与功能增强。

本分支的开发原则：
- **与上游改动保持大体一致**，不做无意义的重复造轮子
- **在细节部分进一步开发与优化**，修复实际使用中遇到的问题
- **独立维护**，不依赖上游的发布节奏

---

## 核心功能

- **本地 Vault**：所有核心凭据本地加密存储，数据不托付给第三方云
- **双生态聚合**：兼容 Bitwarden API/同步能力与 KeePass (`.kdbx`) 读写能力
- **智能检索**：按标题、域名、标签快速定位凭据
- **生物识别解锁**：使用系统级生物识别能力提升安全与可用性
- **TOTP 管理**：统一存储并生成动态验证码
- **Android Autofill**：Android 自动填充服务，支持应用与浏览器密码/账号自动填入
- **WebDAV 备份与同步**：通过自有 WebDAV 基础设施实现跨设备数据流转
- **导入支持**：支持 KeePass 数据迁移与 Bitwarden 兼容接入

---

## 快速安装

### Android
1. 从 [Releases](https://github.com/Chaniug/monicpass/releases) 下载最新 APK（含 Development Preview）
2. 在 Android 8.0+ 设备安装并初始化主密码

### 浏览器扩展
从上游 [Monica Pass](https://github.com/Monica-Pass/Monica) 获取浏览器扩展，与本分支的 Android 端配合使用。

---

## 与上游的主要差异

| 方面 | 上游 (Monica-Pass/Monica) | monicpass (本分支) |
|------|---------------------------|---------------------|
| 自动填充 | 原始实现 | 增强兼容（电影猎手等小众 App），修复普通输入框误弹密码 |
| 版本号策略 | 固定 versionCode | epoch 秒自增，每次构建支持覆盖安装 |
| 构建方式 | 需自行配置签名密钥 | CI 内置签名 + Preview Release 自动发布 |
| 更新频率 | 按上游节奏 | 持续迭代，独立发布 |

---

## 构建

```bash
# 克隆
git clone https://github.com/Chaniug/monicpass.git

# 进入 Android 目录
cd monicpass/Monica\ for\ Android

# 构建
./gradlew :app:assembleRelease
```

GitHub Actions 在每次推送 `main` 分支时自动构建并发布 Development Preview，可在 [Releases](https://github.com/Chaniug/monicpass/releases) 页面下载。

---

## 许可

本项目基于上游 [Monica Pass](https://github.com/Monica-Pass/Monica) 二次开发，继承其 [GNU General Public License v3.0](LICENSE) 开源许可。

---

## 致谢

感谢 [Monica Pass](https://github.com/Monica-Pass/Monica) 原作者提供的优秀基础，以及以下开源项目的启发与帮助：

- [Bitwarden](https://bitwarden.com/) — 开源密码管理生态、Vault 模型与同步能力
- [KeePass](https://keepass.info/) — 本地密码库理念与 `.kdbx` 生态兼容
