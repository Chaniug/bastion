# Bastion OneDrive 登录失败诊断报告（`invalid_request: redirect_uri`）

> 日期：2026-08-02　|　分支：dev（408eef3）　|　状态：**根因已定位，需 Azure 门户操作修复**

## 一、问题现象

在 App 内 OneDrive 登录，浏览器打开 Microsoft 登录页，输入账号后报错：

```
我们无法完成你的请求
invalid_request: The provided value for the input parameter 'redirect_uri' is not valid.
The expected value is a URI which matches a redirect URI registered for this client application.
```

该错误即 Azure AD 的 **AADSTS50011**（reply URL 与注册不匹配），发生在 Azure 颁发授权码前校验 `redirect_uri` 时。

## 二、根因（一句话）

**2026-07-27 的 Rebrand 提交（Monica Pass → Bastion）把包名从 `takagi.ru.monica` 改为 `com.bastion.app`，代码内 MSAL 的 `redirect_uri` 随之更新，但 Azure 门户的应用注册没有同步添加新的 redirect URI，导致登录被 Azure 拒绝。**

## 三、证据链

### 1. Rebrand 前后 MSAL 配置对比

| 项目 | Rebrand 前（b4c2f41，Monica Pass） | Rebrand 后（当前 dev） |
|---|---|---|
| client_id | `2aaf8c2c-b817-4085-9517-586a4a113dfc` | `2aaf8c2c-b817-4085-9517-586a4a113dfc`（**未变**） |
| 证书哈希 | `FwPDMDAjg96+0gxjMKKmnQS/dQA=` | `FwPDMDAjg96+0gxjMKKmnQS/dQA=`（**未变**，签名证书没换） |
| redirect_uri host | `msauth://takagi.ru.monica/...` | `msauth://com.bastion.app/...`（**包名变了**） |
| 完整 redirect_uri | `msauth://takagi.ru.monica/FwPDMDAjg96%2B0gxjMKKmnQS%2FdQA%3D` | `msauth://com.bastion.app/FwPDMDAjg96%2B0gxjMKKmnQS%2FdQA%3D` |

Rebrand 提交信息（`4899931cfb`）原文：`Change applicationId/namespace takagi.ru.monica -> com.bastion.app`。

### 2. 代码侧配置自洽（已逐项验证，无问题）

| 检查项 | 内容 | 结论 |
|---|---|---|
| `onedrive_msal_config.json` redirect_uri | `msauth://com.bastion.app/FwPDMDAjg96%2B0gxjMKKmnQS%2FdQA%3D`（URL 编码） | ✓ 标准 MSAL 格式 |
| `AndroidManifest.xml` BrowserTabActivity intent-filter | `scheme=msauth, host=com.bastion.app, path=/FwPDMDAjg96+0gxjMKKmnQS/dQA=`（未编码） | ✓ 与 config 一致 |
| `build.gradle` applicationId | `com.bastion.app` | ✓ 与 URI host 一致 |
| client_id 有效性 | 模拟 authorize 请求返回登录页（无效 client 会报 AADSTS700038） | ✓ client 存在 |

### 3. Azure 校验行为实测

通过直接构造 authorize 请求验证（`login.microsoftonline.com`）：

- 无效 `client_id` → 立即返回 **AADSTS700038**（证明测试链路有效）
- 任意 `redirect_uri`（含伪造 hash、`https://example.com/callback`）→ **登录页正常显示**（第一关不校验 URI）
- 结论：**`redirect_uri` 的精确校验发生在用户输入账号密码登录之后**。用户看到错误页，说明发送的 `msauth://com.bastion.app/FwPDMDAjg96+0gxjMKKmnQS/dQA=` 不在 Azure 注册列表。

### 4. MSAL 机制说明（为什么不是签名问题）

反编译 MSAL 8.3.2（`PublicClientApplicationConfiguration`）确认：
- 非 broker 流程（本项目 `broker_redirect_uri_registered: false`、无 Authenticator）**不做** APK 签名哈希校验
- `getRedirectUri()` 直接返回配置值，发送给 Azure 的就是 `msauth://com.bastion.app/FwPDMDAjg96+0gxjMKKmnQS/dQA=`
- 因此无论预览版 APK 用什么签名，错误表现相同，**与签名无关**

## 四、为什么不能靠改代码修复

代码里 `redirect_uri` 的 host **必须等于应用当前包名**：

- Android 的 MSAL 回调靠 manifest intent-filter 路由（`scheme=msauth` + `host=包名`）
- 若把 host 改回 `takagi.ru.monica`，即使 Azure 接受旧 URI，**回调 intent 也无法路由到新包名的 App**，登录依然失败
- 结论：**代码侧已正确，修复只能发生在 Azure 门户**

## 五、修复步骤（需在 Azure 门户操作，约 2 分钟）

1. 登录 [Azure 门户](https://portal.azure.com) → **Microsoft Entra ID** → **App registrations**
2. 搜索并打开 client_id 为 `2aaf8c2c-b817-4085-9517-586a4a113dfc` 的应用注册
3. 左侧菜单 → **Authentication**
4. 在 **Redirect URIs** 区域点击 **Add URI**（或 Add a platform → Android），输入**未编码形式**：

   ```
   msauth://com.bastion.app/FwPDMDAjg96+0gxjMKKmnQS/dQA=
   ```

   > 若选择 "Add a platform → Android"，Package name 填 `com.bastion.app`，Signature hash 填 `FwPDMDAjg96+0gxjMKKmnQS/dQA=`（Azure 会自动拼出同一 URI）。

5. 点击 **Save**
6. 旧 URI `msauth://takagi.ru.monica/FwPDMDAjg96+0gxjMKKmnQS/dQA=` 可保留（无副作用）或删除

### 验证方法

- 修复后重新打开 App → OneDrive 登录 → 输入账号 → 应正常进入授权/回调流程
- 修复前可先用以下命令快速复现（返回登录页 = 第一关通过；真正的校验在登录后）：

  ```bash
  curl -s "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=2aaf8c2c-b817-4085-9517-586a4a113dfc&response_type=code&redirect_uri=msauth%3A%2F%2Fcom.bastion.app%2FFwPDMDAjg96%2B0gxjMKKmnQS%2FdQA%3D&scope=User.Read"
  ```

## 六、关键文件位置

| 文件 | 路径 |
|---|---|
| MSAL 配置 | `Bastion/app/src/main/res/raw/onedrive_msal_config.json` |
| 登录 UI | `Bastion/app/src/main/java/com/bastion/app/ui/screens/OneDriveBackupScreen.kt` |
| 认证管理器 | `Bastion/app/src/main/java/com/bastion/app/utils/OneDriveAuthManager.kt` |
| Manifest intent-filter | `Bastion/app/src/main/AndroidManifest.xml`（85-96 行 BrowserTabActivity） |
| CI 签名配置 | `.github/workflows/main.yml`（push 事件注入固定 keystore） |

## 七、后续建议

1. **本 Bug 修复后**，建议在 `docs/` 记录一条运维备忘：**Azure 应用注册的 redirect URI 与包名强绑定，任何包名/签名变更都必须同步 Azure 门户**。
2. 若希望 OneDrive 登录失败时给出更友好的提示（指引用户检查 Azure 配置），可考虑在 `OneDriveBackupScreen.kt` 的错误分支中识别 `redirect_uri` 类错误并显示专门文案——**属可选优化，需确认后再动代码**。
