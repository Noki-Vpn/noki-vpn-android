package com.noki.vpn.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRenderingContractTest {
    private fun source(name: String): String =
        File("src/main/java/com/noki/vpn/ui/$name.kt").readText()

    private fun function(source: String, name: String): String =
        source.substringAfter("fun $name(").substringBefore("\n@Composable", source)

    @Test
    fun interactiveGlassScaleUsesPrismalLayerBlock() {
        val expectedLayerBlockOwners = mapOf(
            "AuthButtons" to listOf("AuthSecondaryButton", "AuthPrimaryButton"),
            "AuthWelcomeScreen" to listOf("WelcomeGlassButton"),
            "AuthVerificationCodeField" to listOf("AuthVerificationCodeField"),
            "HomeBackground" to listOf("HomeSettingsGlassSurface"),
            "DevicesListComponents" to listOf("DeviceRow"),
            "SecurityGlassComponents" to listOf("SecurityPanelSurface"),
            "AdvancedSettingsComponents" to listOf("AdvancedSmallButton"),
            "SecurityActionComponents" to listOf("SecurityGlassActionButton"),
            "SecurityLoggingControls" to listOf("SecurityLogSendButton"),
        )

        expectedLayerBlockOwners.forEach { (file, functions) ->
            val source = source(file)
            functions.forEach { name ->
                val body = function(source, name)
                assertTrue("$name must pass scale through layerBlock", body.contains("layerBlock ="))
            }
        }
    }

    @Test
    fun activeBottomNavigationSamplesLocalSurfaceAndIconsWithoutScreenContent() {
        val navigation = source("HomeBottomNavigation")
        val activeButton = function(navigation, "ButtonLayer")

        assertTrue(navigation.contains("val navigationSurfaceBackdrop ="))
        assertTrue(navigation.contains("nestedGlassSource = navigationSurfaceBackdrop"))
        assertTrue(
            navigation.contains(
                "rememberPrismalMergedSource(navigationSurfaceBackdrop, iconsBackdrop)",
            ),
        )
        assertFalse(navigation.contains("rememberPrismalMergedSource(backdrop, iconsBackdrop)"))
        assertTrue(activeButton.contains("depthShadow = null"))
        assertTrue(activeButton.contains("depthInset = null"))
    }

    @Test
    fun nestedPanelsDoNotCaptureTheirOwnBackdrop() {
        listOf(
            "SecurityLoggingControls",
            "AdvancedSettingsRuleComponents",
            "AdvancedSettingsProtocolComponents",
        ).forEach { file ->
            val source = source(file)
            assertTrue("$file must export its panel backdrop", source.contains("nestedGlassSource = panelBackdrop"))
            assertFalse("$file must not capture its own backdrop", source.contains(".prismalGlassLayer(panelBackdrop)"))
        }
    }

    @Test
    fun zeroRadiusArgumentsAreNotUsedToDisableBackdropNodes() {
        val zeroEffectArgument = Regex(
            """(blurRadiusDp|lensRadiusDp|lensRefractionDp)\s*=\s*0f""",
        )
        val offenders = File("src/main/java/com/noki/vpn/ui")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .filter { zeroEffectArgument.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        assertTrue("Use blurAndLensEnabled=false instead: $offenders", offenders.isEmpty())
    }

    @Test
    fun notificationHistoryScrimDrawsBehindSystemBars() {
        val account = source("AccountScreen")
        val dialog = function(account, "AccountNotificationHistory")

        assertTrue(dialog.contains("decorFitsSystemWindows = false"))
    }

    @Test
    fun countryDisclosureDoesNotAnimateGlassBoundsOrCaptureItself() {
        val sheet = function(source("HomeServerSheet"), "HomeServerDropdownOverlay")
        assertFalse(sheet.contains("animateContentSize"))
        assertFalse(sheet.contains("prismalGlassLayer("))
    }

}
