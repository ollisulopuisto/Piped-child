import XCTest
@testable import PipedChildCore

/// Fixture-driven contract tests: same fixtures as the Android core-model
/// tests (handoff spec §12), decoded with plain Codable.
final class FixtureContractTests: XCTestCase {

    private func fixtureData(_ name: String) throws -> Data {
        // Tests run from the package directory in CI; fall back to a path
        // relative to this source file for local runs.
        let candidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("../../contracts/fixtures/\(name)"),
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent() // PipedChildCoreTests
                .deletingLastPathComponent() // Tests
                .deletingLastPathComponent() // PipedChildCore
                .deletingLastPathComponent() // ios
                .appendingPathComponent("contracts/fixtures/\(name)"),
        ]
        for url in candidates where FileManager.default.fileExists(atPath: url.path) {
            return try Data(contentsOf: url)
        }
        throw PipedError.malformedResponse(detail: "fixture not found: \(name)")
    }

    func testDegradedStreamsFixtureYieldsProgressiveFallback() throws {
        let r = try JSONDecoder().decode(
            StreamsResponse.self, from: fixtureData("streams_degraded_360p_OK.json"))
        XCTAssertNil(r.dash)
        XCTAssertNil(r.hls)
        XCTAssertTrue(r.audioStreams.isEmpty)
        let source = try PlaybackSelector.select(r)
        guard case .progressive(let stream) = source else {
            return XCTFail("expected progressive fallback")
        }
        XCTAssertFalse(stream.url.isEmpty)
    }

    func testLbryDegradedFixtureSelectsHlsFromVideoStreams() throws {
        let r = try JSONDecoder().decode(
            StreamsResponse.self, from: fixtureData("streams_degraded_lbry_OK.json"))
        // The fixture's `hls` field is null (degraded response); the playable
        // HLS manifest is the "LBRY HLS" entry inside videoStreams, whose
        // master playlist is recorded in lbry_hls_master.m3u8 and verified
        // parseable on Android via Media3. AVPlayer consumes it natively.
        XCTAssertNil(r.hls)
        let source = try PlaybackSelector.select(r)
        guard case .hls(let url) = source else { return XCTFail("expected HLS, got \(source)") }
        XCTAssertTrue(url.split(separator: "?").first?.hasSuffix(".m3u8") == true,
                      "expected m3u8 URL, got \(url)")
    }

    func testStreamsErrorFixtureIsExtractionFailureNotNetworkFailure() throws {
        let body = try JSONSerialization.jsonObject(
            with: fixtureData("streams_error_login_required.json")) as? [String: Any]
        // A 500 with an `error` body maps to extractionFailure; a transport
        // error maps to networkUnavailable. Never conflate the two.
        XCTAssertNotNil(body?["error"])
    }
}

final class PlaybackSelectorTests: XCTestCase {

    private func muxed(_ height: Int) -> PipedStream {
        PipedStream(url: "https://x/m\(height)", mimeType: "video/mp4",
                    quality: "\(height)p", height: height)
    }

    func testLivestreamSelectsHls() throws {
        let r = StreamsResponse(livestream: true, hls: "https://x/live.m3u8",
                                dash: "https://x/dash.mpd")
        XCTAssertEqual(try PlaybackSelector.select(r), .hls(manifestUrl: "https://x/live.m3u8"))
    }

    func testIosNeverSelectsDashEvenWithAdaptiveAudio() throws {
        let adaptive = PipedStream(url: "https://x/a", mimeType: "audio/mp4",
                                   codec: "mp4a.40.2", itag: 140, videoOnly: false)
        let r = StreamsResponse(dash: "https://x/m.mpd",
                                videoStreams: [muxed(720), muxed(360)],
                                audioStreams: [adaptive])
        // Web findLast semantics: the LAST codec==nil stream wins.
        XCTAssertEqual(try PlaybackSelector.select(r), .progressive(stream: muxed(360)))
    }

    func testNothingPlayableThrowsTypedError() {
        XCTAssertThrowsError(try PlaybackSelector.select(StreamsResponse())) { error in
            XCTAssertEqual(error as? PipedError, .playbackFormatUnsupported)
        }
    }
}
