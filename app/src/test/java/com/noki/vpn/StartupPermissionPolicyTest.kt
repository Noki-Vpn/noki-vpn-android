package com.noki.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPermissionPolicyTest {
    @Test
    fun backgroundPermissionIsRequestedOnceOnSupportedDevicesWithoutExemption() {
        assertFalse(StartupPermissionPolicy.shouldRequestBackgroundPermissionOnFirstLaunch(22, false, false))
        assertTrue(StartupPermissionPolicy.shouldRequestBackgroundPermissionOnFirstLaunch(23, false, false))
        assertTrue(StartupPermissionPolicy.shouldRequestBackgroundPermissionOnFirstLaunch(36, false, false))
        assertFalse(StartupPermissionPolicy.shouldRequestBackgroundPermissionOnFirstLaunch(36, true, false))
        assertFalse(StartupPermissionPolicy.shouldRequestBackgroundPermissionOnFirstLaunch(36, false, true))
    }

    @Test
    fun notificationPermissionRemainsIndependentAndIsRequestedOnceFromAndroid13() {
        assertFalse(StartupPermissionPolicy.shouldRequestNotificationPermissionOnFirstLaunch(32, false, false))
        assertTrue(StartupPermissionPolicy.shouldRequestNotificationPermissionOnFirstLaunch(33, false, false))
        assertFalse(StartupPermissionPolicy.shouldRequestNotificationPermissionOnFirstLaunch(36, true, false))
        assertFalse(StartupPermissionPolicy.shouldRequestNotificationPermissionOnFirstLaunch(36, false, true))
    }
}
