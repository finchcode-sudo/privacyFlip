package io.github.dorumrr.privacyflip.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Helper class for sending debug notifications about privacy actions.
 * Used to help users understand what Privacy Flip is doing in the background.
 * 
 * Debug notifications are only sent when the user enables the debug toggle.
 */
class DebugNotificationHelper private constructor(private val context: Context) {

    companion object : SingletonHolder<DebugNotificationHelper, Context>({ context ->
        DebugNotificationHelper(context.applicationContext)
    }) {
        private const val TAG = "privacyFlip-DebugNotificationHelper"
        private const val AUTO_DISMISS_MS = 5000L
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager.getInstance(context)
    }

    // Incrementing notification ID to allow multiple notifications
    private val notificationIdCounter = AtomicInteger(Constants.DebugNotification.NOTIFICATION_ID_BASE)

    init {
        createNotificationChannel()
    }

    /**
     * Creates the debug notification channel.
     * Called on initialization and can be called again safely.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.DebugNotification.CHANNEL_ID,
                "隐私操作调试通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "显示隐私操作的调试通知（可关闭）"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Debug notification channel created")
        }
    }

    /**
     * Send a debug notification if debug notifications are enabled.
     * 
     * @param title The notification title
     * @param message The notification message
     * @param autoDismiss Whether to auto-dismiss after a timeout (default: true)
     */
    fun notify(title: String, message: String, autoDismiss: Boolean = true) {
        val isEnabled = preferenceManager.debugNotificationsEnabled
        Log.d(TAG, "notify() called - debugNotificationsEnabled=$isEnabled, title=$title")
        
        if (!isEnabled) {
            Log.d(TAG, "Debug notifications disabled - skipping")
            return
        }

        try {
            val notificationId = notificationIdCounter.getAndIncrement()
            
            // Reset counter if it gets too high
            if (notificationIdCounter.get() > Constants.DebugNotification.NOTIFICATION_ID_BASE + 100) {
                notificationIdCounter.set(Constants.DebugNotification.NOTIFICATION_ID_BASE)
            }

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, Constants.DebugNotification.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_privacy_shield)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .apply {
                    if (autoDismiss) {
                        setTimeoutAfter(AUTO_DISMISS_MS)
                    }
                }
                .build()

            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "Debug notification sent: $title - $message")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send debug notification", e)
        }
    }

    /**
     * Convenience methods for common notification types
     */
    
    fun notifyLockAction(features: List<String>) {
        if (features.isEmpty()) return
        notify(
            "🔒 已锁屏",
            "正在关闭：${NotificationText.localizeList(features.joinToString(", "))}"
        )
    }

    fun notifyUnlockAction(features: List<String>) {
        if (features.isEmpty()) return
        notify(
            "🔓 已解锁",
            "正在开启：${NotificationText.localizeList(features.joinToString(", "))}"
        )
    }

    fun notifyFeatureSkipped(feature: String, reason: String) {
        notify(
            "⏸️ 已跳过",
            NotificationText.localizeList("$feature - $reason")
        )
    }

    fun notifyActionCancelled(reason: String) {
        notify(
            "⚠️ 操作已取消",
            NotificationText.localize(reason)
        )
    }

    fun notifyError(message: String) {
        notify(
            "❌ 操作失败",
            NotificationText.localizeList(message),
            autoDismiss = false
        )
    }

    fun notifyGlobalPrivacyDisabled() {
        notify(
            "🚫 隐私保护已关闭",
            "已跳过操作：全局隐私开关已关闭"
        )
    }

    fun notifyNoPrivilege() {
        notify(
            "❌ 没有权限",
            "无法执行操作：未授予 Shizuku 权限",
            autoDismiss = false
        )
    }
}
