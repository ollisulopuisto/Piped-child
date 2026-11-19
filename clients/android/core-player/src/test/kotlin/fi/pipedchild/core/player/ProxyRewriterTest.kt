package fi.pipedchild.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProxyRewriter against the web frontend's shaka request filter
 * (VideoPlayer.vue, verified 2026-11-19): googlevideo hosts are rewritten
 * through the instance proxy with a host parameter, Range headers become a
 * range parameter, and every other host is blocked outright.
 */
class ProxyRewriterTest {

    private val proxy = "https://piped-proxy.ducks.party"

    @Test
    fun `googlevideo url is rewritten through the proxy with host param`() {
        val r = ProxyRewriter.resolve(
            "https://rr2---sn-abc.googlevideo.com/videoplayback?itag=137&expire=1",
            proxy,
            null,
        )
        assertEquals(
            ProxyRewriter.Result.Open(
                "https://piped-proxy.ducks.party/videoplayback?itag=137&expire=1&host=rr2---sn-abc.googlevideo.com",
                rangeConverted = false,
            ),
            r,
        )
    }

    @Test
    fun `already-proxied url passes through unchanged`() {
        val url = "https://piped-proxy.ducks.party/videoplayback?itag=18"
        assertEquals(
            ProxyRewriter.Result.Open(url, rangeConverted = false),
            ProxyRewriter.resolve(url, proxy, null),
        )
    }

    @Test
    fun `range header becomes a query parameter and the header is dropped`() {
        // the frontend: url.searchParams.set("range", headers.Range.split("=")[1])
        val r = ProxyRewriter.resolve(
            "https://rr1.googlevideo.com/videoplayback?itag=18",
            proxy,
            "bytes=1024-2047",
        )
        assertEquals(
            ProxyRewriter.Result.Open(
                "https://piped-proxy.ducks.party/videoplayback?itag=18&host=rr1.googlevideo.com&range=1024-2047",
                rangeConverted = true,
            ),
            r,
        )
    }

    @Test
    fun `range conversion also applies to already-proxied requests`() {
        val r = ProxyRewriter.resolve(
            "https://piped-proxy.ducks.party/videoplayback?itag=18",
            proxy,
            "bytes=0-",
        )
        assertEquals(
            ProxyRewriter.Result.Open(
                "https://piped-proxy.ducks.party/videoplayback?itag=18&range=0-",
                rangeConverted = true,
            ),
            r,
        )
    }

    @Test
    fun `LBRY cdn is blocked, never leaked`() {
        assertTrue(
            ProxyRewriter.resolve("https://player.odycdn.com/stream.mp4", proxy, null)
                is ProxyRewriter.Result.Blocked,
        )
    }

    @Test
    fun `arbitrary third-party host is blocked`() {
        assertTrue(
            ProxyRewriter.resolve("https://tracker.example.com/pixel.gif", proxy, null)
                is ProxyRewriter.Result.Blocked,
        )
    }

    @Test
    fun `garbage url is blocked, not crashed on`() {
        assertTrue(
            ProxyRewriter.resolve("not a url ::::", proxy, null) is ProxyRewriter.Result.Blocked,
        )
    }

    @Test
    fun `relative URLs are rejected before opening`() {
        assertEquals(
            ProxyRewriter.Result.Blocked("/api/v1/thing"),
            ProxyRewriter.resolve("/api/v1/thing", proxy, null),
        )
    }

    @Test
    fun `unparseable proxy base blocks everything`() {
        assertTrue(
            ProxyRewriter.resolve("https://rr1.googlevideo.com/videoplayback", "::", null)
                is ProxyRewriter.Result.Blocked,
        )
    }

    @Test
    fun `scheme port credentials and lookalike hosts cannot bypass consent`() {
        listOf("http://piped-proxy.ducks.party/videoplayback",
            "https://piped-proxy.ducks.party:444/videoplayback",
            "https://piped-proxy.ducks.party.evil.example/videoplayback",
            "https://rr1.googlevideo.com.evil.example/videoplayback",
            "https://user:password@piped-proxy.ducks.party/videoplayback",
            "file:///tmp/movie", "//outside.example/movie", "/segment.ts").forEach {
            assertTrue(it, ProxyRewriter.resolve(it, proxy, null) is ProxyRewriter.Result.Blocked)
        }
    }

    @Test
    fun `signed path escapes survive rewriting and existing parameters are replaced`() {
        assertEquals(
            ProxyRewriter.Result.Open(
                "https://piped-proxy.ducks.party/prefix/videoplayback?sig=a%2Bb&host=rr1.googlevideo.com&range=0-15", true),
            ProxyRewriter.resolve("https://rr1.googlevideo.com/videoplayback?sig=a%2Bb&host=old&range=old",
                "$proxy/prefix", "bytes=0-15"),
        )
        assertEquals(
            ProxyRewriter.Result.Open("https://piped-proxy.ducks.party/a%2Fb?sig=a%2Bb&host=rr1.googlevideo.com", false),
            ProxyRewriter.resolve("https://rr1.googlevideo.com/a%2Fb?sig=a%2Bb", proxy, null),
        )
    }

    @Test
    fun `proxy base rejects plaintext credentials and query destinations`() {
        listOf("http://proxy.example", "https://user:pass@proxy.example", "https://proxy.example?x=1",
            "https://proxy.example#fragment").forEach { assertEquals(null, ProxyRewriter.validProxyBase(it)) }
    }
}
