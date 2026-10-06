package io.github.dorumrr.privacyflip.util

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

/**
 * Watches the default display state directly via DisplayManager.
 *
 * Some ROMs (e.g. ColorOS with AOD / double-tap-to-sleep) put the display into DOZE
 * without delivering ACTION_SCREEN_OFF to third-party receivers. This listener catches
 * those transitions and triggers the same lock work as ScreenStateReceiver.
 */
class DisplayStateMonitor(private val context: Context) {

    companion object {
        private const val TAG = "privacyFlip-DisplayStateMonitor"
        private const val WORK_NAME_LOCK = "privacy_action_lock"
    }

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var lastState: Int = currentState()
    private var registered = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val newState = currentState()
            val old = lastState
            lastState = newState
            if (old == newState) return

            log("Display state: ${name(old)} -> ${name(newState)}")

            if (old == Display.STATE_ON && newState != Display.STATE_ON && newState != Display.STATE_UNKNOWN) {
                onScreenTurnedOff()
            }
        }
    }

    fun start() {
        if (registered) return
        lastState = currentState()
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        registered = true
        log("Display state monitor started (state=${name(lastState)})")
    }

    fun stop() {
        if (!registered) return
        try {
            displayManager.unregisterDisplayListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister display listener", e)
        }
        registered = false
    }

    private fun onScreenTurnedOff() {
        try {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val deviceLocked = keyguard?.isKeyguardLocked ?: true

            val request = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to true,
                        "is_device_locked" to deviceLocked,
                        "trigger" to "display_state",
                        "reason" to "Display Off (DisplayManager)"
                    )
                )
                .build()

            // KEEP: if ScreenStateReceiver already queued/started the lock work, don't restart it
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_LOCK,
                ExistingWorkPolicy.KEEP,
                request
            )
            log("Lock work enqueued from display state (deviceLocked=$deviceLocked)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue lock work", e)
        }
    }

    private fun currentState(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN

    private fun name(state: Int) = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN($state)"
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        DebugLogHelper.getInstance(context).i(TAG, message)
    }
}
