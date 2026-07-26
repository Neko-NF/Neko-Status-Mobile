package com.nekonf.nekostatus.core.data

import com.nekonf.nekostatus.core.model.AuthSession
import com.nekonf.nekostatus.core.model.DeviceCredential
import com.nekonf.nekostatus.core.model.UserProfile
import com.nekonf.nekostatus.core.model.WidgetCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun `account invalidation clears credentials tied to the device binding`() {
        val store = InMemoryCredentialStore()
        val repository = SessionRepository(store)
        repository.saveSession(AuthSession("jwt", UserProfile(7, "neko", "neko@example.test")))
        repository.saveDeviceCredential(DeviceCredential("device-key", 8, "Test phone"))
        repository.saveWidgetCredential(WidgetCredential("widget-token"))

        repository.clearAccountAccess()

        assertNull(repository.session.value)
        assertNull(repository.deviceCredential.value)
        assertNull(repository.widgetCredential.value)
        assertNull(SessionRepository(store).widgetCredential.value)
    }

    @Test
    fun `widget invalidation does not clear account or device credentials`() {
        val store = InMemoryCredentialStore()
        val repository = SessionRepository(store)
        repository.saveSession(AuthSession("jwt", UserProfile(username = "neko")))
        repository.saveDeviceCredential(DeviceCredential("device-key"))
        repository.saveWidgetCredential(WidgetCredential("widget-token", username = "neko"))

        repository.clearWidgetCredential()

        assertEquals("jwt", repository.session.value?.token)
        assertEquals("device-key", repository.deviceCredential.value?.deviceKey)
        assertNull(repository.widgetCredential.value)
    }

    @Test
    fun `logout clears every credential type but keeps installation identity`() {
        val store = InMemoryCredentialStore()
        val installationId = InstallationRepository(store).installationId
        val repository = SessionRepository(store)
        repository.saveSession(AuthSession("jwt", UserProfile(username = "neko")))
        repository.saveDeviceCredential(DeviceCredential("device-key"))
        repository.saveWidgetCredential(WidgetCredential("widget-token"))

        repository.clearAll()

        val restored = SessionRepository(store)
        assertNull(restored.session.value)
        assertNull(restored.deviceCredential.value)
        assertNull(restored.widgetCredential.value)
        assertNotNull(store.get("installation_id"))
        assertEquals(installationId, InstallationRepository(store).installationId)
    }
}

private class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun put(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun get(key: String): String? = values[key]

    override fun clear(keys: Iterable<String>) {
        keys.forEach(values::remove)
    }
}
