package fi.pipedchild.core.player

import androidx.media3.common.C
import androidx.media3.common.Player

/** One tick only. The UI lifecycle owns scheduling and cancelling ticks. */
fun applySponsorSkip(player: Player, engine: SponsorSkipEngine) {
    if (!player.isPlaying || player.isPlayingAd || player.isCurrentMediaItemLive ||
        !player.isCurrentMediaItemSeekable || !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
    val duration = player.duration
    if (duration == C.TIME_UNSET) return
    engine.targetMs(player.currentPosition, duration)?.let { player.seekTo(it) }
}
