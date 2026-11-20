import Foundation

/// Error taxonomy per handoff spec §10, mirrored from the Android
/// core-model PipedError. Every failure maps to exactly one case; the UI
/// surfaces an actionable state, never a crash.
public enum PipedError: Error, Equatable {
    case networkUnavailable
    case instanceUnavailable(httpStatus: Int)
    /// 500 from /streams with bot-block/extraction message. Distinct from
    /// a network failure: the remedy is another instance, not a retry.
    case extractionFailure(serverMessage: String)
    case authenticationFailure
    case videoUnavailable
    case playbackFormatUnsupported
    case rateLimited
    case malformedResponse(detail: String)
}

public struct PipedStream: Codable, Equatable {
    public let url: String
    public let format: String?
    public let quality: String?
    public let codec: String?
    public let mimeType: String?
    public let itag: Int?
    public let bitrate: Int64
    public let width: Int
    public let height: Int
    public let fps: Int
    public let videoOnly: Bool
    public let initStart: Int64
    public let initEnd: Int64
    public let indexStart: Int64
    public let indexEnd: Int64
    public let audioTrackId: String?
    public let audioTrackLocale: String?
    public let audioTrackName: String?
    public let audioTrackType: String?
    public let contentLength: Int64

    // Initializer defaults are not applied by synthesized Decodable. Default
    // only absent keys; explicit null/wrong types for required values still fail.
    private enum CodingKeys: String, CodingKey {
        case url, format, quality, codec, mimeType, itag, bitrate, width, height, fps
        case videoOnly, initStart, initEnd, indexStart, indexEnd, audioTrackId
        case audioTrackLocale, audioTrackName, audioTrackType, contentLength
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        url = try values.decode(String.self, forKey: .url)
        format = try values.decodeIfPresent(String.self, forKey: .format)
        quality = try values.decodeIfPresent(String.self, forKey: .quality)
        codec = try values.decodeIfPresent(String.self, forKey: .codec)
        mimeType = try values.decodeIfPresent(String.self, forKey: .mimeType)
        itag = try values.decodeIfPresent(Int.self, forKey: .itag)
        bitrate = try values.contains(.bitrate) ? values.decode(Int64.self, forKey: .bitrate) : 0
        width = try values.contains(.width) ? values.decode(Int.self, forKey: .width) : 0
        height = try values.contains(.height) ? values.decode(Int.self, forKey: .height) : 0
        fps = try values.contains(.fps) ? values.decode(Int.self, forKey: .fps) : 0
        videoOnly = try values.contains(.videoOnly) ? values.decode(Bool.self, forKey: .videoOnly) : false
        initStart = try values.contains(.initStart) ? values.decode(Int64.self, forKey: .initStart) : 0
        initEnd = try values.contains(.initEnd) ? values.decode(Int64.self, forKey: .initEnd) : 0
        indexStart = try values.contains(.indexStart) ? values.decode(Int64.self, forKey: .indexStart) : 0
        indexEnd = try values.contains(.indexEnd) ? values.decode(Int64.self, forKey: .indexEnd) : 0
        audioTrackId = try values.decodeIfPresent(String.self, forKey: .audioTrackId)
        audioTrackLocale = try values.decodeIfPresent(String.self, forKey: .audioTrackLocale)
        audioTrackName = try values.decodeIfPresent(String.self, forKey: .audioTrackName)
        audioTrackType = try values.decodeIfPresent(String.self, forKey: .audioTrackType)
        contentLength = try values.contains(.contentLength) ? values.decode(Int64.self, forKey: .contentLength) : 0
    }

    public init(url: String, format: String? = nil, quality: String? = nil,
                codec: String? = nil, mimeType: String? = nil, itag: Int? = nil,
                bitrate: Int64 = 0, width: Int = 0, height: Int = 0, fps: Int = 0,
                videoOnly: Bool = false, initStart: Int64 = 0, initEnd: Int64 = 0,
                indexStart: Int64 = 0, indexEnd: Int64 = 0,
                audioTrackId: String? = nil, audioTrackLocale: String? = nil,
                audioTrackName: String? = nil, audioTrackType: String? = nil,
                contentLength: Int64 = 0) {
        self.url = url; self.format = format; self.quality = quality
        self.codec = codec; self.mimeType = mimeType; self.itag = itag
        self.bitrate = bitrate; self.width = width; self.height = height
        self.fps = fps; self.videoOnly = videoOnly
        self.initStart = initStart; self.initEnd = initEnd
        self.indexStart = indexStart; self.indexEnd = indexEnd
        self.audioTrackId = audioTrackId; self.audioTrackLocale = audioTrackLocale
        self.audioTrackName = audioTrackName; self.audioTrackType = audioTrackType
        self.contentLength = contentLength
    }
}

public struct StreamsResponse: Codable, Equatable {
    public let title: String?
    public let duration: Int64
    public let livestream: Bool
    public let dash: String?
    public let hls: String?
    public let lbryId: String?
    public let videoStreams: [PipedStream]
    public let audioStreams: [PipedStream]

    private enum CodingKeys: String, CodingKey {
        case title, duration, livestream, dash, hls, lbryId, videoStreams, audioStreams
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        title = try values.decodeIfPresent(String.self, forKey: .title)
        duration = try values.contains(.duration) ? values.decode(Int64.self, forKey: .duration) : 0
        livestream = try values.contains(.livestream) ? values.decode(Bool.self, forKey: .livestream) : false
        dash = try values.decodeIfPresent(String.self, forKey: .dash)
        hls = try values.decodeIfPresent(String.self, forKey: .hls)
        lbryId = try values.decodeIfPresent(String.self, forKey: .lbryId)
        videoStreams = try values.contains(.videoStreams) ? values.decode([PipedStream].self, forKey: .videoStreams) : []
        audioStreams = try values.contains(.audioStreams) ? values.decode([PipedStream].self, forKey: .audioStreams) : []
    }

    public init(title: String? = nil, duration: Int64 = 0, livestream: Bool = false,
                dash: String? = nil, hls: String? = nil, lbryId: String? = nil,
                videoStreams: [PipedStream] = [], audioStreams: [PipedStream] = []) {
        self.title = title; self.duration = duration; self.livestream = livestream
        self.dash = dash; self.hls = hls; self.lbryId = lbryId
        self.videoStreams = videoStreams; self.audioStreams = audioStreams
    }
}

/// AVPlayer cannot play DASH. The client-side MPD trick the web and Android
/// clients use (DashUtils.js / DashManifestGenerator) is therefore NOT
/// available on iOS: HLS > progressive MP4 > actionable error.
public enum PlaybackSource: Equatable {
    case hls(manifestUrl: String)
    case progressive(stream: PipedStream)
}

public enum PlaybackSelector {
    /// Mirrors the iOS branch of the Android PlaybackSelector:
    /// `hls` field > HLS manifests inside videoStreams (LBRY HLS entries) >
    /// progressive MP4 > typed error. The middle step deviates from the web
    /// client (which has no AVPlayer constraint and picks progressive there);
    /// a muxed HLS manifest plays natively on AVPlayer and beats 360p.
    public static func select(_ response: StreamsResponse) throws -> PlaybackSource {
        if let hls = response.hls, !hls.isEmpty {
            return .hls(manifestUrl: hls)
        }
        if let hlsEntry = response.videoStreams.first(where: {
            $0.url.split(separator: "?").first?.hasSuffix(".m3u8") == true
        }) {
            return .hls(manifestUrl: hlsEntry.url)
        }
        if let progressive = bestProgressive(response) {
            return .progressive(stream: progressive)
        }
        throw PipedError.playbackFormatUnsupported
    }

    /// The web client's progressive pick: the LAST stream with codec == nil
    /// (VideoPlayer.vue `findLast(stream => stream.codec == null)`), matched
    /// verbatim — including on degraded payloads where height is 0.
    public static func bestProgressive(_ response: StreamsResponse) -> PipedStream? {
        response.videoStreams.last { $0.codec == nil && !$0.url.isEmpty }
    }
}
