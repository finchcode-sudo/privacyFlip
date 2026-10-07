package io.github.dorumrr.privacyflip.privacy

import android.os.Build
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class LocationToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.LOCATION
    override val featureName = "位置服务"

    override val enableCommands = getEnableCommandsForApi()
    override val disableCommands = getDisableCommandsForApi()
    override val statusCommands = getStatusCommandsForApi()
    
    private fun getEnableCommandsForApi(): List<CommandSet> {
        return when {
            Build.VERSION.SDK_INT >= 28 -> {
                listOf(
                    CommandSet("settings put secure location_mode 3", description = "High accuracy mode"),
                    CommandSet("settings put secure location_providers_allowed +gps,+network", 
                              description = "Enable GPS and network providers")
                )
            }
            Build.VERSION.SDK_INT >= 19 -> {
                listOf(
                    CommandSet("settings put secure location_providers_allowed gps,network,passive", 
                              description = "Enable all location providers"),
                    CommandSet("settings put secure location_mode 3", description = "High accuracy mode")
                )
            }
            else -> {
                listOf(
                    CommandSet("settings put secure location_providers_allowed +gps", 
                              description = "Enable GPS provider"),
                    CommandSet("settings put secure location_providers_allowed +network", 
                              description = "Enable network provider")
                )
            }
        }
    }
    
    private fun getDisableCommandsForApi(): List<CommandSet> {
        return when {
            Build.VERSION.SDK_INT >= 28 -> {
                listOf(
                    CommandSet("settings put secure location_mode 0", description = "Disable location"),
                    CommandSet("settings put secure location_providers_allowed ''", 
                              description = "Clear location providers")
                )
            }
            Build.VERSION.SDK_INT >= 19 -> {
                listOf(
                    CommandSet("settings put secure location_providers_allowed ''", 
                              description = "清除定位提供程序"),
                    CommandSet("settings put secure location_mode 0", description = "Disable location")
                )
            }
            else -> {
                listOf(
                    CommandSet("禁用 GPS 定位提供程序", 
                              description = "禁用 GPS 提供程序"),
                    CommandSet("禁用网络定位", 
                              description = "Disable network provider")
                )
            }
        }
    }
    
    private fun getStatusCommandsForApi(): List<CommandSet> {
        return listOf(
            CommandSet("settings get secure location_mode", description = "Check location mode"),
            CommandSet("获取允许的定位提供程序", description = "检查提供程序"),
            CommandSet("dumpsys location | grep 'Location'", description = "Dumpsys 方法")
        )
    }

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseLocationOutput(output)
    }
}
