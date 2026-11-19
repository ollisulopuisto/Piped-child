package fi.pipedchild.core.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * Thin Media3 assembly for the TV/tablet apps (architecture §7 core-player).
 * All decisions live in the pure, JVM-tested [StreamPicker] and
 * [ProxyRewriter]; this class only wires them into ExoPlayer objects, so it
 * stays free of logic that would need an emulator to verify.
 *
 * Every media request goes through [PipedProxyDataSource]: bytes only ever
 * come from the instance's media proxy.
 */
class PlayerManager(context: Context, private val proxyBase: String) {

    private val appContext = context.applicationContext

    fun createPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build()

    /** Media3 needs a non-null manifest URI; segment BaseURLs inside the
     *  generated MPD are absolute proxy URLs, so this placeholder never
     *  produces a request. */
    private fun generatedManifestItem(): MediaItem =
        MediaItem.Builder()
            .setUri("$proxyBase/generated.mpd")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()

    fun mediaSourceFor(choice: PlaybackChoice): MediaSource {
        val proxyFactory: DataSource.Factory = PipedProxyDataSource.Factory(proxyBase)
        return when (choice) {
            is PlaybackChoice.DashManifest -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(proxyFactory),
                DataSource.Factory { ByteArrayDataSource(choice.mpd.toByteArray(Charsets.UTF_8)) },
            ).createMediaSource(generatedManifestItem())

            is PlaybackChoice.DashUrl -> DashMediaSource.Factory(proxyFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(choice.url).setMimeType(MimeTypes.APPLICATION_MPD).build(),
                )

            is PlaybackChoice.Hls -> HlsMediaSource.Factory(proxyFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(choice.url).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                )

            is PlaybackChoice.Progressive -> ProgressiveMediaSource.Factory(proxyFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(choice.url).setMimeType(MimeTypes.VIDEO_MP4).build(),
                )
        }
    }
}
