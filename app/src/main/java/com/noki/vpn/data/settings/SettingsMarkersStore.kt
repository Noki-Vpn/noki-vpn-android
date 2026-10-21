package com.noki.vpn.data

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.temporal.ChronoUnit

internal class SettingsMarkersStore(
    private val preferences: SharedPreferences,
    private val appVersionCode: () -> Long,
    private val now: () -> Instant = Instant::now,
) {
    fun loadInstallOnboardingStep(): InstallOnboardingStep = runCatching {
        when (val stored = preferences.getString(KEY_INSTALL_ONBOARDING_STEP, null)) {
            "COMPLETE" -> InstallOnboardingStep.APPEARANCE
            COMPLETE_HOME_APPEARANCE -> InstallOnboardingStep.COMPLETE
            else -> InstallOnboardingStep.valueOf(stored.orEmpty())
        }
    }.getOrDefault(InstallOnboardingStep.CONSENT)

    fun saveInstallOnboardingStep(step: InstallOnboardingStep) {
        val stored = if (step == InstallOnboardingStep.COMPLETE) COMPLETE_HOME_APPEARANCE else step.name
        check(preferences.edit().putString(KEY_INSTALL_ONBOARDING_STEP, stored).commit()) {
            "install_onboarding_persist_failed"
        }
    }

    fun clearVpnRuntimeState() {
        preferences.edit {
            remove(KEY_VPN_RUNTIME_STATE)
            remove(KEY_VPN_RUNTIME_CONNECTED_AT)
        }
    }

    fun shouldUploadAppLogsAutomatically(): Boolean {
        val last = preferences.getString(KEY_LAST_AUTO_APP_LOG_UPLOAD_AT, null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return true
        return last.isBefore(now().minus(AUTO_LOG_UPLOAD_INTERVAL_HOURS, ChronoUnit.HOURS))
    }

    fun markAppLogsAutomaticallyUploaded() {
        preferences.edit { putString(KEY_LAST_AUTO_APP_LOG_UPLOAD_AT, now().toString()) }
    }

    fun loadSeenAppNotificationIds(): Set<String> =
        preferences.getStringSet(KEY_SEEN_APP_NOTIFICATION_IDS, emptySet())
            ?.filter(String::isNotBlank)?.toSet().orEmpty()

    fun markAppNotificationsSeen(ids: Collection<String>) {
        val clean = ids.map(String::trim).filter(String::isNotBlank)
        if (clean.isEmpty()) return
        synchronized(SEEN_APP_NOTIFICATION_IDS_LOCK) {
            val retained = (loadSeenAppNotificationIds() + clean).sorted().takeLast(SEEN_IDS_MAX).toSet()
            preferences.edit { putStringSet(KEY_SEEN_APP_NOTIFICATION_IDS, retained) }
        }
    }

    fun claimFreeTrialNotification(userId: String): Boolean = claimFreeTrialNotice(userId, KEY_FREE_TRIAL_NOTIFICATION_USERS)

    fun claimFreeTrialDialog(userId: String): Boolean = claimFreeTrialNotice(userId, KEY_FREE_TRIAL_DIALOG_USERS)

    private fun claimFreeTrialNotice(userId: String, key: String): Boolean {
        if (userId.isBlank()) return false
        return synchronized(SEEN_APP_NOTIFICATION_IDS_LOCK) {
            val users = preferences.getStringSet(key, emptySet()).orEmpty()
            if (userId in users) false else {
                preferences.edit { putStringSet(key, users + userId) }
                true
            }
        }
    }

    fun loadLastRegisteredFcmTokenHash(): String =
        preferences.getString(KEY_LAST_REGISTERED_FCM_TOKEN_HASH, "").orEmpty()

    fun loadLastRegisteredFcmDeviceId(): String =
        preferences.getString(KEY_LAST_REGISTERED_FCM_DEVICE_ID, "").orEmpty()

    fun clearLastRegisteredFcmTokenHash() {
        preferences.edit {
            remove(KEY_LAST_REGISTERED_FCM_TOKEN_HASH)
            remove(KEY_LAST_REGISTERED_FCM_DEVICE_ID)
        }
    }

    fun saveLastRegisteredFcmTokenHash(tokenHash: String, deviceId: String) {
        preferences.edit {
            putString(KEY_LAST_REGISTERED_FCM_TOKEN_HASH, tokenHash.take(128))
            putString(KEY_LAST_REGISTERED_FCM_DEVICE_ID, deviceId.take(128))
        }
    }

    fun isAndroidUpdateAvailable(): Boolean {
        if (!preferences.getBoolean(KEY_ANDROID_UPDATE_AVAILABLE, false)) return false
        val markerVersionCode = preferences.getLong(KEY_ANDROID_UPDATE_MARKER_VERSION_CODE, -1L)
        if (markerVersionCode < 0L || appVersionCode() > markerVersionCode) {
            clearAndroidUpdateAvailable()
            return false
        }
        return true
    }

    fun markAndroidUpdateAvailable() {
        preferences.edit {
            putBoolean(KEY_ANDROID_UPDATE_AVAILABLE, true)
            putLong(KEY_ANDROID_UPDATE_MARKER_VERSION_CODE, appVersionCode())
        }
    }

    fun clearAndroidUpdateAvailable() {
        preferences.edit {
            remove(KEY_ANDROID_UPDATE_AVAILABLE)
            remove(KEY_ANDROID_UPDATE_MARKER_VERSION_CODE)
        }
    }

    private companion object {
        const val KEY_INSTALL_ONBOARDING_STEP = "install_onboarding_step"
        const val COMPLETE_HOME_APPEARANCE = "COMPLETE_HOME_APPEARANCE"
        val SEEN_APP_NOTIFICATION_IDS_LOCK = Any()
        const val KEY_VPN_RUNTIME_STATE = "vpn_runtime_state"
        const val KEY_VPN_RUNTIME_CONNECTED_AT = "vpn_runtime_connected_at"
        const val KEY_LAST_AUTO_APP_LOG_UPLOAD_AT = "last_auto_app_log_upload_at"
        const val KEY_SEEN_APP_NOTIFICATION_IDS = "seen_app_notification_ids"
        const val KEY_FREE_TRIAL_NOTIFICATION_USERS = "free_trial_notification_users"
        const val KEY_FREE_TRIAL_DIALOG_USERS = "free_trial_dialog_users"
        const val KEY_LAST_REGISTERED_FCM_TOKEN_HASH = "last_registered_fcm_token_hash"
        const val KEY_LAST_REGISTERED_FCM_DEVICE_ID = "last_registered_fcm_device_id"
        const val KEY_ANDROID_UPDATE_AVAILABLE = "android_update_available"
        const val KEY_ANDROID_UPDATE_MARKER_VERSION_CODE = "android_update_marker_version_code"
        const val AUTO_LOG_UPLOAD_INTERVAL_HOURS = 24L
        const val SEEN_IDS_MAX = 200
    }
}
