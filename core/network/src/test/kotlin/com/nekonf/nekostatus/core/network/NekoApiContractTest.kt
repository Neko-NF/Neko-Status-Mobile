package com.nekonf.nekostatus.core.network

import com.nekonf.nekostatus.core.model.MediaSnapshot
import com.nekonf.nekostatus.core.network.dto.AuthRequest
import com.nekonf.nekostatus.core.network.dto.DeviceKeyRequest
import com.nekonf.nekostatus.core.network.dto.HandshakeRequest
import com.nekonf.nekostatus.core.network.dto.ProfileUpdateRequest
import com.nekonf.nekostatus.core.network.dto.StatusPayload
import com.nekonf.nekostatus.core.network.dto.WidgetTokenRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NekoApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NekoApi
    private lateinit var widgetApi: WidgetApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val factory =
            NekoApiFactory(
                OkHttpClient(),
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        api = factory.create(server.url("/").toString())
        widgetApi = factory.createWidget(server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login uses desktop compatible path and json`() =
        runTest {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"success":true,"token":"jwt","user":{"id":1,"username":"neko"}}""",
                ),
            )

            val response = api.login(AuthRequest("neko", "secret"))
            val request = server.takeRequest()

            assertTrue(response.isSuccessful)
            assertEquals("/api/auth/login", request.path)
            assertTrue(request.body.readUtf8().contains("\"username\":\"neko\""))
        }

    @Test
    fun `authentication and device endpoints preserve paths and bearer credentials`() =
        runTest {
            repeat(6) {
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"success":true,"valid":true,"deviceKey":"device","user":{"username":"neko"}}"""),
                )
            }

            api.register(AuthRequest("new-neko", "secret"))
            api.me("Bearer jwt")
            api.updateProfile("Bearer jwt", ProfileUpdateRequest(username = "renamed"))
            api.generateDeviceKey("Bearer jwt", DeviceKeyRequest("Pixel", deviceFingerprint = "install-id"))
            api.handshake(HandshakeRequest("pair-token", "Pixel"))
            api.validateDevice("Bearer device", "install-id")

            assertEquals("/api/auth/register", server.takeRequest().path)
            assertBearerRequest("/api/auth/me", "jwt")
            assertBearerRequest("/api/auth/profile", "jwt")
            assertBearerRequest("/api/auth/device-key", "jwt")
            assertEquals("/api/pair/handshake", server.takeRequest().path)
            assertBearerRequest("/api/device/validate?fingerprint=install-id", "device")
        }

    @Test
    fun `profile update sends editable identity fields`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true,"user":{"id":1,"username":"renamed","email":"new@example.test"}}"""),
            )

            api.updateProfile(
                "Bearer jwt",
                ProfileUpdateRequest(
                    username = "renamed",
                    email = "new@example.test",
                    avatar = "data:image/jpeg;base64,YXZhdGFy",
                    currentPassword = "old-secret",
                    newPassword = "new-secret",
                ),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/api/auth/profile", request.path)
            assertEquals("Bearer jwt", request.getHeader("Authorization"))
            assertTrue(body.contains("\"username\":\"renamed\""))
            assertTrue(body.contains("\"email\":\"new@example.test\""))
            assertTrue(body.contains("\"avatar\":\"data:image/jpeg;base64,YXZhdGFy\""))
            assertTrue(body.contains("\"currentPassword\":\"old-secret\""))
            assertTrue(body.contains("\"newPassword\":\"new-secret\""))
        }

    @Test
    fun `status payload matches checked in golden request`() {
        val json =
            Json {
                explicitNulls = false
                encodeDefaults = true
            }
        val payload =
            StatusPayload(
                deviceKey = "device_key_fixture",
                deviceFingerprint = "installation_id_fixture",
                clientVersion = "2.0.0-alpha.1",
                appVersion = "2.0.0-alpha.1",
                appName = "Example App",
                packageName = "com.example.app",
                status = "online",
                screenStatus = "on",
                batteryLevel = 83,
                isCharging = false,
                music = MediaSnapshot(title = "Fixture Track", artist = "Fixture Artist"),
            )
        val golden = requireNotNull(javaClass.classLoader?.getResource("status-report.json")).readText()

        assertEquals(json.parseToJsonElement(golden), json.parseToJsonElement(json.encodeToString(payload)))
    }

    @Test
    fun `status report uses multipart data and preserves terminal HTTP codes`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"INVALID_KEY"}"""))

            val response =
                api.reportStatus(
                    authorization = "Bearer device-key",
                    data =
                        """{"status":"away","screenStatus":"locked","batteryLevel":83}"""
                            .toRequestBody("application/json".toMediaType()),
                )
            val request = server.takeRequest()

            assertEquals(401, response.code())
            assertEquals("/api/v2/status/report", request.path)
            assertEquals("Bearer device-key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val multipart = request.body.readUtf8()
            assertTrue(multipart.contains("name=\"data\""))
            assertTrue(multipart.contains("\"screenStatus\":\"locked\""))
        }

    @Test
    fun `widget token uses device bearer and json body`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"success":true,"widgetToken":"widget-token","userId":"42","username":"neko","userType":"admin"}""",
                    ),
            )

            val response = widgetApi.generateWidgetToken("Bearer device-key", WidgetTokenRequest("device-key"))
            val request = server.takeRequest()

            assertTrue(response.isSuccessful)
            assertEquals("/api/widget/token", request.path)
            assertEquals("Bearer device-key", request.getHeader("Authorization"))
            assertEquals(
                Json.parseToJsonElement("""{"deviceKey":"device-key"}"""),
                Json.parseToJsonElement(request.body.readUtf8()),
            )
        }

    @Test
    fun `widget status uses widget bearer and selection query`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true,"data":{"users":[]}}"""),
            )

            widgetApi.widgetStatus("Bearer widget-token", userId = "42", limit = 4)
            val request = server.takeRequest()

            assertEquals("/api/v2/widget/status?userId=42&limit=4", request.path)
            assertEquals("Bearer widget-token", request.getHeader("Authorization"))
        }

    @Test
    fun `widget endpoints preserve server response codes`() =
        runTest {
            val expectedCodes = listOf(401, 403, 404, 429, 500)
            expectedCodes.forEach { code -> server.enqueue(MockResponse().setResponseCode(code)) }

            val actualCodes =
                expectedCodes.map {
                    widgetApi.widgetStatus("Bearer widget-token").code()
                }

            assertEquals(expectedCodes, actualCodes)
        }

    private fun assertBearerRequest(
        expectedPath: String,
        token: String,
    ) {
        val request = server.takeRequest()
        assertEquals(expectedPath, request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }
}
