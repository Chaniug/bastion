# Phase A：AGP 9 下自定义 APK 文件名还原方案

> 状态：待实施（当前 dev 分支为尽快产出可真机验证的 preview 包，已临时移除失效块）
> 关联：Phase A 跨代升级（AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.2，目标 Android 17 / API 37）
> 负责人：接续开发的 agent 在确认本方案后实施

## 1. 背景与根因

原 `Bastion/app/build.gradle` 在 `android {}` 块内用旧版 variant API 自定义 APK 输出文件名：

```groovy
applicationVariants.all { variant ->
    variant.outputs.all { output ->
        def archTag = resolveArchTag(output)
        def versionNameTag = sanitizeTag(project.findProperty('apkVersionName') ?: variant.versionName ?: defaultConfig.versionName, '0.0.0')
        def namePrefix = "${apkPrefix}-Android-${archTag}-${versionNameTag}-${buildDateTag}${buildVersionTag}"
        outputFileName = "${namePrefix}-${buildDailySeq}.APK"
    }
}
```

升级到 **AGP 9.0+** 后，构建门在配置阶段直接失败：

```
Could not get unknown property 'applicationVariants' for object of type
com.android.build.gradle.internal.dsl.ApplicationExtensionImpl$AgpDecorated.
```

**根因**（已查证 AGP 9.0 官方发行说明与 `android/gradle-recipes` `agp-9.0` 分支）：

- AGP 9 彻底移除了 `applicationVariants` / `libraryVariants` / `testVariants` / `unitTestVariants` 这四个旧 variant 扩展点，官方唯一替代是 `androidComponents.onVariants` API。
- 新 `VariantOutput` 接口（`com.android.build.api.variant.VariantOutput`）**只暴露** `versionCode` / `versionName` / `outputType`，**没有可写的 `outputFileName`**。因此不能在 `onVariants` 里直接 `output.outputFileName = "..."`。
- 官方「Renaming APKs」示例（gradle-recipes 的 `listenToArtifacts` 配方）明确指出：AGP 9 重命名 APK 的唯一正确方式是 `androidComponents` + `SingleArtifact.APK` 的 **`listenToArtifacts` 机制**——注册一个监听 APK 产出、把 APK 复制到新名字的任务，并通过 `BuiltArtifactsLoader` 读取/写入 `output-metadata.json` 元数据。

## 2. 当前临时处置（dev 已合并）

为让构建门尽快越过配置阶段、进入真正的 Kotlin 编译并产出可真机验证的 preview 包，已**临时移除**上述 `applicationVariants` 块。

- 产物文件名回退为 Gradle 默认命名：`app-arm64-v8a-debug.apk`（因 `splits.abi.include 'arm64-v8a'` + `universalApk false`，恒为单 ABI）。
- CI 的 APK 收集通配符 `*.apk` / `*.APK`（upload-artifact、action-gh-release、apk_meta 步骤）对默认命名同样生效，**出包与发布链路不受影响**。
- 原辅助闭包 `sanitizeTag` / `resolveArchTag` 暂留作未使用代码，供本方案实施后直接复用。

> 注意：`sanitizeTag`、`resolveArchTag`、`findNextDailySeqForVersion` 中，`findNextDailySeqForVersion` 仍被 `buildDailySeq` 使用（决定文件名序号），不可删；`sanitizeTag` / `resolveArchTag` 当前仅服务于被移除的命名逻辑。

## 3. 还原方案（AGP 9 正确写法）

采用 `listenToArtifacts`：在 `androidComponents.onVariants` 中为每个 variant 注册一个重命名任务，监听 `SingleArtifact.APK`，把产出复制为 `Bastion-Android-<arch>-<versionName>-<yyMMdd><VV>-<SS>.APK`。

### 3.1 关键事实（来自 gradle-recipes `listenToArtifacts`）

- `variant.artifacts.getBuiltArtifactsLoader()`：返回 `Provider<BuiltArtifactsLoader>`。
- `variant.artifacts.use(task).wiredWith { it.inputDir }.toListenTo(SingleArtifact.APK)`：把任务接线为 APK 产出后的监听者，`inputDir` 自动指向 APK 目录。
- `BuiltArtifactsLoader.load(File)` 返回 `BuiltArtifacts`（可能为 null），其 `elements` 为 `Collection<BuiltArtifact>`，每个元素有 `outputFile`(String)、`versionName`、`versionCode`、`filters`(Collection<FilterConfiguration>)。
- `FilterConfiguration.getFilterType()` 为枚举（`ABI`/`DENSITY`/`LANGUAGE`），`getIdentifier()` 为 ABI 名（如 `arm64-v8a`）。

### 3.2 建议实现（Groovy，置于 `app/build.gradle`）

> 注意：Groovy 脚本里定义的类**无法访问脚本局部变量**，因此命名所需的字符串（archTag / versionNameTag / 日期 / 序号）应在 `onVariants` 闭包里**计算好后作为任务属性传入**，不要写在任务类内部去引用脚本变量。

```groovy
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader

androidComponents {
    onVariants(selector().all()) { variant ->
        // 本工程恒产 arm64-v8a 单 ABI（splits.abi.include 'arm64-v8a' + universalApk false），
        // 故 archTag 直接取 apkArch 覆盖或固定 'arm64-v8a'，无需再解析 variant 过滤器。
        def archTag = project.findProperty('apkArch') ? sanitizeTag(project.findProperty('apkArch'), 'universal') : 'arm64-v8a'
        def vnTag = sanitizeTag(project.findProperty('apkVersionName') ?: baseVersionName, '0.0.0')

        def renameTask = tasks.register("rename${variant.name.capitalize()}Apk", RenameBastionApk) { t ->
            // inputDir 由 toListenTo(SingleArtifact.APK) 自动接线
            t.outputDir.set(layout.buildDirectory.dir("outputs/apk/${variant.name}/renamed"))
            t.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            t.archTag.set(archTag)
            t.versionNameTag.set(vnTag)
            t.dateTag.set(buildDateTag)
            t.versionTag.set(buildVersionTag)
            t.dailySeq.set(buildDailySeq)
        }
        variant.artifacts.use(renameTask).wiredWith { it.inputDir }.toListenTo(SingleArtifact.APK)
    }
}

abstract class RenameBastionApk extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract Property<File> getInputDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @Internal
    abstract Property<BuiltArtifactsLoader> getBuiltArtifactsLoader()

    @Internal abstract Property<String> getArchTag()
    @Internal abstract Property<String> getVersionNameTag()
    @Internal abstract Property<String> getDateTag()
    @Internal abstract Property<String> getVersionTag()
    @Internal abstract Property<String> getDailySeq()

    @TaskAction
    void run() {
        def out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        def built = builtArtifactsLoader.get().load(inputDir.get())
        if (built == null) throw new GradleException("Cannot load APKs from ${inputDir.get()}")
        built.elements.each { art ->
            def name = "Bastion-Android-${archTag.get()}-${versionNameTag.get()}-${dateTag.get()}${versionTag.get()}-${dailySeq.get()}.APK"
            java.nio.file.Files.copy(new File(art.outputFile).toPath(), new File(out, name).toPath())
        }
    }
}
```

### 3.3 必须同步修改 CI（` .github/workflows/main.yml`）

重命名后的 APK 落在 `app/build/outputs/apk/<variant>/renamed/`，需把以下 4 处 APK 收集路径从 `apk/debug` 改为 `apk/debug/renamed`（或递归 `apk/**`）：

1. `List debug APK outputs` 步骤：`find app/build/outputs/apk/debug/renamed -maxdepth 1 -type f -print`
2. `Upload APK`（upload-artifact）：`Bastion/app/build/outputs/apk/debug/renamed/*.apk` 与 `*.APK`
3. `Collect debug APK metadata`（apk_meta）：`cd app/build/outputs/apk/debug/renamed` 后 `find . -maxdepth 1 -iname '*.apk'`
4. `Publish Debug APK to preview Release`（action-gh-release）`files`：`Bastion/app/build/outputs/apk/debug/renamed/*.apk` 与 `*.APK`

## 4. 验收标准

- `./gradlew :app:assembleDebug` 产出 `Bastion-Android-arm64-v8a-1.0.<n>-<yyMMdd><VV>-<SS>.APK`。
- dev 分支 CI 绿，preview Release 页列出该命名 APK。
- 真机（荣耀 Android 17）可正常安装并覆盖旧版（版本号严格递增由 `versionCode` 保证）。

## 5. 风险与备注

- `RenameBastionApk` 类必须定义在 `androidComponents {}` 引用它**之前**（脚本编译单元内类通常全局可见，但保险起见放 `plugins` 之后、`android {}` 之前，并带 `import`）。
- 若 `toListenTo` 未被 `assembleDebug` 自动依赖，可显式 `tasks.named("assemble${variant.name.capitalize()}") { dependsOn renameTask }`。
- 当前临时处置不影响功能，本方案可在 dev 编译错误清零、preview 包可测后再实施，无需阻塞出包。
