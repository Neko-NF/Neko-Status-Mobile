package com.nekonf.nekostatus.core.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.nekonf.nekostatus.core.model.AuthSession
import com.nekonf.nekostatus.core.model.DeviceCredential
import com.nekonf.nekostatus.core.model.UserProfile
import com.nekonf.nekostatus.core.model.WidgetCredential
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface CredentialStore {
    fun put(
        key: String,
        value: String?,
    )

    fun get(key: String): String?

    fun clear(keys: Iterable<String>)
}

enum class AccountBoundaryReason {
    LOGOUT,
    ACCESS_REVOKED,
    ACCOUNT_REPLACED,
}

@Singleton
class SecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : CredentialStore {
        private val preferences = context.getSharedPreferences("neko-secure-values", Context.MODE_PRIVATE)
        private val aead: Aead

        init {
            AeadConfig.register()
            val keysetHandle =
                AndroidKeysetManager.Builder()
                    .withSharedPref(context, "neko-aead-keyset", "neko-aead-keyset-store")
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri("android-keystore://neko-master-key")
                    .build()
                    .keysetHandle
            aead = keysetHandle.getPrimitive(Aead::class.java)
        }

        override fun put(
            key: String,
            value: String?,
        ) {
            if (value == null) {
                preferences.edit().remove(key).apply()
                return
            }
            val cipher = aead.encrypt(value.toByteArray(StandardCharsets.UTF_8), key.toByteArray(StandardCharsets.UTF_8))
            preferences.edit().putString(key, Base64.encodeToString(cipher, Base64.NO_WRAP)).apply()
        }

        override fun get(key: String): String? =
            runCatching {
                val encoded = preferences.getString(key, null) ?: return null
                val clear = aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), key.toByteArray(StandardCharsets.UTF_8))
                String(clear, StandardCharsets.UTF_8)
            }.getOrNull()

        override fun clear(keys: Iterable<String>) {
            preferences.edit().apply { keys.forEach(::remove) }.apply()
        }
    }

@Singleton
class SessionRepository
    @Inject
    constructor(
        private val secureStore: CredentialStore,
    ) {
        private val _session = MutableStateFlow(loadSession())
        private val _deviceCredential = MutableStateFlow(loadDeviceCredential())
        private val _widgetCredential = MutableStateFlow(loadWidgetCredential())
        private val accountBoundaryChannel = Channel<AccountBoundaryReason>(Channel.BUFFERED)

        val session: StateFlow<AuthSession?> = _session.asStateFlow()
        val deviceCredential: StateFlow<DeviceCredential?> = _deviceCredential.asStateFlow()
        val widgetCredential: StateFlow<WidgetCredential?> = _widgetCredential.asStateFlow()
        val accountBoundaryEvents = accountBoundaryChannel.receiveAsFlow()

        fun saveSession(
            session: AuthSession,
            preserveAccountBinding: Boolean = false,
        ) {
            val previous = _session.value
            val replacesAccount =
                !preserveAccountBinding &&
                    when {
                        previous == null -> _deviceCredential.value != null || _widgetCredential.value != null
                        previous.user.id != null && session.user.id != null -> previous.user.id != session.user.id
                        else -> previous.user.username != session.user.username
                    }
            if (replacesAccount) {
                clearBoundCredentials()
                accountBoundaryChannel.trySend(AccountBoundaryReason.ACCOUNT_REPLACED)
            }
            secureStore.put("auth_token", session.token)
            secureStore.put("user_id", session.user.id?.toString())
            secureStore.put("username", session.user.username)
            secureStore.put("user_email", session.user.email)
            secureStore.put("user_avatar", session.user.avatarUrl)
            _session.value = session
        }

        fun saveDeviceCredential(credential: DeviceCredential) {
            secureStore.put("device_key", credential.deviceKey)
            secureStore.put("device_id", credential.deviceId?.toString())
            secureStore.put("device_name", credential.deviceName)
            _deviceCredential.value = credential
        }

        fun saveWidgetCredential(credential: WidgetCredential) {
            secureStore.put(WIDGET_TOKEN, credential.token)
            secureStore.put("widget_user_id", credential.userId)
            secureStore.put("widget_username", credential.username)
            secureStore.put("widget_user_type", credential.userType)
            secureStore.put("widget_expires_at", credential.expiresAt)
            _widgetCredential.value = credential
        }

        fun clearAccountAccess(reason: AccountBoundaryReason = AccountBoundaryReason.ACCESS_REVOKED) {
            secureStore.clear(ACCOUNT_ACCESS_KEYS)
            _session.value = null
            _deviceCredential.value = null
            _widgetCredential.value = null
            accountBoundaryChannel.trySend(reason)
        }

        fun clearWidgetCredential() {
            secureStore.clear(WIDGET_CREDENTIAL_KEYS)
            _widgetCredential.value = null
        }

        fun clearAll() {
            secureStore.clear(ALL_CREDENTIAL_KEYS)
            _session.value = null
            _deviceCredential.value = null
            _widgetCredential.value = null
            accountBoundaryChannel.trySend(AccountBoundaryReason.LOGOUT)
        }

        private fun clearBoundCredentials() {
            secureStore.clear(DEVICE_CREDENTIAL_KEYS + WIDGET_CREDENTIAL_KEYS)
            _deviceCredential.value = null
            _widgetCredential.value = null
        }

        private fun loadSession(): AuthSession? {
            val token = secureStore.get("auth_token") ?: return null
            val username = secureStore.get("username") ?: return null
            return AuthSession(
                token,
                UserProfile(
                    id = secureStore.get("user_id")?.toLongOrNull(),
                    username = username,
                    email = secureStore.get("user_email"),
                    avatarUrl = secureStore.get("user_avatar"),
                ),
            )
        }

        private fun loadDeviceCredential(): DeviceCredential? {
            val key = secureStore.get("device_key") ?: return null
            return DeviceCredential(
                deviceKey = key,
                deviceId = secureStore.get("device_id")?.toLongOrNull(),
                deviceName = secureStore.get("device_name"),
            )
        }

        private fun loadWidgetCredential(): WidgetCredential? {
            val token = secureStore.get(WIDGET_TOKEN) ?: return null
            return WidgetCredential(
                token = token,
                userId = secureStore.get("widget_user_id"),
                username = secureStore.get("widget_username"),
                userType = secureStore.get("widget_user_type"),
                expiresAt = secureStore.get("widget_expires_at"),
            )
        }

        private companion object {
            const val WIDGET_TOKEN = "widget_token"
            val SESSION_KEYS =
                setOf(
                    "auth_token",
                    "user_id",
                    "username",
                    "user_email",
                    "user_avatar",
                )
            val DEVICE_CREDENTIAL_KEYS =
                setOf(
                    "device_key",
                    "device_id",
                    "device_name",
                )
            val WIDGET_CREDENTIAL_KEYS =
                setOf(
                    WIDGET_TOKEN,
                    "widget_user_id",
                    "widget_username",
                    "widget_user_type",
                    "widget_expires_at",
                )
            val AUTHENTICATION_KEYS = SESSION_KEYS + DEVICE_CREDENTIAL_KEYS
            val ACCOUNT_ACCESS_KEYS = AUTHENTICATION_KEYS + WIDGET_CREDENTIAL_KEYS
            val ALL_CREDENTIAL_KEYS = ACCOUNT_ACCESS_KEYS
        }
    }

@Singleton
class InstallationRepository
    @Inject
    constructor(
        private val secureStore: CredentialStore,
    ) {
        val installationId: String by lazy {
            secureStore.get("installation_id") ?: UUID.randomUUID().toString().also {
                secureStore.put("installation_id", it)
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialStoreModule {
    @Binds
    @Singleton
    abstract fun bindCredentialStore(implementation: SecureCredentialStore): CredentialStore
}
