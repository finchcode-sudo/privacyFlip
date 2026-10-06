package io.github.dorumrr.privacyflip.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.util.DebugLogHelper
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

/**
 * Accessibility Service that detects screen-off / lock events earlier than the standard
 * ACTION_SCREEN_OFF broadcast. This allows sensor privacy features (camera/microphone)
 * to be disabled even when using the side/power button for instant-lock.
 * 
 * Android's security restriction prevents changing sensor privacy while the device is locked.
 * By detecting the lock earlier (before keyguard fully engages), we can disable sensors
 * in time even with instant-lock methods.
 * 
 * This service is OPTIONAL and requires explicit user permission via Android Settings > Accessibility.
 * Users must opt-in to this experimental feature.
 */
class PrivacyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "privacyFlip-AccessibilityService"
        
        @Volatile
        private var isServiceRunning = false
        
        /**
         * Check if the accessibility service is currently running.
         * Used by UI to show service status.
         */
        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "✅ Accessibility Service connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Only process window state changes (lock screen appearance)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        
        try {
            // Check if user has enabled this feature in app preferences
            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            if (!preferenceManager.accessibilityServiceEnabled) {
                Log.d(TAG, "Accessibility feature disabled in app preferences - ignoring event")
                return
            }
            
            val className = event.className?.toString() ?: ""
            val packageName = event.packageName?.toString() ?: ""

            // Diagnostic: record lock-related windows in the debug log file
            if (className.contains("lock", ignoreCase = true) ||
                className.contains("keyguard", ignoreCase = true) ||
                packageName == "com.android.systemui") {
                DebugLogHelper.getInstance(applicationContext).i(
                    TAG, "Window event: pkg=$packageName class=$className matched=${isLockScreenWindow(packageName, className)}"
                )
            }

            // Detect if this is a lock screen window
            if (isLockScreenWindow(packageName, className)) {
                Log.d(TAG, "🔒 Lock screen detected via Accessibility (pkg: $packageName, class: $className)")
                triggerEarlyPrivacyActions()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        }
    }

    /**
     * Detects if the window class name indicates a lock screen.
     * Uses conservative matching to avoid false positives.
     * 
     * Common lock screen class names across Android versions:
     * - com.android.systemui.statusbar.phone.StatusBar
     * - com.android.internal.policy.impl.keyguard.KeyguardViewMediator
     * - com.android.systemui.keyguard.KeyguardViewMediator
     * - Various manufacturer-specific lock screen classes
     */
    private fun isLockScreenWindow(packageName: String, className: String): Boolean {
        // Only real system UI / keyguard windows count. Without the package check, pages such as
        // "Lock screen settings" (class name contains "LockScreen") were mistaken for the lock screen.
        val isSystemUi = packageName == "com.android.systemui" ||
            packageName.contains("keyguard", ignoreCase = true)
        if (!isSystemUi) return false
        return className.contains("Keyguard", ignoreCase = true) ||
               className.contains("LockScreen", ignoreCase = true)
    }

    /**
     * Triggers privacy actions early (before keyguard fully engages).
     * Uses WorkManager with REPLACE policy to prevent duplicate execution
     * if the normal ACTION_SCREEN_OFF flow also triggers.
     */
    private fun triggerEarlyPrivacyActions() {
        try {
            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to true,
                        "is_device_locked" to false,  // Device not fully locked yet
                        "trigger" to "accessibility_service",
                        "reason" to "Early Lock Detection (Accessibility)"
                    )
                )
                .build()

            // Use same work name as ScreenStateReceiver
            // REPLACE policy ensures no duplicate execution
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "privacy_action_lock",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.i(TAG, "✅ Early privacy actions triggered (unique work: privacy_action_lock)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger early privacy actions", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "❌ Accessibility Service destroyed")
    }
}
