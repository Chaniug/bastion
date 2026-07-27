package com.bastion.app.autofill_ng.builder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.bastion.app.R
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import com.bastion.app.autofill_ng.AutofillCipherCallbackActivity
import com.bastion.app.autofill_ng.AutofillPickerActivityV2
import com.bastion.app.autofill_ng.AutofillUnlockActivity
import com.bastion.app.autofill_ng.PasswordSuggestionActivity
import com.bastion.app.autofill_ng.auth.AutofillAuthenticationPolicy
import com.bastion.app.autofill_ng.auth.AutofillGrantContext
import com.bastion.app.autofill_ng.auth.AutofillUnlockRequests
import com.bastion.app.autofill_ng.auth.PendingAutofillUnlockRequest
import com.bastion.app.autofill_ng.builder.AutofillDatasetBuilder
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.autofill_ng.model.AutofillRequest
import com.bastion.app.autofill_ng.model.AutofillView
import com.bastion.app.autofill_ng.model.FilledData
import com.bastion.app.autofill_ng.model.FilledPartition
import com.bastion.app.data.PasswordEntry
import com.bastion.app.utils.PasswordGenerator
import kotlin.random.Random

class FillResponseBuilderNg(
    private val context: Context,
) {
    private companion object {
        private const val TAG = "BastionAutofillBwCompat"
        private const val MANUAL_PLACEHOLDER_VALUE = "PLACEHOLDER"
    }

    fun build(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        passwordSuggestionEnabled: Boolean = true,
        requireAuthentication: Boolean = true,
        matchedPasswords: List<PasswordEntry> = emptyList(),
    ): FillResponse? {
        val fillableAutofillIds = filledData.fillableAutofillIds
        if (fillableAutofillIds.isEmpty()) {
            android.util.Log.w(TAG, "build skipped: no fillableAutofillIds")
            AutofillLogger.w("FILLING", "Build skipped: no fillableAutofillIds")
            return null
        }

        if (AutofillAuthenticationPolicy.requiresResponseUnlock(
                authenticationRequired = requireAuthentication,
                vaultLocked = filledData.isVaultLocked,
                grantActive = false,
            )
        ) {
            return buildLockedResponse(
                request = request,
                filledData = filledData,
                matchedPasswords = matchedPasswords,
                passwordSuggestionEnabled = passwordSuggestionEnabled,
            )
        }

        val responseBuilder = FillResponse.Builder()
        var cipherDatasetCount = 0
        var failedCipherDatasetCount = 0
        var callbackCipherDatasetCount = 0
        filledData.filledPartitions.forEachIndexed { index, partition ->
            if (partition.filledItems.isEmpty()) return@forEachIndexed
            runCatching {
                responseBuilder.addDataset(
                    buildCipherDataset(
                        request = request,
                        partition = partition,
                        index = index,
                    )
                )
                cipherDatasetCount++
                if (partition.requiresAuthentication ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        partition.inlinePresentationSpec != null
                    )
                ) {
                    callbackCipherDatasetCount++
                }
            }.onFailure { error ->
                failedCipherDatasetCount++
                android.util.Log.w(TAG, "Failed to build cipher dataset index=$index", error)
                AutofillLogger.w(
                    "FILLING",
                    "Failed to build cipher dataset",
                    metadata = mapOf(
                        "index" to index,
                        "error" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
        }

        val strongPasswordDataset = if (passwordSuggestionEnabled) {
            buildStrongPasswordSuggestionDataset(
                request = request
            )
        } else {
            null
        }
        if (strongPasswordDataset != null) {
            responseBuilder.addDataset(strongPasswordDataset)
        }

        responseBuilder.addDataset(
            buildVaultItemDataset(
                request = request,
                filledData = filledData,
                fillableAutofillIds = fillableAutofillIds
            )
        )

        if (filledData.ignoreAutofillIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*filledData.ignoreAutofillIds.toTypedArray())
        }

        attachSaveInfoIfNeeded(
            responseBuilder = responseBuilder,
            request = request
        )

        android.util.Log.i(
            TAG,
            "build result: cipherDatasets=$cipherDatasetCount, " +
                "failedCipherDatasets=$failedCipherDatasetCount, " +
                "strongPasswordDataset=${if (strongPasswordDataset != null) 1 else 0}, " +
                "vaultDataset=1, fillableIds=${fillableAutofillIds.size}, " +
                "suggestedIds=${filledData.filledPartitions.count { it.autofillCipher.cipherId != null }}, " +
                "authRequired=$requireAuthentication, sdk=${Build.VERSION.SDK_INT}, " +
                "callbackCipherDatasets=$callbackCipherDatasetCount"
        )
        AutofillLogger.i(
            "FILLING",
            "FillResponse build result",
            metadata = mapOf(
                "cipherDatasets" to cipherDatasetCount,
                "failedCipherDatasets" to failedCipherDatasetCount,
                "strongPasswordDataset" to (strongPasswordDataset != null),
                "vaultDataset" to true,
                "fillableIds" to fillableAutofillIds.size,
                "suggestedIds" to filledData.filledPartitions.count { it.autofillCipher.cipherId != null },
                "authRequired" to requireAuthentication,
                "sdk" to Build.VERSION.SDK_INT,
                "callbackCipherDatasets" to callbackCipherDatasetCount,
            )
        )
        return responseBuilder.build()
    }

    private fun buildLockedResponse(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        matchedPasswords: List<PasswordEntry>,
        passwordSuggestionEnabled: Boolean,
    ): FillResponse {
        val targetIds = filledData.fillableAutofillIds.distinct()
        val grantContext = AutofillGrantContext.fromRequestUri(
            packageName = request.packageName,
            requestUri = request.uri,
            interactionIdentifier = request.interactionIdentifier,
            fieldSignatureKey = request.fieldSignatureKey,
        )
        val requestToken = AutofillUnlockRequests.put(
            PendingAutofillUnlockRequest(
                request = request,
                passwordIds = matchedPasswords.map { it.id }.distinct(),
                passwordSuggestionEnabled = passwordSuggestionEnabled,
                grantContext = grantContext,
            )
        )
        val unlockIntent = AutofillUnlockActivity.getIntent(context, requestToken)
        val pendingIntent = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            unlockIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        val unlockTitle = context.getString(R.string.autofill_unlock_bastion)
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createUnlockPrompt(
            context = context,
            message = unlockTitle,
        )
        val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val spec = filledData.vaultItemInlinePresentationSpec
                ?: request.inlinePresentationSpecs?.firstOrNull()
            spec?.let {
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = it,
                    specs = request.inlinePresentationSpecs,
                    index = request.inlinePresentationSpecs?.indexOf(it) ?: 0,
                    pendingIntent = pendingIntent,
                    title = unlockTitle,
                    subtitle = grantContext.webDomain ?: request.packageName,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName,
                    ),
                    contentDescription = unlockTitle,
                )
            }
        } else {
            null
        }

        val responseBuilder = FillResponse.Builder()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val presentations = Presentations.Builder()
                    .setMenuPresentation(menuPresentation)
                    .apply {
                        if (inlinePresentation != null) {
                            setInlinePresentation(inlinePresentation)
                        }
                    }
                    .build()
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    presentations,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                @Suppress("DEPRECATION")
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    menuPresentation,
                    inlinePresentation,
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    menuPresentation,
                )
            }
        }
        if (filledData.ignoreAutofillIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*filledData.ignoreAutofillIds.toTypedArray())
        }
        AutofillLogger.i(
            "AUTH",
            "Locked vault response contains one response-level unlock action",
            metadata = mapOf(
                "packageName" to request.packageName,
                "webDomain" to (grantContext.webDomain ?: "none"),
                "targetCount" to targetIds.size,
                "passwordCount" to matchedPasswords.size,
            )
        )
        return responseBuilder.build()
    }

    private fun buildCipherDataset(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        index: Int,
    ): android.service.autofill.Dataset {
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = context,
            title = partition.autofillCipher.name,
            username = partition.autofillCipher.subtitle
        )

        val hasInlinePresentation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            partition.inlinePresentationSpec != null
        val callbackTargets = buildLoginCallbackTargets(request.partition.views)
        // 回归修复：原本仅在 requiresAuthentication || hasInlinePresentation 时才给数据集挂
        // AutofillCipherCallbackActivity。但 partition.requiresAuthentication 在 vault 解锁时
        // 恒为 false（requiresAuthentication && isVaultLocked），于是 vault 解锁后的常规填充
        // 会被框架“直接填充”而不经过任何 Activity，OTP 自动复制副作用永远无法触发。
        // 这里让所有凭据数据集都挂上 cipher callback；当 requireAuthentication=false 时，
        // callback 内部会跳过二次认证、直接完成填充并触发 OTP 复制，恢复旧架构
        // “菜单/inline 点选 → Activity → 填充 + OTP”的预期行为。
        val authPendingIntent = createCipherAuthPendingIntent(
            request = request,
            partition = partition,
            callbackTargets = callbackTargets,
            requireAuthentication = partition.requiresAuthentication,
        )
        if (partition.requiresAuthentication && authPendingIntent == null) {
            throw IllegalStateException("Authentication required but cipher callback pending intent is unavailable")
        }

        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        partition.filledItems.forEach { filledItem ->
            fields[filledItem.autofillId] = AutofillDatasetBuilder.FieldData(
                value = filledItem.value,
                presentation = menuPresentation
            )
        }

        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val spec = partition.inlinePresentationSpec ?: return@create null
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = index,
                    pendingIntent = authPendingIntent ?: return@create null,
                    title = partition.autofillCipher.name,
                    subtitle = partition.autofillCipher.subtitle,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = partition.autofillCipher.name
                )
            } else {
                null
            }
        }
        // 关键修复：原先要求 requiresAuthentication 才挂认证，导致 vault 解锁后的常规填充
        // 走“直接填充”而绕开 OTP 复制。现改为：只要有 cipher callback PendingIntent 就挂上，
        // 由 callback 内部按 requireAuthentication 决定是否二次认证。
        if (authPendingIntent != null) {
            datasetBuilder.setAuthentication(authPendingIntent.intentSender)
            android.util.Log.d(
                "BastionOtpCopy",
                "dataset auth attached: cipherId=${partition.autofillCipher.cipherId}, " +
                    "requiredAuth=${partition.requiresAuthentication}, inline=$hasInlinePresentation"
            )
        }
        return datasetBuilder.build()
    }

    private fun buildStrongPasswordSuggestionDataset(
        request: AutofillRequest.Fillable,
    ): android.service.autofill.Dataset? {
        val passwordHints = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .map { it.data.hint }
        if (!StrongPasswordSuggestionPolicy.shouldOffer(passwordHints)) {
            return null
        }

        val newPasswordIds = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .filter { it.data.hint == FieldHint.NEW_PASSWORD }
            .map { it.data.autofillId }
            .distinct()
            .ifEmpty { return null }

        val pendingIntent = createStrongPasswordSuggestionPendingIntent(
            request = request,
            passwordFieldIds = newPasswordIds,
        )
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordSuggestion(context)
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        newPasswordIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = menuPresentation,
            )
        }

        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val spec = request.inlinePresentationSpecs?.firstOrNull() ?: return@create null
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = 0,
                    pendingIntent = pendingIntent,
                    title = context.getString(R.string.password_suggestion_title),
                    subtitle = context.getString(R.string.password_suggestion_subtitle),
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = context.getString(R.string.password_suggestion_title)
                )
            } else {
                null
            }
        }
        datasetBuilder.setAuthentication(pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun createStrongPasswordSuggestionPendingIntent(
        request: AutofillRequest.Fillable,
        passwordFieldIds: List<AutofillId>,
    ): PendingIntent {
        val webDomain = extractWebDomain(request.uri)
        val username = request.partition.views
            .filterIsInstance<AutofillView.Login.Username>()
            .firstOrNull { !it.data.textValue.isNullOrBlank() }
            ?.data
            ?.textValue
            .orEmpty()
        val generatedPassword = PasswordGenerator().generatePassword(
            PasswordGenerator.PasswordOptions(
                length = 16,
                includeUppercase = true,
                includeLowercase = true,
                includeNumbers = true,
                includeSymbols = true,
                excludeSimilar = true,
            )
        )

        val intent = Intent(context, PasswordSuggestionActivity::class.java).apply {
            putExtra(PasswordSuggestionActivity.EXTRA_USERNAME, username)
            putExtra(PasswordSuggestionActivity.EXTRA_GENERATED_PASSWORD, generatedPassword)
            putExtra(PasswordSuggestionActivity.EXTRA_PACKAGE_NAME, request.packageName)
            putExtra(PasswordSuggestionActivity.EXTRA_WEB_DOMAIN, webDomain)
            putParcelableArrayListExtra(
                PasswordSuggestionActivity.EXTRA_PASSWORD_FIELD_IDS,
                ArrayList(passwordFieldIds)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun createCipherAuthPendingIntent(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        callbackTargets: List<AutofillCallbackTarget>,
        requireAuthentication: Boolean,
    ): PendingIntent? {
        val passwordId = partition.autofillCipher.cipherId?.toLongOrNull() ?: return null
        val targets = callbackTargets.ifEmpty {
            partition.filledItems
                .map { AutofillCallbackTarget(it.autofillId, "") }
                .distinctBy { it.autofillId.toString() }
        }
        if (targets.isEmpty()) return null

        val args = AutofillCipherCallbackActivity.Args(
            passwordId = passwordId,
            applicationId = request.packageName,
            webDomain = extractWebDomain(request.uri),
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(targets.map { it.autofillId }),
            autofillHints = ArrayList(targets.map { it.hintName }),
            fieldSignatureKey = request.fieldSignatureKey,
            rememberLastFilled = true,
            requireAuthentication = requireAuthentication,
        )
        val pickerIntent = AutofillCipherCallbackActivity.getIntent(context, args)
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun buildVaultItemDataset(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): android.service.autofill.Dataset {
        val targetIds = fillableAutofillIds.distinct()
        val manualEntry = buildManualEntryArtifacts(
            request = request,
            filledData = filledData,
            fillableAutofillIds = targetIds,
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        targetIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                // Bitwarden-compatible approach:
                // keep one authenticated manual dataset anchored to all fillable fields
                // with a placeholder value so framework keeps entry visible consistently.
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = manualEntry.menuPresentation
            )
        }
        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = manualEntry.menuPresentation,
            fields = fields
        ) { manualEntry.inlinePresentation }
        datasetBuilder.setAuthentication(manualEntry.pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun buildManualEntryArtifacts(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): ManualEntryArtifacts {
        val webDomain = extractWebDomain(request.uri)
        val autofillHints = buildAutofillHintNames(request.partition.views)
        val suggestedPasswordIds = filledData.filledPartitions
            .mapNotNull { it.autofillCipher.cipherId?.toLongOrNull() }
            .distinct()
            .toLongArray()
        val args = AutofillPickerActivityV2.Args(
            applicationId = request.packageName,
            webDomain = webDomain,
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(fillableAutofillIds),
            autofillHints = ArrayList(autofillHints),
            suggestedPasswordIds = suggestedPasswordIds,
            isSaveMode = false,
            fieldSignatureKey = request.fieldSignatureKey,
            responseAuthMode = false,
            rememberLastFilled = true,
        )
        val pickerIntent = AutofillPickerActivityV2.getIntent(context, args)
        val pendingIntent = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )

        val menuPresentation = if (filledData.isVaultLocked) {
            AutofillDatasetBuilder.RemoteViewsFactory.createUnlockPrompt(
                context = context,
                message = context.getString(R.string.autofill_manual_entry_title)
            )
        } else {
            AutofillDatasetBuilder.RemoteViewsFactory.createManualSelection(
                context = context,
                domain = webDomain,
                packageName = request.packageName
            )
        }

        val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            filledData.vaultItemInlinePresentationSpec?.let { spec ->
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = request.inlinePresentationSpecs?.indexOf(spec) ?: 0,
                    pendingIntent = pendingIntent,
                    title = context.getString(R.string.autofill_manual_entry_title),
                    subtitle = webDomain?.takeIf { it.isNotBlank() } ?: request.packageName,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = context.getString(R.string.autofill_manual_entry_title)
                )
            }
        } else {
            null
        }

        return ManualEntryArtifacts(
            pendingIntent = pendingIntent,
            menuPresentation = menuPresentation,
            inlinePresentation = inlinePresentation,
        )
    }

    private fun extractWebDomain(uri: String?): String? =
        uri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun attachSaveInfoIfNeeded(
        responseBuilder: FillResponse.Builder,
        request: AutofillRequest.Fillable,
    ) {
        // Bitwarden-compatible: skip save for login fields in compat mode because password
        // values can be masked and lead to low-quality save prompts.
        if (request.isCompatMode) return
        if (!request.partition.canPerformSaveRequest) return
        val requiredIds = request.partition.requiredSaveIds.toTypedArray()
        if (requiredIds.isEmpty()) return

        val saveInfoBuilder = SaveInfo.Builder(request.partition.saveType, requiredIds)
        val requiredSet = requiredIds.toSet()
        val optionalIds = request.partition.optionalSaveIds
            .filterNot { requiredSet.contains(it) }
            .toTypedArray()
        if (optionalIds.isNotEmpty()) {
            saveInfoBuilder.setOptionalIds(optionalIds)
        }
        responseBuilder.setSaveInfo(saveInfoBuilder.build())
    }
}

private data class ManualEntryArtifacts(
    val pendingIntent: PendingIntent,
    val menuPresentation: android.widget.RemoteViews,
    val inlinePresentation: InlinePresentation?,
)

private data class AutofillCallbackTarget(
    val autofillId: AutofillId,
    val hintName: String,
)

private fun buildLoginCallbackTargets(views: List<AutofillView>): List<AutofillCallbackTarget> {
    val seenIds = mutableSetOf<String>()
    return views.mapNotNull { view ->
        val target = when (view) {
            is AutofillView.Login.Username -> AutofillCallbackTarget(view.data.autofillId, "USERNAME")
            is AutofillView.Login.Password -> AutofillCallbackTarget(view.data.autofillId, "PASSWORD")
            is AutofillView.Field -> null
        }
        target?.takeIf { seenIds.add(it.autofillId.toString()) }
    }
}

private fun buildAutofillHintNames(views: List<AutofillView>): List<String> {
    return views.map { view ->
        when (view) {
            is AutofillView.Login.Username -> "USERNAME"
            is AutofillView.Login.Password -> "PASSWORD"
            is AutofillView.Field -> view.hint.name
        }
    }
}

private val FilledData.fillableAutofillIds: List<AutofillId>
    get() = originalPartition.views.map { it.data.autofillId }

