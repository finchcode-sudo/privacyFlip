package io.github.dorumrr.privacyflip.util

/**
 * Translates the English strings used in notifications / toasts into Chinese.
 * Internal logic keeps using the English feature display names; only user-visible text is converted.
 */
object NotificationText {

    // Longer phrases first so they are not partially replaced by shorter ones
    private val replacements = listOf(
        "device locked before sensors could be disabled" to "传感器关闭前设备已锁定",
        "Screen unlocked during delay - disable cancelled" to "延迟期间已解锁，已取消关闭",
        "Screen locked during delay - enable cancelled" to "延迟期间已锁屏，已取消开启",
        "Global privacy disabled during delay" to "延迟期间全局隐私已关闭",
        "Screen state changed during action" to "操作期间屏幕状态已变化",
        "exempt app in foreground: " to "豁免应用在前台：",
        "device already locked" to "设备已锁定",
        "in use/connected" to "正在使用/已连接",
        "already enabled" to "已开启",
        "manually set" to "已手动设置",
        "Worker failed: " to "任务失败：",
        "Failed to disable: " to "关闭失败：",
        "Failed to enable: " to "开启失败：",
        "Disabled: " to "已关闭：",
        "Enabled: " to "已开启：",
        "Location Services" to "定位服务",
        "Mobile Data" to "移动数据",
        "Airplane Mode" to "飞行模式",
        "Battery Saver" to "省电模式",
        "All features" to "所有功能",
        "Microphone" to "麦克风",
        "Bluetooth" to "蓝牙",
        "Camera" to "摄像头"
    )

    fun localize(text: String): String {
        var result = text
        for ((en, zh) in replacements) {
            result = result.replace(en, zh)
        }
        return result
    }

    /** Localize and use the Chinese list separator for feature lists. */
    fun localizeList(text: String): String = localize(text).replace(", ", "、")
}
