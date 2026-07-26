package com.nekonf.nekostatus.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.nekonf.nekostatus.core.network.dto.AuthRequest
import com.nekonf.nekostatus.core.network.dto.AuthResponse
import com.nekonf.nekostatus.core.network.dto.DeviceKeyRequest
import com.nekonf.nekostatus.core.network.dto.DeviceKeyResponse
import com.nekonf.nekostatus.core.network.dto.DeviceValidationResponse
import com.nekonf.nekostatus.core.network.dto.HandshakeRequest
import com.nekonf.nekostatus.core.network.dto.HandshakeResponse
import com.nekonf.nekostatus.core.network.dto.ProfileUpdateRequest
import com.nekonf.nekostatus.core.network.dto.StatusResponse
import com.nekonf.nekostatus.core.network.dto.WidgetStatusResponse
import com.nekonf.nekostatus.core.network.dto.WidgetTokenRequest
import com.nekonf.nekostatus.core.network.dto.WidgetTokenResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface NekoApi {
    @POST("/api/auth/login")
    suspend fun login(
        @Body request: AuthRequest,
    ): Response<AuthResponse>

    @POST("/api/auth/register")
    suspend fun register(
        @Body request: AuthRequest,
    ): Response<AuthResponse>

    @GET("/api/auth/me")
    suspend fun me(
        @Header("Authorization") authorization: String,
    ): Response<AuthResponse>

    @PUT("/api/auth/profile")
    suspend fun updateProfile(
        @Header("Authorization") authorization: String,
        @Body request: ProfileUpdateRequest,
    ): Response<AuthResponse>

    @POST("/api/auth/device-key")
    suspend fun generateDeviceKey(
        @Header("Authorization") authorization: String,
        @Body request: DeviceKeyRequest,
    ): Response<DeviceKeyResponse>

    @POST("/api/pair/handshake")
    suspend fun handshake(
        @Body request: HandshakeRequest,
    ): Response<HandshakeResponse>

    @GET("/api/pair/handshake")
    suspend fun handshakeResult(
        @Query("token") token: String,
    ): Response<HandshakeResponse>

    @GET("/api/device/validate")
    suspend fun validateDevice(
        @Header("Authorization") authorization: String,
        @Query("fingerprint") fingerprint: String,
    ): Response<DeviceValidationResponse>

    @Multipart
    @POST("/api/v2/status/report")
    suspend fun reportStatus(
        @Header("Authorization") authorization: String,
        @Part("data") data: RequestBody,
        @Part icon: MultipartBody.Part? = null,
    ): Response<StatusResponse>
}

interface WidgetApi {
    @POST("/api/widget/token")
    suspend fun generateWidgetToken(
        @Header("Authorization") authorization: String,
        @Body request: WidgetTokenRequest,
    ): Response<WidgetTokenResponse>

    @GET("/api/v2/widget/status")
    suspend fun widgetStatus(
        @Header("Authorization") authorization: String,
        @Query("userId") userId: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<WidgetStatusResponse>
}

@Singleton
class NekoApiFactory
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val json: Json,
    ) {
        private data class ApiClients(
            val main: NekoApi,
            val widget: WidgetApi,
        )

        private val cache = ConcurrentHashMap<String, ApiClients>()

        fun create(serverUrl: String): NekoApi = clients(serverUrl).main

        fun createWidget(serverUrl: String): WidgetApi = clients(serverUrl).widget

        private fun clients(serverUrl: String): ApiClients {
            val normalized = "${serverUrl.trimEnd('/')}/"
            return cache.getOrPut(normalized) {
                val retrofit =
                    Retrofit.Builder()
                        .baseUrl(normalized)
                        .client(client)
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                ApiClients(
                    main = retrofit.create(NekoApi::class.java),
                    widget = retrofit.create(WidgetApi::class.java),
                )
            }
        }

        suspend fun probe(
            serverUrl: String,
            path: String,
            bearerToken: String? = null,
        ): Int =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val builder = Request.Builder().url("${serverUrl.trimEnd('/')}$path").get()
                bearerToken?.let { builder.header("Authorization", "Bearer $it") }
                client.newCall(builder.build()).execute().use { it.code }
            }
    }

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
