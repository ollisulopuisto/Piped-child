package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the M1-B networking layer. Every test drives the client
 * through Ktor's MockEngine against the shared fixtures in
 * clients/contracts/fixtures — no test touches the network (owner rule:
 * no unsolicited sends; fixtures are the recorded truth).
 */
class PipedApiClientTest {

    private val baseUrl = "https://pipedapi.example.com"

    private val recorded = mutableListOf<HttpRequestData>()

    private fun fixture(name: String): String {
        val path = java.nio.file.Path.of("..", "..", "contracts", "fixtures", name)
        return java.nio.file.Files.readString(path)
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun client(
        url: String = baseUrl,
        tokenStore: TokenStore = InMemoryTokenStore(),
        authBaseUrl: String? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): PipedApiClient {
        recorded.clear()
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        return PipedApiClient(url, tokenStore, engine, authBaseUrl)
    }

    // ---------- discovery ----------

    @Test
    fun `trending parses recorded fixture and sends region`() = runTest {
        val c = client { req ->
            assertEquals("/trending", req.url.encodedPath)
            json(fixture("trending_OK.json"))
        }
        val items = c.trending("US")
        assertTrue(items.isNotEmpty())
        assertEquals("US", recorded.single().url.parameters["region"])
        assertTrue(items.all { it.videoId.isNotBlank() })
    }

    @Test
    fun `search parses recorded fixture and sends query and filter`() = runTest {
        val c = client { json(fixture("search_videos_OK.json")) }
        val r = c.search("lofi", SearchFilter.VIDEOS)
        assertTrue(r.items.isNotEmpty())
        val req = recorded.single()
        assertEquals("/search", req.url.encodedPath)
        assertEquals("lofi", req.url.parameters["q"])
        assertEquals("videos", req.url.parameters["filter"])
    }

    @Test
    fun `search next page forwards query filter and token`() = runTest {
        val c = client { json(fixture("search_videos_OK.json")) }
        c.searchNextPage("lofi", SearchFilter.CHANNELS, "page-token-2")
        val req = recorded.single()
        assertEquals("/nextpage/search", req.url.encodedPath)
        assertEquals("lofi", req.url.parameters["q"])
        assertEquals("channels", req.url.parameters["filter"])
        assertEquals("page-token-2", req.url.parameters["nextpage"])
    }

    @Test
    fun `suggestions parse the opensearch array`() = runTest {
        val c = client { json(fixture("search_suggestions_OK.json")) }
        val s = c.suggestions("lofi")
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.isNotBlank() })
        assertEquals("lofi", recorded.single().url.parameters["query"])
    }

    // ---------- playback metadata ----------

    @Test
    fun `hostile ids cannot break out of their path segment`() = runTest {
        val c = client { json(fixture("streams_degraded_360p_OK.json")) }
        c.streams("a/b?c=1")
        assertEquals("/streams/a%2Fb%3Fc%3D1", recorded.single().url.encodedPath)
    }

    @Test
    fun `streams degraded fixture parses`() = runTest {
        val c = client { json(fixture("streams_degraded_360p_OK.json")) }
        val r = c.streams("dQw4w9WgXcQ")
        assertNull(r.dash)
        assertNull(r.hls)
        assertTrue(r.videoStreams.isNotEmpty())
        assertEquals("/streams/dQw4w9WgXcQ", recorded.single().url.encodedPath)
    }

    @Test
    fun `streams extraction failure maps to ExtractionFailure not a crash`() = runTest {
        val c = client {
            json(fixture("streams_error_login_required.json"), HttpStatusCode.InternalServerError)
        }
        val e = assertFailsWith<PipedError.ExtractionFailure> { c.streams("someVideo") }
        assertTrue(e.serverMessage.isNotBlank())
    }

    // ---------- social / metadata ----------

    @Test
    fun `comments parse with nextpage`() = runTest {
        val c = client { json(fixture("comments_OK.json")) }
        val r = c.comments("dQw4w9WgXcQ")
        assertTrue(r.comments.isNotEmpty())
        assertNotNull(r.nextpage)
    }

    @Test
    fun `comments next page hits the nextpage endpoint`() = runTest {
        val c = client { json(fixture("comments_OK.json")) }
        c.commentsNextPage("dQw4w9WgXcQ", "comment-token-2")
        val req = recorded.single()
        assertEquals("/nextpage/comments/dQw4w9WgXcQ", req.url.encodedPath)
        assertEquals("comment-token-2", req.url.parameters["nextpage"])
    }

    @Test
    fun `sponsors empty object parses to zero segments and encodes categories`() = runTest {
        val c = client { json(fixture("sponsors_empty_OK.json")) }
        val r = c.sponsors("dQw4w9WgXcQ", listOf("sponsor", "selfpromo"))
        assertTrue(r.segments.isEmpty())
        assertEquals(
            "[\"sponsor\",\"selfpromo\"]",
            recorded.single().url.parameters["category"],
        )
    }

    @Test
    fun `dearrow maps video ids to entries`() = runTest {
        val c = client { json(fixture("dearrow_OK.json")) }
        val r = c.dearrow(listOf("dQw4w9WgXcQ"))
        assertTrue(r.containsKey("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", recorded.single().url.parameters["videoIds"])
    }

    @Test
    fun `channel parses synthetic fixture with related streams`() = runTest {
        val c = client { json(fixture("channel_OK_SYNTHETIC.json")) }
        val r = c.channel("UCXuqSBlHAE6Xw-yeJA0Tunw")
        assertEquals("Linus Tech Tips", r.name)
        assertEquals(1, r.relatedStreams.size)
        assertEquals("syntheticVid1", r.relatedStreams[0].videoId)
        assertNotNull(r.nextpage)
        assertEquals("/channel/UCXuqSBlHAE6Xw-yeJA0Tunw", recorded.single().url.encodedPath)
    }

    @Test
    fun `channel aliases use the c and user paths`() = runTest {
        val c = client { json(fixture("channel_OK_SYNTHETIC.json")) }
        c.channel("LinusTechTips", ChannelPath.CUSTOM)
        c.channel("LinusTechTips", ChannelPath.USER)
        assertEquals("/c/LinusTechTips", recorded[0].url.encodedPath)
        assertEquals("/user/LinusTechTips", recorded[1].url.encodedPath)
    }

    @Test
    fun `playlist parses synthetic fixture`() = runTest {
        val c = client { json(fixture("playlist_OK_SYNTHETIC.json")) }
        val r = c.playlist("synthetic-playlist-id-1")
        assertEquals("Synthetic playlist", r.name)
        assertEquals(2, r.relatedStreams.size)
        assertEquals(2, r.videos)
        // the frontend sends NO Authorization header for playlist reads
        assertNull(recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `playlist reads hit the auth instance without credentials`() = runTest {
        // verified in usePlaylists.js getPlaylist + PlaylistPage.vue: playlist
        // content comes from authApiUrl() with no Authorization header, so a
        // user-created playlist (which lives on the account instance) resolves.
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            requests += req
            respond(fixture("playlist_OK_SYNTHETIC.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val c = PipedApiClient("https://discovery.example", InMemoryTokenStore(), engine, "https://accounts.example")
        c.playlist("pl-1")
        c.playlistNextPage("pl-1", "tok")
        assertEquals("accounts.example", requests[0].url.host)
        assertEquals("accounts.example", requests[1].url.host)
        assertNull(requests[0].headers[HttpHeaders.Authorization])
        assertNull(requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `register stores the issued session token`() = runTest {
        // verified in RegisterPage.vue: a successful register returns {token}
        // and the frontend stores it exactly like a login
        val store = InMemoryTokenStore()
        val c = client(tokenStore = store) { json(fixture("login_OK_SYNTHETIC.json")) }
        val token = c.register("olli", "hunter2")
        assertEquals("synthetic-token-0123456789abcdef", token)
        assertEquals(token, store.token(baseUrl))
    }

    @Test
    fun `register rejected with a 200 error body maps to AuthenticationFailure`() = runTest {
        val store = InMemoryTokenStore()
        val c = client(tokenStore = store) { json("""{"error":"username taken"}""") }
        assertFailsWith<PipedError.AuthenticationFailure> { c.register("olli", "hunter2") }
        assertNull(store.token(baseUrl))
    }

    @Test
    fun `playlist description update uses PATCH like the frontend`() = runTest {
        // verified in usePlaylists.js changePlaylistDescription: method PATCH
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json(fixture("message_OK_SYNTHETIC.json")) }
        c.updatePlaylistDescription("pl-1", "new description")
        val req = recorded.single()
        assertEquals("PATCH", req.method.value)
        assertEquals("/user/playlists/description", req.url.encodedPath)
    }

    @Test
    fun `malformed opensearch payload maps to MalformedResponse`() = runTest {
        // a 200 body like ["lofi",{}] must surface as a typed error, not an
        // IllegalArgumentException escaping the taxonomy
        val c = client { json("""["lofi",{}]""") }
        assertFailsWith<PipedError.MalformedResponse> { c.suggestions("lofi") }
    }

    // ---------- account (token in Authorization header) ----------

    @Test
    fun `login posts credentials and stores the token under the instance`() = runTest {
        val store = InMemoryTokenStore()
        val c = client(tokenStore = store) { json(fixture("login_OK_SYNTHETIC.json")) }
        val token = c.login("olli", "hunter2")
        assertEquals("synthetic-token-0123456789abcdef", token)
        assertEquals(token, store.token(baseUrl))
        val req = recorded.single()
        assertEquals("/login", req.url.encodedPath)
        val body = (req.body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("\"username\":\"olli\""))
        assertTrue(body.contains("\"password\":\"hunter2\""))
    }

    @Test
    fun `login rejected with a 200 error body maps to AuthenticationFailure`() = runTest {
        // verified frontend behavior: LoginPage.vue reads resp.error on a 200
        val store = InMemoryTokenStore()
        val c = client(tokenStore = store) { json("""{"error":"wrong password"}""") }
        assertFailsWith<PipedError.AuthenticationFailure> { c.login("olli", "wrong") }
        assertNull(store.token(baseUrl), "no token may be stored on a failed login")
    }

    @Test
    fun `subscribe rejection surfaces the error body instead of silent success`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json("""{"error":"already subscribed"}""") }
        val resp = c.subscribe("UCXuqSBlHAE6Xw-yeJA0Tunw")
        assertEquals("already subscribed", resp.error)
    }

    @Test
    fun `authed endpoints send the Authorization header`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json(fixture("subscriptions_OK_SYNTHETIC.json")) }
        val subs = c.subscriptions()
        assertEquals(2, subs.size)
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", subs[0].channelId)
        assertEquals("tok-1", recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `authed call without a token fails before any request is made`() = runTest {
        val c = client { json("{}") }
        assertFailsWith<PipedError.AuthenticationFailure> { c.subscriptions() }
        assertTrue(recorded.isEmpty(), "no request may leave without a token")
    }

    @Test
    fun `logout posts with the token and clears it`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json(fixture("message_OK_SYNTHETIC.json")) }
        c.logout()
        assertNull(store.token(baseUrl))
        assertEquals("tok-1", recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `feed mirrors the verified frontend pattern authToken query param`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json(fixture("feed_OK_SYNTHETIC.json")) }
        val feed = c.feed()
        assertEquals(1, feed.size)
        // Verified frontend behavior (useSubscriptions.js fetchFeed): the
        // token goes in the authToken query parameter. The contracts README
        // prefers an Authorization header, but no live instance verified that
        // variant on 2026-11-18, so the client matches what is proven to work.
        assertEquals("tok-1", recorded.single().url.parameters["authToken"])
    }

    @Test
    fun `user playlists parse synthetic fixture`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { json(fixture("user_playlists_OK_SYNTHETIC.json")) }
        val playlists = c.userPlaylists()
        assertEquals(2, playlists.size)
        assertEquals("Watch Later", playlists[0].name)
        assertEquals(2, playlists[0].videos)
        assertNull(playlists[1].thumbnail)
        assertEquals(0, playlists[1].videos)
    }

    @Test
    fun `playlist management sends the documented request bodies`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { req ->
            when (req.url.encodedPath) {
                "/user/playlists/create" -> json("""{"playlistId":"new-id-1"}""")
                else -> json(fixture("message_OK_SYNTHETIC.json"))
            }
        }
        assertEquals("new-id-1", c.createPlaylist("My list"))
        c.deletePlaylist("new-id-1")
        c.renamePlaylist("new-id-1", "Renamed")
        c.addToPlaylist("new-id-1", listOf("v1", "v2"))
        c.removeFromPlaylist("new-id-1", 0)

        assertEquals("/user/playlists/create", recorded[0].url.encodedPath)
        assertTrue((recorded[0].body as io.ktor.http.content.TextContent).text.contains("\"name\":\"My list\""))
        assertEquals("/user/playlists/delete", recorded[1].url.encodedPath)
        assertTrue((recorded[1].body as io.ktor.http.content.TextContent).text.contains("\"playlistId\":\"new-id-1\""))
        assertEquals("/user/playlists/rename", recorded[2].url.encodedPath)
        assertTrue((recorded[2].body as io.ktor.http.content.TextContent).text.contains("\"newName\":\"Renamed\""))
        assertEquals("/user/playlists/add", recorded[3].url.encodedPath)
        assertTrue((recorded[3].body as io.ktor.http.content.TextContent).text.contains("\"videoIds\":[\"v1\",\"v2\"]"))
        assertEquals("/user/playlists/remove", recorded[4].url.encodedPath)
        assertTrue((recorded[4].body as io.ktor.http.content.TextContent).text.contains("\"index\":0"))
        recorded.forEach {
            assertEquals("tok-1", it.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `subscribe unsubscribe and subscribed status`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "tok-1")
        val c = client(tokenStore = store) { req ->
            when (req.url.encodedPath) {
                "/subscribed" -> json(fixture("subscribed_OK_SYNTHETIC.json"))
                else -> json(fixture("message_OK_SYNTHETIC.json"))
            }
        }
        c.subscribe("UCXuqSBlHAE6Xw-yeJA0Tunw")
        c.unsubscribe("UCXuqSBlHAE6Xw-yeJA0Tunw")
        assertTrue(c.subscribed("UCXuqSBlHAE6Xw-yeJA0Tunw"))
        assertEquals("/subscribe", recorded[0].url.encodedPath)
        assertEquals("/unsubscribe", recorded[1].url.encodedPath)
        assertTrue((recorded[0].body as io.ktor.http.content.TextContent).text.contains("\"channelId\":\"UCXuqSBlHAE6Xw-yeJA0Tunw\""))
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", recorded[2].url.parameters["channelId"])
    }

    @Test
    fun `config parses synthetic fixture`() = runTest {
        val c = client { json(fixture("config_OK_SYNTHETIC.json")) }
        val cfg = c.config()
        assertEquals("synthetic-1.0", cfg.version)
        assertEquals("https://example.com/privacy", cfg.privacyPolicyUrl)
    }

    // ---------- error taxonomy ----------

    @Test
    fun `401 maps to AuthenticationFailure`() = runTest {
        val store = InMemoryTokenStore()
        store.put(baseUrl, "expired")
        val c = client(tokenStore = store) {
            json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
        }
        assertFailsWith<PipedError.AuthenticationFailure> { c.subscriptions() }
    }

    @Test
    fun `404 maps to VideoUnavailable`() = runTest {
        val c = client { json("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertFailsWith<PipedError.VideoUnavailable> { c.streams("gone") }
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        val c = client { json("""{"error":"slow down"}""", HttpStatusCode.TooManyRequests) }
        assertFailsWith<PipedError.RateLimited> { c.trending("US") }
    }

    @Test
    fun `generic 500 without extraction signal maps to InstanceUnavailable`() = runTest {
        val c = client { json("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }
        val e = assertFailsWith<PipedError.InstanceUnavailable> { c.trending("US") }
        assertEquals(500, e.httpStatus)
    }

    @Test
    fun `malformed success body maps to MalformedResponse`() = runTest {
        val c = client { json("this is not json") }
        assertFailsWith<PipedError.MalformedResponse> { c.trending("US") }
    }

    @Test
    fun `network failure maps to NetworkUnavailable`() = runTest {
        val c = client { throw java.net.ConnectException("connection refused") }
        assertFailsWith<PipedError.NetworkUnavailable> { c.trending("US") }
    }

    @Test
    fun `redirects are not followed blindly`() = runTest {
        // Observed live on 2026-11-18: pipedapi.adminforge.de 301-redirects to
        // a non-API host. Blindly following could carry an Authorization
        // header to a different host, so the client surfaces it instead.
        val c = client {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://other.example/"))
        }
        assertFailsWith<PipedError.InstanceUnavailable> { c.trending("US") }
        assertEquals(1, recorded.size, "the redirect must not be followed")
    }
}

class InstanceUrlValidationTest {

    @Test
    fun `rejects non-http schemes`() {
        assertFailsWith<IllegalArgumentException> { PipedApiClient("ftp://pipedapi.example.com") }
    }

    @Test
    fun `rejects credentials in the URL`() {
        assertFailsWith<IllegalArgumentException> { PipedApiClient("https://user:pass@pipedapi.example.com") }
    }

    @Test
    fun `rejects query and fragment`() {
        assertFailsWith<IllegalArgumentException> { PipedApiClient("https://pipedapi.example.com/?x=1") }
        assertFailsWith<IllegalArgumentException> { PipedApiClient("https://pipedapi.example.com/#frag") }
    }

    @Test
    fun `rejects a path on the instance URL`() {
        assertFailsWith<IllegalArgumentException> { PipedApiClient("https://pipedapi.example.com/api/v1") }
    }

    @Test
    fun `trailing slash and host case are normalized`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = InMemoryTokenStore()
        store.put("https://pipedapi.example.com", "tok-1")
        val c = PipedApiClient("HTTPS://PipedAPI.Example.COM/", store, engine)
        c.trending("US")
        assertEquals("pipedapi.example.com", requests.single().url.host)
        // token stored under the normalized form is found by a client whose
        // base URL was written differently
        store.put("HTTPS://PipedAPI.Example.COM/", "tok-2")
        assertEquals("tok-2", store.token("https://pipedapi.example.com"))
    }

    @Test
    fun `self-hosted instance with a port is allowed`() {
        // the family may self-host; localhost, private ranges and the
        // tailnet CGNAT range must keep working over plain http
        PipedApiClient("http://192.168.1.222:8080")
        PipedApiClient("http://localhost:3000")
        PipedApiClient("http://10.0.0.2")
        PipedApiClient("http://100.64.1.5:8080")
        PipedApiClient("http://nas.local")
        // URI.getHost() keeps IPv6 brackets; compressed and expanded
        // loopback spellings must both be recognized
        PipedApiClient("http://[::1]:8080")
        PipedApiClient("http://[0:0:0:0:0:0:0:1]")
    }

    @Test
    fun `plain http is rejected for public hosts`() {
        // credentials and tokens are sent to the instance; http to a public
        // host would expose them on the wire
        assertFailsWith<IllegalArgumentException> { PipedApiClient("http://pipedapi.example.com") }
        assertFailsWith<IllegalArgumentException> { PipedApiClient("http://8.8.8.8") }
        assertFailsWith<IllegalArgumentException> { PipedApiClient("http://[2001:db8::1]") }
    }

    @Test
    fun `dot segments are rejected as ids`() = runTest {
        // percent-encoding cannot neutralize ".." - it would walk a path level
        val c = PipedApiClient("https://pipedapi.example.com")
        assertFailsWith<IllegalArgumentException> { c.streams("..") }
        assertFailsWith<IllegalArgumentException> { c.streams(".") }
    }

    @Test
    fun `sponsor categories are encoded as real JSON even with quotes`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            recorded += req
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val c = PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine)
        val nasty = listOf("sponsor", "we\"ird")
        c.sponsors("dQw4w9WgXcQ", nasty)
        val param = recorded.single().url.parameters["category"]!!
        // must round-trip as JSON, not break out of the array
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(param).jsonArray
        assertEquals(nasty, parsed.map { it.jsonPrimitive.content })
    }
}

class TokenIsolationTest {

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `a token is never sent to an instance it was not issued by`() = runTest {
        val store = InMemoryTokenStore()
        store.put("https://instance-a.example.com", "token-for-a")

        val requestsB = mutableListOf<HttpRequestData>()
        val clientB = PipedApiClient(
            "https://instance-b.example.com",
            store,
            MockEngine { req -> requestsB += req; json("[]") },
        )
        // B has no token of its own: the call must fail BEFORE any request
        assertFailsWith<PipedError.AuthenticationFailure> { clientB.subscriptions() }
        assertTrue(requestsB.isEmpty(), "instance B must never see instance A's token")
    }

    @Test
    fun `two clients on different instances share a store without leaking`() = runTest {
        val store = InMemoryTokenStore()
        store.put("https://instance-a.example.com", "token-for-a")
        store.put("https://instance-b.example.com", "token-for-b")

        val requests = mutableListOf<HttpRequestData>()
        fun mk(url: String) = PipedApiClient(
            url,
            store,
            MockEngine { req -> requests += req; json("[]") },
        )
        mk("https://instance-a.example.com").subscriptions()
        mk("https://instance-b.example.com").subscriptions()
        assertEquals("token-for-a", requests[0].headers[HttpHeaders.Authorization])
        assertEquals("token-for-b", requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a separate auth instance receives the account calls and the token`() = runTest {
        val store = InMemoryTokenStore()
        val requests = mutableListOf<HttpRequestData>()
        val c = PipedApiClient(
            "https://pipedapi.example.com",
            store,
            MockEngine { req ->
                requests += req
                when (req.url.encodedPath) {
                    "/login" -> json("""{"token":"tok-auth"}""")
                    "/subscriptions" -> json("[]")
                    "/trending" -> json("[]")
                    else -> json("{}")
                }
            },
            authBaseUrl = "https://auth.example.com",
        )
        c.login("olli", "hunter2")
        c.subscriptions()
        c.trending("US")
        // account traffic goes to the auth instance, discovery to the base
        assertEquals("auth.example.com", requests[0].url.host)
        assertEquals("auth.example.com", requests[1].url.host)
        assertEquals("pipedapi.example.com", requests[2].url.host)
        assertEquals("tok-auth", requests[1].headers[HttpHeaders.Authorization])
        assertNull(requests[2].headers[HttpHeaders.Authorization])
        // and the token is scoped to the auth instance, not the base one
        assertEquals("tok-auth", store.token("https://auth.example.com"))
        assertNull(store.token("https://pipedapi.example.com"))
    }
}
