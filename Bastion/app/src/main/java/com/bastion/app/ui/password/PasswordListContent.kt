package com.bastion.app.ui

import com.bastion.app.logging.runCatchingObserved
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.bastion.app.R
import com.bastion.app.bitwarden.sync.isUserVisibleSyncInProgress
import com.bastion.app.data.AppSettings
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.data.CategorySelectionUiMode
import com.bastion.app.data.ItemType
import com.bastion.app.data.KeePassSyncPhase
import com.bastion.app.data.KeePassSyncStatus
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordListTopModule
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.data.SecureItem
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.isLocalOnlyItem
import com.bastion.app.data.isRemoteSource
import com.bastion.app.data.model.TotpData
import com.bastion.app.data.model.TimelinePasswordLocationState
import com.bastion.app.data.model.TimelineEvent
import com.bastion.app.data.model.isSshKeyEntry
import com.bastion.app.data.model.isBarcodeEntry
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.OperationLogger
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel
import com.bastion.app.viewmodel.TotpViewModel
import com.bastion.app.viewmodel.CategoryFilter
import com.bastion.app.data.Category
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.DocumentViewModel
import com.bastion.app.viewmodel.GeneratorViewModel
import com.bastion.app.viewmodel.GeneratorType
import com.bastion.app.viewmodel.NoteViewModel
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.TimelineViewModel
import com.bastion.app.ui.screens.SettingsScreen
import com.bastion.app.ui.screens.GeneratorScreen  // 添加生成器页面导入
import com.bastion.app.ui.screens.NoteListScreen
import com.bastion.app.ui.screens.NoteListContent
import com.bastion.app.ui.screens.PasswordDetailScreen
import com.bastion.app.ui.screens.SendScreen
import com.bastion.app.ui.screens.CardWalletScreen
import com.bastion.app.ui.screens.CardWalletTab
import com.bastion.app.ui.screens.BankCardDetailScreen
import com.bastion.app.ui.screens.DocumentDetailScreen
import com.bastion.app.ui.screens.TimelineScreen
import com.bastion.app.ui.screens.PasskeyListScreen
import com.bastion.app.ui.gestures.SwipeActions
import com.bastion.app.ui.haptic.rememberHapticFeedback
import kotlin.math.absoluteValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bastion.app.ui.components.QrCodeDialog
import com.bastion.app.ui.components.ExpressiveTopBar
import com.bastion.app.ui.components.DraggableBottomNavScaffold
import com.bastion.app.ui.components.SwipeableAddFab
import com.bastion.app.ui.components.DraggableNavItem
import com.bastion.app.ui.components.QuickActionItem
import com.bastion.app.ui.components.QuickAddCallback
import com.bastion.app.ui.components.SyncStatusIcon
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.CreateCategoryDialog
import com.bastion.app.ui.components.BastionExpressiveFilterChip
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterBottomSheet
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.password.PasswordAggregateCardStyle
import com.bastion.app.ui.password.PasswordAggregateListItemUi
import com.bastion.app.ui.password.PasswordAggregateRetainedStateViewModel
import com.bastion.app.ui.password.PasswordAggregateWalletItemType
import com.bastion.app.ui.password.PasswordListCardBadge
import com.bastion.app.ui.password.PasswordGroupListItemUi
import com.bastion.app.ui.password.PasswordListAggregateConfig
import com.bastion.app.ui.password.PasswordPageListItemUi
import com.bastion.app.ui.password.PasswordListSingleCardItem
import com.bastion.app.ui.password.PasswordSupplementaryListItemUi
import com.bastion.app.ui.password.PasswordBatchDeleteProgressTracker
import com.bastion.app.ui.password.PasswordBatchTransferProgressTracker
import com.bastion.app.ui.password.appendAggregateContentQuickFilterItems
import com.bastion.app.ui.password.buildPasswordAggregateManualStackGroups
import com.bastion.app.ui.password.buildPasswordAggregateItems
import com.bastion.app.ui.password.buildPasswordPageListItems
import com.bastion.app.ui.password.filterPasswordAggregateItemsByQuickFilters
import com.bastion.app.ui.password.flattenPasswordPageCardItems
import com.bastion.app.ui.password.icon
import com.bastion.app.ui.password.labelRes
import com.bastion.app.ui.password.resolvePasswordPageDisplayedTypes
import com.bastion.app.ui.password.resolvePasswordPageQuickFilterTypes
import com.bastion.app.ui.password.resolveSelectedPasswordPageCardItems
import com.bastion.app.ui.password.toPasswordPageContentTypeOrNull
import com.bastion.app.ui.password.toSelectedSupplementaryItemOrNull
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
import com.bastion.app.ui.components.UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
import com.bastion.app.ui.components.rememberUnifiedCategoryFilterChipMenuWidth
import com.bastion.app.ui.common.dialog.DeleteConfirmDialog
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.InspectorRow
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.common.pull.PullActionVisualState
import com.bastion.app.ui.common.pull.PullGestureIndicator
import com.bastion.app.ui.common.pull.rememberPullActionState
import com.bastion.app.ui.common.selection.CategoryListItem
import com.bastion.app.ui.common.selection.SelectionActionBar
import com.bastion.app.ui.common.selection.SelectionModeTopBar
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.main.navigation.fullLabelRes
import com.bastion.app.ui.main.navigation.indexToDefaultTabKey
import com.bastion.app.ui.main.navigation.shortLabelRes
import com.bastion.app.ui.main.navigation.toBottomNavItem
import com.bastion.app.ui.main.layout.AdaptiveMainScaffold
import com.bastion.app.ui.icons.BastionIcons
import com.bastion.app.ui.password.buildAdditionalInfoPreview
import com.bastion.app.ui.password.MultiPasswordEntryCard
import com.bastion.app.ui.password.StackedPasswordGroup
import com.bastion.app.ui.password.PasswordEntryCard
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.getGroupKeyForMode
import com.bastion.app.ui.password.getPasswordGroupTitle
import com.bastion.app.ui.password.getPasswordInfoKey
import com.bastion.app.ui.password.passwordIdFromSelectionKey
import com.bastion.app.ui.password.passwordSelectionKey
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenSend
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.security.SecurityManager
import com.bastion.app.sync.SyncKey
import com.bastion.app.sync.SyncPhase
import com.bastion.app.sync.SyncTarget
import com.bastion.app.sync.SyncTaskRunner
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.bastion.app.ui.screens.AddEditPasswordScreen
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.ui.screens.AddEditBankCardScreen
import com.bastion.app.ui.screens.AddEditDocumentScreen
import com.bastion.app.ui.screens.AddEditNoteScreen
import com.bastion.app.ui.screens.AddEditSendScreen
import com.bastion.app.ui.theme.BastionTheme
import java.util.concurrent.CancellationException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.Json

private val stringSetSaver = Saver<Set<String>, ArrayList<String>>(
    save = { value -> ArrayList(value) },
    restore = { saved -> saved.toSet() }
)

private const val FAST_SCROLL_LOG_TAG = "PasswordFastScroll"
private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"
private const val PASSWORD_EMPTY_STATE_DEBOUNCE_MS = 220L

private fun QuickStatusKeePassSyncState.dialogSuppressionKey(): String {
    return "$databaseId:$status:$phase:$coordinatorPhase:$coordinatorErrorKind"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PasswordListInitialLoadingIndicator() {
    androidx.compose.material3.LoadingIndicator(
        modifier = Modifier.size(64.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PasswordListContent(
    viewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    groupMode: String = "none",
    stackCardMode: StackCardMode,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onPasswordClick: (com.bastion.app.data.PasswordEntry) -> Unit,
    onSelectionModeChange: (
        isSelectionMode: Boolean,
        selectedCount: Int,
        onExit: () -> Unit,
        onSelectAll: () -> Unit,
        onFavorite: (() -> Unit)?,
        onMoveToCategory: (() -> Unit)?,
        onStack: (() -> Unit)?,
        onDelete: () -> Unit
    ) -> Unit,
    onBackToTopVisibilityChange: (Boolean) -> Unit = {},
    scrollToTopRequestKey: Int = 0,
    onOpenHistory: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenCommonAccountTemplates: () -> Unit = {},
    onScanFidoQr: () -> Unit = {},
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    aggregateConfig: PasswordListAggregateConfig? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val passwordEntries by viewModel.passwordEntries.collectAsState()
    val passwordEntriesReady by viewModel.passwordEntriesReady.collectAsState()
    val allPasswords by viewModel.allPasswordsForUi.collectAsState()
    val allPasswordsReady by viewModel.allPasswordsForUiReady.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val categoriesReady by viewModel.categoriesReady.collectAsState()
    val currentFilter by viewModel.categoryFilter.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val aggregateRetainedStateViewModel: PasswordAggregateRetainedStateViewModel = viewModel()
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            aggregateRetainedStateViewModel.retainedState.clear()
        }
    }
    // settings
    val appSettings by settingsViewModel.settings.collectAsState()
    val aggregateUiState = rememberPasswordAggregateUiState(
        aggregateConfig = aggregateConfig,
        searchQuery = searchQuery,
        currentFilter = currentFilter,
        appSettings = appSettings,
        retainedState = aggregateRetainedStateViewModel.retainedState,
    )
    val fastScrollRequestKey by viewModel.fastScrollRequestKey.collectAsState()
    val fastScrollProgress by viewModel.fastScrollProgress.collectAsState()
    val quickStatusTransferState by PasswordBatchTransferProgressTracker.progress.collectAsState()
    val quickStatusDeleteState by PasswordBatchDeleteProgressTracker.progress.collectAsState()
    var showQuickStatusTransferDialog by remember { mutableStateOf(false) }
    var showQuickStatusDeleteDialog by remember { mutableStateOf(false) }
    var showQuickStatusKeePassSyncDialog by remember { mutableStateOf(false) }
    var backgroundedTransferOperationId by remember { mutableStateOf<Long?>(null) }
    var backgroundedDeleteOperationId by remember { mutableStateOf<Long?>(null) }
    var backgroundedKeePassSyncKey by remember { mutableStateOf<String?>(null) }

    // "仅本地" 的核心目标是给用户看待上传清单，不应该出现堆叠容器。
    // 因此这里强制扁平展示，仅在该筛选下生效，不影响其他页面。
    val isLocalOnlyView = currentFilter is CategoryFilter.LocalOnly
    val isAllView = currentFilter is CategoryFilter.All
    // Bitwarden pages use pull-to-search only; disable pull-to-sync behavior.
    val isBitwardenDatabaseView = false && when (currentFilter) {
        is CategoryFilter.BitwardenVault,
        is CategoryFilter.BitwardenFolderFilter,
        is CategoryFilter.BitwardenVaultStarred,
        is CategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = currentFilter) {
        is CategoryFilter.BitwardenVault -> filter.vaultId
        is CategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is CategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is CategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = currentFilter) {
        is CategoryFilter.KeePassDatabase -> filter.databaseId
        is CategoryFilter.KeePassGroupFilter -> filter.databaseId
        is CategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is CategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    val keepassGroupsForSelectedDbFlow = remember(selectedKeePassDatabaseId, localKeePassViewModel) {
        selectedKeePassDatabaseId?.let { databaseId ->
            localKeePassViewModel.getGroups(databaseId)
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val keepassGroupsForSelectedDb by keepassGroupsForSelectedDbFlow.collectAsState(initial = emptyList())
    val isKeePassDatabaseView = selectedKeePassDatabaseId != null
    val selectedKeePassDatabase = remember(selectedKeePassDatabaseId, keepassDatabases) {
        selectedKeePassDatabaseId?.let { databaseId ->
            keepassDatabases.find { it.id == databaseId }
        }
    }
    LaunchedEffect(
        selectedKeePassDatabase?.id,
        selectedKeePassDatabase?.lastSyncStatus
    ) {
        val database = selectedKeePassDatabase ?: return@LaunchedEffect
        if (database.isRemoteSource()) {
            localKeePassViewModel.autoSyncVisibleRemoteDatabase(database.id)
        }
    }
    val selectedKeePassSyncStateFlow = remember(selectedKeePassDatabaseId, localKeePassViewModel) {
        selectedKeePassDatabaseId?.let { databaseId ->
            localKeePassViewModel.getRemoteSyncState(databaseId)
        } ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val selectedKeePassRemoteSyncState by selectedKeePassSyncStateFlow.collectAsState(initial = null)
    val selectedKeePassCoordinatorStatusFlow = remember(selectedKeePassDatabaseId) {
        if (selectedKeePassDatabaseId == null) {
            kotlinx.coroutines.flow.flowOf(null)
        } else {
            SyncTaskRunner.observe(SyncKey("keepass_visible_remote"))
        }
    }
    val visibleKeePassRemoteCoordinatorStatus by selectedKeePassCoordinatorStatusFlow.collectAsState(initial = null)
    val selectedKeePassCoordinatorStatus = remember(
        selectedKeePassDatabaseId,
        visibleKeePassRemoteCoordinatorStatus
    ) {
        val selectedId = selectedKeePassDatabaseId ?: return@remember null
        visibleKeePassRemoteCoordinatorStatus?.takeIf { status ->
            (status.target as? SyncTarget.KeePassDatabase)?.databaseId == selectedId
        }
    }
    val bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = viewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val selectedBitwardenFoldersFlow = remember(selectedBitwardenVaultId, viewModel) {
        selectedBitwardenVaultId?.let(viewModel::getBitwardenFolders)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val selectedBitwardenFolders by selectedBitwardenFoldersFlow.collectAsState(initial = emptyList())
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    val quickStatusBitwardenSyncState = remember(
        selectedBitwardenVaultId,
        bitwardenSyncStatusByVault,
        bitwardenVaults
    ) {
        val vaultId = selectedBitwardenVaultId ?: return@remember null
        val status = bitwardenSyncStatusByVault[vaultId] ?: return@remember null
        if (!status.isUserVisibleSyncInProgress()) return@remember null
        val vaultName = bitwardenVaults
            .firstOrNull { it.id == vaultId }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: bitwardenVaults.firstOrNull { it.id == vaultId }?.email
            ?: "Bitwarden"
        QuickStatusBitwardenSyncState(
            vaultName = vaultName,
            isRunning = status.isRunning
        )
    }
    val quickStatusKeePassSyncState = remember(
        selectedKeePassDatabase,
        selectedKeePassRemoteSyncState,
        selectedKeePassCoordinatorStatus,
        localKeePassViewModel
    ) {
        val database = selectedKeePassDatabase
        if (database == null || !database.isRemoteSource()) {
            null
        } else {
            val phase = selectedKeePassRemoteSyncState?.syncPhase
            val coordinatorPhase = selectedKeePassCoordinatorStatus?.phase
            val coordinatorShouldShow = coordinatorPhase in setOf(
                SyncPhase.RUNNING,
                SyncPhase.BLOCKED,
                SyncPhase.CONFLICT
            )
            val shouldShow = database.lastSyncStatus in setOf(
                KeePassSyncStatus.PENDING_UPLOAD,
                KeePassSyncStatus.SYNCING,
                KeePassSyncStatus.REMOTE_CHANGED,
                KeePassSyncStatus.CONFLICT
            ) || phase in setOf(
                KeePassSyncPhase.COMPARING,
                KeePassSyncPhase.DOWNLOADING,
                KeePassSyncPhase.UPLOADING
            ) || coordinatorShouldShow
            if (!shouldShow) {
                null
            } else {
                QuickStatusKeePassSyncState(
                    databaseId = database.id,
                    databaseName = database.name,
                    status = database.lastSyncStatus,
                    phase = phase,
                    coordinatorPhase = coordinatorPhase,
                    coordinatorErrorKind = selectedKeePassCoordinatorStatus?.lastError?.kind,
                    onSync = {
                        localKeePassViewModel.syncRemoteDatabase(database.id)
                    }
                )
            }
        }
    }
    val isArchiveView = currentFilter is CategoryFilter.Archived
    val effectiveGroupMode = if (isLocalOnlyView) "none" else groupMode
    val effectiveStackCardMode = if (isLocalOnlyView) {
        StackCardMode.ALWAYS_EXPANDED
    } else {
        stackCardMode
    }
    val quickFoldersEnabledForCurrentFilter = false
    val quickFolderPathBannerEnabledForCurrentFilter =
        appSettings.passwordListQuickFolderPathBannerEnabled && !isAllView
    val quickStatusBannerEnabled = quickFolderPathBannerEnabledForCurrentFilter
    LaunchedEffect(quickStatusTransferState?.operationId, quickStatusBannerEnabled) {
        val state = quickStatusTransferState
        if (state == null) {
            showQuickStatusTransferDialog = false
            backgroundedTransferOperationId = null
            return@LaunchedEffect
        }
        if (!quickStatusBannerEnabled && state.operationId != backgroundedTransferOperationId) {
            showQuickStatusTransferDialog = true
        }
    }
    LaunchedEffect(quickStatusDeleteState?.operationId, quickStatusBannerEnabled) {
        val state = quickStatusDeleteState
        if (state == null) {
            showQuickStatusDeleteDialog = false
            backgroundedDeleteOperationId = null
            return@LaunchedEffect
        }
        if (!quickStatusBannerEnabled && state.operationId != backgroundedDeleteOperationId) {
            showQuickStatusDeleteDialog = true
        }
    }
    LaunchedEffect(
        quickStatusKeePassSyncState?.databaseId,
        quickStatusKeePassSyncState?.status,
        quickStatusKeePassSyncState?.phase,
        quickStatusKeePassSyncState?.coordinatorPhase,
        quickStatusKeePassSyncState?.coordinatorErrorKind,
        quickStatusBannerEnabled
    ) {
        val state = quickStatusKeePassSyncState
        if (state == null) {
            showQuickStatusKeePassSyncDialog = false
            backgroundedKeePassSyncKey = null
            return@LaunchedEffect
        }
        val stateKey = state.dialogSuppressionKey()
        if (!quickStatusBannerEnabled && stateKey != backgroundedKeePassSyncKey) {
            showQuickStatusKeePassSyncDialog = true
        }
    }
    
    // 选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemKeys by remember { mutableStateOf(setOf<String>()) }
    var swipeSelectionAnchorKey by remember { mutableStateOf<String?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showMoveToCategoryDialog by remember { mutableStateOf(false) }
    var showManualStackConfirmDialog by remember { mutableStateOf(false) }
    var selectedManualStackMode by remember { mutableStateOf(ManualStackDialogMode.STACK) }
    
    // 详情对话框状态
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedPasswordForDetail by remember { mutableStateOf<com.bastion.app.data.PasswordEntry?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }
    
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? FragmentActivity
    val biometricHelper = remember { BiometricHelper(context) }
    val canUseBiometric = activity != null && appSettings.biometricEnabled && biometricHelper.isBiometricAvailable()
    val database = remember { com.bastion.app.data.PasswordDatabase.getDatabase(context) }
    val attachmentParentIds by database.attachmentDao()
        .observeParentsWithActiveAttachments()
        .collectAsState(initial = emptyList())
    val activeAttachmentParentIds = remember(attachmentParentIds) {
        attachmentParentIds.toSet()
    }
    val bitwardenRepository = remember { com.bastion.app.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val aggregateStackRepository = remember(database) {
        com.bastion.app.repository.PasswordPageAggregateStackRepository(
            database.passwordPageAggregateStackDao()
        )
    }
    val aggregateStackEntries by aggregateStackRepository.observeAll().collectAsState(initial = emptyList())

    // Top actions menu and display options sheet state
    var topActionsMenuExpanded by remember { mutableStateOf(false) }
    var showDisplayOptionsSheet by remember { mutableStateOf(false) }
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            topActionsMenuExpanded = false
        }
    }
    // Search state hoisted for morphing animation
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    // 如果搜索框展开，按返回键关闭搜索框
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
        focusManager.clearFocus()
    }

    // Handle back press for selection mode
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedItemKeys = emptySet()
        swipeSelectionAnchorKey = null
    }

    // 在归档页按返回键时，先退出归档回到密码主列表
    BackHandler(enabled = isArchiveView && !isSelectionMode && !isSearchExpanded) {
        viewModel.closeArchiveView()
    }
    // Category sheet state
    var isCategorySheetVisible by rememberSaveable { mutableStateOf(false) }
    var categoryPillBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    LaunchedEffect(isArchiveView) {
        if (isArchiveView && isCategorySheetVisible) {
            isCategorySheetVisible = false
        }
    }

    LaunchedEffect(aggregateStackRepository) {
        aggregateStackRepository.pruneDegenerateGroups()
    }
    
    // 添加触觉反馈
    val haptic = rememberHapticFeedback()
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Pull-to-search/sync state (shared implementation)
    val triggerDistance = remember(density) { with(density) { 40.dp.toPx() } }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val pullAction = rememberPullActionState(
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = triggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        maxDragDistance = maxDragDistance,
        bitwardenRepository = bitwardenRepository,
        onSearchTriggered = { isSearchExpanded = true }
    )
    
    // 添加单项删除对话框状态
    var itemToDelete by remember { mutableStateOf<com.bastion.app.data.PasswordEntry?>(null) }
    var singleItemPasswordInput by remember { mutableStateOf("") }
    var showSingleItemPasswordVerify by remember { mutableStateOf(false) }
    
    // 添加已删除项ID集合（用于在验证前隐藏项）
    var deletedItemIds by remember { mutableStateOf(setOf<Long>()) }
    
    // 堆叠展开状态 - 记录哪些分组已展开（托管到 ViewModel，导航返回后保持）
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    var quickFilterFavorite by rememberSaveable { mutableStateOf(false) }
    var quickFilter2fa by rememberSaveable { mutableStateOf(false) }
    var quickFilterNotes by rememberSaveable { mutableStateOf(false) }
    var quickFilterPasskey by rememberSaveable { mutableStateOf(false) }
    var quickFilterBoundNote by rememberSaveable { mutableStateOf(false) }
    var quickFilterAttachments by rememberSaveable { mutableStateOf(false) }
    var quickFilterUncategorized by rememberSaveable { mutableStateOf(false) }
    var quickFilterLocalOnly by rememberSaveable { mutableStateOf(false) }
    var quickFilterManualStackOnly by rememberSaveable { mutableStateOf(false) }
    var quickFilterNeverStack by rememberSaveable { mutableStateOf(false) }
    var quickFilterUnstacked by rememberSaveable { mutableStateOf(false) }
    // WIFI 筛选走"按需出现"分支：只有数据库里存在 WIFI 条目才渲染 chip，
    // 也不写进用户的 [PasswordListQuickFilterItem] 清单，避免污染备份结构。
    var quickFilterWifi by rememberSaveable { mutableStateOf(false) }
    val hasAnyWifiEntry = remember(passwordEntries) {
        passwordEntries.any { it.isWifiEntry() }
    }
    LaunchedEffect(hasAnyWifiEntry) {
        if (!hasAnyWifiEntry) quickFilterWifi = false
    }
    // SSH 密钥筛选：与 WIFI 同样按需出现，不进入 [PasswordListQuickFilterItem] 清单。
    var quickFilterSshKey by rememberSaveable { mutableStateOf(false) }
    val hasAnySshKeyEntry = remember(passwordEntries) {
        passwordEntries.any { it.isSshKeyEntry() }
    }
    LaunchedEffect(hasAnySshKeyEntry) {
        if (!hasAnySshKeyEntry) quickFilterSshKey = false
    }
    var quickFilterBarcode by rememberSaveable { mutableStateOf(false) }
    val hasAnyBarcodeEntry = remember(passwordEntries) {
        passwordEntries.any { it.isBarcodeEntry() }
    }
    LaunchedEffect(hasAnyBarcodeEntry) {
        if (!hasAnyBarcodeEntry) quickFilterBarcode = false
    }
    var manualStackGroupByEntryId by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var noStackEntryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var lastCustomFieldEntryIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    val configuredQuickFilterItems = remember(
        appSettings.passwordPageAggregateEnabled,
        aggregateUiState.visibleContentTypes
    ) {
        appendAggregateContentQuickFilterItems(
            configuredItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
            visibleTypes = aggregateUiState.visibleContentTypes,
            aggregateEnabled = appSettings.passwordPageAggregateEnabled
        )
    }
    val quickFolderStyle = appSettings.passwordListQuickFolderStyle
    var quickFolderRootKey by rememberSaveable {
        mutableStateOf(currentFilter.toQuickFolderRootKeyOrNull() ?: QUICK_FOLDER_ROOT_ALL)
    }
    val outsideTapInteractionSource = remember { MutableInteractionSource() }
    val canCollapseExpandedGroups = effectiveStackCardMode == StackCardMode.AUTO && expandedGroups.isNotEmpty()

    // 当分组模式改变时,重置展开状态（初始值用 null 标记，重建时不误清空）
    val prevGroupMode = remember { mutableStateOf<String?>(null) }
    val prevStackCardMode = remember { mutableStateOf<StackCardMode?>(null) }
    LaunchedEffect(effectiveGroupMode, effectiveStackCardMode) {
        val prev1 = prevGroupMode.value
        val prev2 = prevStackCardMode.value
        if (prev1 != null && prev2 != null &&
            (effectiveGroupMode != prev1 || effectiveStackCardMode != prev2)) {
            viewModel.clearExpandedGroups()
        }
        prevGroupMode.value = effectiveGroupMode
        prevStackCardMode.value = effectiveStackCardMode
    }

    LaunchedEffect(configuredQuickFilterItems) {
        if (com.bastion.app.data.PasswordListQuickFilterItem.FAVORITE !in configuredQuickFilterItems) {
            quickFilterFavorite = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.TWO_FA !in configuredQuickFilterItems) {
            quickFilter2fa = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.NOTES !in configuredQuickFilterItems) {
            quickFilterNotes = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.PASSKEY !in configuredQuickFilterItems) {
            quickFilterPasskey = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.NOTE !in configuredQuickFilterItems) {
            quickFilterBoundNote = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.ATTACHMENTS !in configuredQuickFilterItems) {
            quickFilterAttachments = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.UNCATEGORIZED !in configuredQuickFilterItems) {
            quickFilterUncategorized = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.LOCAL_ONLY !in configuredQuickFilterItems) {
            quickFilterLocalOnly = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.MANUAL_STACK_ONLY !in configuredQuickFilterItems) {
            quickFilterManualStackOnly = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.NEVER_STACK !in configuredQuickFilterItems) {
            quickFilterNeverStack = false
        }
        if (com.bastion.app.data.PasswordListQuickFilterItem.UNSTACKED !in configuredQuickFilterItems) {
            quickFilterUnstacked = false
        }
    }

    LaunchedEffect(currentFilter) {
        currentFilter.toQuickFolderRootKeyOrNull()?.let { key ->
            quickFolderRootKey = key
        }
    }

    val quickFolderUiState = rememberPasswordListQuickFolderUiState(
        context = context,
        appSettings = appSettings,
        currentFilter = currentFilter,
        categories = categories,
        searchQuery = searchQuery,
        passwordEntries = passwordEntries,
        allPasswords = allPasswords,
        keepassDatabases = keepassDatabases,
        keepassGroupsForSelectedDb = keepassGroupsForSelectedDb,
        bitwardenVaults = bitwardenVaults,
        selectedBitwardenFolders = selectedBitwardenFolders,
        quickFolderRootKey = quickFolderRootKey,
        quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
        quickFolderPathBannerEnabledForCurrentFilter = quickFolderPathBannerEnabledForCurrentFilter
    )
    val quickFolderSystemBackTarget = quickFolderUiState.systemBackTarget
    val quickFolderShortcuts = quickFolderUiState.shortcuts
    val categoryMenuQuickFolderShortcuts = quickFolderUiState.categoryMenuShortcuts
    val quickFolderBreadcrumbs = quickFolderUiState.breadcrumbs

    BackHandler(
        enabled = !isSearchExpanded &&
            !isSelectionMode &&
            !isArchiveView &&
            quickFolderSystemBackTarget != null
    ) {
        viewModel.setCategoryFilter(requireNotNull(quickFolderSystemBackTarget))
    }

    val shouldLoadManualStackMetadata =
        effectiveStackCardMode != StackCardMode.ALWAYS_EXPANDED ||
            quickFilterManualStackOnly ||
            quickFilterNeverStack ||
            quickFilterUnstacked
    val emptyStateMessage = remember(
        currentFilter,
        quickFoldersEnabledForCurrentFilter,
        quickFolderShortcuts,
        appSettings.passwordListCategoryQuickFiltersEnabled,
        categoryMenuQuickFolderShortcuts
    ) {
        resolvePasswordListEmptyStateMessage(
            currentFilter = currentFilter,
            quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
            hasQuickFolderShortcuts =
                quickFolderShortcuts.isNotEmpty() ||
                    (
                        appSettings.passwordListCategoryQuickFiltersEnabled &&
                            categoryMenuQuickFolderShortcuts.isNotEmpty()
                        )
        )
    }
    val effectiveManualStackGroupByEntryId =
        if (shouldLoadManualStackMetadata) manualStackGroupByEntryId else emptyMap()
    val effectiveNoStackEntryIds =
        if (shouldLoadManualStackMetadata) noStackEntryIds else emptySet()
    val groupingConfig = remember(
        isLocalOnlyView,
        effectiveStackCardMode,
        effectiveGroupMode,
        appSettings.passwordWebsiteStackMatchMode,
        effectiveNoStackEntryIds,
        effectiveManualStackGroupByEntryId,
        context
    ) {
        PasswordGroupingConfig(
            isLocalOnlyView = isLocalOnlyView,
            effectiveStackCardMode = effectiveStackCardMode,
            effectiveGroupMode = effectiveGroupMode,
            websiteStackMatchMode = appSettings.passwordWebsiteStackMatchMode,
            effectiveNoStackEntryIds = effectiveNoStackEntryIds,
            effectiveManualStackGroupByEntryId = effectiveManualStackGroupByEntryId,
            untitledLabel = context.getString(R.string.untitled)
        )
    }
    
    val preStackFilteredPasswordEntries = remember(
        passwordEntries,
        deletedItemIds,
        quickFoldersEnabledForCurrentFilter,
        currentFilter,
        configuredQuickFilterItems,
        quickFilterFavorite,
        quickFilter2fa,
        quickFilterNotes,
        quickFilterPasskey,
        quickFilterBoundNote,
        quickFilterAttachments,
        activeAttachmentParentIds,
        quickFilterUncategorized,
        quickFilterLocalOnly,
        quickFilterNeverStack,
        quickFilterWifi,
        quickFilterSshKey,
        quickFilterBarcode,
        effectiveNoStackEntryIds,
        aggregateUiState.hasActiveContentTypeFilter,
        aggregateUiState.contentTypeFilterTypes
    ) {
        filterPreStackPasswordEntries(
            passwordEntries = passwordEntries,
            deletedItemIds = deletedItemIds,
            quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterFavorite,
            quickFilter2fa = quickFilter2fa,
            quickFilterNotes = quickFilterNotes,
            quickFilterPasskey = quickFilterPasskey,
            quickFilterBoundNote = quickFilterBoundNote,
            quickFilterAttachments = quickFilterAttachments,
            activeAttachmentParentIds = activeAttachmentParentIds,
            quickFilterUncategorized = quickFilterUncategorized,
            quickFilterLocalOnly = quickFilterLocalOnly,
            quickFilterNeverStack = quickFilterNeverStack,
            quickFilterWifi = quickFilterWifi,
            quickFilterSshKey = quickFilterSshKey,
            quickFilterBarcode = quickFilterBarcode,
            effectiveNoStackEntryIds = effectiveNoStackEntryIds,
            hasActiveContentTypeFilter = aggregateUiState.hasActiveContentTypeFilter,
            contentTypeFilterTypes = aggregateUiState.contentTypeFilterTypes
        )
    }

    val preStackFilteredAggregateItems = remember(
        aggregateUiState.visibleItems,
        configuredQuickFilterItems,
        quickFilterFavorite,
        quickFilter2fa,
        quickFilterNotes,
        quickFilterUncategorized,
        quickFilterLocalOnly,
        quickFilterWifi,
        quickFilterSshKey,
        quickFilterBarcode,
        quickFilterNeverStack,
        currentFilter,
        effectiveStackCardMode
    ) {
        filterPasswordAggregateItemsByQuickFilters(
            items = aggregateUiState.visibleItems,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterFavorite,
            quickFilter2fa = quickFilter2fa,
            quickFilterNotes = quickFilterNotes,
            quickFilterUncategorized = quickFilterUncategorized,
            quickFilterLocalOnly = quickFilterLocalOnly,
            quickFilterWifi = quickFilterWifi,
            quickFilterSshKey = quickFilterSshKey,
            quickFilterBarcode = quickFilterBarcode,
            quickFilterManualStackOnly = false,
            quickFilterNeverStack = quickFilterNeverStack,
            quickFilterUnstacked = false,
            effectiveStackCardMode = effectiveStackCardMode,
            manualStackedKeys = emptySet()
        )
    }
    val manualAggregateStackBuildResult = remember(
        aggregateStackEntries,
        preStackFilteredPasswordEntries,
        preStackFilteredAggregateItems
    ) {
        buildPasswordAggregateManualStackGroups(
            stackEntries = aggregateStackEntries,
            passwords = preStackFilteredPasswordEntries,
            aggregateItems = preStackFilteredAggregateItems
        )
    }
    val validAggregateStackedItemKeys = remember(manualAggregateStackBuildResult.stackedItemKeys) {
        manualAggregateStackBuildResult.stackedItemKeys
    }
    val visiblePasswordEntries = remember(
        preStackFilteredPasswordEntries,
        configuredQuickFilterItems,
        quickFilterManualStackOnly,
        quickFilterUnstacked,
        effectiveStackCardMode,
        effectiveManualStackGroupByEntryId,
        validAggregateStackedItemKeys,
        manualAggregateStackBuildResult.stackedPasswordIds,
        groupingConfig
    ) {
        filterPasswordEntriesByStackQuickFilters(
            items = preStackFilteredPasswordEntries,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterManualStackOnly = quickFilterManualStackOnly,
            quickFilterUnstacked = quickFilterUnstacked,
            effectiveStackCardMode = effectiveStackCardMode,
            effectiveManualStackGroupByEntryId = effectiveManualStackGroupByEntryId,
            aggregateManualStackedItemKeys = validAggregateStackedItemKeys,
            aggregateManualStackedPasswordIds = manualAggregateStackBuildResult.stackedPasswordIds,
            groupingConfig = groupingConfig
        )
    }
    val visibleAggregateItems = remember(
        preStackFilteredAggregateItems,
        configuredQuickFilterItems,
        quickFilterManualStackOnly,
        quickFilterUnstacked,
        quickFilterWifi,
        quickFilterSshKey,
        quickFilterBarcode,
        effectiveStackCardMode,
        validAggregateStackedItemKeys
    ) {
        filterPasswordAggregateItemsByQuickFilters(
            items = preStackFilteredAggregateItems,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = false,
            quickFilter2fa = false,
            quickFilterNotes = false,
            quickFilterUncategorized = false,
            quickFilterLocalOnly = false,
            quickFilterWifi = quickFilterWifi,
            quickFilterSshKey = quickFilterSshKey,
            quickFilterBarcode = quickFilterBarcode,
            quickFilterManualStackOnly = quickFilterManualStackOnly,
            quickFilterNeverStack = false,
            quickFilterUnstacked = quickFilterUnstacked,
            effectiveStackCardMode = effectiveStackCardMode,
            manualStackedKeys = validAggregateStackedItemKeys
        )
    }
    LaunchedEffect(isSelectionMode, selectedItemKeys) {
        if (isSelectionMode && selectedItemKeys.isEmpty()) {
            isSelectionMode = false
        }
        if (selectedItemKeys.isEmpty()) {
            swipeSelectionAnchorKey = null
        }
    }

    LaunchedEffect(passwordEntries, deletedItemIds, shouldLoadManualStackMetadata) {
        if (!shouldLoadManualStackMetadata) {
            manualStackGroupByEntryId = emptyMap()
            noStackEntryIds = emptySet()
            lastCustomFieldEntryIds = emptyList()
            return@LaunchedEffect
        }
        val entriesSnapshot = passwordEntries
        val deletedIdsSnapshot = deletedItemIds
        val allIds = withContext(Dispatchers.Default) {
            entriesSnapshot
                .asSequence()
                .map { it.id }
                .filter { id -> id !in deletedIdsSnapshot }
                .toList()
        }
        if (allIds.isEmpty()) {
            manualStackGroupByEntryId = emptyMap()
            noStackEntryIds = emptySet()
            lastCustomFieldEntryIds = emptyList()
            return@LaunchedEffect
        }
        if (allIds == lastCustomFieldEntryIds) {
            return@LaunchedEffect
        }
        lastCustomFieldEntryIds = allIds
        val fieldMap = withContext(Dispatchers.IO) {
            viewModel.getCustomFieldsByEntryIds(allIds)
        }
        val (manualStackMap, noStackIds) = withContext(Dispatchers.Default) {
            val manualStack = fieldMap.mapNotNull { (entryId, fields) ->
                val groupId = fields.firstOrNull {
                    it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE
                }?.value?.takeIf { value -> value.isNotBlank() }
                groupId?.let { entryId to it }
            }.toMap()
            val noStack = fieldMap.mapNotNull { (entryId, fields) ->
                val hasNoStack = fields.any {
                    it.title == MONICA_NO_STACK_FIELD_TITLE && it.value != "0"
                }
                if (hasNoStack) entryId else null
            }.toSet()
            manualStack to noStack
        }
        manualStackGroupByEntryId = manualStackMap
        noStackEntryIds = noStackIds
    }
    
    // 根据分组模式对密码进行分组（后台线程计算，避免阻塞首滑）
    var groupedPasswords by remember {
        mutableStateOf<Map<String, List<com.bastion.app.data.PasswordEntry>>>(emptyMap())
    }
    var hasGroupedPasswordsReadyForCurrentInputs by remember {
        mutableStateOf(false)
    }
    val visiblePasswordsForAutoGrouping = remember(
        visiblePasswordEntries,
        manualAggregateStackBuildResult.stackedPasswordIds
    ) {
        visiblePasswordEntries.filter { it.id !in manualAggregateStackBuildResult.stackedPasswordIds }
    }
    LaunchedEffect(
        visiblePasswordsForAutoGrouping,
        effectiveGroupMode,
        appSettings.passwordWebsiteStackMatchMode,
        effectiveStackCardMode,
        effectiveManualStackGroupByEntryId,
        effectiveNoStackEntryIds
    ) {
        val sourceEntries = visiblePasswordsForAutoGrouping
        if (sourceEntries.isEmpty()) {
            groupedPasswords = emptyMap()
            hasGroupedPasswordsReadyForCurrentInputs = true
            return@LaunchedEffect
        }
        hasGroupedPasswordsReadyForCurrentInputs = false
        groupedPasswords = withContext(Dispatchers.Default) {
            buildGroupedPasswordsForEntries(
                sourceEntries = sourceEntries,
                config = groupingConfig
            )
        }
        hasGroupedPasswordsReadyForCurrentInputs = true
    }

    val groupedPasswordsForRender = remember(
        groupedPasswords,
        hasGroupedPasswordsReadyForCurrentInputs,
        visiblePasswordsForAutoGrouping
    ) {
        if (
            groupedPasswords.isEmpty() &&
            !hasGroupedPasswordsReadyForCurrentInputs &&
            visiblePasswordsForAutoGrouping.isNotEmpty()
        ) {
            visiblePasswordsForAutoGrouping
                .sortedBy { entry -> entry.sortOrder }
                .associate { entry -> "entry_${entry.id}" to listOf(entry) }
        } else {
            groupedPasswords
        }
    }

    val shouldRenderPasswordGroups = remember(aggregateUiState.displayedContentTypes) {
        PasswordPageContentType.PASSWORD in aggregateUiState.displayedContentTypes ||
            PasswordPageContentType.AUTHENTICATOR in aggregateUiState.displayedContentTypes ||
            PasswordPageContentType.PASSKEY in aggregateUiState.displayedContentTypes
    }
    val visiblePasswordIds = remember(visiblePasswordsForAutoGrouping) {
        visiblePasswordsForAutoGrouping.map(PasswordEntry::id)
    }
    val groupedPasswordIds = remember(groupedPasswordsForRender) {
        groupedPasswordsForRender.values.flatten().map(PasswordEntry::id)
    }
    // 首次进入页面后保持稳定态，避免目录切换/返回父级时重复触发首帧门控
    var hasCompletedInitialPasswordListStabilization by rememberSaveable {
        mutableStateOf(false)
    }
    val initialRenderState = remember(
        hasCompletedInitialPasswordListStabilization,
        passwordEntriesReady,
        allPasswordsReady,
        categoriesReady,
        shouldRenderPasswordGroups,
        visiblePasswordIds,
        groupedPasswordIds,
        aggregateUiState.displayedContentTypes,
        searchQuery
    ) {
        resolvePasswordListInitialRenderState(
            hasCompletedInitialPasswordListStabilization = hasCompletedInitialPasswordListStabilization,
            passwordEntriesReady = passwordEntriesReady,
            allPasswordsForUiReady = allPasswordsReady,
            categoriesReady = categoriesReady,
            shouldRenderPasswordGroups = shouldRenderPasswordGroups,
            visiblePasswordIds = visiblePasswordIds,
            groupedPasswordIds = groupedPasswordIds,
            displayedContentTypes = aggregateUiState.displayedContentTypes,
            searchQuery = searchQuery
        )
    }
    val isPasswordPageListModelReady = initialRenderState.isPasswordPageListModelReady
    LaunchedEffect(initialRenderState.isPasswordListDataLoaded, isPasswordPageListModelReady) {
        if (initialRenderState.isPasswordListDataLoaded && isPasswordPageListModelReady) {
            hasCompletedInitialPasswordListStabilization = true
        }
    }
    val shouldGateInitialPasswordFirstFrame = initialRenderState.shouldGateInitialContent
    val effectiveVisibleAggregateItems = remember(
        shouldGateInitialPasswordFirstFrame,
        visibleAggregateItems,
        manualAggregateStackBuildResult.stackedAggregateKeys
    ) {
        if (shouldGateInitialPasswordFirstFrame) {
            emptyList()
        } else {
            visibleAggregateItems.filter { item ->
                item.key !in manualAggregateStackBuildResult.stackedAggregateKeys
            }
        }
    }
    val effectiveQuickFolderShortcuts = remember(
        shouldGateInitialPasswordFirstFrame,
        quickFolderShortcuts
    ) {
        if (shouldGateInitialPasswordFirstFrame) emptyList() else quickFolderShortcuts
    }
    val effectiveCategoryQuickFilterShortcuts = remember(
        shouldGateInitialPasswordFirstFrame,
        appSettings.passwordListCategoryQuickFiltersEnabled,
        currentFilter,
        categoryMenuQuickFolderShortcuts
    ) {
        if (shouldGateInitialPasswordFirstFrame || !appSettings.passwordListCategoryQuickFiltersEnabled) {
            emptyList()
        } else {
            when (currentFilter) {
                is CategoryFilter.BitwardenVault,
                is CategoryFilter.BitwardenFolderFilter ->
                    categoryMenuQuickFolderShortcuts.filterNot { it.isBack }
                else -> categoryMenuQuickFolderShortcuts
            }
        }
    }
    val effectiveQuickFolderCardShortcuts = remember(
        appSettings.passwordListCategoryQuickFiltersEnabled,
        effectiveQuickFolderShortcuts
    ) {
        if (appSettings.passwordListCategoryQuickFiltersEnabled) {
            emptyList()
        } else {
            effectiveQuickFolderShortcuts
        }
    }
    val effectiveQuickFolderBreadcrumbs = remember(
        shouldGateInitialPasswordFirstFrame,
        quickFolderBreadcrumbs
    ) {
        if (shouldGateInitialPasswordFirstFrame) emptyList() else quickFolderBreadcrumbs
    }
    val passwordPageListItems = remember(
        aggregateUiState.displayedContentTypes,
        groupedPasswordsForRender,
        effectiveVisibleAggregateItems,
        effectiveGroupMode
    ) {
        buildPasswordPageListItems(
            selectedContentTypes = aggregateUiState.displayedContentTypes,
            groupedPasswords = groupedPasswordsForRender,
            supplementaryItems = effectiveVisibleAggregateItems,
            groupMode = effectiveGroupMode,
            manualStackGroups = manualAggregateStackBuildResult.groups
        )
    }
    val passwordPageListItemKeys = remember(passwordPageListItems) {
        passwordPageListItems.map { item -> item.key }
    }
    val passwordPageListItemKeySet = remember(passwordPageListItemKeys) {
        passwordPageListItemKeys.toSet()
    }
    val visiblePageCards = remember(passwordPageListItems) {
        flattenPasswordPageCardItems(passwordPageListItems)
    }
    val visibleSelectableKeys = remember(visiblePageCards) {
        visiblePageCards.mapTo(linkedSetOf<String>()) { card -> card.key }
    }
    val selectedPageCards = remember(passwordPageListItems, selectedItemKeys) {
        resolveSelectedPasswordPageCardItems(
            items = passwordPageListItems,
            selectedKeys = selectedItemKeys
        )
    }
    val selectedPasswords = remember(selectedPageCards) {
        selectedPageCards.mapNotNullTo(linkedSetOf<Long>()) { card -> card.passwordId }
    }
    val selectedSupplementaryItems = remember(selectedPageCards) {
        selectedPageCards.mapNotNull { card -> card.toSelectedSupplementaryItemOrNull() }
    }
    val hasSelectedSupplementaryItems = remember(selectedSupplementaryItems) {
        selectedSupplementaryItems.isNotEmpty()
    }

    LaunchedEffect(visibleSelectableKeys) {
        if (selectedItemKeys.isEmpty()) return@LaunchedEffect
        selectedItemKeys = selectedItemKeys.intersect(visibleSelectableKeys)
    }
    val hasVisibleQuickFilters = remember(
        appSettings.passwordListQuickFiltersEnabled,
        configuredQuickFilterItems,
        aggregateUiState.visibleContentTypes,
        shouldGateInitialPasswordFirstFrame,
        hasAnyWifiEntry,
        hasAnySshKeyEntry,
        hasAnyBarcodeEntry
    ) {
        if (shouldGateInitialPasswordFirstFrame) return@remember false
        val hasConfiguredChips = appSettings.passwordListQuickFiltersEnabled &&
            configuredQuickFilterItems.any { item ->
                shouldShowQuickFilterItem(item, aggregateUiState.visibleContentTypes)
            }
        // WIFI / SSH chip 无需 quickFilters 设置开关——"有数据就冒出来"语义。
        hasConfiguredChips || hasAnyWifiEntry || hasAnySshKeyEntry || hasAnyBarcodeEntry
    }
    val hasVisibleCategoryQuickFilters = remember(
        effectiveCategoryQuickFilterShortcuts
    ) {
        effectiveCategoryQuickFilterShortcuts.isNotEmpty()
    }
    val hasQuickStatusProgress =
        quickStatusTransferState != null ||
            quickStatusDeleteState != null ||
            quickStatusBitwardenSyncState != null
    val showPinnedQuickFolderPathBanner =
        quickStatusBannerEnabled &&
            (effectiveQuickFolderBreadcrumbs.isNotEmpty() || hasQuickStatusProgress)
    val hasScrollableHeaderContent = remember(
        hasVisibleQuickFilters,
        hasVisibleCategoryQuickFilters,
        effectiveQuickFolderCardShortcuts,
        showPinnedQuickFolderPathBanner
    ) {
        hasVisibleQuickFilters ||
            hasVisibleCategoryQuickFilters ||
            effectiveQuickFolderCardShortcuts.isNotEmpty() ||
            showPinnedQuickFolderPathBanner
    }
    val hasVisibleListItems = passwordPageListItems.isNotEmpty()
    val usesLazyColumn = remember(
        isPasswordPageListModelReady,
        hasVisibleListItems,
        hasScrollableHeaderContent,
        searchQuery,
        visiblePasswordEntries,
        effectiveVisibleAggregateItems
    ) {
        if (!isPasswordPageListModelReady) {
            visiblePasswordEntries.isNotEmpty() ||
                effectiveVisibleAggregateItems.isNotEmpty() ||
                hasScrollableHeaderContent ||
                searchQuery.isNotEmpty()
        } else {
            hasVisibleListItems || hasScrollableHeaderContent || searchQuery.isNotEmpty()
        }
    }
    val shouldShowEmptyState = remember(
        isPasswordPageListModelReady,
        usesLazyColumn,
        hasVisibleListItems,
        searchQuery,
        shouldGateInitialPasswordFirstFrame
    ) {
        isPasswordPageListModelReady &&
            usesLazyColumn &&
            !hasVisibleListItems &&
            searchQuery.isEmpty() &&
            !shouldGateInitialPasswordFirstFrame
    }
    var showEmptyStateWithHeaders by remember {
        mutableStateOf(false)
    }
    LaunchedEffect(shouldShowEmptyState) {
        if (!shouldShowEmptyState) {
            showEmptyStateWithHeaders = false
            return@LaunchedEffect
        }
        delay(PASSWORD_EMPTY_STATE_DEBOUNCE_MS)
        showEmptyStateWithHeaders = true
    }
    val listState = rememberPasswordListLazyListState(
        viewModel = viewModel,
        currentListItemKeys = passwordPageListItemKeys,
        scrollToTopRequestKey = scrollToTopRequestKey,
        fastScrollRequestKey = fastScrollRequestKey,
        fastScrollProgress = fastScrollProgress,
        allowScrollPositionPersistence =
            isPasswordPageListModelReady &&
                hasVisibleListItems &&
                !shouldGateInitialPasswordFirstFrame,
        onBackToTopVisibilityChange = onBackToTopVisibilityChange
    )
    var lastHandledFilterForScrollReset by remember {
        mutableStateOf<CategoryFilter?>(null)
    }
    LaunchedEffect(currentFilter, usesLazyColumn) {
        val previousFilter = lastHandledFilterForScrollReset
        if (previousFilter == null) {
            lastHandledFilterForScrollReset = currentFilter
            return@LaunchedEffect
        }
        if (previousFilter == currentFilter) {
            return@LaunchedEffect
        }
        lastHandledFilterForScrollReset = currentFilter
        Log.d(
            PASSWORD_SCROLL_LOG_TAG,
            "source=v1_filter_change_force_top from=$previousFilter to=$currentFilter usesLazyColumn=$usesLazyColumn"
        )
        if (usesLazyColumn) {
            runCatchingObserved {
                listState.scrollToItem(0, 0)
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                Log.w(
                    PASSWORD_SCROLL_LOG_TAG,
                    "source=v1_filter_change_force_top_failed to=$currentFilter",
                    throwable
                )
            }
        }
        viewModel.updatePasswordListScrollPosition(
            0,
            0,
            null,
            source = "v1_filter_change_force_top"
        )
    }

    val selectionHandlers = rememberPasswordListSelectionHandlers(
        context = context,
        coroutineScope = coroutineScope,
        viewModel = viewModel,
        selectedItemKeys = selectedItemKeys,
        visibleSelectableKeys = visibleSelectableKeys,
        selectedPasswords = selectedPasswords,
        passwordEntries = passwordEntries,
        selectedSupplementaryItems = selectedSupplementaryItems,
        aggregateUiState = aggregateUiState,
        onClearSelection = {
            isSelectionMode = false
            selectedItemKeys = emptySet()
            swipeSelectionAnchorKey = null
        },
        onSelectedItemKeysChange = {
            selectedItemKeys = it
            if (it.isEmpty()) swipeSelectionAnchorKey = null
        },
        onShowMoveToCategoryDialog = { showMoveToCategoryDialog = true },
        onShowManualStackConfirmDialog = {
            selectedManualStackMode = ManualStackDialogMode.STACK
            showManualStackConfirmDialog = true
        },
        onShowBatchDeleteDialog = { showBatchDeleteDialog = true }
    )

    BindPasswordListSelectionModeChange(
        isSelectionMode = isSelectionMode,
        selectedItemKeys = selectedItemKeys,
        selectedPasswords = selectedPasswords,
        selectedSupplementaryItems = selectedSupplementaryItems,
        handlers = selectionHandlers,
        onSelectionModeChange = onSelectionModeChange
    )

    PasswordBatchMoveSheet(
        visible = showMoveToCategoryDialog,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        database = database,
        localKeePassViewModel = localKeePassViewModel,
        securityManager = securityManager,
        selectedPasswords = selectedPasswords,
        selectedSupplementaryItems = selectedSupplementaryItems,
        passwordEntries = passwordEntries,
        aggregateUiState = aggregateUiState,
        viewModel = viewModel,
        bitwardenRepository = bitwardenRepository,
        context = context,
        coroutineScope = coroutineScope,
        onRenameCategory = onRenameCategory,
        onDeleteCategory = onDeleteCategory,
        onDismiss = { showMoveToCategoryDialog = false },
        onSelectionCleared = {
            isSelectionMode = false
            selectedItemKeys = emptySet()
            swipeSelectionAnchorKey = null
        }
    )

    @Composable
    fun RenderPasswordListTopSection() {
        PasswordListTopSection(
            currentFilter = currentFilter,
            categories = categories,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            viewModel = viewModel,
            localKeePassViewModel = localKeePassViewModel,
            bitwardenViewModel = bitwardenViewModel,
            selectedBitwardenVaultId = selectedBitwardenVaultId,
            selectedKeePassDatabaseId = selectedKeePassDatabaseId,
            isTopBarSyncing = isTopBarSyncing,
            isArchiveView = isArchiveView,
            isKeePassDatabaseView = isKeePassDatabaseView,
            searchQuery = searchQuery,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = { isSearchExpanded = it },
            onSearchQueryChange = viewModel::updateSearchQuery,
            topActionsMenuExpanded = isAuthenticated && topActionsMenuExpanded,
            onTopActionsMenuExpandedChange = { topActionsMenuExpanded = it },
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            isCategorySheetVisible = isCategorySheetVisible,
            onCategorySheetVisibleChange = { isCategorySheetVisible = it },
            categoryPillBoundsInWindow = categoryPillBoundsInWindow,
            onCategoryPillBoundsChange = { categoryPillBoundsInWindow = it },
            showDisplayOptionsSheet = showDisplayOptionsSheet,
            onShowDisplayOptionsSheetChange = { showDisplayOptionsSheet = it },
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterFavorite,
            onQuickFilterFavoriteChange = { quickFilterFavorite = it },
            quickFilter2fa = quickFilter2fa,
            onQuickFilter2faChange = { quickFilter2fa = it },
            quickFilterNotes = quickFilterNotes,
            onQuickFilterNotesChange = { quickFilterNotes = it },
            quickFilterPasskey = quickFilterPasskey,
            onQuickFilterPasskeyChange = { quickFilterPasskey = it },
            quickFilterBoundNote = quickFilterBoundNote,
            onQuickFilterBoundNoteChange = { quickFilterBoundNote = it },
            quickFilterAttachments = quickFilterAttachments,
            onQuickFilterAttachmentsChange = { quickFilterAttachments = it },
            quickFilterUncategorized = quickFilterUncategorized,
            onQuickFilterUncategorizedChange = { quickFilterUncategorized = it },
            quickFilterLocalOnly = quickFilterLocalOnly,
            onQuickFilterLocalOnlyChange = { quickFilterLocalOnly = it },
            quickFilterManualStackOnly = quickFilterManualStackOnly,
            onQuickFilterManualStackOnlyChange = { quickFilterManualStackOnly = it },
            quickFilterNeverStack = quickFilterNeverStack,
            onQuickFilterNeverStackChange = { quickFilterNeverStack = it },
            quickFilterUnstacked = quickFilterUnstacked,
            onQuickFilterUnstackedChange = { quickFilterUnstacked = it },
            aggregateSelectedTypes = aggregateUiState.selectedContentTypes,
            aggregateVisibleTypes = aggregateUiState.visibleContentTypes,
            onToggleAggregateType = { type -> aggregateConfig?.onToggleContentType?.invoke(type) },
            categoryMenuQuickFolderShortcuts = categoryMenuQuickFolderShortcuts,
            stackCardMode = stackCardMode,
            groupMode = groupMode,
            passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
            settingsViewModel = settingsViewModel,
            context = context,
            activity = activity,
            biometricHelper = biometricHelper,
            canUseBiometric = canUseBiometric,
            coroutineScope = coroutineScope,
            bitwardenRepository = bitwardenRepository,
            securityManager = securityManager,
            onRenameCategory = onRenameCategory,
            onDeleteCategory = onDeleteCategory,
            onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
            onOpenHistory = onOpenHistory,
            onOpenTrash = onOpenTrash,
            onScanFidoQr = onScanFidoQr
        )
    }

    val decryptAuthenticatorKeyForPreview: (String) -> String = remember(securityManager) {
        { value: String ->
            runCatchingObserved { securityManager.decryptDataIfBastionCiphertext(value) }
                .getOrDefault(value)
        }
    }

    @Composable
    fun RenderPasswordListMainPaneHost() {
        PasswordListMainPaneHost(
            canCollapseExpandedGroups = canCollapseExpandedGroups,
            outsideTapInteractionSource = outsideTapInteractionSource,
            onCollapseExpandedGroups = viewModel::clearExpandedGroups,
            isBitwardenDatabaseView = isBitwardenDatabaseView,
            pullAction = pullAction,
            triggerDistance = triggerDistance,
            syncTriggerDistance = syncTriggerDistance,
            density = density,
            showPinnedQuickFolderPathBanner = showPinnedQuickFolderPathBanner,
            quickFolderBreadcrumbs = effectiveQuickFolderBreadcrumbs,
            quickStatusTransferState = quickStatusTransferState,
            onShowQuickStatusTransferDialog = {
                backgroundedTransferOperationId = null
                showQuickStatusTransferDialog = true
            },
            quickStatusDeleteState = quickStatusDeleteState,
            onShowQuickStatusDeleteDialog = {
                backgroundedDeleteOperationId = null
                showQuickStatusDeleteDialog = true
            },
            quickStatusBitwardenSyncState = quickStatusBitwardenSyncState,
            quickStatusKeePassSyncState = quickStatusKeePassSyncState,
            currentFilter = currentFilter,
            onNavigateFilter = viewModel::setCategoryFilter,
            shouldGateInitialPasswordFirstFrame = shouldGateInitialPasswordFirstFrame,
            searchQuery = searchQuery,
            isPasswordPageListModelReady = isPasswordPageListModelReady,
            hasVisibleListItems = hasVisibleListItems,
            showEmptyState = showEmptyStateWithHeaders,
            hasScrollableHeaderContent = hasScrollableHeaderContent,
            hasVisibleQuickFilters = hasVisibleQuickFilters,
            hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
            aggregateUiState = aggregateUiState,
            emptyStateMessage = emptyStateMessage,
            listState = listState,
            appSettings = appSettings,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterFavorite,
            onQuickFilterFavoriteChange = { quickFilterFavorite = it },
            quickFilter2fa = quickFilter2fa,
            onQuickFilter2faChange = { quickFilter2fa = it },
            quickFilterNotes = quickFilterNotes,
            onQuickFilterNotesChange = { quickFilterNotes = it },
            quickFilterPasskey = quickFilterPasskey,
            onQuickFilterPasskeyChange = { quickFilterPasskey = it },
            quickFilterBoundNote = quickFilterBoundNote,
            onQuickFilterBoundNoteChange = { quickFilterBoundNote = it },
            quickFilterAttachments = quickFilterAttachments,
            onQuickFilterAttachmentsChange = { quickFilterAttachments = it },
            quickFilterUncategorized = quickFilterUncategorized,
            onQuickFilterUncategorizedChange = { quickFilterUncategorized = it },
            quickFilterLocalOnly = quickFilterLocalOnly,
            onQuickFilterLocalOnlyChange = { quickFilterLocalOnly = it },
            quickFilterManualStackOnly = quickFilterManualStackOnly,
            onQuickFilterManualStackOnlyChange = { quickFilterManualStackOnly = it },
            quickFilterNeverStack = quickFilterNeverStack,
            onQuickFilterNeverStackChange = { quickFilterNeverStack = it },
            quickFilterUnstacked = quickFilterUnstacked,
            onQuickFilterUnstackedChange = { quickFilterUnstacked = it },
            quickFilterWifi = quickFilterWifi,
            onQuickFilterWifiChange = { quickFilterWifi = it },
            wifiQuickFilterVisible = hasAnyWifiEntry,
            quickFilterSshKey = quickFilterSshKey,
            onQuickFilterSshKeyChange = { quickFilterSshKey = it },
            sshKeyQuickFilterVisible = hasAnySshKeyEntry,
            quickFilterBarcode = quickFilterBarcode,
            onQuickFilterBarcodeChange = { quickFilterBarcode = it },
            barcodeQuickFilterVisible = hasAnyBarcodeEntry,
            onToggleAggregateType = aggregateConfig?.onToggleContentType,
            categoryQuickFilterShortcuts = effectiveCategoryQuickFilterShortcuts,
            quickFolderShortcuts = effectiveQuickFolderCardShortcuts,
            quickFolderStyle = quickFolderStyle,
            passwordPageListItems = passwordPageListItems,
            effectiveStackCardMode = effectiveStackCardMode,
            expandedGroups = expandedGroups,
            itemToDelete = itemToDelete,
            onItemToDeleteChange = { itemToDelete = it },
            isSelectionMode = isSelectionMode,
            onSelectionModeChange = { isSelectionMode = it },
            selectedItemKeys = selectedItemKeys,
            onSelectedItemKeysChange = { selectedItemKeys = it },
            swipeSelectionAnchorKey = swipeSelectionAnchorKey,
            onSwipeSelectionAnchorKeyChange = { swipeSelectionAnchorKey = it },
            selectedPasswords = selectedPasswords,
            showBatchDeleteDialog = showBatchDeleteDialog,
            onShowBatchDeleteDialogChange = { showBatchDeleteDialog = it },
            viewModel = viewModel,
            haptic = haptic,
            onPasswordClick = onPasswordClick,
            passwordPageListItemKeySet = passwordPageListItemKeySet,
            coroutineScope = coroutineScope,
            context = context,
            passwordEntries = passwordEntries,
            aggregateConfig = aggregateConfig,
            decryptAuthenticatorKey = decryptAuthenticatorKeyForPreview
        )
    }

    RenderPasswordListTopSection()
    RenderPasswordListMainPaneHost()

    PasswordListQuickStatusDialogs(
        showQuickStatusTransferDialog = showQuickStatusTransferDialog,
        quickStatusTransferState = quickStatusTransferState,
        onMoveTransferToBackground = {
            backgroundedTransferOperationId = quickStatusTransferState?.operationId
            showQuickStatusTransferDialog = false
        },
        showQuickStatusDeleteDialog = showQuickStatusDeleteDialog,
        quickStatusDeleteState = quickStatusDeleteState,
        onMoveDeleteToBackground = {
            backgroundedDeleteOperationId = quickStatusDeleteState?.operationId
            showQuickStatusDeleteDialog = false
        },
        showQuickStatusKeePassSyncDialog = showQuickStatusKeePassSyncDialog,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        onMoveKeePassSyncToBackground = { state ->
            backgroundedKeePassSyncKey = state.dialogSuppressionKey()
            showQuickStatusKeePassSyncDialog = false
        },
        onRunKeePassSyncNow = { state ->
            backgroundedKeePassSyncKey = null
            state.onSync()
            showQuickStatusKeePassSyncDialog = false
        }
    )
    
    PasswordListDialogs(
        showManualStackConfirmDialog = showManualStackConfirmDialog,
        onShowManualStackConfirmDialogChange = { showManualStackConfirmDialog = it },
        selectedItemKeys = selectedItemKeys,
        selectedPasswords = selectedPasswords,
        selectedCount = selectedItemKeys.size,
        selectedManualStackMode = selectedManualStackMode,
        onSelectedManualStackModeChange = { selectedManualStackMode = it },
        onApplyManualStackMode = { dialogMode, itemKeys, passwordIds ->
            val validItemKeys = itemKeys.filterTo(linkedSetOf()) { it.isNotBlank() }
            if (
                validItemKeys.size < 2 ||
                validItemKeys.size != passwordIds.size
            ) {
                0
            } else {
                val mode = when (dialogMode) {
                    ManualStackDialogMode.STACK -> PasswordViewModel.ManualStackMode.STACK
                    ManualStackDialogMode.AUTO_STACK -> PasswordViewModel.ManualStackMode.AUTO_STACK
                    ManualStackDialogMode.NEVER_STACK -> PasswordViewModel.ManualStackMode.NEVER_STACK
                }
                viewModel.applyManualStackMode(passwordIds.toList(), mode)
                passwordIds.size
            }
        },
        viewModel = viewModel,
        context = context,
        coroutineScope = coroutineScope,
        enableBatchDeleteProgress = selectedPasswords.any { id ->
            passwordEntries.any { it.id == id && it.keepassDatabaseId != null }
        } || selectedSupplementaryItems.any { it.entry.keepassDatabaseId != null },
        onDeleteSelection = { onProgress ->
            val selectedPasswordIdsSnapshot = selectedPasswords.toSet()
            val selectedSupplementaryItemsSnapshot = selectedSupplementaryItems.toList()
            val selectedItemKeysSnapshot = selectedItemKeys.toList()
            val selectedPasswordEntries = passwordEntries.filter { it.id in selectedPasswordIdsSnapshot }
            val totalToProcess = selectedPasswordEntries.size + selectedSupplementaryItemsSnapshot.size
            var processedCount = 0
            onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            if (selectedItemKeysSnapshot.isNotEmpty()) {
                coroutineScope.launch {
                    aggregateStackRepository.clearManualStack(selectedItemKeysSnapshot)
                }
            }
            val deletedPasswordCount = viewModel.deletePasswordEntriesBatch(selectedPasswordEntries) { processed, _ ->
                processedCount = processed.coerceIn(0, selectedPasswordEntries.size)
                onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            }

            selectedSupplementaryItemsSnapshot.forEach { item ->
                when (item.type) {
                    PasswordPageContentType.AUTHENTICATOR -> {
                        aggregateUiState.totpItems
                            .firstOrNull { it.id == item.secureItemId }
                            ?.let { aggregateUiState.totpViewModel?.deleteTotpItem(it) }
                    }

                    PasswordPageContentType.CARD_WALLET -> {
                        item.secureItemId?.let { id ->
                            when (item.walletItemType) {
                                PasswordAggregateWalletItemType.BANK_CARD ->
                                    aggregateUiState.bankCardViewModel?.deleteCard(id)
                                PasswordAggregateWalletItemType.DOCUMENT ->
                                    aggregateUiState.documentViewModel?.deleteDocument(id)
                                PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                                    aggregateUiState.billingAddressViewModel?.deleteAddress(id)
                                null -> Unit
                            }
                        }
                    }

                    PasswordPageContentType.NOTE -> {
                        aggregateUiState.notes
                            .firstOrNull { it.id == item.secureItemId }
                            ?.let { aggregateUiState.noteViewModel?.deleteNote(it) }
                    }

                    PasswordPageContentType.PASSKEY -> {
                        item.passkeyRecordId?.let { recordId ->
                            aggregateUiState.passkeyViewModel?.deletePasskeyByRecordId(recordId)
                        }
                    }

                    PasswordPageContentType.PASSWORD -> Unit
                }
                processedCount = (processedCount + 1).coerceAtMost(totalToProcess.coerceAtLeast(1))
                onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            }

            deletedPasswordCount + selectedSupplementaryItemsSnapshot.size
        },
        onBatchDeleteStarted = {
            isSelectionMode = false
            selectedItemKeys = emptySet()
            swipeSelectionAnchorKey = null
        },
        onSelectionCleared = {
            isSelectionMode = false
            selectedItemKeys = emptySet()
            swipeSelectionAnchorKey = null
        },
        showBatchDeleteDialog = showBatchDeleteDialog,
        onShowBatchDeleteDialogChange = { showBatchDeleteDialog = it },
        passwordInput = passwordInput,
        onPasswordInputChange = {
            passwordInput = it
            passwordError = false
        },
        passwordError = passwordError,
        onPasswordErrorChange = { passwordError = it },
        canUseBiometric = canUseBiometric,
        activity = activity,
        biometricHelper = biometricHelper,
        itemToDelete = itemToDelete,
        onItemToDeleteChange = { itemToDelete = it },
        appSettings = appSettings,
        singleItemPasswordInput = singleItemPasswordInput,
        onSingleItemPasswordInputChange = { singleItemPasswordInput = it },
        showSingleItemPasswordVerify = showSingleItemPasswordVerify,
        onShowSingleItemPasswordVerifyChange = { showSingleItemPasswordVerify = it }
    )
}

@Composable
private fun PasswordListMainPaneHost(
    canCollapseExpandedGroups: Boolean,
    outsideTapInteractionSource: MutableInteractionSource,
    onCollapseExpandedGroups: () -> Unit,
    isBitwardenDatabaseView: Boolean,
    pullAction: com.bastion.app.ui.common.pull.PullActionStateHandle,
    triggerDistance: Float,
    syncTriggerDistance: Float,
    density: androidx.compose.ui.unit.Density,
    showPinnedQuickFolderPathBanner: Boolean,
    quickFolderBreadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    quickStatusTransferState: com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState?,
    onShowQuickStatusTransferDialog: () -> Unit,
    quickStatusDeleteState: com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState?,
    onShowQuickStatusDeleteDialog: () -> Unit,
    quickStatusBitwardenSyncState: QuickStatusBitwardenSyncState?,
    quickStatusKeePassSyncState: QuickStatusKeePassSyncState?,
    currentFilter: CategoryFilter,
    onNavigateFilter: (CategoryFilter) -> Unit,
    shouldGateInitialPasswordFirstFrame: Boolean,
    searchQuery: String,
    isPasswordPageListModelReady: Boolean,
    hasVisibleListItems: Boolean,
    showEmptyState: Boolean,
    hasScrollableHeaderContent: Boolean,
    hasVisibleQuickFilters: Boolean,
    hasVisibleCategoryQuickFilters: Boolean,
    aggregateUiState: PasswordListAggregateUiState,
    emptyStateMessage: PasswordListEmptyStateMessage,
    listState: LazyListState,
    appSettings: AppSettings,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    quickFilterFavorite: Boolean,
    onQuickFilterFavoriteChange: (Boolean) -> Unit,
    quickFilter2fa: Boolean,
    onQuickFilter2faChange: (Boolean) -> Unit,
    quickFilterNotes: Boolean,
    onQuickFilterNotesChange: (Boolean) -> Unit,
    quickFilterPasskey: Boolean,
    onQuickFilterPasskeyChange: (Boolean) -> Unit,
    quickFilterBoundNote: Boolean,
    onQuickFilterBoundNoteChange: (Boolean) -> Unit,
    quickFilterAttachments: Boolean,
    onQuickFilterAttachmentsChange: (Boolean) -> Unit,
    quickFilterUncategorized: Boolean,
    onQuickFilterUncategorizedChange: (Boolean) -> Unit,
    quickFilterLocalOnly: Boolean,
    onQuickFilterLocalOnlyChange: (Boolean) -> Unit,
    quickFilterManualStackOnly: Boolean,
    onQuickFilterManualStackOnlyChange: (Boolean) -> Unit,
    quickFilterNeverStack: Boolean,
    onQuickFilterNeverStackChange: (Boolean) -> Unit,
    quickFilterUnstacked: Boolean,
    onQuickFilterUnstackedChange: (Boolean) -> Unit,
    quickFilterWifi: Boolean,
    onQuickFilterWifiChange: (Boolean) -> Unit,
    wifiQuickFilterVisible: Boolean,
    quickFilterSshKey: Boolean,
    onQuickFilterSshKeyChange: (Boolean) -> Unit,
    sshKeyQuickFilterVisible: Boolean,
    quickFilterBarcode: Boolean,
    onQuickFilterBarcodeChange: (Boolean) -> Unit,
    barcodeQuickFilterVisible: Boolean,
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)?,
    categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderStyle: com.bastion.app.data.PasswordListQuickFolderStyle,
    passwordPageListItems: List<PasswordPageListItemUi>,
    effectiveStackCardMode: StackCardMode,
    expandedGroups: Set<String>,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    onSelectedItemKeysChange: (Set<String>) -> Unit,
    swipeSelectionAnchorKey: String?,
    onSwipeSelectionAnchorKeyChange: (String?) -> Unit,
    selectedPasswords: Set<Long>,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    viewModel: PasswordViewModel,
    haptic: com.bastion.app.ui.haptic.HapticFeedbackHelper,
    onPasswordClick: (PasswordEntry) -> Unit,
    passwordPageListItemKeySet: Set<String>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    passwordEntries: List<PasswordEntry>,
    aggregateConfig: PasswordListAggregateConfig?,
    decryptAuthenticatorKey: ((String) -> String)?
) {
    PasswordListMainPane(
        canCollapseExpandedGroups = canCollapseExpandedGroups,
        outsideTapInteractionSource = outsideTapInteractionSource,
        onCollapseExpandedGroups = onCollapseExpandedGroups,
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        pullAction = pullAction,
        triggerDistance = triggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        density = density,
        showPinnedQuickFolderPathBanner = showPinnedQuickFolderPathBanner,
        quickFolderBreadcrumbs = quickFolderBreadcrumbs,
        quickStatusTransferState = quickStatusTransferState,
        onQuickStatusTransferClick = {
            if (quickStatusTransferState != null) {
                onShowQuickStatusTransferDialog()
            }
        },
        quickStatusDeleteState = quickStatusDeleteState,
        onQuickStatusDeleteClick = {
            if (quickStatusDeleteState != null) {
                onShowQuickStatusDeleteDialog()
            }
        },
        quickStatusBitwardenSyncState = quickStatusBitwardenSyncState,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        currentFilter = currentFilter,
        onNavigateFilter = onNavigateFilter,
        shouldGateInitialPasswordFirstFrame = shouldGateInitialPasswordFirstFrame,
        searchQuery = searchQuery,
        isPasswordPageListModelReady = isPasswordPageListModelReady,
        hasVisibleListItems = hasVisibleListItems,
        showEmptyState = showEmptyState,
        hasScrollableHeaderContent = hasScrollableHeaderContent,
        hasVisibleQuickFilters = hasVisibleQuickFilters,
        hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
        aggregateUiState = aggregateUiState,
        emptyStateMessage = emptyStateMessage,
        listState = listState,
        appSettings = appSettings,
        configuredQuickFilterItems = configuredQuickFilterItems,
        quickFilterFavorite = quickFilterFavorite,
        onQuickFilterFavoriteChange = onQuickFilterFavoriteChange,
        quickFilter2fa = quickFilter2fa,
        onQuickFilter2faChange = onQuickFilter2faChange,
        quickFilterNotes = quickFilterNotes,
        onQuickFilterNotesChange = onQuickFilterNotesChange,
        quickFilterPasskey = quickFilterPasskey,
        onQuickFilterPasskeyChange = onQuickFilterPasskeyChange,
        quickFilterBoundNote = quickFilterBoundNote,
        onQuickFilterBoundNoteChange = onQuickFilterBoundNoteChange,
        quickFilterAttachments = quickFilterAttachments,
        onQuickFilterAttachmentsChange = onQuickFilterAttachmentsChange,
        quickFilterUncategorized = quickFilterUncategorized,
        onQuickFilterUncategorizedChange = onQuickFilterUncategorizedChange,
        quickFilterLocalOnly = quickFilterLocalOnly,
        onQuickFilterLocalOnlyChange = onQuickFilterLocalOnlyChange,
        quickFilterManualStackOnly = quickFilterManualStackOnly,
        onQuickFilterManualStackOnlyChange = onQuickFilterManualStackOnlyChange,
        quickFilterNeverStack = quickFilterNeverStack,
        onQuickFilterNeverStackChange = onQuickFilterNeverStackChange,
        quickFilterUnstacked = quickFilterUnstacked,
        onQuickFilterUnstackedChange = onQuickFilterUnstackedChange,
        quickFilterWifi = quickFilterWifi,
        onQuickFilterWifiChange = onQuickFilterWifiChange,
        wifiQuickFilterVisible = wifiQuickFilterVisible,
        quickFilterSshKey = quickFilterSshKey,
        onQuickFilterSshKeyChange = onQuickFilterSshKeyChange,
        sshKeyQuickFilterVisible = sshKeyQuickFilterVisible,
        quickFilterBarcode = quickFilterBarcode,
        onQuickFilterBarcodeChange = onQuickFilterBarcodeChange,
        barcodeQuickFilterVisible = barcodeQuickFilterVisible,
        onToggleAggregateType = onToggleAggregateType,
        categoryQuickFilterShortcuts = categoryQuickFilterShortcuts,
        quickFolderShortcuts = quickFolderShortcuts,
        quickFolderStyle = quickFolderStyle,
        renderPasswordRows = {
            passwordPageListRows(
                passwordPageListItems = passwordPageListItems,
                effectiveStackCardMode = effectiveStackCardMode,
                expandedGroups = expandedGroups,
                itemToDelete = itemToDelete,
                onItemToDeleteChange = onItemToDeleteChange,
                isSelectionMode = isSelectionMode,
                onSelectionModeChange = onSelectionModeChange,
                selectedItemKeys = selectedItemKeys,
                onSelectedItemKeysChange = onSelectedItemKeysChange,
                swipeSelectionAnchorKey = swipeSelectionAnchorKey,
                onSwipeSelectionAnchorKeyChange = onSwipeSelectionAnchorKeyChange,
                selectedPasswords = selectedPasswords,
                showBatchDeleteDialog = showBatchDeleteDialog,
                onShowBatchDeleteDialogChange = onShowBatchDeleteDialogChange,
                viewModel = viewModel,
                haptic = haptic,
                onPasswordClick = { password ->
                    val topVisibleKey = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { item -> item.key.toString() in passwordPageListItemKeySet }
                        ?.key
                        ?.toString()
                    Log.d(
                        PASSWORD_SCROLL_LOG_TAG,
                        "source=v1_click_open_detail persist=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} anchor=$topVisibleKey"
                    )
                    viewModel.updatePasswordListScrollPosition(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                        topVisibleKey,
                        source = "v1_click_open_detail"
                    )
                    onPasswordClick(password)
                },
                appSettings = appSettings,
                coroutineScope = coroutineScope,
                context = context,
                passwordEntries = passwordEntries,
                aggregateConfig = aggregateConfig,
                aggregateUiState = aggregateUiState,
                decryptAuthenticatorKey = decryptAuthenticatorKey
            )
        }
    )
}

private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__bastion_manual_stack_group"
private const val MONICA_NO_STACK_FIELD_TITLE = "__bastion_no_stack"
