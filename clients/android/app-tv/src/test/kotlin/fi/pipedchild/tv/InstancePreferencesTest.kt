package fi.pipedchild.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstancePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test
    fun `HTTPS host roots are normalized without a network probe`() {
        assertEquals("https://api.example", normalizeTvInstance(" HTTPS://API.example:443/ "))
        assertEquals("https://api.example:8443", normalizeTvInstance("https://api.example:8443"))
        assertEquals("https://[::1]:8443", normalizeTvInstance("https://[::1]:8443/"))
    }

    @Test
    fun `unsafe URLs and unsupported plaintext are rejected`() {
        listOf("http://api.example", "http://127.0.0.1", "https://user:secret@api.example",
            "https://api.example/api/v1", "https://api.example//", "https://api.example?token=secret",
            "https://api.example#fragment", "https://api.example:0", "https://api.example:65536",
            "not a URL", "//api.example", "javascript:alert(1)").forEach {
            assertNull(normalizeTvInstance(it), "invalid input must not become an instance")
        }
    }

    @Test
    fun `changing instance persists normalized URL and removes API approval`() {
        val store = InstancePreferences(context)
        prefs.edit().putString(KEY_CONSENTED_INSTANCE, DEFAULT_INSTANCE).commit()
        assertTrue(store.save("https://NEW.example/"))
        assertEquals("https://new.example", InstancePreferences(context).current())
        assertNull(prefs.getString(KEY_CONSENTED_INSTANCE, null))
    }

    @Test
    fun `invalid save leaves both current instance and its approval unchanged`() {
        val store = InstancePreferences(context)
        prefs.edit().putString(KEY_CONSENTED_INSTANCE, DEFAULT_INSTANCE).commit()
        assertFalse(store.save("https://user:secret@api.example"))
        assertEquals(DEFAULT_INSTANCE, store.current())
        assertEquals(DEFAULT_INSTANCE, prefs.getString(KEY_CONSENTED_INSTANCE, null))
    }

    @Test
    fun `same instance spelling does not reset consent`() {
        val store = InstancePreferences(context)
        prefs.edit().putString(KEY_CONSENTED_INSTANCE, DEFAULT_INSTANCE).commit()
        assertTrue(store.save("HTTPS://PIPEDAPI.DUCKS.PARTY:443/"))
        assertEquals(DEFAULT_INSTANCE, prefs.getString(KEY_CONSENTED_INSTANCE, null))
    }
}
