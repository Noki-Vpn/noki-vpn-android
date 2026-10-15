package com.noki.vpn

object StartupPermissionPolicy {
    private const val ANDROID_6_API = 23
    private const val ANDROID_13_API = 33

    fun shouldRequestBackgroundPermissionOnFirstLaunch(
        sdkInt: Int,
        ignoringBatteryOptimizations: Boolean,
        alreadyRequested: Boolean,
    ): Boolean = sdkInt >= ANDROID_6_API && !ignoringBatteryOptimizations && !alreadyRequested

    fun shouldRequestNotificationPermissionOnFirstLaunch(
        sdkInt: Int,
        notificationGranted: Boolean,
        alreadyRequested: Boolean,
    ): Boolean =
        sdkInt >= ANDROID_13_API &&
            !notificationGranted &&
            !alreadyRequested
}
