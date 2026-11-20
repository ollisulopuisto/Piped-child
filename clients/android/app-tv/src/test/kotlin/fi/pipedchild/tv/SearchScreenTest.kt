package fi.pipedchild.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import fi.pipedchild.core.network.PipedApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/tv-screens/$name.png"); file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun `remote submits explicit query and title selection restores after playback`() {
        var requests = 0
        val vm = SearchViewModel(PipedApiClient("https://api.example", engine = MockEngine { request ->
            requests++
            assertEquals("/search", request.url.encodedPath)
            respond("""{"items":[{"type":"stream","url":"/watch?v=cat","title":"A cat video","thumbnail":"https://unapproved.example/image.png","uploaderName":"Example channel"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        var playing by mutableStateOf(false)
        var selected: String? = null
        compose.setContent { TvTheme {
            val holder = rememberSaveableStateHolder()
            if (!playing) holder.SaveableStateProvider("search") {
                SearchScreen(vm, {}, { selected = it; playing = true })
            } else Button(onClick = { playing = false }, modifier = initialFocus()) { Text("Return from playback") }
        } }
        compose.onNodeWithText("Video query").assertIsFocused().performTextInput("cats")
        compose.runOnIdle { assertEquals(0, requests) }
        capture("search-entry")
        compose.onNodeWithText("Video query").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Search").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("A cat video").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A cat video").assertIsDisplayed()
        capture("search-results")
        compose.onNodeWithText("Search").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("A cat video").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("/watch?v=cat", selected); assertEquals(1, requests) }
        compose.onNodeWithText("Return from playback").performClick()
        compose.onNodeWithText("A cat video").assertIsDisplayed().assertIsFocused()
        vm.shutdown()
    }

    @Test fun `load more retains results and retry on rate limit keeps page token`() {
        var pages = 0
        val vm = SearchViewModel(PipedApiClient("https://api.example", engine = MockEngine { request ->
            val page = request.url.encodedPath != "/search"
            if (page) pages++
            respond(if (!page) """{"items":[{"type":"stream","url":"/watch?v=one","title":"First video"}],"nextpage":"cursor"}"""
                else if (pages == 1) "{}" else """{"items":[{"type":"stream","url":"/watch?v=two","title":"Second video"}]}""",
                if (pages == 1 && page) HttpStatusCode.TooManyRequests else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        var back = false
        compose.setContent { TvTheme { SearchScreen(vm, { back = true }, {}) } }
        compose.onNodeWithText("Video query").performTextInput("video")
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Load more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Load more").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Retry next page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First video").assertIsDisplayed()
        capture("search-page-error")
        compose.onNodeWithText("Retry next page").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Second video").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(back); assertEquals(2, pages) }
        vm.shutdown()
    }
}
