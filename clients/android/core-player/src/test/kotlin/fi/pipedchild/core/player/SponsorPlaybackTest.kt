package fi.pipedchild.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import fi.pipedchild.core.model.SponsorSegment
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SponsorPlaybackTest {
    @Test fun `actual Player interface seeks VOD once and refuses paused ad live unknown or unseekable timelines`() {
        for (blocked in listOf("", "paused", "ad", "live", "unseekable", "command", "unknown")) {
            val seeks = mutableListOf<Long>()
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                when (method.name) {
                    "isPlaying" -> blocked != "paused"
                    "isPlayingAd" -> blocked == "ad"
                    "isCurrentMediaItemLive" -> blocked == "live"
                    "isCurrentMediaItemSeekable" -> blocked != "unseekable"
                    "isCommandAvailable" -> blocked != "command"
                    "getDuration" -> if (blocked == "unknown") C.TIME_UNSET else 60000L
                    "getCurrentPosition" -> 15000L
                    "seekTo" -> { seeks += args!![0] as Long; null }
                    else -> error("Unexpected Player call: ${method.name}")
                }
            } as Player
            val engine = SponsorSkipEngine(listOf(SponsorSegment(listOf(10.0, 20.0), "sponsor")))
            applySponsorSkip(player, engine)
            applySponsorSkip(player, engine)
            assertEquals(blocked, if (blocked.isEmpty()) listOf(20000L) else emptyList<Long>(), seeks)
        }
    }
}
