package fi.pipedchild.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import fi.pipedchild.core.player.PlaybackChoice
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fi.pipedchild.core.network.InMemoryTokenStore
import fi.pipedchild.core.network.PipedApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM-rendered TV layouts and remote-key regression tests. These do not
 * prove TV launcher integration or hardware video decode. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val file = File("build/tv-screens/$name.png")
        file.parentFile.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test
    fun `first-run Continue receives focus and accepts a remote center press`() {
        var continued = false
        compose.setContent { TvTheme {
            FirstRunScreen("https://pipedapi.ducks.party") { continued = true }
        } }
        compose.onNodeWithText("Continue").assertIsDisplayed().assertIsFocused()
        capture("first-run")
        compose.onNodeWithText("Continue").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun `grid remote selection and Back restore the selected card`() {
        val items = (0..19).joinToString(",", "[", "]") {
            """{"url":"/watch?v=id$it","title":"Video $it"}"""
        }
        val vm = HomeViewModel(PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine {
            respond(items, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        runBlocking { vm.loadOnce() }
        var selected by mutableStateOf<String?>(null)
        compose.setContent { TvTheme {
            val grid = rememberLazyGridState()
            var last by remember { mutableStateOf<String?>(null) }
            if (selected == null) {
                HomeScreen(vm, grid, last) { last = it; selected = it }
            } else {
                androidx.activity.compose.BackHandler { selected = null }
                androidx.compose.material3.Button(onClick = { selected = null }, modifier = initialFocus()) {
                    androidx.compose.material3.Text("Return")
                }
            }
        } }
        compose.waitForIdle()
        capture("home-focused")
        compose.onNodeWithText("Video 0").performKeyInput { pressKey(Key.DirectionRight) }
        repeat(4) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
        capture("home-after-down")
        compose.onNodeWithText("Video 17").assertIsDisplayed().assertIsFocused()
        capture("home-scrolled")
        compose.onNodeWithText("Video 17").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("/watch?v=id17", selected) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Video 17").assertIsDisplayed().assertIsFocused()
        capture("home-restored")
        compose.onNodeWithText("Video 17").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("/watch?v=id17", selected) }
        vm.shutdown()
    }

    @Test
    fun `proxy approval screen renders before a media player exists`() {
        val media = PlaybackMedia("Example video", "https://piped-proxy.ducks.party",
            PlaybackChoice.Progressive("https://piped-proxy.ducks.party/videoplayback"))
        compose.setContent { TvTheme { ProxyConsentScreen(media, {}, {}) } }
        compose.onNodeWithText("Play").assertIsDisplayed()
        capture("proxy-consent")
        compose.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test
    fun `thumbnail consent can be declined with the remote`() {
        var decision: Boolean? = null
        compose.setContent { TvTheme {
            ThumbnailConsentScreen(listOf("https://piped-proxy.ducks.party")) { decision = it }
        } }
        compose.onNodeWithText("Load thumbnails").assertIsFocused()
        capture("thumbnail-consent")
        compose.onNodeWithText("Load thumbnails").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Titles only").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(false, decision) }
    }

    @Test
    fun `approved thumbnail renders actual image pixels and denied consent bypasses cache`() {
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(android.graphics.Color.rgb(22, 70, 90))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(255, 190, 65) }
        canvas.drawCircle(320f, 180f, 95f, paint)
        val bytes = java.io.ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        var calls = 0
        val repo = ThumbnailRepository { calls++; bytes }
        val consent = ThumbnailConsent().decide(listOf("https://images.example"), true)
        compose.setContent { TvTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(48.dp)) {
                VideoThumbnail("https://images.example/image.png", consent, repo)
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("thumbnail-loaded")
        compose.runOnIdle { assertEquals(1, calls) }
        runBlocking {
            assertNull(repo.load("https://images.example/image.png", ThumbnailConsent()))
            assertNotNull(repo.load("https://images.example/image.png", consent))
        }
        assertEquals(1, calls)
    }

    @Test
    fun `home makes no thumbnail request until its own consent gate is accepted`() {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(25, 80, 130))
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        var requests = 0
        val repo = ThumbnailRepository { requests++; bytes }
        val vm = HomeViewModel(PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine {
            respond("""[{"url":"/watch?v=example","title":"Example video","thumbnail":"https://images.example/a.png"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        runBlocking { vm.loadOnce() }
        compose.setContent { TvTheme { HomeScreen(vm, rememberLazyGridState(), null, repo) {} } }
        compose.onNodeWithText("Load thumbnails").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("Load thumbnails").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Example video").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Example video").assertIsDisplayed()
        capture("thumbnail-in-grid")
        compose.runOnIdle { assertEquals(1, requests) }
        vm.shutdown()
    }

    @Test
    fun `instance settings render and reject invalid URLs before save`() {
        var saved: String? = null
        compose.setContent { TvTheme {
            InstanceSettingsScreen("https://pipedapi.ducks.party", { saved = it; true }, {})
        } }
        capture("instance-settings")
        compose.onNodeWithText("API instance URL").performTextReplacement("https://user:secret@api.example")
        compose.onNodeWithText("Save instance").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Use an HTTPS host root", substring = true).assertIsDisplayed()
        compose.onNodeWithText("API instance URL").performTextReplacement("HTTPS://NEW.example:443/")
        compose.onNodeWithText("Save instance").performClick()
        compose.runOnIdle { assertEquals("https://new.example", saved) }
    }

    @Test
    fun `up from the first grid row reaches settings and down returns to grid`() {
        val vm = HomeViewModel(PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine {
            respond("""[{"url":"/watch?v=one","title":"First video"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        runBlocking { vm.loadOnce() }
        compose.setContent { TvTheme {
            val header = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Button({}, modifier = androidx.compose.ui.Modifier
                    .focusRequester(header).then(remoteFocus())) { androidx.compose.material3.Text("Settings target") }
                HomeScreen(vm, rememberLazyGridState(), null, onNavigateUp = { header.requestFocus() }) {}
            }
        } }
        compose.onNodeWithText("First video").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Settings target").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("First video").assertIsFocused()
        vm.shutdown()
    }
}
