package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillInlineClickRegressionGuardTest {

    @Test
    fun directInlineSuggestionsUseRealAuthenticationCallbackInsteadOfNoopIntent() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/builder/FillResponseBuilderNg.kt"
        ).readText()
        val cipherDatasetBody = source.substringAfter("private fun buildCipherDataset(")
            .substringBefore("private fun buildStrongPasswordSuggestionDataset(")

        assertTrue(
            "Locked inline suggestions should still create the real callback PendingIntent for keyboards that launch the slice PendingIntent.",
            cipherDatasetBody.contains("val attachAuth = partition.requiresAuthentication") &&
                cipherDatasetBody.contains("createCipherAuthPendingIntent(")
        )
        assertTrue(
            "The real callback PendingIntent should be wired to the inline presentation itself when present.",
            cipherDatasetBody.contains("val intent = authPendingIntent ?: return@create null") &&
                cipherDatasetBody.contains("pendingIntent = intent")
        )
        assertFalse(
            "Inline suggestion clicks must not be wired to a no-op PendingIntent, because some keyboards launch it instead of applying dataset values.",
            cipherDatasetBody.contains("createNoopPendingIntent")
        )
        assertTrue(
            "Dataset authentication is only attached for locked authenticated suggestions; unlocked suggestions fill directly (Bitwarden-style).",
            cipherDatasetBody.contains("if (attachAuth && authPendingIntent != null)") &&
                cipherDatasetBody.contains("datasetBuilder.setAuthentication(authPendingIntent.intentSender)")
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
