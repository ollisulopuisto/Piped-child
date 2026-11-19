import XCTest
@testable import PipedChildCore

/// Sparse JSON accepted by the Kotlin core-model must not fail because Swift
/// initializer defaults are not used by synthesized Decodable.
final class SparsePlaybackModelsTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func testSparseStreamKeepsRequiredURLAndDefaults() throws {
        let stream = try decode(PipedStream.self, #"{"url":"https://proxy.example/video"}"#)
        XCTAssertEqual(stream.url, "https://proxy.example/video")
        XCTAssertEqual(stream.bitrate, 0)
        XCTAssertEqual(stream.height, 0)
        XCTAssertEqual(stream.width, 0)
        XCTAssertEqual(stream.fps, 0)
        XCTAssertEqual(stream.initStart, 0)
        XCTAssertEqual(stream.initEnd, 0)
        XCTAssertEqual(stream.indexStart, 0)
        XCTAssertEqual(stream.indexEnd, 0)
        XCTAssertEqual(stream.contentLength, 0)
        XCTAssertFalse(stream.videoOnly)
        XCTAssertNil(stream.codec)
        XCTAssertNil(stream.audioTrackName)
        XCTAssertThrowsError(try decode(PipedStream.self, "{}"))
        XCTAssertThrowsError(try decode(PipedStream.self, #"{"url":null}"#))
    }

    func testSparseResponseDefaultsLikeKotlin() throws {
        let response = try decode(StreamsResponse.self, "{}")
        XCTAssertNil(response.title)
        XCTAssertEqual(response.duration, 0)
        XCTAssertFalse(response.livestream)
        XCTAssertTrue(response.videoStreams.isEmpty)
        XCTAssertTrue(response.audioStreams.isEmpty)
        XCTAssertThrowsError(try PlaybackSelector.select(response))
    }

    func testSparseProgressiveResponseRemainsSelectable() throws {
        let response = try decode(StreamsResponse.self,
            #"{"title":"Example","videoStreams":[{"url":"https://proxy.example/video","format":"MPEG_4","videoOnly":false}]}"#)
        guard case let .progressive(stream) = try PlaybackSelector.select(response) else {
            return XCTFail("Expected progressive selection")
        }
        XCTAssertEqual(stream.url, "https://proxy.example/video")
    }

    func testExplicitMalformedTypesAreNotSilentlyDefaulted() throws {
        for json in [#"{"duration":"bad"}"#, #"{"livestream":"false"}"#,
                     #"{"videoStreams":{}}"#, #"{"audioStreams":null}"#] {
            XCTAssertThrowsError(try decode(StreamsResponse.self, json), json)
        }
        for json in [#"{"url":"x","height":"bad"}"#,
                     #"{"url":"x","videoOnly":null}"#] {
            XCTAssertThrowsError(try decode(PipedStream.self, json), json)
        }
    }

    func testPresentFieldsAndRoundTripRemainIntact() throws {
        let stream = PipedStream(url: "https://proxy.example/video", format: "MPEG_4",
            quality: "720p", codec: "avc1", mimeType: "video/mp4", itag: 22,
            bitrate: 12345, width: 1280, height: 720, fps: 30, videoOnly: true,
            initStart: 1, initEnd: 2, indexStart: 3, indexEnd: 4,
            audioTrackId: "en", audioTrackLocale: "en-US", audioTrackName: "English",
            audioTrackType: "ORIGINAL", contentLength: 555)
        XCTAssertEqual(stream, try JSONDecoder().decode(PipedStream.self, from: JSONEncoder().encode(stream)))
        let response = StreamsResponse(title: "Example", duration: 120, livestream: true,
            dash: "https://proxy.example/a.mpd", hls: "https://proxy.example/a.m3u8", lbryId: "lbry",
            videoStreams: [stream], audioStreams: [stream])
        XCTAssertEqual(response, try JSONDecoder().decode(StreamsResponse.self, from: JSONEncoder().encode(response)))
    }
}
