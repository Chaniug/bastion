# SaveRequest 调试指南

## 问题描述
SaveInfo 已经正确配置,但 `onSaveRequest()` 回调没有被触发。

## 已完成的修复

### 1. 移除 FLAG_DELAY_SAVE
```kotlin
// 之前 (可能导致延迟显示)
saveInfoBuilder.setFlags(
    SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE or SaveInfo.FLAG_DELAY_SAVE
)

// 修复后 (立即显示)
saveInfoBuilder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
```

**原因**: `FLAG_DELAY_SAVE` 会延迟保存提示的显示,可能导致某些情况下不显示。

## Android Autofill 触发条件

### onSaveRequest 触发的必要条件:

1. **SaveInfo 必须正确配置** ✅
   - 已完成:在 FillResponse 中添加了 SaveInfo
   - 验证:日志显示 "💾 SaveInfo configured"

2. **用户必须改变表单内容** ❌ 可能原因
   - Android 系统会比对初始值和最终值
   - 如果值没有改变,不会触发保存
   
3. **表单提交必须被检测到** ❌ 可能原因
   - 点击提交按钮
   - 按下 Enter 键
   - 表单视图消失(Activity 关闭)

4. **AutofillId 必须匹配** ✅
   - SaveInfo 中的 AutofillId 必须与 onFillRequest 中的相同
   - 已完成:使用相同的 parsedStructure.items

## 详细调试步骤

### 步骤 1: 验证 SaveInfo 配置

**执行操作**:
```bash
# 启动 logcat 过滤
adb logcat | findstr "SaveInfo"
```

**期望日志**:
```
💾 SaveInfo configured: scenario=LOGIN, username=1, password=1, newPassword=0
💾 Login SaveInfo added: requiredFields=1, optionalFields=1
```

**验证项目**:
- ✅ scenario 值正确(LOGIN 或 NEW_PASSWORD)
- ✅ requiredFields > 0
- ✅ flags = FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE

---

### 步骤 2: 测试表单提交检测

**测试场景 A: 修改密码后提交**
```
1. 打开测试应用的登录表单
2. 在用户名字段输入: test@example.com
3. 在密码字段输入: password123
4. 点击"登录"按钮
```

**测试场景 B: Activity 关闭触发**
```
1. 打开登录表单
2. 输入用户名和密码
3. 按下系统返回键(关闭 Activity)
```

**测试场景 C: Enter 键提交**
```
1. 打开登录表单
2. 输入用户名
3. Tab 到密码字段
4. 输入密码
5. 按下 Enter 键
```

---

### 步骤 3: 检查日志中的关键信息

**启动完整调试日志**:
```bash
adb logcat -c  # 清空日志
adb logcat | findstr "Bastion Autofill SaveInfo onSaveRequest"
```

**期望的完整流程**:
```
[onFillRequest]
🔐 Processing autofill request for: com.example.testapp
📊 Parser found fields: username=1, password=1, newPassword=0
💾 SaveInfo configured: scenario=LOGIN, username=1, password=1, newPassword=0
💾 Login SaveInfo added: requiredFields=1, optionalFields=1

[表单提交后]
💾 onSaveRequest triggered  <-- 这是关键!
💾 Processing save request...
```

---

### 步骤 4: 验证字段值变化

**关键代码位置**: `BastionAutofillService.kt` line 1440+

**添加调试日志**:
在 `onSaveRequest()` 方法开始处添加:
```kotlin
override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
    android.util.Log.w("BastionAutofill", "💾💾💾 onSaveRequest TRIGGERED! 💾💾💾")
    android.util.Log.d("BastionAutofill", "SaveRequest contexts: ${request.fillContexts.size}")
    
    // ... 原有代码
}
```

**重新编译并测试**:
```bash
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

### 步骤 5: 测试不同的提交方式

#### 方法 A: 使用 InputMethodManager
某些应用使用 IME 提交表单:
```kotlin
// 在测试应用中
passwordField.setOnEditorActionListener { v, actionId, event ->
    if (actionId == EditorInfo.IME_ACTION_DONE) {
        submitForm()
        true
    } else false
}
```

#### 方法 B: 使用 View 消失
Activity finish 应该触发保存:
```kotlin
// 点击登录按钮
loginButton.setOnClickListener {
    // 模拟登录成功
    finish()  // 关闭 Activity
}
```

#### 方法 C: 明确的提交信号
使用 `IMPORTANT_FOR_AUTOFILL_YES`:
```xml
<!-- 在测试应用的布局中 -->
<Button
    android:id="@+id/loginButton"
    android:importantForAutofill="yes"
    android:autofillHints="login"
    ... />
```

---

## 常见问题排查

### 问题 1: onSaveRequest 从未被调用

**可能原因**:
1. ❌ SaveInfo 未正确添加到 FillResponse
2. ❌ 用户没有修改字段值
3. ❌ 表单提交未被系统检测到
4. ❌ AutofillId 不匹配

**解决方案**:
```kotlin
// 在 addSaveInfo() 中添加详细日志
android.util.Log.d("AutofillPicker", """
    🔍 SaveInfo Details:
    - Required IDs: ${passwordFields.map { it.toString() }}
    - Optional IDs: ${usernameFields.map { it.toString() }}
    - Flags: FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE
    - Type: SAVE_DATA_TYPE_USERNAME or SAVE_DATA_TYPE_PASSWORD
""".trimIndent())
```

---

### 问题 2: 字段值没有改变

**检测方法**:
在 `onSaveRequest()` 中打印所有字段的值:
```kotlin
request.fillContexts.lastOrNull()?.structure?.let { structure ->
    structure.windowNodeAt(0).rootViewNode.let { root ->
        printNodeValues(root, 0)
    }
}

fun printNodeValues(node: ViewNode, depth: Int) {
    val indent = "  ".repeat(depth)
    node.autofillValue?.let { value ->
        if (value.isText) {
            android.util.Log.d("SaveRequest", "$indent Value: '${value.textValue}'")
        }
    }
    for (i in 0 until node.childCount) {
        printNodeValues(node.getChildAt(i), depth + 1)
    }
}
```

---

### 问题 3: 测试应用配置问题

**验证测试应用的配置**:

1. **AndroidManifest.xml**:
```xml
<activity android:name=".LoginActivity">
    <!-- 确保没有禁用 autofill -->
    <!-- 不要有 android:importantForAutofill="no" -->
</activity>
```

2. **布局文件**:
```xml
<EditText
    android:id="@+id/username"
    android:autofillHints="username"
    android:inputType="textEmailAddress"
    android:importantForAutofill="yes" />

<EditText
    android:id="@+id/password"
    android:autofillHints="password"
    android:inputType="textPassword"
    android:importantForAutofill="yes" />

<Button
    android:id="@+id/loginButton"
    android:text="登录"
    android:importantForAutofill="yes" />
```

3. **代码中的提交逻辑**:
```kotlin
loginButton.setOnClickListener {
    // 方式 1: 延迟后关闭 Activity
    Handler(Looper.getMainLooper()).postDelayed({
        finish()
    }, 100)
    
    // 方式 2: 启动新 Activity
    startActivity(Intent(this, HomeActivity::class.java))
    finish()
}
```

---

## 验证清单

在报告问题之前,请确保:

- [ ] 已移除 `FLAG_DELAY_SAVE`
- [ ] 重新编译并安装应用: `.\gradlew assembleDebug`
- [ ] SaveInfo 日志显示正确配置
- [ ] 在表单中**输入了新值**(不是只选择现有密码)
- [ ] 点击了提交按钮或关闭了 Activity
- [ ] Logcat 中搜索 "onSaveRequest" 查看是否有调用
- [ ] 测试应用没有禁用 autofill
- [ ] 字段有正确的 `autofillHints`

---

## 下一步调试

### 如果 onSaveRequest 仍未触发:

1. **检查系统版本**:
```kotlin
android.util.Log.d("BastionAutofill", "Android version: ${Build.VERSION.SDK_INT}")
// onSaveRequest 需要 Android 8.0+ (API 26+)
```

2. **检查系统设置**:
```
设置 → 系统 → 语言和输入法 → 高级 → 自动填充服务
→ 确认 Bastion 已选中
```

3. **尝试简单场景**:
创建最简单的测试:
```kotlin
// 最小可行测试
class SimpleLoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        val username = EditText(this).apply {
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        
        val password = EditText(this).apply {
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val button = Button(this).apply {
            text = "Login"
            setOnClickListener { finish() }
        }
        
        layout.addView(username)
        layout.addView(password)
        layout.addView(button)
        setContentView(layout)
    }
}
```

4. **对比 Google 示例**:
参考官方 Autofill 示例:
https://github.com/android/input-samples/tree/main/AutofillFramework

---

## 预期结果

**成功的流程应该是**:
```
1. 用户打开表单
   → onFillRequest 被调用
   → SaveInfo 被配置

2. 用户输入新的用户名/密码
   → Android 系统监控字段变化

3. 用户点击提交或关闭界面
   → Android 系统检测到表单提交
   → onSaveRequest 被调用
   → 显示"保存到 Bastion"提示

4. 用户点击"保存"
   → processSaveRequest() 处理
   → 密码被保存到数据库
   → 显示成功通知
```

---

## 联系支持

如果按照以上步骤仍然无法解决,请提供:

1. 完整的 logcat 日志(从打开应用到提交表单)
2. Android 版本和设备型号
3. 测试应用的代码(LoginActivity + 布局文件)
4. SaveInfo 配置日志截图

---

**最后更新**: 2024
**相关文档**: 
- PASSWORD_SAVE_IMPLEMENTATION.md
- TROUBLESHOOTING.md
