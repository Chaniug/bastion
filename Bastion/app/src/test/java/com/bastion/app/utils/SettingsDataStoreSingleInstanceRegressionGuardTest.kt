package com.bastion.app.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：进程内 "settings" DataStore 必须只有一个委托声明。
 *
 * 背景：AutofillPreferences 从 "autofill_settings" 迁移到统一的 "settings" 存储时，
 * 曾在自己的 companion 里又声明了一次 preferencesDataStore(name = "settings")，
 * 导致同一文件出现两个 DataStore 实例，进入"自动填充设置"页面时直接崩溃。
 */
class SettingsDataStoreSingleInstanceRegressionGuardTest {

    @Test
    fun settingsDataStoreIsDeclaredExactlyOnceInMainSources() {
        val declarations = mainSourceFiles()
            .filter { file ->
                SETTINGS_STORE_DECLARATION.containsMatchIn(file.readText())
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "\"settings\" DataStore 只允许在 AppDataStore.kt 中声明一次，实际声明于：$declarations",
            listOf("AppDataStore.kt"),
            declarations
        )
    }

    @Test
    fun sharedDelegateIsInternalAndReusedByConsumers() {
        val appDataStore = projectFile(
            "app/src/main/java/com/bastion/app/utils/AppDataStore.kt"
        ).readText()
        assertTrue(
            appDataStore.contains(
                "internal val Context.dataStore: DataStore<Preferences> " +
                    "by preferencesDataStore(name = \"settings\")"
            )
        )

        val autofillPreferences = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt"
        ).readText()
        assertTrue(autofillPreferences.contains("import com.bastion.app.utils.dataStore"))
        assertTrue(autofillPreferences.contains("context.dataStore"))
    }

    @Test
    fun legacyAutofillStoreStillMigratesOnce() {
        val autofillPreferences = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt"
        ).readText()

        assertTrue(
            autofillPreferences.contains("preferencesDataStore(name = \"autofill_settings\")")
        )
        assertTrue(autofillPreferences.contains("suspend fun migrateLegacyStoreIfNeeded()"))
        assertTrue(
            autofillPreferences.contains("if (targetPrefs[KEY_AUTOFILL_MIGRATION_DONE] == true) return")
        )

        val application = projectFile(
            "app/src/main/java/com/bastion/app/BastionApplication.kt"
        ).readText()
        assertTrue(application.contains("migrateLegacyAutofillStoreIfNeeded()"))
    }

    private fun mainSourceFiles(): List<File> =
        projectFile("app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }

    private companion object {
        val SETTINGS_STORE_DECLARATION =
            Regex("""by\s+preferencesDataStore\(\s*(?:name\s*=\s*)?"settings"\s*\)""")
    }
}
