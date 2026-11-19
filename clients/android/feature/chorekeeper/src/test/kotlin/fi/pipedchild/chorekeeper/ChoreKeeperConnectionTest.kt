package fi.pipedchild.chorekeeper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChoreKeeperConnectionTest {
    private val endpoint = ChoreKeeperEndpoint.parse("https://chores.example")

    @Test fun `disabled module never calls source`() = runTest {
        var calls = 0
        val connection = ChoreKeeperConnection { calls++; ConnectionStatus.Available }
        assertEquals(ConnectionStatus.Disabled, connection.check())
        assertEquals(0, calls)
    }

    @Test fun `configuration alone does not approve contact`() = runTest {
        var calls = 0
        val connection = ChoreKeeperConnection { calls++; ConnectionStatus.Available }
        connection.configure(endpoint)
        assertEquals(ConnectionStatus.NeedsConsent(endpoint), connection.check())
        assertEquals(0, calls)
        assertTrue(connection.approve(endpoint))
        assertEquals(ConnectionStatus.Available, connection.check())
        assertEquals(1, calls)
    }

    @Test fun `endpoint switch revokes permission and stale approval is refused`() = runTest {
        val contacted = mutableListOf<ChoreKeeperEndpoint>()
        val connection = ChoreKeeperConnection { contacted += it; ConnectionStatus.Available }
        connection.configure(endpoint)
        connection.approve(endpoint)
        connection.check()
        val other = ChoreKeeperEndpoint.parse("https://other.example")
        connection.configure(other)
        assertFalse(connection.approve(endpoint))
        assertEquals(ConnectionStatus.NeedsConsent(other), connection.check())
        assertEquals(listOf(endpoint), contacted)
    }

    @Test fun `disable requires fresh consent even for the same endpoint`() = runTest {
        val connection = ChoreKeeperConnection { error("must not contact") }
        connection.configure(endpoint)
        connection.approve(endpoint)
        connection.disable()
        assertEquals(ConnectionStatus.Disabled, connection.check())
        connection.configure(endpoint)
        assertEquals(ConnectionStatus.NeedsConsent(endpoint), connection.check())
    }

    @Test fun `same normalized configuration retains consent`() = runTest {
        val connection = ChoreKeeperConnection { ConnectionStatus.Available }
        connection.configure(endpoint)
        connection.approve(endpoint)
        connection.configure(ChoreKeeperEndpoint.parse("https://CHORES.example:443/"))
        assertEquals(ConnectionStatus.Available, connection.check())
    }

    @Test fun `stale completion after switch cannot report success`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val connection = ChoreKeeperConnection {
            started.complete(Unit)
            finish.await()
            ConnectionStatus.Available
        }
        connection.configure(endpoint)
        connection.approve(endpoint)
        val pending = async { connection.check() }
        started.await()
        val other = ChoreKeeperEndpoint.parse("https://other.example")
        connection.configure(other)
        finish.complete(Unit)
        assertEquals(ConnectionStatus.NeedsConsent(other), pending.await())
    }

    @Test fun `failures remain distinct and cancellation is not a failure`() = runTest {
        for (failure in listOf(ConnectionStatus.Unreachable, ConnectionStatus.AuthenticationRequired,
            ConnectionStatus.ApiError(404), ConnectionStatus.ApiError(503))) {
            val connection = ChoreKeeperConnection { failure }
            connection.configure(endpoint)
            connection.approve(endpoint)
            assertEquals(failure, connection.check())
        }
        val connection = ChoreKeeperConnection { throw CancellationException("stopped") }
        connection.configure(endpoint)
        connection.approve(endpoint)
        assertFailsWith<CancellationException> { connection.check() }
    }

    @Test fun `only credential-free HTTPS host roots are accepted`() {
        for (url in listOf("http://chores.example", "https://u:p@chores.example", "https://chores.example/api",
            "https://chores.example/?key=x", "https://chores.example/#x", "file:///etc/passwd", "https://chores.example:0")) {
            assertFailsWith<IllegalArgumentException>(url) { ChoreKeeperEndpoint.parse(url) }
        }
        assertEquals("https://chores.example", endpoint.url)
        assertEquals("https://chores.example:8443", ChoreKeeperEndpoint.parse("https://chores.example:8443/").url)
    }
}
