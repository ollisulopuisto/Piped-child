package fi.pipedchild.tv

import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailConsentTest {
    @Test
    fun `no origin approved by default and decline still permits title-only use`() {
        val urls = listOf("https://images.example/one.jpg", "https://images.example/two.jpg")
        val initial = ThumbnailConsent()
        assertFalse(initial.allows(urls.first()))
        assertEquals(listOf("https://images.example"), initial.pending(urls))
        val declined = initial.decide(initial.pending(urls), false)
        assertTrue(declined.pending(urls).isEmpty())
        assertFalse(declined.allows(urls.first()))
    }

    @Test
    fun `approval covers only exact origin and new hosts ask again`() {
        val consent = ThumbnailConsent().decide(listOf("https://images.example"), true)
        assertTrue(consent.allows("https://images.example/a.jpg?width=320"))
        assertTrue(consent.allows("https://IMAGES.example:443/a.jpg"))
        for (url in listOf("http://images.example/a", "https://images.example:444/a",
            "https://images.example.evil.example/a", "https://user:pass@images.example/a",
            "file:///a", "::::", "//images.example/a")) assertFalse(consent.allows(url), url)
        assertEquals(listOf("https://new.example"), consent.pending(listOf("https://new.example/a")))
    }

    @Test
    fun `repository does not call transport for unapproved URLs`() = runTest {
        var calls = 0
        val repository = ThumbnailRepository(transport = { calls++; null })
        assertNull(repository.load("https://images.example/a", ThumbnailConsent()))
        assertEquals(0, calls)
        assertNull(repository.load("https://images.example/a",
            ThumbnailConsent().decide(listOf("https://images.example"), true)))
        assertEquals(1, calls)
    }

    @Test
    fun `oversized or invalid image bodies are not decoded`() = runTest {
        val consent = ThumbnailConsent().decide(listOf("https://images.example"), true)
        val huge = ThumbnailRepository { ByteArray(MAX_THUMBNAIL_BYTES + 1) }
        assertNull(huge.load("https://images.example/a", consent))
        val bad = ThumbnailRepository { "not an image".toByteArray() }
        assertNull(bad.load("https://images.example/a", consent))
    }

    @Test
    fun `cancellation is propagated rather than converted into a placeholder`() = runTest {
        val consent = ThumbnailConsent().decide(listOf("https://images.example"), true)
        val repo = ThumbnailRepository { throw kotlinx.coroutines.CancellationException("test") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { repo.load("https://images.example/a", consent) }
    }

    @Test
    fun `titles only can revoke earlier approval in a multi-origin list`() {
        val approved = ThumbnailConsent().decide(listOf("https://one.example"), true)
        val revoked = approved.decide(listOf("https://one.example", "https://two.example"), false)
        assertFalse(revoked.allows("https://one.example/a"))
        assertFalse(revoked.allows("https://two.example/a"))
        assertTrue(revoked.pending(listOf("https://one.example/a", "https://two.example/a")).isEmpty())
    }
}
