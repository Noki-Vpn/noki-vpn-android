package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendIncyDevice
import com.noki.vpn.data.BackendIncyDeviceCreate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncyDeviceWorkflowTest {
    @Test
    fun `forbidden device name operations ask for another name in the selected language`() {
        val error = BackendException("Forbidden", 403)

        assertEquals("Попробуйте другое имя", AppErrorMapper.readableDeviceNameError(AppLanguage.RU, error))
        assertEquals("Try another name", AppErrorMapper.readableDeviceNameError(AppLanguage.EN, error))
        assertEquals("Forbidden", AppErrorMapper.readableNetworkError(AppLanguage.RU, error))
    }

    @Test
    fun `device name mapping keeps other errors unchanged`() {
        for (error in listOf(BackendException("Unauthorized", 401), BackendException("Invalid name", 422),
            BackendException("Unavailable", 503), java.io.IOException())) {
            assertEquals(AppErrorMapper.readableNetworkError(AppLanguage.RU, error),
                AppErrorMapper.readableDeviceNameError(AppLanguage.RU, error))
        }
    }

    @Test
    fun `duplicate device name is explained instead of generic validation`() {
        val error = BackendException("Устройство INCY с таким названием уже существует", 422)

        assertEquals(
            "Устройство INCY с таким названием уже существует",
            AppErrorMapper.readableNetworkError(AppLanguage.RU, error),
        )
        assertEquals(
            "An INCY device with this name already exists",
            AppErrorMapper.readableNetworkError(AppLanguage.EN, error),
        )
    }

    @Test
    fun `unknown validation detail is not exposed as a duplicate name`() {
        val error = BackendException("Internal validation details", 422)

        assertEquals(
            "Проверьте введенные данные",
            AppErrorMapper.readableNetworkError(AppLanguage.RU, error),
        )
        assertEquals(
            "Check the entered data",
            AppErrorMapper.readableNetworkError(AppLanguage.EN, error),
        )
    }

    @Test
    fun `create trims name and returns one validated import value`() = runBlocking {
        val gateway = FakeGateway()
        val result = IncyDeviceWorkflow(gateway).create("  Планшет  ")

        assertEquals("Планшет", gateway.lastName)
        assertTrue(result.importLink.value.startsWith("incy://crypt1/"))
        assertEquals(
            "https://api.noki.ykino.tech/v1/v2rayn/subscriptions/test-token",
            result.v2raynSubscriptionUrl?.value,
        )
    }

    private class FakeGateway : IncyDeviceGateway {
        var lastName = ""

        override suspend fun create(name: String): BackendIncyDeviceCreate {
            lastName = name
            return BackendIncyDeviceCreate(
                device = BackendIncyDevice(id = "d1", name = name),
                importLink = "incy://crypt1/AAECAwQFBgcICQoLNyIQL3rDwRZqnyoD8pGKSLXP6o8NdSXQVSSALNbbUyIr__tWGFUexdIfKvvmDnuDGbmBvuppfNef6aKNZUwOm4c-Sg",
                v2raynSubscriptionUrl = "https://api.noki.ykino.tech/v1/v2rayn/subscriptions/test-token",
            )
        }
    }
}
