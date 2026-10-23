package com.noki.vpn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredSettingsPrivacyMigrationTest {
    private val codec = StoredSettingsCodec(DefaultStoredSettingsFactory::create)

    @Test fun customBackgroundIsLocalOptionalAndRoundTripsWithoutChangingOtherPreferences() {
        val defaults = DefaultStoredSettingsFactory.create()
        assertNull(codec.decode(null).personalizationSettings.customBackground)
        val name = "a41b08f0-426c-4ec1-93ae-e1ae549f535d.video"
        val custom = defaults.copy(personalizationSettings = defaults.personalizationSettings.copy(customBackground = name))
        assertEquals(custom.personalizationSettings, codec.decode(codec.encode(custom)).personalizationSettings)
        assertNull(codec.decode("""{"customBackground":"../../private.video"}""").personalizationSettings.customBackground)
        assertNull(codec.decode(codec.encode(defaults)).personalizationSettings.customBackground)
    }

    @Test
    fun notificationStatusDefaultsOnAndExplicitChoiceSurvivesRoundTrip() {
        assertTrue(codec.decode(null).personalizationSettings.vpnStatusNotificationEnabled)
        assertTrue(codec.decode("""{"glassMode":"SIMPLE"}""").personalizationSettings.vpnStatusNotificationEnabled)
        val defaults = DefaultStoredSettingsFactory.create()
        val hidden = defaults.copy(personalizationSettings = defaults.personalizationSettings.copy(
            vpnStatusNotificationEnabled = false,
        ))

        assertFalse(codec.decode(codec.encode(hidden)).personalizationSettings.vpnStatusNotificationEnabled)
        assertTrue(codec.decode(codec.encode(defaults)).personalizationSettings.vpnStatusNotificationEnabled)
    }

    @Test
    fun firstInstallEnablesLoggingAndAutomaticUpload() {
        val settings = codec.decode(null).advancedSettings

        assertTrue(settings.connectionLogsEnabled)
        assertTrue(settings.errorLogsEnabled)
        assertTrue(settings.anonymousLogsEnabled)
    }

    @Test
    fun legacyAutomaticUploadIsResetUntilExplicitConsent() {
        val migrated = codec.decode("""{"anonymousLogsEnabled":true}""")

        assertFalse(migrated.advancedSettings.anonymousLogsEnabled)
    }

    @Test
    fun explicitConsentSurvivesCurrentCodecRoundTrip() {
        val settings = DefaultStoredSettingsFactory.create().copy(
            advancedSettings = DefaultStoredSettingsFactory.create().advancedSettings.copy(
                anonymousLogsEnabled = true,
            ),
        )

        val restored = codec.decode(codec.encode(settings))

        assertTrue(restored.advancedSettings.anonymousLogsEnabled)
    }
}
