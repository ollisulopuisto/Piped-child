package fi.pipedchild.core.network

import fi.pipedchild.core.model.AddToPlaylistRequest
import fi.pipedchild.core.model.ApiErrorBody
import fi.pipedchild.core.model.ChannelResponse
import fi.pipedchild.core.model.CommentsResponse
import fi.pipedchild.core.model.CreatePlaylistRequest
import fi.pipedchild.core.model.CreatePlaylistResponse
import fi.pipedchild.core.model.DeArrowEntry
import fi.pipedchild.core.model.DeleteAccountRequest
import fi.pipedchild.core.model.DeletePlaylistRequest
import fi.pipedchild.core.model.InstanceConfig
import fi.pipedchild.core.model.LoginRequest
import fi.pipedchild.core.model.LoginResponse
import fi.pipedchild.core.model.MessageResponse
import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.model.PipedErrorMapper
import fi.pipedchild.core.model.PlaylistDescriptionRequest
import fi.pipedchild.core.model.PlaylistResponse
import fi.pipedchild.core.model.RegisterRequest
import fi.pipedchild.core.model.RemoveFromPlaylistRequest
import fi.pipedchild.core.model.RenamePlaylistRequest
import fi.pipedchild.core.model.SearchResponse
import fi.pipedchild.core.model.SponsorsResponse
import fi.pipedchild.core.model.StreamItem
import fi.pipedchild.core.model.StreamsResponse
import fi.pipedchild.core.model.SubscribeRequest
import fi.pipedchild.core.model.SubscribedResponse
import fi.pipedchild.core.model.SubscriptionChannel
import fi.pipedchild.core.model.UserPlaylist
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.nio.channels.UnresolvedAddressException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Search filters accepted by GET /search (contracts/api/README.md). */
enum class SearchFilter(val wire: String) {
    VIDEOS("videos"),
    CHANNELS("channels"),
    PLAYLISTS("playlists"),
    MUSIC_SONGS("music_songs"),
    MUSIC_VIDEOS("music_videos"),
    MUSIC_ALBUMS("music_albums"),
    MUSIC_ARTISTS("music_artists"),
}

/** Path aliases for channel pages (frontend router uses all three). */
enum class ChannelPath(val prefix: String) {
    ID("/channel/"),
    CUSTOM("/c/"),
    USER("/user/"),
}

/**
 * M1-B networking layer for the Android clients. Talks to one user-configured
 * Piped instance (bare paths, no /api/v1) plus, optionally, the separate
 * authentication instance the frontend supports (useApi.js authApiUrl()).
 *
 * Credentials:
 * - tokens live in [tokenStore], keyed by the instance that issued them;
 * - a token is attached ONLY to requests to its issuing instance, so this
 *   client can never leak credentials to a fallback host (spec §10);
 * - redirects are NOT followed: observed live on 2026-11-18, a broken
 *   instance list entry (adminforge.de) 301s to a non-API host, and following
 *   could carry an Authorization header along.
 *
 * Failures map to the shared [PipedError] taxonomy via [PipedErrorMapper]:
 * extraction failure (500 with bot-block body) is a normal, actionable state,
 * never a crash.
 */
class PipedApiClient(
    baseUrl: String,
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    engine: HttpClientEngine = CIO.create(),
    authBaseUrl: String? = null,
) : Closeable {

    /** Normalized base URL of the Piped instance this client talks to. */
    val baseUrl: String = normalizeInstanceUrl(baseUrl)

    /** Account endpoints go here; defaults to [baseUrl] like the frontend. */
    val authUrl: String = authBaseUrl?.let(::normalizeInstanceUrl) ?: this.baseUrl

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(ContentNegotiation) { json(this@PipedApiClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    override fun close() = client.close()

    /** Encodes one dynamic path segment so an id cannot break out of its
     *  path position ("/", "?", "#"). URLEncoder is form-style, so "+"
     *  becomes the path-correct "%20". */
    private fun String.pathSegment(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // ---------- discovery ----------

    suspend fun trending(region: String): List<StreamItem> =
        call(HttpMethod.Get, "$baseUrl/trending", params = mapOf("region" to region))

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.VIDEOS): SearchResponse =
        call(HttpMethod.Get, "$baseUrl/search", params = mapOf("q" to query, "filter" to filter.wire))

    suspend fun searchNextPage(query: String, filter: SearchFilter, nextpage: String): SearchResponse =
        call(
            HttpMethod.Get,
            "$baseUrl/nextpage/search",
            params = mapOf("q" to query, "filter" to filter.wire, "nextpage" to nextpage),
        )

    /** OpenSearch suggestions: the response is `["query", ["suggestion", ...]]`. */
    suspend fun suggestions(query: String): List<String> {
        val arr = call<JsonArray>(
            HttpMethod.Get,
            "$baseUrl/opensearch/suggestions",
            params = mapOf("query" to query),
        )
        if (arr.size < 2) throw PipedError.MalformedResponse("opensearch array too short (${arr.size} elements)")
        val list = arr[1] as? JsonArray
            ?: throw PipedError.MalformedResponse("opensearch second element is not an array")
        return list.map {
            (it as? JsonPrimitive)?.contentOrNull
                ?: throw PipedError.MalformedResponse("opensearch suggestion is not a string")
        }
    }

    // ---------- playback metadata ----------

    suspend fun streams(videoId: String): StreamsResponse =
        call(HttpMethod.Get, "$baseUrl/streams/${videoId.pathSegment()}")

    // ---------- social / metadata ----------

    suspend fun comments(videoId: String): CommentsResponse =
        call(HttpMethod.Get, "$baseUrl/comments/${videoId.pathSegment()}")

    suspend fun commentsNextPage(videoId: String, nextpage: String): CommentsResponse =
        call(HttpMethod.Get, "$baseUrl/nextpage/comments/${videoId.pathSegment()}", params = mapOf("nextpage" to nextpage))

    /** Instance-proxied SponsorBlock; never sponsor.ajay.app directly. */
    suspend fun sponsors(videoId: String, categories: List<String>): SponsorsResponse =
        call(
            HttpMethod.Get,
            "$baseUrl/sponsors/${videoId.pathSegment()}",
            params = mapOf("category" to categories.joinToString(",", "[", "]") { "\"$it\"" }),
        )

    /** Instance-proxied DeArrow. */
    suspend fun dearrow(videoIds: List<String>): Map<String, DeArrowEntry> =
        call(HttpMethod.Get, "$baseUrl/dearrow", params = mapOf("videoIds" to videoIds.joinToString(",")))

    suspend fun channel(idOrName: String, path: ChannelPath = ChannelPath.ID): ChannelResponse =
        call(HttpMethod.Get, "$baseUrl${path.prefix}${idOrName.pathSegment()}")

    suspend fun channelNextPage(id: String, nextpage: String): ChannelResponse =
        call(HttpMethod.Get, "$baseUrl/nextpage/channel/${id.pathSegment()}", params = mapOf("nextpage" to nextpage))

    /** Playlist content reads come from the AUTH instance (verified in
     *  usePlaylists.js getPlaylist: authApiUrl) but carry NO credentials, so
     *  a user-created playlist resolves while nothing leaks. */
    suspend fun playlist(playlistId: String): PlaylistResponse =
        call(HttpMethod.Get, "$authUrl/playlists/${playlistId.pathSegment()}")

    suspend fun playlistNextPage(playlistId: String, nextpage: String): PlaylistResponse =
        call(HttpMethod.Get, "$authUrl/nextpage/playlists/${playlistId.pathSegment()}", params = mapOf("nextpage" to nextpage))

    suspend fun config(): InstanceConfig =
        call(HttpMethod.Get, "$baseUrl/config")

    // ---------- account (Authorization: <token> header) ----------

    /**
     * Logs in and stores the token under [authUrl]. Returns the token.
     * A wrong password is a 200 with `{"error": ...}` (verified frontend
     * behavior, LoginPage.vue), which maps to AuthenticationFailure.
     */
    suspend fun login(username: String, password: String): String {
        val resp = call<LoginResponse>(
            HttpMethod.Post,
            "$authUrl/login",
            body = LoginRequest(username, password),
        )
        val token = resp.token
            ?: if (resp.error != null) throw PipedError.AuthenticationFailure
            else throw PipedError.MalformedResponse("/login returned no token")
        tokenStore.put(authUrl, token)
        return token
    }

    /**
     * Registers and stores the issued session token under [authUrl] (verified
     * in RegisterPage.vue: a successful register returns {token} and the
     * frontend stores it like a login). A 200 with `{"error": ...}` is a
     * rejection and maps to AuthenticationFailure.
     */
    suspend fun register(username: String, password: String): String {
        val resp = call<LoginResponse>(
            HttpMethod.Post,
            "$authUrl/register",
            body = RegisterRequest(username, password),
        )
        val token = resp.token
            ?: if (resp.error != null) throw PipedError.AuthenticationFailure
            else throw PipedError.MalformedResponse("/register returned no token")
        tokenStore.put(authUrl, token)
        return token
    }

    /** Invalidates the session server-side and drops the local token. */
    suspend fun logout(): MessageResponse {
        val resp = call<MessageResponse>(HttpMethod.Post, "$authUrl/logout", auth = true)
        tokenStore.clear(authUrl)
        return resp
    }

    /** The local token is dropped only when the server confirms deletion. */
    suspend fun deleteAccount(password: String): MessageResponse {
        val resp = call<MessageResponse>(HttpMethod.Post, "$authUrl/user/delete", body = DeleteAccountRequest(password), auth = true)
        if (resp.error == null) tokenStore.clear(authUrl)
        return resp
    }

    /**
     * Authenticated feed. Mirrors the VERIFIED frontend pattern: the token
     * goes in the `authToken` query parameter (useSubscriptions.js fetchFeed).
     * The contracts README prefers an Authorization header, but that variant
     * was not verified against a live instance on 2026-11-18, so the client
     * matches what is proven to work. Revisit when a live check lands.
     */
    suspend fun feed(): List<StreamItem> {
        val token = tokenStore.token(authUrl) ?: throw PipedError.AuthenticationFailure
        return call(HttpMethod.Get, "$authUrl/feed", params = mapOf("authToken" to token))
    }

    suspend fun subscriptions(): List<SubscriptionChannel> =
        call(HttpMethod.Get, "$authUrl/subscriptions", auth = true)

    suspend fun subscribed(channelId: String): Boolean =
        call<SubscribedResponse>(
            HttpMethod.Get,
            "$authUrl/subscribed",
            params = mapOf("channelId" to channelId),
            auth = true,
        ).subscribed

    /**
     * A 200 with `{"error": ...}` is how a rejection comes back here
     * (verified in useSubscriptions.js: `!resp.error`), so callers get the
     * body to inspect rather than a silent success.
     */
    suspend fun subscribe(channelId: String): MessageResponse =
        call(HttpMethod.Post, "$authUrl/subscribe", body = SubscribeRequest(channelId), auth = true)

    suspend fun unsubscribe(channelId: String): MessageResponse =
        call(HttpMethod.Post, "$authUrl/unsubscribe", body = SubscribeRequest(channelId), auth = true)

    suspend fun userPlaylists(): List<UserPlaylist> =
        call(HttpMethod.Get, "$authUrl/user/playlists", auth = true)

    /** Returns the new playlist's id. */
    suspend fun createPlaylist(name: String): String =
        call<CreatePlaylistResponse>(
            HttpMethod.Post,
            "$authUrl/user/playlists/create",
            body = CreatePlaylistRequest(name),
            auth = true,
        ).playlistId ?: throw PipedError.MalformedResponse("create playlist returned no id")

    suspend fun deletePlaylist(playlistId: String): MessageResponse =
        call(
            HttpMethod.Post,
            "$authUrl/user/playlists/delete",
            body = DeletePlaylistRequest(playlistId),
            auth = true,
        )

    suspend fun renamePlaylist(playlistId: String, newName: String): MessageResponse =
        call(
            HttpMethod.Post,
            "$authUrl/user/playlists/rename",
            body = RenamePlaylistRequest(playlistId, newName),
            auth = true,
        )

    suspend fun updatePlaylistDescription(playlistId: String, description: String): MessageResponse =
        call(
            // verified in usePlaylists.js changePlaylistDescription: PATCH
            HttpMethod.Patch,
            "$authUrl/user/playlists/description",
            body = PlaylistDescriptionRequest(playlistId, description),
            auth = true,
        )

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): MessageResponse =
        call(
            HttpMethod.Post,
            "$authUrl/user/playlists/add",
            body = AddToPlaylistRequest(playlistId, videoIds),
            auth = true,
        )

    suspend fun removeFromPlaylist(playlistId: String, index: Int): MessageResponse =
        call(
            HttpMethod.Post,
            "$authUrl/user/playlists/remove",
            body = RemoveFromPlaylistRequest(playlistId, index),
            auth = true,
        )

    // ---------- plumbing ----------

    private fun HttpRequestBuilder.applyAuth() {
        val token = tokenStore.token(authUrl) ?: throw PipedError.AuthenticationFailure
        header(HttpHeaders.Authorization, token)
    }

    private suspend fun errorFrom(response: HttpResponse): PipedError {
        val body = try {
            response.body<ApiErrorBody>()
        } catch (e: Exception) {
            null
        }
        return PipedErrorMapper.fromHttp(response.status.value, body)
    }

    private suspend inline fun <reified T> call(
        method: HttpMethod,
        url: String,
        params: Map<String, String> = emptyMap(),
        body: Any? = null,
        auth: Boolean = false,
    ): T {
        val response = try {
            client.request(url) {
                this.method = method
                params.forEach { (k, v) -> parameter(k, v) }
                if (auth) applyAuth()
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: PipedError) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw PipedError.NetworkUnavailable
        } catch (e: ConnectTimeoutException) {
            throw PipedError.NetworkUnavailable
        } catch (e: SocketTimeoutException) {
            throw PipedError.NetworkUnavailable
        } catch (e: UnresolvedAddressException) {
            throw PipedError.NetworkUnavailable
        } catch (e: IOException) {
            throw PipedError.NetworkUnavailable
        }
        if (response.status.value !in 200..299) throw errorFrom(response)
        return try {
            response.body()
        } catch (e: PipedError) {
            throw e
        } catch (e: IOException) {
            throw PipedError.NetworkUnavailable
        } catch (e: Exception) {
            throw PipedError.MalformedResponse(e.message ?: "unparseable response body")
        }
    }
}
