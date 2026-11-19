package fi.pipedchild.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull

/** Discovery list item (trending, related, search stream results). See clients/contracts/api. */
@Serializable
data class StreamItem(
    val url: String,
    val type: String = "stream",
    val title: String,
    val thumbnail: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val uploadedDate: String? = null,
    val uploaded: Long = -1,
    val duration: Long = -1, // -1 = live/unknown
    val views: Long = -1,
    val uploaderVerified: Boolean = false,
    val shortDescription: String? = null,
    val isShort: Boolean = false,
) {
    val videoId: String
        get() = url.substringAfter("v=", url)
}

/** Search result item. Piped returns a heterogeneous `items` list: "stream",
 *  "channel" and "playlist" entries carry different fields — channel and
 *  playlist items have `name`, not `title`, so decoding them as StreamItem
 *  fails the whole response even with ignoreUnknownKeys. */
@Serializable(with = SearchItemSerializer::class)
sealed class SearchItem {
    abstract val url: String

    @Serializable
    data class Stream(
        override val url: String,
        val title: String,
        val thumbnail: String? = null,
        val uploaderName: String? = null,
        val uploaderUrl: String? = null,
        val uploaderAvatar: String? = null,
        val uploadedDate: String? = null,
        val uploaded: Long = -1,
        val duration: Long = -1, // -1 = live/unknown
        val views: Long = -1,
        val uploaderVerified: Boolean = false,
        val shortDescription: String? = null,
        val isShort: Boolean = false,
        // Encoded so a SearchResponse survives an encode/decode round-trip
        // (JsonContentPolymorphicSerializer adds no discriminator itself).
        @kotlinx.serialization.Required val type: String = "stream",
    ) : SearchItem() {
        val videoId: String
            get() = url.substringAfter("v=", url)
    }

    @Serializable
    data class Channel(
        override val url: String,
        val name: String,
        val thumbnail: String? = null,
        val description: String? = null,
        val subscribers: Long = -1,
        // Piped calls the channel video count `videos` (see frontend
        // ChannelItem.vue), NOT `videosCount`.
        val videos: Long = -1,
        val verified: Boolean = false,
        @kotlinx.serialization.Required val type: String = "channel",
    ) : SearchItem()

    @Serializable
    data class Playlist(
        override val url: String,
        val name: String,
        val thumbnail: String? = null,
        val uploaderName: String? = null,
        val uploaderUrl: String? = null,
        val uploaderVerified: Boolean = false,
        val videos: Long = -1,
        val description: String? = null,
        @kotlinx.serialization.Required val type: String = "playlist",
    ) : SearchItem()

    /** Forward compatibility: an unmodelled type must not break decoding. */
    data class Unknown(override val url: String, val rawType: String) : SearchItem()
}

object SearchItemSerializer :
    kotlinx.serialization.json.JsonContentPolymorphicSerializer<SearchItem>(SearchItem::class) {
    override fun selectDeserializer(
        element: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.DeserializationStrategy<SearchItem> {
        val obj = element as kotlinx.serialization.json.JsonObject
        fun field(name: String) =
            (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        return when (field("type")) {
            "stream" -> SearchItem.Stream.serializer()
            "channel" -> SearchItem.Channel.serializer()
            "playlist" -> SearchItem.Playlist.serializer()
            else -> object : kotlinx.serialization.KSerializer<SearchItem.Unknown> {
                override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
                    "SearchItem.Unknown", kotlinx.serialization.descriptors.PrimitiveKind.STRING,
                )
                override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) =
                    SearchItem.Unknown(
                        url = field("url").orEmpty(),
                        rawType = field("type").orEmpty(),
                    )
                override fun serialize(
                    encoder: kotlinx.serialization.encoding.Encoder,
                    value: SearchItem.Unknown,
                ) = encoder.encodeString(value.rawType + ":" + value.url)
            }
        }
    }
}

@Serializable
data class SearchResponse(
    val items: List<SearchItem> = emptyList(),
    val nextpage: String? = null,
    val suggestion: String? = null,
    val corrected: Boolean? = null,
)

@Serializable
data class PipedStream(
    val url: String,
    val format: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val mimeType: String? = null,
    val itag: Int? = null,
    val bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val videoOnly: Boolean = false,
    val initStart: Long = 0,
    val initEnd: Long = 0,
    val indexStart: Long = 0,
    val indexEnd: Long = 0,
    val audioTrackId: String? = null,
    val audioTrackLocale: String? = null,
    val audioTrackName: String? = null,
    val audioTrackType: String? = null,
    val contentLength: Long = 0,
)

@Serializable
data class Subtitle(
    val name: String,
    val code: String,
    val url: String,
    val mimeType: String? = null,
    val autoGenerated: Boolean = false,
)

@Serializable
data class Chapter(val title: String, val start: Double = 0.0, val image: String? = null)

@Serializable
data class PreviewFrames(
    val urls: List<String> = emptyList(),
    val totalCount: Int = 0,
    val durationPerFrame: Int = 0,
    val framesPerPageX: Int = 0,
    val framesPerPageY: Int = 0,
)

@Serializable
data class StreamsResponse(
    val title: String? = null,
    val description: String? = null,
    val uploadDate: String? = null,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val uploaderVerified: Boolean = false,
    val uploaderSubscriberCount: Long = 0,
    val duration: Long = 0,
    val views: Long = 0,
    val likes: Long = 0,
    val dislikes: Long = 0,
    val visibility: String? = null,
    val category: String? = null,
    val license: String? = null,
    val livestream: Boolean = false,
    val dash: String? = null,
    val hls: String? = null,
    val lbryId: String? = null,
    /** Base URL of the instance's media proxy (verified live against
     *  pipedapi.ducks.party on 2026-11-19: field name is `proxyUrl`, a
     *  separate host from the API, e.g. piped-proxy.ducks.party). Media
     *  bytes flow through it, so it is a distinct consent destination. */
    val proxyUrl: String? = null,
    val videoStreams: List<PipedStream> = emptyList(),
    val audioStreams: List<PipedStream> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val previewFrames: List<PreviewFrames> = emptyList(),
    val relatedStreams: List<StreamItem> = emptyList(),
)

@Serializable
data class Comment(
    val author: String? = null,
    val commentId: String? = null,
    val commentText: String? = null,
    val commentedTime: String? = null,
    val likeCount: Long = 0,
    val pinned: Boolean = false,
    val verified: Boolean = false,
    val channelOwner: Boolean = false,
    val creatorReplied: Boolean = false,
    val replyCount: Long = 0,
    val thumbnail: String? = null,
    val repliesPage: String? = null,
    val hearted: Boolean = false,
)

@Serializable
data class CommentsResponse(
    val comments: List<Comment> = emptyList(),
    val nextpage: String? = null,
    val commentCount: Long = 0,
    val disabled: Boolean = false,
)

@Serializable
data class SponsorSegment(
    val segment: List<Double>,
    val category: String? = null,
    val UUID: String? = null,
    val videoDuration: Double = 0.0,
    val actionType: String? = null,
    val description: String? = null,
    val locked: Int = 0,
    val votes: Int = 0,
)

@Serializable
data class SponsorsResponse(
    val segments: List<SponsorSegment> = emptyList(),
)

@Serializable
data class DeArrowTitle(
    val title: String,
    val original: Boolean = false,
    val votes: Int = 0,
    val locked: Boolean = false,
    val UUID: String? = null,
)

@Serializable
data class DeArrowThumbnail(
    val timestamp: Double? = null,
    val original: Boolean = false,
    val votes: Int = 0,
    val locked: Boolean = false,
    val UUID: String? = null,
)

@Serializable
data class DeArrowEntry(
    val titles: List<DeArrowTitle> = emptyList(),
    val thumbnails: List<DeArrowThumbnail> = emptyList(),
)

/** Body of non-2xx API responses, e.g. extraction bot-block. */
@Serializable
data class ApiErrorBody(
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

/** 200 with `{"error": ...}` is how a rejected login comes back (LoginPage.vue). */
@Serializable
data class LoginResponse(val token: String? = null, val error: String? = null)

@Serializable
data class ChannelResponse(
    val id: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String? = null,
    val subscriberCount: Long = 0,
    val verified: Boolean = false,
    val nextpage: String? = null,
    val relatedStreams: List<StreamItem> = emptyList(),
)

@Serializable
data class PlaylistResponse(
    val name: String? = null,
    val description: String? = null,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val videos: Long = 0,
    val nextpage: String? = null,
    val relatedStreams: List<StreamItem> = emptyList(),
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val shortDescription: String? = null,
    val thumbnail: String? = null,
    val videos: Long = 0,
)

/** One row of GET /subscriptions. The frontend keys on `url`
 *  ("/channel/UC..."); the channel id is its last path segment. */
@Serializable
data class SubscriptionChannel(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val verified: Boolean = false,
) {
    val channelId: String
        get() = url.substringAfterLast("/")
}

@Serializable
data class SubscribedResponse(val subscribed: Boolean = false)

/** Success body of /logout, /subscribe, /unsubscribe and the user-playlist endpoints. */
@Serializable
data class MessageResponse(
    val message: String? = null,
    val error: String? = null,
)

/** GET /config. The frontend reads these four fields; the rest are ignored. */
@Serializable
data class InstanceConfig(
    val version: String? = null,
    val donationUrl: String? = null,
    val statusPageUrl: String? = null,
    val privacyPolicyUrl: String? = null,
)

@Serializable
data class CreatePlaylistResponse(val playlistId: String? = null)

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class SubscribeRequest(val channelId: String)

@Serializable
data class CreatePlaylistRequest(val name: String)

@Serializable
data class DeletePlaylistRequest(val playlistId: String)

@Serializable
data class RenamePlaylistRequest(val playlistId: String, val newName: String)

@Serializable
data class PlaylistDescriptionRequest(val playlistId: String, val description: String)

@Serializable
data class AddToPlaylistRequest(val playlistId: String, val videoIds: List<String>)

@Serializable
data class RemoveFromPlaylistRequest(val playlistId: String, val index: Int)
