package com.bastion.app.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.core.content.ContextCompat
import com.bastion.app.R
import com.bastion.app.data.model.PermissionCategory
import com.bastion.app.data.model.PermissionImportance
import com.bastion.app.data.model.PermissionInfo
import com.bastion.app.data.model.PermissionStats
import com.bastion.app.data.model.PermissionStatus
import com.bastion.app.service.BastionAccessibilityService

/**
 * 权限管理Repository
 * Permission management repository
 */
class PermissionRepository(private val context: Context) {

    private var cachedPermissions: List<PermissionInfo>? = null
    private var cacheTimestamp: Long = 0
    private val cacheValidityMs = 5000L // 5秒缓存

    /**
     * 获取所有权限信息
     * Get all permissions with current status
     */
    fun getAllPermissions(forceRefresh: Boolean = false): List<PermissionInfo> {
        val now = System.currentTimeMillis()

        if (!forceRefresh &&
            cachedPermissions != null &&
            (now - cacheTimestamp) < cacheValidityMs
        ) {
            return cachedPermissions!!
        }

        val permissions = loadPermissions()
        cachedPermissions = permissions
        cacheTimestamp = now

        return permissions
    }

    /**
     * 加载所有权限并检测状态
     * Load all permissions and check their status
     */
    private fun loadPermissions(): List<PermissionInfo> {
        // 仅保留用户真正需要手动授予/检查的权限。
        // 以下“普通权限”在安装时自动授予且全项目无功能依赖，列为卡片纯属噪声，已移除：
        //   INTERNET / ACCESS_NETWORK_STATE / VIBRATE（永远 GRANTED）
        //   READ_PHONE_STATE（无人使用）
        return listOf(
            createBiometricPermission(),
            createCameraPermission(),
            createStoragePermission(),
            createNotificationPermission(),
            createAutofillPermission(),
            createAccessibilityPermission()
        ).map { permission ->
            try {
                val status = checkPermissionStatus(permission)
                permission.copy(status = status)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check permission: ${permission.id}", e)
                permission.copy(status = PermissionStatus.UNKNOWN)
            }
        }
    }

    /**
     * 检测权限状态
     * Check permission status
     */
    private fun checkPermissionStatus(permission: PermissionInfo): PermissionStatus {
        return when (permission.id) {
            "BIOMETRIC" -> checkBiometricStatus()
            "AUTOFILL" -> checkAutofillStatus()
            "ACCESSIBILITY" -> checkAccessibilityStatus()
            "NOTIFICATION" -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = ContextCompat.checkSelfPermission(
                        context,
                        permission.androidPermission
                    )
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        PermissionStatus.GRANTED
                    } else {
                        PermissionStatus.DENIED
                    }
                } else {
                    PermissionStatus.GRANTED
                }
            }
            else -> {
                val result = ContextCompat.checkSelfPermission(
                    context,
                    permission.androidPermission
                )
                if (result == PackageManager.PERMISSION_GRANTED) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }
            }
        }
    }

    /**
     * 检测生物识别状态
     * Check biometric authentication status
     */
    private fun checkBiometricStatus(): PermissionStatus {
        return try {
            val biometricManager = BiometricManager.from(context)
            when (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
            )) {
                BiometricManager.BIOMETRIC_SUCCESS -> PermissionStatus.GRANTED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> PermissionStatus.UNAVAILABLE
                else -> PermissionStatus.DENIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check biometric status", e)
            PermissionStatus.UNKNOWN
        }
    }

    /**
     * 检测自动填充服务状态
     * Check autofill service status
     */
    private fun checkAutofillStatus(): PermissionStatus {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val autofillManager = context.getSystemService(android.view.autofill.AutofillManager::class.java)
                if (autofillManager?.hasEnabledAutofillServices() == true) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }
            } else {
                PermissionStatus.UNAVAILABLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check autofill status", e)
            PermissionStatus.UNKNOWN
        }
    }

    private fun checkAccessibilityStatus(): PermissionStatus {
        return try {
            if (BastionAccessibilityService.isServiceEnabled(context)) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility status", e)
            PermissionStatus.UNKNOWN
        }
    }

    /**
     * 按分类分组权限（固定分类顺序，仅显示非空分类；卡内按“未授予优先 + 重要性”排序）
     * Group permissions by category with a stable order; within a category,
     * denied/unknown permissions (that need user action) are shown first.
     */
    fun getPermissionsByCategory(): Map<PermissionCategory, List<PermissionInfo>> {
        val all = getAllPermissions()
        val grouped = all.groupBy { it.category }
        val orderedCategories = PermissionCategory.values().filter { grouped.containsKey(it) }
        val result = linkedMapOf<PermissionCategory, List<PermissionInfo>>()
        for (category in orderedCategories) {
            val sorted = grouped[category].orEmpty().sortedWith(
                compareBy<PermissionInfo> { statusAttentionPriority(it.status) }
                    .thenByDescending { importanceRank(it.importance) }
            )
            result[category] = sorted
        }
        return result
    }

    /**
     * 需要用户关注的权限排前面：未授予 > 未知 > 不可用 > 已授予
     */
    private fun statusAttentionPriority(status: PermissionStatus): Int = when (status) {
        PermissionStatus.DENIED -> 0
        PermissionStatus.UNKNOWN -> 1
        PermissionStatus.UNAVAILABLE -> 2
        PermissionStatus.GRANTED -> 3
    }

    private fun importanceRank(importance: PermissionImportance): Int = when (importance) {
        PermissionImportance.REQUIRED -> 2
        PermissionImportance.RECOMMENDED -> 1
        PermissionImportance.OPTIONAL -> 0
    }

    /**
     * 获取权限统计信息
     * Get permission statistics
     */
    fun getPermissionStats(): PermissionStats {
        val allPermissions = getAllPermissions()
        val requiredPermissions = allPermissions.filter {
            it.importance == PermissionImportance.REQUIRED
        }
        val grantedRequired = requiredPermissions.count {
            it.status == PermissionStatus.GRANTED
        }

        return PermissionStats(
            totalRequired = requiredPermissions.size,
            grantedRequired = grantedRequired,
            totalPermissions = allPermissions.size,
            grantedPermissions = allPermissions.count {
                it.status == PermissionStatus.GRANTED
            }
        )
    }

    // 创建各个权限信息的私有方法
    // Private methods to create permission info

    private fun createBiometricPermission() = PermissionInfo(
        id = "BIOMETRIC",
        androidPermission = Manifest.permission.USE_BIOMETRIC,
        icon = Icons.Default.Fingerprint,
        nameResId = R.string.permission_biometric_name,
        descriptionResId = R.string.permission_biometric_description,
        category = PermissionCategory.SECURITY,
        importance = PermissionImportance.RECOMMENDED
    )

    private fun createCameraPermission() = PermissionInfo(
        id = "CAMERA",
        androidPermission = Manifest.permission.CAMERA,
        icon = Icons.Default.CameraAlt,
        nameResId = R.string.permission_camera_name,
        descriptionResId = R.string.permission_camera_description,
        category = PermissionCategory.DEVICE,
        importance = PermissionImportance.RECOMMENDED
    )

    private fun createStoragePermission() = PermissionInfo(
        id = "STORAGE",
        androidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
        icon = Icons.Default.Storage,
        nameResId = R.string.permission_storage_name,
        descriptionResId = R.string.permission_storage_description,
        category = PermissionCategory.STORAGE,
        importance = PermissionImportance.RECOMMENDED
    )

    private fun createNotificationPermission() = PermissionInfo(
        id = "NOTIFICATION",
        androidPermission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.POST_NOTIFICATIONS"
        } else {
            "android.permission.POST_NOTIFICATIONS"
        },
        icon = Icons.Default.Notifications,
        nameResId = R.string.permission_notification_name,
        descriptionResId = R.string.permission_notification_description,
        category = PermissionCategory.DEVICE,
        importance = PermissionImportance.RECOMMENDED
    )

    private fun createAutofillPermission() = PermissionInfo(
        id = "AUTOFILL",
        androidPermission = "android.permission.BIND_AUTOFILL_SERVICE",
        icon = Icons.Default.AutoAwesome,
        nameResId = R.string.permission_autofill_name,
        descriptionResId = R.string.permission_autofill_description,
        category = PermissionCategory.SECURITY,
        importance = PermissionImportance.RECOMMENDED
    )

    private fun createAccessibilityPermission() = PermissionInfo(
        id = "ACCESSIBILITY",
        androidPermission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
        icon = Icons.Default.Accessibility,
        nameResId = R.string.permission_accessibility_name,
        descriptionResId = R.string.permission_accessibility_description,
        category = PermissionCategory.SECURITY,
        importance = PermissionImportance.RECOMMENDED
    )

    companion object {
        private const val TAG = "PermissionRepository"
    }
}
