import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)

    implementation(libs.kotlinx.coroutines.javafx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // KDBX 解析（shared jvmMain 中 KeePassKdbxService 的 public API 暴露了 kotpass 类型）
    implementation(libs.kotpass)
}

kotlin {
    // CI 已统一到 JDK 21（见 .github/workflows/*.yml）。jvmToolchain 若仍写 17，
    // 在只有 JDK 21 的构建环境下 Gradle 会去找 JDK 17 工具链并失败。
    jvmToolchain(21)
    compilerOptions {
        // 必须与 jvmToolchain 一致：Kotlin 2.x 会校验 compileJava 的 target（由 toolchain
        // 推导为 21）与 compileKotlin 的 jvmTarget 是否相同，不一致直接构建失败：
        //   Inconsistent JVM-target compatibility detected for tasks
        //   'compileJava' (21) and 'compileKotlin' (17)
        // 桌面端无 Android API 限制，且 jpackage 打包时自带 JRE 由 JDK 21 的 jlink 生成，
        // 直接用 21 是安全的（注意：这里不能只升 toolchain 而留 jvmTarget 17）。
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

compose.desktop {
    application {
        mainClass = "com.bastion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "BastionDesktop"
            // 发布版本号由 CI 通过 -Pbastion.packageVersion=<version> 注入（与 GitHub Release 标签保持一致），
            // 本地构建默认 0.1.0。避免安装包文件名（BastionDesktop-<version>.msi/.exe）与 Release 版本不一致，
            // 导致用户把新版安装包误当成旧版（jpackage 检测到已安装同版本会报 1638 进入维护模式）。
            packageVersion = (findProperty("bastion.packageVersion") as? String)
                ?.takeIf { it.isNotBlank() } ?: "0.1.0"
            description = "Bastion Password Manager - Bitwarden sync, KDBX editor, OneDrive sync"
            // 显式指定内置 JRE 的 jlink 模块。SQLDelight JdbcSqliteDriver 通过
            // Class.forName 反射加载 org.sqlite.JDBC 并调用 java.sql.DriverManager，
            // jdeps 静态分析看不到反射依赖，默认模块列表不含 java.sql，
            // 会导致打包后启动即崩 java.lang.NoClassDefFoundError: java/sql/DriverManager
            // （jpackage 启动器弹 "Failed to launch JVM"）。
            modules(
                "java.base", "java.datatransfer", "java.xml", "java.prefs",
                "java.desktop", "java.logging", "java.sql", "jdk.crypto.ec"
            )
            windows {
                // 固定升级 UUID：保证各版本/各次构建的 MSI 走同一条升级链，
                // 避免升级安装变成“已安装同版本(1638)维护模式”或残留多个注册项。
                upgradeUuid = "7a4f1c9e-2b3d-4e5f-8a6b-1c2d3e4f5a6b"
            }
        }
    }
}
