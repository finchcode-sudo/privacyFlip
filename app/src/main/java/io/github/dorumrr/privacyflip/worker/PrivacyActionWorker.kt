package io.github.dorumrr.privacyflip.worker

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.ConnectionStateChecker
import io.github.dorumrr.privacyflip.util.DebugLogHelper
import io.github.dorumrr.privacyflip.util.DebugNotificationHelper
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.FeatureConfigurationManager
import io.github.dorumrr.privacyflip.util.ForegroundAppDetector
import kotlinx.coroutines.delay

class PrivacyActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "privacyFlip-PrivacyActionWorker"
    }

    private val debugNotifier: DebugNotificationHelper by lazy {
        DebugNotificationHelper.getInstance(applicationContext)
    }

    private val debugLogger: DebugLogHelper by lazy {
        DebugLogHelper.getInstance(applicationContext)
    }

    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager.getInstance(applicationContext)
    }

    private fun logDebug(message: String) {
        Log.i(TAG, message)
        debugLogger.i(TAG, message)
    }

    private fun logWarning(message: String) {
        Log.w(TAG, message)
        debugLogger.w(TAG, message)
    }

    private fun logError(message: String, e: Exception? = null) {
        Log.e(TAG, message, e)
        debugLogger.e(TAG, message, e)
    }

    private fun showToast(message: String) {
        // 仅在启用调试通知时显示 Toast
        if (!preferenceManager.debugNotificationsEnabled) {
            return
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查屏幕当前是否已锁定。
     * 用于在延迟后验证屏幕状态，防止执行过期的操作。
     *
     * @return 如果屏幕已锁定则返回 true，已解锁则返回 false
     */
    private fun isScreenCurrentlyLocked(): Boolean {
        return try {
            val keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            val isKeyguardLocked = keyguardManager.isKeyguardLocked
            val isScreenOn = powerManager.isInteractive

            // 如果键盘锁处于活动状态 或 屏幕已关闭，则视为已锁定
            isKeyguardLocked || !isScreenOn
        } catch (e: Exception) {
            Log.e(TAG, "检查屏幕锁定状态时出错", e)
            false // 如果无法确定状态，则默认视为未锁定
        }
    }
    
    override suspend fun doWork(): Result {
        try {
            val isLocking = inputData.getBoolean("is_locking", false)
            val isDeviceLocked = inputData.getBoolean("is_device_locked", false)
            val trigger = inputData.getString("trigger") ?: "unknown"
            val reason = inputData.getString("reason") ?: "Unknown"

            logDebug("🔒 正在执行隐私操作: locking=$isLocking, deviceLocked=$isDeviceLocked, trigger=$trigger, reason=$reason")

            val rootManager = RootManager.getInstance(Unit)
            rootManager.initialize(applicationContext)

            // 检查是否已授予特权（适用于 Root、Dhizuku、Shizuku 和 Sui）
            val hasPrivilege = rootManager.isRootGranted()

            if (!hasPrivilege) {
                logWarning("未授予特权权限 - 无法执行隐私操作")
                logWarning("用户必须先从 UI 授予权限才能执行隐私操作")
                debugNotifier.notifyNoPrivilege()
                return Result.failure()
            }

            val privacyManager = PrivacyManager.getInstance(applicationContext)
            val configManager = FeatureConfigurationManager(preferenceManager)
            val connectionChecker = ConnectionStateChecker(applicationContext, rootManager)
            val foregroundAppDetector = ForegroundAppDetector(applicationContext)

            val isGlobalPrivacyEnabled = preferenceManager.isGlobalPrivacyEnabled
            if (!isGlobalPrivacyEnabled) {
                logDebug("🚫 全局隐私已禁用 - 跳过所有隐私操作")
                debugNotifier.notifyGlobalPrivacyDisabled()
                return Result.success()
            }

            // 检查是否有任何豁免应用在前台
            val exemptApps = preferenceManager.getExemptApps()
            val foregroundExemptApp = if (exemptApps.isNotEmpty()) {
                foregroundAppDetector.getFirstForegroundApp(exemptApps)
            } else {
                null
            }

            if (foregroundExemptApp != null) {
                logDebug("🛡️ 豁免应用 '$foregroundExemptApp' 在前台 - 跳过所有隐私操作")
                debugNotifier.notifyFeatureSkipped("所有功能", "前台有豁免应用: $foregroundExemptApp")
                return Result.success()
            }

            if (isLocking) {
                val featuresToDisable = configManager.getFeaturesToDisableOnLock()

                if (featuresToDisable.isNotEmpty()) {
                    logDebug("锁屏时禁用功能: ${featuresToDisable.map { it.displayName }}")

                    // 根据“仅在未使用/未连接时”设置过滤功能
                    val skippedFeatures = mutableListOf<String>()
                    val filteredFeatures = featuresToDisable.filter { feature ->
                        val onlyIfUnused = preferenceManager.getFeatureOnlyIfUnused(feature)
                        if (!onlyIfUnused) {
                            true // 如果未启用“仅在未使用时”，则始终禁用
                        } else {
                            // 检查功能是否正在使用中
                            val inUse = connectionChecker.isFeatureInUse(feature)
                            if (inUse) {
                                logDebug("⏸️ ${feature.displayName} 正在使用中 - 跳过禁用 (onlyIfUnused=true)")
                                skippedFeatures.add(feature.displayName)
                                debugNotifier.notifyFeatureSkipped(feature.displayName, "正在使用中/已连接")
                            }
                            !inUse // 仅在未使用时包含
                        }
                    }

                    logDebug("过滤后要禁用的功能: ${filteredFeatures.map { it.displayName }}")

                    // 将功能分为三组：
                    // 1. 相机/麦克风 - 必须立即禁用（在设备锁定之前）
                    //    因为 Android 在锁定时会阻止更改传感器隐私
                    // 2. 保护模式（飞行模式、省电模式）- 必须启用（而非禁用）
                    // 3. 其他常规功能 - 在配置的延迟后禁用
                    val sensorFeatures = filteredFeatures.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val protectionModes = filteredFeatures.filter {
                        it in PrivacyFeature.getSystemModeFeatures()
                    }
                    val regularFeatures = filteredFeatures.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE &&
                        it !in PrivacyFeature.getSystemModeFeatures()
                    }

                    // 禁用相机/麦克风，并增加稳定延迟
                    // 75毫秒的延迟可防止键盘锁在命令执行期间介入的竞态条件
                    if (sensorFeatures.isNotEmpty()) {
                        if (!isDeviceLocked) {
                            // 为键盘锁完全介入增加稳定延迟
                            // 这防止了键盘锁在命令执行期间锁定的竞态条件
                            logDebug("⏱️ 等待 75 毫秒以便键盘锁稳定，然后再禁用传感器: ${sensorFeatures.map { it.displayName }}")
                            delay(75) // 小延迟让键盘锁完全介入
                            
                            // 关键：稳定后再次检查锁定状态
                            val isNowLocked = isScreenCurrentlyLocked()
                            
                            if (!isNowLocked) {
                                // 可以安全继续 - 键盘锁尚未介入
                                logDebug("✅ 键盘锁稳定，设备仍处于解锁状态 - 正在禁用传感器: ${sensorFeatures.map { it.displayName }}")
                                val sensorResults = privacyManager.disableFeatures(sensorFeatures.toSet())
                                processResults(sensorResults, sensorFeatures, "🔒", "已禁用", "已禁用", isLockAction = true)
                            } else {
                                // 键盘锁在稳定期间介入 - 预期行为
                                logDebug("🔒 键盘锁在稳定期间介入 - 跳过传感器（设计如此）")
                                debugNotifier.notifyFeatureSkipped(
                                    sensorFeatures.map { it.displayName }.joinToString(", "),
                                    "在传感器禁用前设备已锁定"
                                )
                            }
                        } else {
                            logWarning("⚠️ 设备在 ACTION_SCREEN_OFF 时已锁定 - 无法禁用传感器: ${sensorFeatures.map { it.displayName }}")
                            debugNotifier.notifyFeatureSkipped(
                                sensorFeatures.map { it.displayName }.joinToString(", "),
                                "设备已锁定"
                            )
                        }
                    }

                    // 延迟后处理常规功能和保护模式
                    if (regularFeatures.isNotEmpty() || protectionModes.isNotEmpty()) {
                        logDebug("📍 检查点：进入常规功能/保护模式代码块")
                        logDebug("📊 regularFeatures 数量: ${regularFeatures.size}, protectionModes 数量: ${protectionModes.size}")
                        logDebug("📊 regularFeatures: ${regularFeatures.map { it.displayName }}")

                        // 如果设备已锁定，则立即禁用（无延迟）
                        // 反正屏幕已关闭，用户看不到过渡过程
                        // 这防止了用户在延迟期间解锁的竞态条件
                        val lockDelay = if (isDeviceLocked) {
                            logDebug("⚡ 设备已锁定 - 立即禁用功能（无延迟）")
                            0
                        } else {
                            preferenceManager.lockDelaySeconds
                        }

                        logDebug("⏱️ 锁定延迟计算值: ${lockDelay}秒 (isDeviceLocked=$isDeviceLocked)")

                        if (lockDelay > 0) {
                            logDebug("⏳ 等待 ${lockDelay}秒 后再禁用其他功能")
                            delay(lockDelay * 1000L)

                            logDebug("⏱️ 延迟完成，现在验证屏幕状态...")

                            // 延迟后验证屏幕是否仍处于锁定状态
                            val isStillLocked = isScreenCurrentlyLocked()
                            logDebug("🔍 屏幕锁定验证: isStillLocked=$isStillLocked")

                            if (!isStillLocked) {
                                logWarning("⚠️ 延迟后屏幕不再处于锁定状态 - 正在取消禁用操作")
                                try {
                                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                                    val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                                    logWarning("🔍 KeyguardManager.isKeyguardLocked: ${km.isKeyguardLocked}")
                                    logWarning("🔍 PowerManager.isInteractive: ${pm.isInteractive}")
                                } catch (e: Exception) {
                                    logError("记录锁定状态详情时出错", e)
                                }
                                debugNotifier.notifyActionCancelled("延迟期间屏幕解锁 - 禁用已取消")
                                return Result.success()
                            }

                            logDebug("🔍 正在检查全局隐私设置...")

                            // 延迟后再次检查全局隐私设置
                            val isGlobalPrivacyStillEnabled = preferenceManager.isGlobalPrivacyEnabled
                            logDebug("🔍 全局隐私已启用: $isGlobalPrivacyStillEnabled")

                            if (!isGlobalPrivacyStillEnabled) {
                                logDebug("🚫 延迟期间全局隐私被禁用 - 正在取消禁用操作")
                                debugNotifier.notifyActionCancelled("延迟期间全局隐私被禁用")
                                return Result.success()
                            }
                        } else {
                            logDebug("⚡ 跳过延迟 (lockDelay=0)，直接进行禁用功能")
                        }

                        logDebug("📍 检查点：已通过所有验证，继续进行禁用功能")

                        // 禁用常规功能（WiFi、蓝牙、NFC 等）
                        if (regularFeatures.isNotEmpty()) {
                            logDebug("🔒 正在禁用常规功能 (数量=${regularFeatures.size}): ${regularFeatures.map { it.displayName }}")
                            logDebug("🔒 即将调用 privacyManager.disableFeatures()...")

                            val regularResults = privacyManager.disableFeatures(regularFeatures.toSet())

                            logDebug("🔒 privacyManager.disableFeatures() 返回了 ${regularResults.size} 个结果")

                            processResults(regularResults, regularFeatures, "🔒", "已禁用", "已禁用", isLockAction = true)
                        } else {
                            logDebug("ℹ️ 没有要禁用的常规功能（列表为空）")
                        }

                        // 启用保护模式（飞行模式、省电模式）- 注意：是启用，不是禁用！
                        // 同时记录我们是否启用了它们（针对“仅在未手动设置时”功能）
                        if (protectionModes.isNotEmpty()) {
                            logDebug("🛡️ 正在锁屏上启用保护模式: ${protectionModes.map { it.displayName }}")
                            
                            // 获取当前状态以检查是否已启用
                            val currentStatus = privacyManager.getCurrentStatus()
                            
                            for (mode in protectionModes) {
                                val wasAlreadyEnabled = currentStatus[mode] == FeatureState.ENABLED
                                
                                if (wasAlreadyEnabled) {
                                    // 已启用（由用户手动设置）- 不启用，标记为未由应用启用
                                    logDebug("🛡️ ${mode.displayName} 已启用（手动设置）- 跳过")
                                    preferenceManager.setFeatureEnabledByApp(mode, false)
                                    debugNotifier.notifyFeatureSkipped(mode.displayName, "已启用")
                                } else {
                                    // 未启用 - 启用它并标记为由应用启用
                                    val results = privacyManager.enableFeatures(setOf(mode))
                                    val success = results.firstOrNull()?.success == true
                                    if (success) {
                                        preferenceManager.setFeatureEnabledByApp(mode, true)
                                        logDebug("🛡️ ${mode.displayName} 由应用启用")
                                    }
                                    processResults(results, listOf(mode), "🛡️", "已启用", "已启用", isLockAction = true)
                                }
                            }
                        }
                    }
                }

            } else {
                val featuresToEnable = configManager.getFeaturesToEnableOnUnlock()

                if (featuresToEnable.isNotEmpty()) {
                    logDebug("解锁时启用功能: ${featuresToEnable.map { it.displayName }}")

                    // 拆分为传感器功能、保护模式和常规功能
                    val sensorFeatures = featuresToEnable.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val protectionModes = featuresToEnable.filter {
                        it in PrivacyFeature.getSystemModeFeatures()
                    }
                    val regularFeatures = featuresToEnable.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE &&
                        it !in PrivacyFeature.getSystemModeFeatures()
                    }

                    // 立即启用相机/麦克风（无延迟）
                    if (sensorFeatures.isNotEmpty()) {
                        logDebug("⚡ 立即启用传感器（无延迟）: ${sensorFeatures.map { it.displayName }}")
                        val sensorResults = privacyManager.enableFeatures(sensorFeatures.toSet())
                        processResults(sensorResults, sensorFeatures, "🔓", "已启用", "已重新启用", isLockAction = false)
                    }

                    // 延迟后处理常规功能和保护模式
                    if (regularFeatures.isNotEmpty() || protectionModes.isNotEmpty()) {
                        val unlockDelay = preferenceManager.unlockDelaySeconds
                        if (unlockDelay > 0) {
                            logDebug("⏳ 等待 ${unlockDelay}秒 后再启用其他功能")
                            delay(unlockDelay * 1000L)

                            // 延迟后验证屏幕是否仍处于解锁状态
                            if (isScreenCurrentlyLocked()) {
                                logWarning("⚠️ 延迟后屏幕再次锁定 - 正在取消启用操作")
                                debugNotifier.notifyActionCancelled("延迟期间屏幕锁定 - 启用已取消")
                                return Result.success()
                            }

                            // 延迟后再次检查全局隐私设置
                            if (!preferenceManager.isGlobalPrivacyEnabled) {
                                logDebug("🚫 延迟期间全局隐私被禁用 - 正在跳过启用操作")
                                debugNotifier.notifyActionCancelled("延迟期间全局隐私被禁用")
                                return Result.success()
                            }
                        }

                        // 启用常规功能（WiFi、蓝牙等）
                        if (regularFeatures.isNotEmpty()) {
                            // 根据“仅在尚未启用时”设置过滤功能
                            // 这防止了连接重置（例如 WiFi/VPN 断开连接）
                            val currentStatus = privacyManager.getCurrentStatus()
                            val filteredRegularFeatures = regularFeatures.filter { feature ->
                                val onlyIfNotEnabled = preferenceManager.getFeatureOnlyIfNotEnabled(feature)
                                if (!onlyIfNotEnabled) {
                                    true // 如果未设置“仅在尚未启用时”，则始终启用
                                } else {
                                    // 检查当前状态
                                    val currentState = currentStatus[feature]
                                    val isAlreadyEnabled = currentState == FeatureState.ENABLED

                                    if (isAlreadyEnabled) {
                                        logDebug("⏸️ ${feature.displayName} 已启用 - 跳过启用 (onlyIfNotEnabled=true)")
                                        debugNotifier.notifyFeatureSkipped(feature.displayName, "已启用")
                                    }
                                    !isAlreadyEnabled // 仅在尚未启用时包含
                                }
                            }

                            if (filteredRegularFeatures.isNotEmpty()) {
                                logDebug("🔓 正在启用常规功能: ${filteredRegularFeatures.map { it.displayName }}")
                                val regularResults = privacyManager.enableFeatures(filteredRegularFeatures.toSet())
                                processResults(regularResults, filteredRegularFeatures, "🔓", "已启用", "已重新启用", isLockAction = false)
                            }
                        }

                        // 禁用保护模式（飞行模式、省电模式）- 注意：是禁用，不是启用！
                        // 在禁用前检查“仅在未手动设置时”偏好设置
                        if (protectionModes.isNotEmpty()) {
                            logDebug("🛡️ 正在解锁时禁用保护模式: ${protectionModes.map { it.displayName }}")
                            
                            for (mode in protectionModes) {
                                val onlyIfNotManual = preferenceManager.getFeatureOnlyIfNotManual(mode)
                                val wasEnabledByApp = preferenceManager.getFeatureEnabledByApp(mode)
                                
                                if (onlyIfNotManual && !wasEnabledByApp) {
                                    // “仅在未手动设置时”已启用 且 我们未启用它
                                    // 跳过禁用 - 用户手动启用了它
                                    logDebug("🛡️ ${mode.displayName} 是手动设置的 - 跳过禁用 (onlyIfNotManual=true)")
                                    debugNotifier.notifyFeatureSkipped(mode.displayName, "手动设置")
                                } else {
                                    // 要么“仅在未手动设置时”已禁用，要么我们启用了它
                                    // 禁用它并清除标志
                                    val results = privacyManager.disableFeatures(setOf(mode))
                                    preferenceManager.setFeatureEnabledByApp(mode, false)
                                    processResults(results, listOf(mode), "🛡️", "已禁用", "已禁用", isLockAction = false)
                                }
                            }
                        }
                    }
                }
            }

            return Result.success()
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 工作已被取消（例如，延迟期间屏幕状态改变）
            // 这是预期行为，不是错误
            logDebug("⚠️ 隐私操作已取消（屏幕状态改变）")
            debugNotifier.notifyActionCancelled("操作期间屏幕状态改变")
            throw e // 重新抛出以正确取消协程
        } catch (e: Exception) {
            logError("隐私操作工作器失败", e)
            debugNotifier.notifyError("工作器失败: ${e.message}")
            return Result.failure()
        }
    }

    private fun processResults(
        results: List<PrivacyResult>,
        features: List<PrivacyFeature>,
        logIcon: String,
        actionPastTense: String,
        toastPrefix: String,
        isLockAction: Boolean
    ) {
        val successCount = results.count { it.success }
        val failedResults = results.filter { !it.success }

        results.forEach { result ->
            val status = if (result.success) "✅ 成功" else "❌ 失败"
            Log.i(TAG, "$logIcon ${result.feature.displayName}: $status")
        }

        Log.i(TAG, "锁定操作完成: $successCount/${features.size} 个功能 $actionPastTense")

        if (successCount > 0) {
            val successfulFeatures = results.filter { it.success }.map { result ->
                features.find { it.displayName == result.feature.displayName }?.displayName ?: result.feature.displayName
            }
            val toastMessage = "$toastPrefix: ${successfulFeatures.joinToString(", ")}"
            showToast(toastMessage)

            // 为成功的操作发送调试通知
            if (isLockAction) {
                debugNotifier.notifyLockAction(successfulFeatures)
            } else {
                debugNotifier.notifyUnlockAction(successfulFeatures)
            }
        }

        // 通知失败情况
        if (failedResults.isNotEmpty()) {
            val failedFeatureNames = failedResults.map { it.feature.displayName }
            debugNotifier.notifyError("${if (isLockAction) "禁用" else "启用"}失败: ${failedFeatureNames.joinToString(", ")}")
        }
    }
}
