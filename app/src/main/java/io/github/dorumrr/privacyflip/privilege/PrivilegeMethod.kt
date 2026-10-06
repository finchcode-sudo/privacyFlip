package io.github.dorumrr.privacyflip.privilege

/**
 * Represents the method used to execute privileged commands.
 * Only Shizuku is supported.
 */
enum class PrivilegeMethod {
    /**
     * No privilege available - app cannot function
     */
    NONE,

    /**
     * Shizuku with ADB privileges (UID 2000)
     * Requires Shizuku app and wireless debugging or PC connection
     */
    SHIZUKU;

    /**
     * Returns true if any privilege is available
     */
    fun isAvailable(): Boolean = this != NONE

    fun getDisplayName(): String = when (this) {
        NONE -> "No Privilege"
        SHIZUKU -> "Shizuku (ADB)"
    }

    fun getDescription(): String = when (this) {
        NONE -> "Shizuku required"
        SHIZUKU -> "ADB privileges via Shizuku app"
    }
}
