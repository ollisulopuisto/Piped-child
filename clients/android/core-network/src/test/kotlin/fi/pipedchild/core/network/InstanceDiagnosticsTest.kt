package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InstanceDiagnosticsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun `construction is quiet and discovery alone never chooses a sample video`() = runTest {
        val paths = mutableListOf<String>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("FI", request.url.parameters["region"])
            respond("[]", HttpStatusCode.OK, json)
        })
        client.use {
            val probe = InstanceDiagnostics(client)
            assertTrue(paths.isEmpty())
            val result = probe.check("FI")
            assertEquals("https://api.example", result.instanceUrl)
            assertEquals(EndpointDiagnostic.Responded, result.discovery)
            assertEquals(EndpointDiagnostic.NotChecked, result.streamMetadata)
            assertEquals(listOf("/trending"), paths)
        }
    }

    @Test fun `explicit sample probes metadata only on the configured instance`() = runTest {
        val paths = mutableListOf<String>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            assertEquals("api.example", request.url.host)
            paths += request.url.encodedPath
            // Foreign media URLs are data, never follow-up requests.
            respond(if (paths.size == 1) "[]" else
                """{"hls":"https://media.example/master.m3u8","thumbnailUrl":"https://images.example/x.png"}""",
                HttpStatusCode.OK, json)
        })
        client.use {
            val result = InstanceDiagnostics(it).check("US", "a/b?c")
            assertEquals(EndpointDiagnostic.Responded, result.discovery)
            assertEquals(EndpointDiagnostic.Responded, result.streamMetadata)
            assertEquals(listOf("/trending", "/streams/a%2Fb%3Fc"), paths)
        }
    }

    @Test fun `successful discovery does not hide extraction failure or retry`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            count++
            if (count == 1) respond("[]", HttpStatusCode.OK, json)
            else respond("""{"message":"LOGIN_REQUIRED"}""", HttpStatusCode.InternalServerError, json)
        })
        client.use {
            val result = InstanceDiagnostics(it).check("US", "selected")
            assertEquals(EndpointDiagnostic.Responded, result.discovery)
            assertIs<PipedError.ExtractionFailure>(assertIs<EndpointDiagnostic.Failed>(result.streamMetadata).error)
            assertEquals(2, count)
        }
    }

    @Test fun `discovery failure and metadata success remain separate`() = runTest {
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            if (request.url.encodedPath == "/trending")
                respond("{}", HttpStatusCode.TooManyRequests, json)
            else respond("{}", HttpStatusCode.OK, json)
        })
        client.use {
            val result = InstanceDiagnostics(it).check("US", "selected")
            assertEquals(PipedError.RateLimited, assertIs<EndpointDiagnostic.Failed>(result.discovery).error)
            assertEquals(EndpointDiagnostic.Responded, result.streamMetadata)
        }
    }

    @Test fun `saved tokens are never sent and redirects never expand the audience`() = runTest {
        var count = 0
        val tokens = InMemoryTokenStore().also { it.put("https://api.example", "private-token") }
        val client = PipedApiClient("https://api.example", tokens, MockEngine { request ->
            count++
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("api.example", request.url.host)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/trending"))
        })
        client.use {
            assertIs<EndpointDiagnostic.Failed>(InstanceDiagnostics(it).check("US").discovery)
            assertEquals(1, count)
        }
    }

    @Test fun `cancellation propagates and prevents the second endpoint request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            count++
            started.complete(Unit)
            awaitCancellation()
        })
        client.use {
            val pending = async { InstanceDiagnostics(it).check("US", "selected") }
            withTimeout(10000) { started.await() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, count)
        }
    }

    @Test fun `malformed data and transport failures stay typed and are not hidden`() = runTest {
        for (transportFailure in listOf(false, true)) {
            val client = PipedApiClient("https://api.example", engine = MockEngine {
                if (transportFailure) throw java.io.IOException("offline")
                respond("not JSON", HttpStatusCode.OK, json)
            })
            client.use {
                val result = InstanceDiagnostics(it).check("US")
                val error = assertIs<EndpointDiagnostic.Failed>(result.discovery).error
                if (transportFailure) assertEquals(PipedError.NetworkUnavailable, error)
                else assertIs<PipedError.MalformedResponse>(error)
                assertEquals(EndpointDiagnostic.NotChecked, result.streamMetadata)
            }
        }
    }

    @Test fun `programming failures propagate rather than manufacturing an endpoint verdict`() = runTest {
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            throw IllegalStateException("test programming failure")
        })
        client.use {
            assertFailsWith<IllegalStateException> { InstanceDiagnostics(it).check("US") }
        }
    }

    @Test fun `invalid input fails before any endpoint receives a request`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine { count++; error("unexpected") })
        client.use {
            val probe = InstanceDiagnostics(it)
            assertFailsWith<IllegalArgumentException> { probe.check(" ", "video") }
            for (id in listOf("", " ", ".", "..")) {
                assertFailsWith<IllegalArgumentException> { probe.check("US", id) }
            }
            assertEquals(0, count)
        }
    }
}
