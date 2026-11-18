import Foundation

// M1-B models mirroring clients/android/core-model/Models.kt. Both clients
// must parse the same fixtures (handoff spec §12). Fields are optional where
// the server is allowed to omit them; identity fields stay required.

public struct StreamItem: Codable, Equatable {
    public let url: String
    public let type: String?
    public let title: String?
    public let thumbnail: String?
    public let uploaderName: String?
    public let uploaderUrl: String?
    public let uploaderAvatar: String?
    public let uploadedDate: String?
    public let uploaded: Int64?
    public let duration: Int64? // -1 = live/unknown
    public let views: Int64?
    public let uploaderVerified: Bool?
    public let shortDescription: String?
    public let isShort: Bool?

    public var videoId: String {
        guard let range = url.range(of: "v=") else { return url }
        return String(url[range.upperBound...])
    }
}

public struct Comment: Codable, Equatable {
    public let author: String?
    public let commentId: String?
    public let commentText: String?
    public let commentedTime: String?
    public let likeCount: Int64?
    public let pinned: Bool?
    public let verified: Bool?
    public let channelOwner: Bool?
    public let creatorReplied: Bool?
    public let replyCount: Int64?
    public let thumbnail: String?
    public let repliesPage: String?
    public let hearted: Bool?
}

public struct CommentsResponse: Codable, Equatable {
    public let comments: [Comment]
    public let nextpage: String?
    public let commentCount: Int64?
    public let disabled: Bool?
}

public struct SponsorSegment: Codable, Equatable {
    public let segment: [Double]
    public let category: String?
    public let UUID: String?
    public let videoDuration: Double?
    public let actionType: String?
    public let description: String?
    public let locked: Int?
    public let votes: Int?
}

public struct SponsorsResponse: Codable, Equatable {
    public let segments: [SponsorSegment]?
}

public struct DeArrowTitle: Codable, Equatable {
    public let title: String
    public let original: Bool?
    public let votes: Int?
    public let locked: Bool?
    public let UUID: String?
}

public struct DeArrowThumbnail: Codable, Equatable {
    public let timestamp: Double?
    public let original: Bool?
    public let votes: Int?
    public let locked: Bool?
    public let UUID: String?
}

public struct DeArrowEntry: Codable, Equatable {
    public let titles: [DeArrowTitle]?
    public let thumbnails: [DeArrowThumbnail]?
}

public struct SearchResponse: Codable, Equatable {
    public let items: [SearchItem]
    public let nextpage: String?
    public let suggestion: String?
    public let corrected: Bool?
}

/// Piped returns a heterogeneous `items` list: channel and playlist entries
/// carry `name`, not `title`, so decoding them as stream items would fail the
/// whole response (see the Android SearchItemSerializer).
public enum SearchItem: Codable, Equatable {
    case stream(StreamSearchItem)
    case channel(ChannelSearchItem)
    case playlist(PlaylistSearchItem)
    /// Forward compatibility: an unmodelled type must not break decoding.
    case unknown(url: String, rawType: String)

    public struct StreamSearchItem: Codable, Equatable {
        public let url: String
        public let title: String?
        public let thumbnail: String?
        public let uploaderName: String?
        public let uploaderUrl: String?
        public let duration: Int64?
        public let views: Int64?
        public let uploadedDate: String?
        public let uploaded: Int64?
        public let uploaderVerified: Bool?
        public let isShort: Bool?
    }

    public struct ChannelSearchItem: Codable, Equatable {
        public let url: String
        public let name: String?
        public let thumbnail: String?
        public let itemDescription: String?
        public let subscribers: Int64?
        /// Piped calls the channel video count `videos` (ChannelItem.vue).
        public let videos: Int64?
        public let verified: Bool?

        private enum CodingKeys: String, CodingKey {
            case url, name, thumbnail, subscribers, videos, verified
            case itemDescription = "description"
        }
    }

    public struct PlaylistSearchItem: Codable, Equatable {
        public let url: String
        public let name: String?
        public let thumbnail: String?
        public let uploaderName: String?
        public let uploaderUrl: String?
        public let uploaderVerified: Bool?
        public let videos: Int64?
        public let itemDescription: String?

        private enum CodingKeys: String, CodingKey {
            case url, name, thumbnail, uploaderName, uploaderUrl, uploaderVerified, videos
            case itemDescription = "description"
        }
    }

    private enum Discriminator: String, CodingKey { case type, url }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Discriminator.self)
        let type = try container.decodeIfPresent(String.self, forKey: .type) ?? ""
        switch type {
        case "stream":
            self = .stream(try StreamSearchItem(from: decoder))
        case "channel":
            self = .channel(try ChannelSearchItem(from: decoder))
        case "playlist":
            self = .playlist(try PlaylistSearchItem(from: decoder))
        default:
            let url = try container.decodeIfPresent(String.self, forKey: .url) ?? ""
            self = .unknown(url: url, rawType: type)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .stream(let item): try container.encode(item)
        case .channel(let item): try container.encode(item)
        case .playlist(let item): try container.encode(item)
        case .unknown(let url, let rawType): try container.encode(["url": url, "type": rawType])
        }
    }
}

public struct ChannelResponse: Codable, Equatable {
    public let id: String?
    public let name: String?
    public let avatarUrl: String?
    public let bannerUrl: String?
    public let channelDescription: String?
    public let subscriberCount: Int64?
    public let verified: Bool?
    public let nextpage: String?
    public let relatedStreams: [StreamItem]?

    private enum CodingKeys: String, CodingKey {
        case id, name, avatarUrl, bannerUrl, subscriberCount, verified, nextpage, relatedStreams
        case channelDescription = "description"
    }
}

public struct PlaylistResponse: Codable, Equatable {
    public let name: String?
    public let playlistDescription: String?
    public let uploader: String?
    public let uploaderUrl: String?
    public let uploaderAvatar: String?
    public let videos: Int64?
    public let nextpage: String?
    public let relatedStreams: [StreamItem]?

    private enum CodingKeys: String, CodingKey {
        case name, uploader, uploaderUrl, uploaderAvatar, videos, nextpage, relatedStreams
        case playlistDescription = "description"
    }
}

public struct UserPlaylist: Codable, Equatable {
    public let id: String
    public let name: String
    public let shortDescription: String?
    public let thumbnail: String?
    public let videos: Int64?
}

/// One row of GET /subscriptions. The frontend keys on `url`
/// ("/channel/UC..."); the channel id is its last path segment.
public struct SubscriptionChannel: Codable, Equatable {
    public let url: String
    public let name: String
    public let avatar: String?
    public let verified: Bool?

    public var channelId: String {
        url.split(separator: "/").last.map(String.init) ?? url
    }
}

public struct SubscribedResponse: Codable, Equatable {
    public let subscribed: Bool?
}

/// Success body of /logout, /subscribe, /unsubscribe and the user-playlist
/// endpoints. A 200 can still carry a rejection in `error` (the frontend
/// checks `resp.error`), so callers must inspect it.
public struct MessageResponse: Codable, Equatable {
    public let message: String?
    public let error: String?
}

/// GET /config. The frontend reads these four fields; the rest are ignored.
public struct InstanceConfig: Codable, Equatable {
    public let version: String?
    public let donationUrl: String?
    public let statusPageUrl: String?
    public let privacyPolicyUrl: String?
}

/// 200 with `{"error": ...}` is how a rejected login comes back
/// (verified in LoginPage.vue).
public struct LoginResponse: Codable, Equatable {
    public let token: String?
    public let error: String?
}

public struct CreatePlaylistResponse: Codable, Equatable {
    public let playlistId: String?
}

/// Body of non-2xx API responses, e.g. extraction bot-block.
struct ApiErrorBody: Codable, Equatable {
    let error: String?
    let message: String?
}

// Request bodies.
struct LoginRequest: Codable { let username: String; let password: String }
struct SubscribeRequest: Codable { let channelId: String }
struct CreatePlaylistRequest: Codable { let name: String }
struct DeletePlaylistRequest: Codable { let playlistId: String }
struct RenamePlaylistRequest: Codable { let playlistId: String; let newName: String }
struct PlaylistDescriptionRequest: Codable { let playlistId: String; let description: String }
struct AddToPlaylistRequest: Codable { let playlistId: String; let videoIds: [String] }
struct RemoveFromPlaylistRequest: Codable { let playlistId: String; let index: Int }
struct DeleteAccountRequest: Codable { let password: String }
