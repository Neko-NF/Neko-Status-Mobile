package com.nekonf.nekostatus

import com.nekonf.nekostatus.core.model.WidgetDeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPagingTest {
    @Test
    fun `selected devices form the first two-row page`() {
        val pages = statusDevicePages(devices(), listOf("three", "one"))

        assertEquals(listOf("three", "one"), pages[0].map { it.deviceId })
        assertEquals(listOf("two"), pages[1].map { it.deviceId })
    }

    @Test
    fun `one selected device produces one-row pages`() {
        val pages = statusDevicePages(devices(), listOf("two"))

        assertEquals(listOf(listOf("two"), listOf("one"), listOf("three")), pages.map { page -> page.map { it.deviceId } })
    }

    @Test
    fun `legacy empty selection keeps first two devices on the first page`() {
        val pages = statusDevicePages(devices(), emptyList())

        assertEquals(listOf("one", "two"), pages[0].map { it.deviceId })
        assertEquals(listOf("three"), pages[1].map { it.deviceId })
    }

    @Test
    fun `screenshot sequence filters devices and puts configured target first`() {
        val devices =
            listOf(
                device("one"),
                device("two", screenshot = "two.png"),
                device("three", screenshot = "three.png"),
            )

        assertEquals(
            listOf("three", "two"),
            screenshotDeviceSequence(devices, "three").map { it.deviceId },
        )
    }

    private fun devices() = listOf(device("one"), device("two"), device("three"))

    private fun device(
        id: String,
        screenshot: String? = null,
    ) = WidgetDeviceStatus(
        deviceId = id,
        deviceName = id,
        screenshotThumbnailUrl = screenshot,
    )
}
