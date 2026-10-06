package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivilegeManager private constructor(private val context: Context) {

    companion object : SingletonHolder<PrivilegeManager, Context>({ context ->
        PrivilegeManager(context.applicationContext)
    }) {
        private const val TAG = "privacyFlip-PrivilegeManager"
    }

    private val logManager = LogManager.getInstance(context)
    private var currentExecutor: PrivilegeExecutor? = null
    private var currentMethod: PrivilegeMethod = PrivilegeMethod.NONE

    suspend fun initialize(): PrivilegeMethod = withContext(Dispatchers.IO) {
        currentMethod = detectBestPrivilegeMethod()
        return@withContext currentMethod
    }
    
    private suspend fun detectBestPrivilegeMethod(): PrivilegeMethod {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val shizukuExecutor = createShizukuExecutor()
                shizukuExecutor.initialize(context)

                if (shizukuExecutor.isAvailable()) {
                    logManager.d(TAG, "Shizuku detected and available")
                    currentExecutor = shizukuExecutor
                    return PrivilegeMethod.SHIZUKU
                } else {
                    logManager.d(TAG, "Shizuku not available (binder not responding)")
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Shizuku detection failed: ${e.message}")
            }
        }

        logManager.w(TAG, "No privilege method available")
        return PrivilegeMethod.NONE
    }

    private fun createShizukuExecutor(): PrivilegeExecutor {
        return ShizukuExecutor()
    }

    fun getCurrentMethod(): PrivilegeMethod = currentMethod

    suspend fun isPrivilegeAvailable(): Boolean {
        return currentExecutor?.isAvailable() ?: false
    }

    suspend fun isPermissionGranted(): Boolean {
        val granted = currentExecutor?.isPermissionGranted() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.isPermissionGranted() - currentMethod: $currentMethod, result: $granted")
        return granted
    }

    suspend fun requestPermission(): Boolean {
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - currentMethod: $currentMethod, calling executor...")
        val granted = currentExecutor?.requestPermission() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - executor returned: $granted")
        return granted
    }

    suspend fun executeCommand(command: String): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeCommand(command)
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeWithFallbacks(commands)
    }

    suspend fun getUid(): Int {
        return currentExecutor?.getUid() ?: -1
    }

    suspend fun redetectPrivilegeMethod(): PrivilegeMethod {
        currentExecutor?.cleanup()
        currentExecutor = null
        return initialize()
    }

    fun cleanup() {
        currentExecutor?.cleanup()
        currentExecutor = null
        currentMethod = PrivilegeMethod.NONE
    }
}

