package com.noki.vpn

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.AppVpnService
import com.noki.vpn.vpn.AppVpnServiceCommandTest.InMemoryAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsMutationEffectTest {
    @Before
    fun provideAndroidKeyStore() {
        Security.addProvider(object : Provider("NokiSettingsEffectTest", 1.0, "Test-only Android keystore") {
            init { put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name) }
        })
    }

    @After
    fun removeAndroidKeyStore() { Security.removeProvider("NokiSettingsEffectTest") }

    @Test
    fun `persisted mutation returns requested runtime effect`() {
        val coordinator = SettingsMutationCoordinator(EffectSettingsStore())

        val result = coordinator.persistUiFields(AppUiState(), SettingsEffect.ApplyRuntimeSettings)

        assertEquals(SettingsEffect.ApplyRuntimeSettings, result.effect)
    }

    @Test
    fun changedAppFilterModePersistsBeforeRestartingAnActiveVpnOnlyOnce() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = AppUiRuntime(app, SavedStateHandle(), CoroutineScope(Job().apply { cancel() }))
        for (connection in listOf(VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING)) {
            for (before in AppFilterMode.entries) for (after in AppFilterMode.entries.filter { it != before }) {
                runtime.uiState = AppUiState(connectionState = connection, filterMode = before,
                    selectedPackages = setOf("example.app"))
                runtime.updateFilterMode(after)
                assertEquals(after, runtime.repository.load().filterMode)
                assertEquals(setOf("example.app"), runtime.repository.load().selectedPackages)
                assertEquals(AppVpnService.restartIntent(app).action, shadowOf(app).nextStartedService?.action)
                runtime.updateFilterMode(after)
                assertNull(shadowOf(app).nextStartedService)
            }
        }
    }

    @Test
    fun changedAppFilterModeWhileOfflineDoesNotConnectOrReplaceAnEmptySelection() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = AppUiRuntime(app, SavedStateHandle(), CoroutineScope(Job().apply { cancel() }))
        for (connection in listOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.FAILED)) {
            runtime.uiState = AppUiState(connectionState = connection, filterMode = AppFilterMode.ALL_APPS)
            runtime.updateFilterMode(AppFilterMode.ONLY_SELECTED)
            assertEquals(AppFilterMode.ONLY_SELECTED, runtime.repository.load().filterMode)
            assertEquals(emptySet<String>(), runtime.repository.load().selectedPackages)
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}

private class EffectSettingsStore : AtomicStoredSettingsStore {
    private var value = DefaultStoredSettingsFactory.create()
    override fun load(): StoredSettings = value
    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        val (updated, result) = transform(value)
        value = updated
        return result
    }
}
