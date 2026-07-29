package com.bastion.app.service

import com.bastion.app.logging.runCatchingObserved
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.bastion.app.autofill_ng.ActiveFillPromptThrottle
import com.bastion.app.autofill_ng.AutofillPreferences
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.linkedAppBindings
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.autofill_ng.ActiveFillNotificationHelper

internal data class TemporaryClipboardSnapshot(
    val text: String?,
    val label: String?,
    val canVerify: Boolean,
)

internal fun shouldRestoreTemporaryClipboard(
    snapshot: TemporaryClipboardSnapshot,
    expectedLabel: String,
    expectedText: String,
): Boolean {
    return !snapshot.canVerify ||
        (snapshot.label == expectedLabel && snapshot.text == expectedText)
}

class BastionAccessibilityService : AccessibilityService() {
    private var lastPackageName: String = ""
    private var lastUrl: String = ""
    private var lastScanTime = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passwordRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PasswordRepository(PasswordDatabase.getDatabase(applicationContext).passwordEntryDao())
    }
    private val autofillPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AutofillPreferences(applicationContext)
    }
    private val activeFillPromptThrottle = ActiveFillPromptThrottle(ACTIVE_FILL_THROTTLE_MS)
    @Volatile
    private var activeFillNotificationEnabled = false
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private val temporaryClipboardLock = Any()
    private var temporaryClipboardRestoreRunnable: Runnable? = null
    private var temporaryClipboardGeneration = 0L
    private var temporaryClipboardOriginal: ClipData? = null
    private var temporaryClipboardOriginalWasReadable = false
    private var temporaryClipboardActive = false
    private var temporaryClipboardExpectedText: String? = null

    companion object {
        private const val TAG = "BastionAccessibility"
        private const val SCAN_THROTTLE_MS = 400L
        private const val MAX_SCAN_DEPTH = 8
        private const val MAX_SCAN_NODES = 300
        private const val MAX_FILL_SCAN_NODES = 400
        private const val SCORE_PASSWORD_SIGNAL = 40
        private const val SCORE_USERNAME_SIGNAL = 24
        private const val SCORE_FOCUSED_BONUS = 12
        private const val SCORE_PASSWORD_FLAG = 90
        private const val SCORE_CONFIRM_PENALTY = 35
        private const val ACTIVE_FILL_THROTTLE_MS = 5000L
        private const val TEMPORARY_CLIPBOARD_LABEL = "Bastion autofill"
        private const val TEMPORARY_CLIPBOARD_RESTORE_DELAY_MS = 500L

        @Volatile
        private var activeInstance: BastionAccessibilityService? = null

        private data class BrowserSpec(
            val packageName: String,
            val urlFieldIds: Set<String>,
        )

        private val browserSpecsByPackage = listOf(
            BrowserSpec(
                packageName = "org.mozilla.fenix",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.firefox",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.firefox_beta",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "org.mozilla.fenix.nightly",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "io.github.forkmaintainers.iceraven",
                urlFieldIds = setOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            ),
            BrowserSpec(
                packageName = "com.android.chrome",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.beta",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.dev",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.chrome.canary",
                urlFieldIds = setOf("url_bar"),
            ),
            BrowserSpec(
                packageName = "com.google.android.apps.chrome",
                urlFieldIds = setOf("url_bar"),
            ),
        ).associateBy { it.packageName }

        private val trackedEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )

        fun requestCredentialFill(
            targetPackageName: String?,
            username: String,
            password: String,
            preferPasswordField: Boolean,
        ): Boolean {
            return activeInstance?.fillCredentialsInActiveWindow(
                targetPackageName = targetPackageName,
                username = username,
                password = password,
                preferPasswordField = preferPasswordField
            ) ?: false
        }

        fun getActiveWindowPackageName(): String? {
            // P6: rootInActiveWindow 是 Binder IPC，目标窗口所属进程已死亡时会抛 DeadObjectException，安全降级
            return activeInstance?.let { runCatchingObserved { it.rootInActiveWindow }.getOrNull() }
                ?.packageName?.toString()
        }

        fun isCredentialFillAvailable(context: Context): Boolean {
            // 服务运行在独立进程(:accessibility)，进程内的 activeInstance 对主进程不可见，
            // 因此只能用系统级 isServiceEnabled 判定可用性，不能依赖 activeInstance != null。
            return isServiceEnabled(context)
        }

        fun isServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val expectedClass = BastionAccessibilityService::class.java.name
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    if (serviceInfo.packageName != context.packageName) return@any false
                    // serviceInfo.name 在不同 Android 版本/解析路径下可能是相对名(以"."开头，
                    // 例如 ".service.BastionAccessibilityService")，也可能是完整类名(FQN)。
                    // 统一归一成完整类名再比对，避免“系统已开启无障碍但 App 判定为未开启”。
                    val resolvedName = if (serviceInfo.name.startsWith(".")) {
                        serviceInfo.packageName + serviceInfo.name
                    } else {
                        serviceInfo.name
                    }
                    resolvedName == expectedClass
                }
        }
    }

    private enum class FillFieldType {
        USERNAME,
        PASSWORD,
        UNKNOWN
    }

    private data class FillCandidate(
        val node: AccessibilityNodeInfo,
        val type: FillFieldType,
        val score: Int,
        val isFocused: Boolean,
        val top: Int,
        val left: Int
    )

    private data class TemporaryClipboardToken(
        val generation: Long,
        val label: String,
        val text: String,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        // 提供应用上下文，使 BrowserAutofillContextStore 能跨进程(:accessibility 写、
        // :autofill 读)共享同一 filesDir 文件中的浏览器填充上下文。
        BrowserAutofillContextStore.attach(applicationContext)
        serviceScope.launch {
            autofillPreferences.isActiveFillNotificationEnabled.collectLatest { enabled ->
                activeFillNotificationEnabled = enabled
                if (enabled) {
                    ActiveFillNotificationHelper.createChannel(this@BastionAccessibilityService)
                } else {
                    activeFillPromptThrottle.clear()
                    ActiveFillNotificationHelper.dismissNotification(this@BastionAccessibilityService)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatchingObserved {
            event ?: return
            if (event.eventType !in trackedEventTypes) return

            val packageName = event.packageName?.toString().orEmpty()
            val browserSpec = browserSpecsByPackage[packageName]
            if (browserSpec != null) {
                // P1: URL 基本只在窗口切换(TYPE_WINDOW_STATE_CHANGED)时变化；
                // TYPE_WINDOW_CONTENT_CHANGED 在浏览时极频繁且通常不改 URL，
                // 仅在尚未拿到 URL(lastUrl 为空)时补扫一次，避免主线程高频遍历节点树。
                val et = event.eventType
                val shouldScan = et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    (et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && lastUrl.isBlank())
                if (!shouldScan) return

                val now = System.currentTimeMillis()
                if (packageName == lastPackageName && now - lastScanTime < SCAN_THROTTLE_MS) return
                lastScanTime = now

                // P2: 节点遍历移到后台线程，避免阻塞无障碍服务主线程(消除 ANR/卡顿风险)。
                // 后台仅做只读遍历；拿到 URL 后回到主线程写上下文，保持与原实现一致线程模型。
                serviceScope.launch(Dispatchers.Default) {
                    runCatchingObserved {
                        // P6: rootInActiveWindow 是 Binder IPC，目标窗口进程死亡时抛 DeadObjectException，安全降级
                        val root = runCatchingObserved { rootInActiveWindow }.getOrNull() ?: return@launch
                        val url = findBrowserUrl(root, browserSpec) ?: return@launch
                        if (packageName == lastPackageName && url == lastUrl) return@launch
                        withContext(Dispatchers.Main.immediate) {
                            lastPackageName = packageName
                            lastUrl = url
                            BrowserAutofillContextStore.update(packageName, url)
                            ValidatorContextManager.updateContext(packageName, url)
                            Log.d(TAG, "Updated browser context: pkg=$packageName, hasUrl=${url.isNotBlank()}")
                        }
                    }.onFailure { e -> Log.w(TAG, "browser context scan failed", e) }
                }
                return
            }

            // 阶段1：非浏览器 App 的登录字段聚焦 -> 主动发填充通知
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                maybePromptActiveFill(packageName, event.source)
            }
        }.onFailure { e ->
            Log.w(TAG, "onAccessibilityEvent failed", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        ActiveFillNotificationHelper.dismissNotification(this)
        restoreTemporaryClipboardImmediately()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun fillCredentialsInActiveWindow(
        targetPackageName: String?,
        username: String,
        password: String,
        preferPasswordField: Boolean,
    ): Boolean = runCatchingObserved {
        val root = rootInActiveWindow ?: return@runCatchingObserved false
        val activePackageName = root.packageName?.toString().orEmpty()
        if (
            !targetPackageName.isNullOrBlank() &&
            !activePackageName.equals(targetPackageName, ignoreCase = true)
        ) {
            Log.d(TAG, "Skip accessibility fill: active package mismatch ($activePackageName != $targetPackageName)")
            return@runCatchingObserved false
        }

        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val candidates = collectFillCandidates(root, focusedNode)
        if (candidates.isEmpty()) {
            Log.d(TAG, "Skip accessibility fill: no editable candidates")
            return@runCatchingObserved false
        }

        val focusedCandidate = candidates.firstOrNull { it.isFocused }
        val passwordCandidate = selectBestCandidate(
            candidates = candidates,
            preferredType = FillFieldType.PASSWORD,
            excludedNode = null,
            fallback = if (preferPasswordField) {
                focusedCandidate?.node
            } else {
                nearestCandidate(candidates, focusedCandidate?.node, null)?.node
            }
        )
        val usernameCandidate = selectBestCandidate(
            candidates = candidates,
            preferredType = FillFieldType.USERNAME,
            excludedNode = passwordCandidate?.node,
            fallback = if (!preferPasswordField) {
                focusedCandidate?.node
            } else {
                nearestCandidate(candidates, focusedCandidate?.node, passwordCandidate?.node)?.node
            }
        )

        var usernameFilled = false
        var passwordFilled = false

        if (username.isNotBlank()) {
            usernameFilled = setNodeText(usernameCandidate?.node, username)
        }
        if (password.isNotBlank()) {
            passwordFilled = setNodeText(passwordCandidate?.node, password)
        }

        if (!usernameFilled && !passwordFilled) {
            Log.d(TAG, "Accessibility fill failed: no fields accepted text")
            return@runCatchingObserved false
        }

        Log.d(
            TAG,
            "Accessibility fill success: usernameFilled=$usernameFilled, passwordFilled=$passwordFilled, preferPassword=$preferPasswordField"
        )
        if (preferPasswordField) {
            passwordFilled || (password.isBlank() && usernameFilled)
        } else {
            usernameFilled || (username.isBlank() && passwordFilled)
        }
    }.onFailure { e ->
        Log.w(TAG, "fillCredentialsInActiveWindow failed", e)
    }.getOrDefault(false)

    private fun maybePromptActiveFill(packageName: String, source: AccessibilityNodeInfo?) {
        if (!activeFillNotificationEnabled) return
        if (packageName.isBlank() || source == null) return
        if (!isLikelyLoginField(source)) return

        val now = System.currentTimeMillis()
        if (!activeFillPromptThrottle.tryAcquire(packageName, now)) return

        serviceScope.launch {
            try {
                // P4: 按包名定向查询，避免每次登录字段聚焦都加载全库（后台高频热点）
                val match = passwordRepository.findByPackageName(packageName).firstOrNull()
                if (match != null && activeFillNotificationEnabled) {
                    val linkedAppName = match.linkedAppBindings()
                        .firstOrNull { binding ->
                            binding.packageName.equals(packageName, ignoreCase = true)
                        }
                        ?.appName
                        .orEmpty()
                    val shown = ActiveFillNotificationHelper.showActiveFillNotification(
                        this@BastionAccessibilityService,
                        packageName,
                        linkedAppName.ifBlank { packageName }
                    )
                    Log.d(TAG, "Active fill prompt for pkg=$packageName shown=$shown")
                } else {
                    Log.d(TAG, "No matching credential for pkg=$packageName, skip active fill")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Active fill query failed", e)
            }
        }
    }

    private fun isLikelyLoginField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (
            (inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ) {
            return true
        }
        if (node.isEditable) {
            val signals = buildList {
                add(node.hintText?.toString().orEmpty())
                add(node.contentDescription?.toString().orEmpty())
                add(node.viewIdResourceName.orEmpty())
            }.joinToString(" ").lowercase()
            if (
                signals.contains("password") || signals.contains("passwd") || signals.contains("pwd") ||
                signals.contains("username") || signals.contains("user_name") ||
                signals.contains("email") || signals.contains("login") || signals.contains("account")
            ) {
                return true
            }
        }
        return false
    }

    private fun findBrowserUrl(root: AccessibilityNodeInfo, browserSpec: BrowserSpec): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val (node, depth) = queue.removeFirst()
            visited += 1

            runCatchingObserved {
                val viewId = node.viewIdResourceName.orEmpty()
                if (browserSpec.urlFieldIds.any { viewId.endsWith(it) }) {
                    extractNodeText(node)?.let { return it }
                }

                if (depth < MAX_SCAN_DEPTH) {
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { child ->
                            queue.add(child to (depth + 1))
                        }
                    }
                }
            }
        }

        return null
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String? {
        return sequenceOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString(),
        ).mapNotNull { it?.trim() }
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    candidate.any { it == '.' || it == ':' || it == '/' } &&
                    !candidate.startsWith("Search", ignoreCase = true)
            }
    }

    private fun collectFillCandidates(
        root: AccessibilityNodeInfo,
        focusedNode: AccessibilityNodeInfo?
    ): List<FillCandidate> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = mutableListOf<FillCandidate>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_FILL_SCAN_NODES) {
            val node = queue.removeFirst()
            visited += 1

            runCatchingObserved {
                if (node.isVisibleToUser && node.isEnabled && supportsSetText(node)) {
                    candidates += classifyFillCandidate(node)
                }

                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(queue::addLast)
                }
            }
        }

        if (
            focusedNode != null &&
            runCatchingObserved { supportsSetText(focusedNode) }.getOrDefault(false) &&
            candidates.none { it.node == focusedNode }
        ) {
            runCatchingObserved { candidates += classifyFillCandidate(focusedNode) }
        }

        return runCatchingObserved {
            candidates.distinctBy { candidate ->
                val bounds = Rect().also(candidate.node::getBoundsInScreen)
                listOf(
                    candidate.node.viewIdResourceName.orEmpty(),
                    bounds.left.toString(),
                    bounds.top.toString(),
                    bounds.right.toString(),
                    bounds.bottom.toString()
                ).joinToString("|")
            }
        }.getOrDefault(candidates)
    }

    private fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        return runCatchingObserved {
            node.actionList.orEmpty().any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }.getOrDefault(false)
    }

    private fun classifyFillCandidate(node: AccessibilityNodeInfo): FillCandidate {
        val bounds = Rect().also { runCatchingObserved { node.getBoundsInScreen(it) } }
        val signals = buildList {
            add(node.viewIdResourceName.orEmpty())
            add(node.hintText?.toString().orEmpty())
            add(node.contentDescription?.toString().orEmpty())
            add(node.text?.toString().orEmpty())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(node.tooltipText?.toString().orEmpty())
            }
        }.joinToString(" ").lowercase()

        var passwordScore = 0
        var usernameScore = 0

        if (node.isPassword) {
            passwordScore += SCORE_PASSWORD_FLAG
        }
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (
            (inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ) {
            passwordScore += SCORE_PASSWORD_FLAG
        }
        if (
            inputClass == InputType.TYPE_CLASS_TEXT &&
            (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )
        ) {
            usernameScore += SCORE_USERNAME_SIGNAL
        }

        if (signals.contains("password") || signals.contains("passwd") || signals.contains("pwd")) {
            passwordScore += SCORE_PASSWORD_SIGNAL
        }
        if (signals.contains("passcode") || signals.contains("pin")) {
            passwordScore += SCORE_PASSWORD_SIGNAL / 2
        }
        if (
            signals.contains("confirm") ||
            signals.contains("re-enter") ||
            signals.contains("repeat")
        ) {
            passwordScore -= SCORE_CONFIRM_PENALTY
        }

        if (signals.contains("username") || signals.contains("user_name")) {
            usernameScore += SCORE_USERNAME_SIGNAL + 12
        }
        if (
            signals.contains("email") ||
            signals.contains("e-mail") ||
            signals.contains("login") ||
            signals.contains("account")
        ) {
            usernameScore += SCORE_USERNAME_SIGNAL
        }
        if (signals.contains("phone") || signals.contains("mobile")) {
            usernameScore += SCORE_USERNAME_SIGNAL / 2
        }

        if (node.isFocused) {
            passwordScore += SCORE_FOCUSED_BONUS
            usernameScore += SCORE_FOCUSED_BONUS
        }

        val type = when {
            passwordScore > usernameScore && passwordScore > 0 -> FillFieldType.PASSWORD
            usernameScore > passwordScore && usernameScore > 0 -> FillFieldType.USERNAME
            else -> FillFieldType.UNKNOWN
        }
        val resolvedScore = maxOf(passwordScore, usernameScore)

        return FillCandidate(
            node = node,
            type = type,
            score = resolvedScore,
            isFocused = node.isFocused,
            top = bounds.top,
            left = bounds.left
        )
    }

    private fun selectBestCandidate(
        candidates: List<FillCandidate>,
        preferredType: FillFieldType,
        excludedNode: AccessibilityNodeInfo?,
        fallback: AccessibilityNodeInfo?,
    ): FillCandidate? {
        val exactMatch = candidates
            .asSequence()
            .filter { candidate -> candidate.type == preferredType }
            .filterNot { candidate -> excludedNode != null && candidate.node == excludedNode }
            .sortedWith(
                compareByDescending<FillCandidate> { it.score }
                    .thenByDescending { it.isFocused }
                    .thenBy { it.top }
                    .thenBy { it.left }
            )
            .firstOrNull()
        if (exactMatch != null) return exactMatch

        val fallbackNode = fallback
            ?.takeIf { node -> excludedNode == null || node != excludedNode }
            ?.let { node -> candidates.firstOrNull { it.node == node } }
        if (fallbackNode != null) return fallbackNode

        return nearestCandidate(candidates, fallback, excludedNode)
            ?: candidates.firstOrNull { candidate -> excludedNode == null || candidate.node != excludedNode }
    }

    private fun nearestCandidate(
        candidates: List<FillCandidate>,
        anchorNode: AccessibilityNodeInfo?,
        excludedNode: AccessibilityNodeInfo?
    ): FillCandidate? {
        val anchor = anchorNode ?: return null
        val anchorRect = Rect().also(anchor::getBoundsInScreen)
        val anchorCenterX = anchorRect.centerX()
        val anchorCenterY = anchorRect.centerY()
        return candidates
            .asSequence()
            .filterNot { candidate -> candidate.node == anchor }
            .filterNot { candidate -> excludedNode != null && candidate.node == excludedNode }
            .sortedBy { candidate ->
                val dx = candidate.left - anchorCenterX
                val dy = candidate.top - anchorCenterY
                dx * dx + dy * dy
            }
            .firstOrNull()
    }

    private fun setNodeText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null || text.isBlank()) return false
        if (!node.isFocused) {
            // P6: performAction 是 Binder IPC，目标节点所属进程已死亡时抛 DeadObjectException，忽略即可
            runCatchingObserved { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)
        }
        runCatchingObserved { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

        val existingText = runCatchingObserved { node.text?.toString().orEmpty() }.getOrDefault("")
        val canPasteWithoutAppending = if (existingText.isEmpty()) {
            true
        } else {
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
            }
            runCatchingObserved {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            }.getOrDefault(false)
        }

        if (canPasteWithoutAppending) {
            val temporaryClipboard = setTemporaryClipboard(text)
            if (temporaryClipboard != null) {
                val pasted = runCatchingObserved {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
                scheduleTemporaryClipboardRestore(temporaryClipboard)
                if (pasted) return true
            }
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatchingObserved {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    private fun setTemporaryClipboard(text: String): TemporaryClipboardToken? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null

        synchronized(temporaryClipboardLock) {
            val startedSession = !temporaryClipboardActive
            if (startedSession) {
                val originalResult = runCatchingObserved { clipboard.primaryClip }
                temporaryClipboardOriginal = originalResult.getOrNull()
                temporaryClipboardOriginalWasReadable = originalResult.isSuccess
                temporaryClipboardActive = true
            }

            val generation = temporaryClipboardGeneration + 1L
            val temporaryClip = ClipData.newPlainText(TEMPORARY_CLIPBOARD_LABEL, text).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
            }
            val written = runCatchingObserved { clipboard.setPrimaryClip(temporaryClip) }.isSuccess
            if (!written) {
                if (startedSession) {
                    resetTemporaryClipboardSessionLocked()
                }
                return null
            }

            temporaryClipboardGeneration = generation
            temporaryClipboardExpectedText = text
            return TemporaryClipboardToken(
                generation = generation,
                label = TEMPORARY_CLIPBOARD_LABEL,
                text = text,
            )
        }
    }

    private fun scheduleTemporaryClipboardRestore(token: TemporaryClipboardToken) {
        val restoreRunnable = Runnable { restoreTemporaryClipboardIfOwned(token) }
        synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive || token.generation != temporaryClipboardGeneration) return
            temporaryClipboardRestoreRunnable?.let(clipboardHandler::removeCallbacks)
            temporaryClipboardRestoreRunnable = restoreRunnable
            clipboardHandler.postDelayed(
                restoreRunnable,
                TEMPORARY_CLIPBOARD_RESTORE_DELAY_MS,
            )
        }
    }

    private fun restoreTemporaryClipboardImmediately() {
        val token = synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive) return
            TemporaryClipboardToken(
                generation = temporaryClipboardGeneration,
                label = TEMPORARY_CLIPBOARD_LABEL,
                text = temporaryClipboardExpectedText.orEmpty(),
            )
        }
        restoreTemporaryClipboardIfOwned(token)
    }

    private fun restoreTemporaryClipboardIfOwned(token: TemporaryClipboardToken) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        synchronized(temporaryClipboardLock) {
            if (!temporaryClipboardActive || token.generation != temporaryClipboardGeneration) return

            val snapshot = if (clipboard == null) {
                TemporaryClipboardSnapshot(text = null, label = null, canVerify = false)
            } else {
                runCatchingObserved {
                    val currentClip = clipboard.primaryClip
                    TemporaryClipboardSnapshot(
                        text = currentClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(this)
                            ?.toString(),
                        label = currentClip?.description?.label?.toString(),
                        canVerify = true,
                    )
                }.getOrElse {
                    TemporaryClipboardSnapshot(text = null, label = null, canVerify = false)
                }
            }

            if (
                clipboard != null &&
                shouldRestoreTemporaryClipboard(snapshot, token.label, token.text)
            ) {
                val restored = if (
                    temporaryClipboardOriginalWasReadable &&
                    temporaryClipboardOriginal != null
                ) {
                    runCatchingObserved {
                        clipboard.setPrimaryClip(requireNotNull(temporaryClipboardOriginal))
                    }.isSuccess
                } else {
                    false
                }
                if (!restored) {
                    runCatchingObserved {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            }

            resetTemporaryClipboardSessionLocked()
        }
    }

    private fun resetTemporaryClipboardSessionLocked() {
        temporaryClipboardRestoreRunnable?.let(clipboardHandler::removeCallbacks)
        temporaryClipboardRestoreRunnable = null
        temporaryClipboardOriginal = null
        temporaryClipboardOriginalWasReadable = false
        temporaryClipboardActive = false
        temporaryClipboardExpectedText = null
    }
}
