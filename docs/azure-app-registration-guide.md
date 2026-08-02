# 自行注册 Azure 应用对接 Bastion OneDrive（操作指引）

> 适用场景：Bastion 内置的 OneDrive client_id（`2aaf8c2c-...`）是项目作者注册的，若你没有该 Azure 账号权限，可**用自己的 Microsoft 账号免费注册一个新应用**，改一行代码即可让 Bastion 使用你自己的应用。

## 一、前置条件

- 一个 Microsoft 账号（Outlook / Hotmail / 任意邮箱注册均可），**免费**，无需企业资质。

## 二、注册步骤（约 5 分钟）

### 1. 打开 Azure 门户

浏览器访问：<https://portal.azure.com> → 用 Microsoft 账号登录

### 2. 进入应用注册

顶部搜索框输入 **App registrations**（应用注册）→ 点击进入 → 点 **+ New registration**（新注册）

### 3. 填写注册信息

| 字段 | 填写内容 |
|---|---|
| **Name** | 任意，例如 `Bastion-OneDrive` |
| **Supported account types** | 推荐选第 3 项：*"Accounts in any organizational directory and personal Microsoft accounts"*（兼容工作账户和个人账户） |
| **Redirect URI** | 先不填（选 *"Web"* 留空即可，下一步手动加） |

点击 **Register** 完成注册。

### 4. 记录 client_id

注册完成后自动跳到应用 **Overview**（概览）页，复制 **Application (client) ID** 一栏的值——这就是新的 `client_id`（形如 `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`）。**先记下来，稍后要用。**

### 5. 添加 Redirect URI（关键步骤）

左侧菜单 → **Authentication**（身份验证）→ 往下找到 **Redirect URIs** 区域 → 点 **+ Add URI**（添加 URI）→ 粘贴以下内容（**未编码形式**，与 Bastion 包名匹配）：

```
msauth://com.bastion.app/FwPDMDAjg96+0gxjMKKmnQS/dQA=
```

点击 **Save**（保存）。

> **为什么 hash 部分用 `FwPDMDAjg96+0gxjMKKmnQS/dQA=`？**
> 该值对应 Bastion 的签名证书。Bastion 的 MSAL 走非 broker 流程（不校验 APK 签名哈希），但 **host 部分必须等于包名 `com.bastion.app`** 才能让登录回调路由回 App。沿用此 hash 可保持与现有 AndroidManifest.xml 完全一致，**manifest 无需任何改动**。

### 6.（可选）添加 API 权限

如 OneDrive 登录后提示权限不足，可在左侧 **API permissions** → **+ Add a permission** → **Microsoft Graph** → **Delegated permissions** → 勾选：

- `User.Read`（读取个人资料）
- `Files.ReadWrite`（读写 OneDrive 文件）

然后点 **Grant admin consent**（如账号允许）。Bastion 登录请求的 scope 就是这两个。

## 三、与 Bastion 对接（我帮你改）

注册完成后，把 **Application (client) ID** 发给我，我会：

1. 更新 `Bastion/app/src/main/res/raw/onedrive_msal_config.json` 中的 `client_id`（仅此一处）
2. 其他文件（manifest、认证代码）**无需改动**（因为 redirect_uri 不变）
3. 推送 dev 分支 → CI 构建新预览版 APK → 你下载安装即可登录

## 四、常见问题

| 问题 | 说明 |
|---|---|
| 登录报 `AADSTS7000218` 等 | 说明 client_id 填错或应用被禁用，检查 Overview 页 ID |
| 登录报 `AADSTS50011`（仍报 redirect_uri） | 说明第 5 步的 URI 没保存成功，回 Azure 检查 Authentication 页 |
| 想换回作者内置的 client_id | 把 `onedrive_msal_config.json` 改回 `2aaf8c2c-b817-4085-9517-586a4a113dfc` 即可 |
