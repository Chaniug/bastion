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
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
        }
    }
}
