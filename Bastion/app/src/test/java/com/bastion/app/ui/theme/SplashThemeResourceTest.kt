package com.bastion.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashThemeResourceTest {
    @Test
    fun systemSplashFallbackUsesBastionM3LightAndDarkColors() {
        val lightColors = projectFile("app/src/main/res/values/colors.xml").readText()
        val darkColors = projectFile("app/src/main/res/values-night/colors.xml").readText()
        val lightApi31 = projectFile("app/src/main/res/values-v31/colors.xml").readText()
        val darkApi31 = projectFile("app/src/main/res/values-night-v31/colors.xml").readText()
        val lightTheme = projectFile("app/src/main/res/values/themes.xml").readText()
        val darkTheme = projectFile("app/src/main/res/values-night/themes.xml").readText()

        assertTrue(lightColors.contains("<color name=\"bastion_startup_background\">#F7F2FA</color>"))
        assertTrue(darkColors.contains("<color name=\"bastion_startup_background\">#1C1B1F</color>"))
        assertTrue(!lightApi31.contains("system_neutral"))
        assertTrue(!darkApi31.contains("system_neutral"))
        assertTrue(lightApi31.contains("android:windowSplashScreenBackground"))
        assertTrue(darkApi31.contains("android:windowSplashScreenBackground"))
        assertTrue(lightTheme.contains("<item name=\"android:windowLightStatusBar\">true</item>"))
        assertTrue(lightTheme.contains("<item name=\"android:windowLightNavigationBar\">true</item>"))
        assertTrue(darkTheme.contains("<item name=\"android:windowLightStatusBar\">false</item>"))
        assertTrue(darkTheme.contains("<item name=\"android:windowLightNavigationBar\">false</item>"))
    }

    @Test
    fun startupUsesOnlyTheAndroidSystemSplashLayer() {
        val mainActivity = projectFile(
            "app/src/main/java/com/bastion/app/MainActivity.kt"
        ).readText()
        val baseActivity = projectFile(
            "app/src/main/java/com/bastion/app/ui/base/BaseBastionActivity.kt"
        ).readText()

        assertTrue(!mainActivity.contains("BastionStartupSplash"))
        assertTrue(!mainActivity.contains("doOnPreDraw"))
        assertTrue(!mainActivity.contains("private fun initializeMainContent()"))
        assertTrue(!baseActivity.contains("cachedSettings = startupSettings"))
        assertTrue(mainActivity.split("installSplashScreen()").size - 1 == 1)
        assertTrue(mainActivity.contains("setContent {"))
        assertTrue(mainActivity.contains("BastionApp(repository"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
