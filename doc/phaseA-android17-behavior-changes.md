# Android 17 (API 37) 行为变更适配报告

> Phase A — 批次 2：行为变更分析与适配  
> 日期：2026-08-05  
> 项目：bastion  
> targetSdk：37 | compileSdk：37 | minSdk：26

---

## 概述

Android 17 引入了大量行为变更，分为两大类：
1. **所有应用**（无论 targetSdkVersion）都会受到影响的变更
2. **仅 targetSdk=37** 的应用才会受影响的变更

本文档对 bastion 项目进行了全面审计，识别需要适配的变更并记录实施决策。

---

## 一、审计结果总览

| # | 变更类别 | 影响范围 | 是否需要适配 | 状态 |
|---|---------|---------|-------------|------|
| 1 | Foreground Service 类型 | 所有应用 | 无需（已合规） | ✅ |
| 2 | Broadcast Receiver 导出标志 | 所有应用 | 无需（已合规） | ✅ |
| 3 | 通知权限 | 所有应用 | 无需（已合规） | ✅ |
| 4 | 反射修改 static final | targetSdk=37 | 无需（未使用） | ✅ |
| 5 | **usesCleartextTraffic 弃用** | 所有应用 | **需要适配** | 🔧 |
| 6 | 隐式 URI 授权 | 所有应用 | 无需（已合规） | ✅ |
| 7 | 后台 Activity 启动 | 所有应用 | 无需（未使用） | ✅ |
| 8 | 大屏边到边 | targetSdk=37 | 无需（未锁定方向） | ✅ |
| 9 | **本地网络权限** | targetSdk=37 | **需要适配** | 🔧 |
| 10 | 后台音频限制 | targetSdk=37 | 无需（无音频功能） | ✅ |
| 11 | 动态代码加载 | targetSdk=37 | 无需（未使用） | ✅ |
| 12 | CP2 联系人 SQL 限制 | targetSdk=37 | 无需（未使用） | ✅ |
| 13 | POST_NOTIFICATIONS 重复代码 | — | **已修复** | 🔧 |

---

## 二、已实施的适配

### 2.1 usesCleartextTraffic → network_security_config

**背景**：Android 17 计划弃用 `usesCleartextTraffic` 属性，推荐使用 `network_security_config` 精确控制明文流量。原配置 `usesCleartextTraffic="true"` 全局开放 HTTP，存在安全风险。

**方案**：
- 创建 `/res/xml/network_security_config.xml`
- 全局默认禁止明文（`cleartextTrafficPermitted="false"`）
- 对局域网私有地址段开放明文：
  - `192.168.0.0/16`
  - `10.0.0.0/8`
  - `172.16.0.0/12`
  - `localhost`
  - `.local` 域名
- 公网服务默认使用 HTTPS（由 base-config 保证）

**修改文件**：
- `Bastion/app/src/main/res/xml/network_security_config.xml`（新建）
- `Bastion/app/src/main/AndroidManifest.xml`：`usesCleartextTraffic="true"` → `networkSecurityConfig="@xml/network_security_config"`

### 2.2 ACCESS_LOCAL_NETWORK 权限

**背景**：Android 17 对 targetSdk=37 的应用强制要求 `ACCESS_LOCAL_NETWORK` 权限才能访问局域网。bastion 支持自托管 WebDAV/Bitwarden/KeePass 等局域网服务连接，需要此权限。

**方案**：
- 在 Manifest 中声明 `ACCESS_LOCAL_NETWORK`（`minSdkVersion="37"`）
- 在 PermissionRepository 中添加权限条目，用户可在权限管理页面查看
- 属于 `NEARBY_DEVICES` 权限组，若用户已授予同组其他权限则自动获得
- API < 37 时不显示此权限条目

**修改文件**：
- `Bastion/app/src/main/AndroidManifest.xml`：添加权限声明
- `Bastion/app/src/main/java/com/bastion/app/repository/PermissionRepository.kt`：添加 `createLocalNetworkPermission()` 和状态检查
- `Bastion/app/src/main/res/values/strings.xml`：添加中文字符串
- `Bastion/app/src/main/res/values-zh/strings.xml`：添加中文字符串

### 2.3 POST_NOTIFICATIONS 重复代码修复

**背景**：`PermissionRepository.createNotificationPermission()` 中 if-else 两个分支返回相同的 `"android.permission.POST_NOTIFICATIONS"` 字符串。pre-API-33 设备不存在此权限，应返回空字符串。

**修改**：
- else 分支返回 `""`
- 提取 `checkStandardPermission()` 通用方法，处理空权限字符串（返回 GRANTED）

---

## 三、无需适配的项目（已确认合规）

### 3.1 Foreground Service（已合规）

两个前台服务均已声明 `foregroundServiceType="specialUse"` 并附带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`：
- `AutofillOtpNotificationService`：显示 TOTP 验证码
- `NotificationValidatorService`：显示 TOTP 验证码

代码中正确使用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`（Android 14+）。

### 3.2 Broadcast Receiver 导出标志（已合规）

所有 receiver 均有正确的 `exported` 标志：
- 系统广播（MY_PACKAGE_REPLACED 等）：`exported="true"`
- 自定义私有 action：`exported="false"`
- 代码注册：使用 `RECEIVER_NOT_EXPORTED`

### 3.3 隐式 URI 授权（已合规）

项目中涉及 URI 授权的场景均已正确添加 FLAG：
- FileProvider：`grantUriPermissions="true"`
- ACTION_IMAGE_CAPTURE：`FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`
- ACTION_SEND：`FLAG_GRANT_READ_URI_PERMISSION`
- ACTION_VIEW：`FLAG_GRANT_READ_URI_PERMISSION`

### 3.4 大屏/边到边（已合规）

- 未声明 `screenOrientation` 锁定
- 未声明 `resizeableActivity` 限制
- 未声明 `maxAspectRatio`/`minAspectRatio`
- 大屏设备上应用自适应

### 3.5 其他无需适配项

- **反射修改 static final**：项目中未使用反射或 JNI 修改 static final 字段
- **后台 Activity 启动**：未使用 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`
- **后台音频**：无音频播放功能
- **动态代码加载**：仅通过标准依赖使用 argon2kt 原生库
- **CP2 联系人**：无联系人读取功能

---

## 四、真机测试清单

> 测试设备：荣耀 安卓 17

| # | 测试项目 | 预期结果 | 备注 |
|---|---------|---------|------|
| 1 | 通过 HTTP 连接局域网 WebDAV | 正常连接，文件列表加载 | 验证网络安全配置 |
| 2 | 通过 HTTPS 连接云端服务 | 正常连接 | 验证 HTTPS 不受影响 |
| 3 | 连接自托管 Bitwarden（局域网 IP） | 正常同步 | 验证本地网络权限 |
| 4 | 通知栏 TOTP 显示 | 正常显示 | 验证 FGS 不受影响 |
| 5 | 生物识别解锁 | 正常 | 验证 FingerprintManager 迁移 |
| 6 | 大屏/折叠屏旋转 | UI 正常自适应 | 验证边到边 |
| 7 | 权限管理页面 | 显示本地网络权限条目 | 验证 PermissionRepository |
| 8 | 后台切换再返回 | 应用状态正常 | 验证生命周期 |

---

## 五、参考文档

- [Android 17 行为变更（所有应用）](https://developer.android.google.cn/about/versions/17/behavior-changes-all)
- [Android 17 行为变更（targetSdk=37）](https://developer.android.google.cn/about/versions/17/behavior-changes-17)
- [本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)
- [网络安全配置](https://developer.android.google.cn/privacy-and-security/security-config)
- [Android 17 适配总结（CSDN）](https://blog.csdn.net/weixin_43976036/article/details/163326625)
