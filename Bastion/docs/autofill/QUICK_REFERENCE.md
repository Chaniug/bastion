# AutofillPicker 快速参考

## 🚀 5分钟集成

### 步骤1: 导入扩展函数

在 `BastionAutofillService.kt` 顶部添加:

```kotlin
import com.bastion.app.autofill.createSmartFillResponse
```

### 步骤2: 替换响应创建代码

找到创建 `FillResponse` 的地方,替换为:

```kotlin
val response = createSmartFillResponse(
    context = applicationContext,
    passwords = matchedPasswords,
    packageName = packageName,
    domain = domain,
    parsedStructure = parsedStructure
)

callback.onSuccess(response)
```

### 步骤3: 测试

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📋 API参考

### createSmartFillResponse()

自动选择最佳展示方式的智能函数。

```kotlin
fun createSmartFillResponse(
    context: Context,
    passwords: List<PasswordEntry>,
    packageName: String?,
    domain: String?,
    parsedStructure: EnhancedAutofillStructureParserV2.ParsedStructure
): FillResponse?
```

**行为:**
- 0个密码 → 返回 null
- 1个密码 → 直接填充
- 多个密码 → 使用 Picker UI

### createPickerFillResponse()

强制使用 Picker UI。

```kotlin
fun createPickerFillResponse(
    context: Context,
    passwords: List<PasswordEntry>,
    packageName: String?,
    domain: String?,
    parsedStructure: EnhancedAutofillStructureParserV2.ParsedStructure
): FillResponse?
```

### createDirectFillResponse()

直接填充,不显示选择界面。

```kotlin
fun createDirectFillResponse(
    context: Context,
    password: PasswordEntry,
    parsedStructure: EnhancedAutofillStructureParserV2.ParsedStructure
): FillResponse?
```

## 🔍 调试

### 添加日志

```kotlin
android.util.Log.d("AutofillPicker", "Passwords: ${passwords.size}")
android.util.Log.d("AutofillPicker", "Using Picker: ${passwords.size > 1}")
```

### 查看日志

```bash
adb logcat | grep AutofillPicker
```

## ⚠️ 常见错误

### 错误1: Unresolved reference: createSmartFillResponse

**解决:** 添加导入语句
```kotlin
import com.bastion.app.autofill.createSmartFillResponse
```

### 错误2: 仍然显示旧UI

**解决:** 
1. 完全卸载旧版本
2. 重新安装新版本
3. 重新选择自动填充服务

### 错误3: parsedStructure is null

**解决:** 确保在调用前已经解析了表单结构
```kotlin
val parsedStructure = enhancedParserV2.parse(structure)
```

## 📚 完整文档

- `INTEGRATION_COMPLETE.md` - 完整集成指南
- `FINAL_SUMMARY.md` - 最终总结
- `MANUAL_INTEGRATION_STEPS.md` - 手动步骤

## ✅ 检查清单

- [ ] 导入了扩展函数
- [ ] 替换了响应创建代码
- [ ] 重新编译项目
- [ ] 卸载旧版本
- [ ] 安装新版本
- [ ] 测试单个密码场景
- [ ] 测试多个密码场景
- [ ] 测试搜索功能

## 🎉 完成!

就这么简单!新的Material Design 3 UI现在应该可以工作了。
