# 快速测试清单 - onSaveRequest 调试

## 📋 测试前准备

### 1. 安装最新版本
```bash
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 2. 启动 Logcat 监控
```bash
# 在新的终端窗口中运行
adb logcat -c  # 清空日志
adb logcat | findstr "💾"
```

**期望看到的日志标记**:
- `💾 SaveInfo configured` - SaveInfo 已配置
- `💾💾💾 onSaveRequest TRIGGERED!` - **这是关键!表示回调被触发**

---

## ✅ 测试步骤

### 场景 1: 基础登录表单测试

1. **打开测试应用的登录界面**
   
2. **点击用户名字段**
   - 应该看到 Bastion 的自动填充建议
   - Logcat 应该显示: `💾 SaveInfo configured: scenario=LOGIN`

3. **不要选择现有密码,手动输入新值**:
   ```
   用户名: newuser@test.com
   密码: TestPassword123
   ```
   ⚠️ **重要**: 必须手动输入,不能选择现有密码!

4. **点击"登录"按钮**
   
5. **检查 Logcat 输出**:
   ```
   期望看到:
   💾💾💾 onSaveRequest TRIGGERED! 💾💾💾
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   SaveRequest contexts: 2
   ```

6. **如果看到上述日志**:
   ✅ onSaveRequest 已触发 - 功能正常!
   
7. **如果没有看到日志**:
   ❌ 继续下面的调试步骤

---

### 场景 2: Activity 关闭触发

1. **打开登录界面**
2. **手动输入用户名和密码**
3. **不点击登录按钮,直接按返回键关闭 Activity**
4. **检查是否触发 onSaveRequest**

---

### 场景 3: 注册/修改密码场景

1. **打开注册或修改密码界面**
2. **输入新密码(两个密码字段都输入相同值)**
3. **点击提交按钮**
4. **检查日志**: 应该看到 `scenario=NEW_PASSWORD`

---

## 🔍 常见问题排查

### 问题 A: SaveInfo 配置了但 onSaveRequest 不触发

**可能原因 1: 字段值没有改变**
```
解决方案: 确保手动输入新值,不要选择现有密码
```

**可能原因 2: 测试应用禁用了 autofill**
```
检查测试应用的 AndroidManifest.xml:
- 不要有 android:importantForAutofill="no"

检查布局文件:
- EditText 需要有 android:autofillHints
- 不要设置 android:importantForAutofill="no"
```

**可能原因 3: Android 版本太低**
```
检查设备版本:
adb shell getprop ro.build.version.sdk

onSaveRequest 需要 API 26+ (Android 8.0+)
```

**可能原因 4: 表单提交未被检测到**
```
测试应用的按钮点击事件应该:
1. 调用 finish() 关闭 Activity
2. 或者启动新的 Activity

示例代码:
loginButton.setOnClickListener {
    // 模拟登录成功
    Handler(Looper.getMainLooper()).postDelayed({
        finish()  // 关闭当前界面
    }, 100)
}
```

---

### 问题 B: Logcat 没有任何 Bastion 相关日志

**解决方案**:
```bash
# 1. 检查 Bastion 服务是否启用
adb shell settings get secure autofill_service
# 应该输出: com.bastion.app/.autofill.BastionAutofillService

# 2. 如果不是,手动设置:
设置 → 系统 → 语言和输入法 → 高级 → 自动填充服务 → Bastion

# 3. 检查应用是否安装
adb shell pm list packages | findstr bastion
```

---

### 问题 C: SaveInfo 未配置

**检查日志是否有**:
```
💾 SaveInfo configured: scenario=LOGIN
```

**如果没有**:
1. 确认 onFillRequest 被调用了
2. 检查字段是否被正确识别(需要有 username 或 password hint)
3. 查看完整日志: `adb logcat | findstr "Bastion Autofill"`

---

## 📊 成功的日志示例

```
# 1. onFillRequest 阶段
🔐 Processing autofill request for: com.example.testapp
📊 Parser found fields: username=1, password=1, newPassword=0
🎯 Found 2 matching passwords
💾 SaveInfo configured: scenario=LOGIN, username=1, password=1, newPassword=0
💾 Login SaveInfo added: requiredFields=1, optionalFields=1

# 2. 用户手动输入新值并提交表单

# 3. onSaveRequest 阶段
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💾💾💾 onSaveRequest TRIGGERED! 💾💾💾
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SaveRequest contexts: 2
💾 开始处理密码保存请求
💾 解析到 2 个字段
💾 提取到的值: username='newuser@test.com', password='TestPassword123'
💾 检测到重复密码: 类型=NoDuplicate
💾 创建新密码条目: title='Test App'
💾 密码保存成功
```

---

## 🎯 关键验证点

在测试时,请确认以下每一项:

- [ ] Bastion 已设置为默认自动填充服务
- [ ] 设备 Android 版本 >= 8.0 (API 26+)
- [ ] APK 已重新安装最新版本
- [ ] Logcat 过滤器正确: `findstr "💾"`
- [ ] 手动输入了新的用户名和密码(不是选择现有密码)
- [ ] 点击了提交按钮或关闭了 Activity
- [ ] 测试应用的字段有 `autofillHints`

---

## 📝 报告问题时请提供

如果 onSaveRequest 仍然不触发,请提供:

1. **完整的 Logcat 日志**:
```bash
adb logcat -d > bastion_autofill_log.txt
```

2. **设备信息**:
```bash
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
```

3. **Autofill 服务设置**:
```bash
adb shell settings get secure autofill_service
```

4. **测试应用的代码**:
- LoginActivity.kt
- activity_login.xml
- 按钮的点击事件处理代码

---

**最后更新**: 2024
**相关文档**: SAVE_REQUEST_DEBUG_GUIDE.md
