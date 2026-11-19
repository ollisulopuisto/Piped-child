package fi.pipedchild.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import fi.pipedchild.core.player.PlaybackChoice
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.asAndroidBitmap
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
}
