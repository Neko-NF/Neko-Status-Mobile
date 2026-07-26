package com.nekonf.nekostatus.core.data

import com.nekonf.nekostatus.core.network.dto.WidgetStatusResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetResponseMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `maps multi-device status response`() {
        val response =
            json.decodeFromString<WidgetStatusResponse>(
                """
                {
                  "success": true,
                  "data": {
                    "users": [{
                      "userId": "user-1",
                      "username": "Neko",
                      "avatarUrl": "/api/avatars/1",
                      "isOnline": true,
                      "userStatus": "away",
                      "devices": [{
                        "deviceId": "device-1",
                        "deviceName": "Pixel 8",
                        "isOnline": true,
                        "userStatus": "away",
                        "lastSeen": "2026-07-26T12:00:00Z",
                        "status": {
                          "appName": "YouTube",
                          "packageName": "com.google.android.youtube",
                          "iconUrl": "/api/icons/youtube",
                          "batteryLevel": 85,
                          "isCharging": true,
                          "userStatus": "away",
                          "screenshotUrl": "/api/screenshots/device-1/full",
                          "screenshotThumbnailUrl": "/api/screenshots/device-1/thumbnail",
                          "screenshotUpdatedAt": "2026-07-26T11:59:30Z",
                          "music": {
                            "title": "Track",
                            "artist": "Artist",
                            "isPlaying": true
                          }
                        }
                      }]
                    }]
                  }
                }
                """.trimIndent(),
            )

        val feed = response.toWidgetFeed(fetchedAtEpochMs = 123L)
        val user = feed.users.single()
        val device = user.devices.single()

        assertEquals(123L, feed.fetchedAtEpochMs)
        assertEquals("away", user.userStatus)
        assertEquals("Pixel 8", device.deviceName)
        assertEquals("YouTube", device.appName)
        assertEquals("/api/icons/youtube", device.appIconUrl)
        assertEquals(85, device.batteryLevel)
        assertTrue(device.isCharging)
        assertEquals("Track", device.media?.title)
        assertEquals("/api/screenshots/device-1/full", device.screenshotUrl)
        assertEquals("/api/screenshots/device-1/thumbnail", device.screenshotThumbnailUrl)
        assertEquals("2026-07-26T11:59:30Z", device.screenshotUpdatedAt)
    }

    @Test
    fun `maps legacy current app device and top-level music response`() {
        val response =
            json.decodeFromString<WidgetStatusResponse>(
                """
                {
                  "success": true,
                  "users": [{
                    "userId": 2,
                    "username": "NF",
                    "isOnline": false,
                    "lastSeen": "2026-07-26T11:00:00Z",
                    "currentApp": {
                      "appName": "Chrome",
                      "packageName": "com.android.chrome",
                      "iconUrl": "/api/icons/chrome"
                    },
                    "device": {
                      "deviceModel": "Pixel 7 Pro",
                      "batteryLevel": 45,
                      "isCharging": false,
                      "screenshotUrl": "/api/screenshots/legacy/full",
                      "screenshotThumbnailUrl": "/api/screenshots/legacy/thumbnail",
                      "screenshotUpdatedAt": "2026-07-26T10:59:30Z",
                      "lastSeen": "2026-07-26T11:00:00Z"
                    },
                    "music": {
                      "title": "Legacy Track",
                      "source": "Chrome",
                      "isPlaying": true
                    }
                  }]
                }
                """.trimIndent(),
            )

        val user = response.toWidgetFeed(fetchedAtEpochMs = 456L).users.single()
        val device = user.devices.single()

        assertEquals("2", user.userId)
        assertFalse(user.isOnline)
        assertEquals("Pixel 7 Pro", device.deviceName)
        assertEquals("Chrome", device.appName)
        assertEquals("/api/icons/chrome", device.appIconUrl)
        assertEquals(45, device.batteryLevel)
        assertEquals("Legacy Track", device.media?.title)
        assertEquals("/api/screenshots/legacy/full", device.screenshotUrl)
        assertEquals("/api/screenshots/legacy/thumbnail", device.screenshotThumbnailUrl)
        assertEquals("2026-07-26T10:59:30Z", device.screenshotUpdatedAt)
    }
}
