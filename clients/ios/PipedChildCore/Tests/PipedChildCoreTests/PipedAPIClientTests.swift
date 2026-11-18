import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import PipedChildCore

/// M1-B contract tests for the Swift networking layer. A MockURLProtocol
/// serves the shared fixtures from clients/contracts/fixtures — the same
/// files the Android core-network tests use (handoff spec §12). No test
/// touches the network.
final class PipedAPIClientTests: XCTestCase {

    private let baseURL = "https://pipedapi.example.com"

    private func fixtureData(_ name: String) throws -> Data {
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

    private func makeClient(
        url: String? = nil,
        tokenStore: TokenStore = InMemoryTokenStore(),
        authBaseURL: String? = nil,
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) throws -> PipedAPIClient {
        MockURLProtocol.handler = handler
        MockURLProtocol.requests = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return try PipedAPIClient(
            baseURL: url ?? baseURL,
            tokenStore: tokenStore,
            authBaseURL: authBaseURL,
            configuration: config
        )
    }

    private func json(_ url: URL, _ body: Data, status: Int = 200) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: url, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"]
        )!
        return (response, body)
    }

    private func query(_ request: URLRequest, _ name: String) -> String? {
        URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == name })?.value
    }

    // ---------- discovery ----------

    func testTrendingParsesRecordedFixtureAndSendsRegion() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("trending_OK.json"))
        }
        let items = try await client.trending(region: "US")
        XCTAssertFalse(items.isEmpty)
        XCTAssertEqual(query(MockURLProtocol.requests[0], "region"), "US")
        XCTAssertTrue(items.allSatisfy { !$0.videoId.isEmpty })
    }

    func testSearchParsesRecordedFixtureAndSendsQueryAndFilter() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("search_videos_OK.json"))
        }
        let response = try await client.search(query: "lofi", filter: .videos)
        XCTAssertFalse(response.items.isEmpty)
        let request = MockURLProtocol.requests[0]
        XCTAssertEqual(request.url?.path, "/search")
        XCTAssertEqual(query(request, "q"), "lofi")
        XCTAssertEqual(query(request, "filter"), "videos")
    }

    func testSearchNextPageForwardsQueryFilterAndToken() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("search_videos_OK.json"))
        }
        _ = try await client.searchNextPage(query: "lofi", filter: .channels, nextpage: "page-token-2")
        let request = MockURLProtocol.requests[0]
        XCTAssertEqual(request.url?.path, "/nextpage/search")
        XCTAssertEqual(query(request, "q"), "lofi")
        XCTAssertEqual(query(request, "filter"), "channels")
        XCTAssertEqual(query(request, "nextpage"), "page-token-2")
    }

    func testSuggestionsParseTheOpensearchArray() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("search_suggestions_OK.json"))
        }
        let suggestions = try await client.suggestions(query: "lofi")
        XCTAssertFalse(suggestions.isEmpty)
        XCTAssertTrue(suggestions.allSatisfy { !$0.isEmpty })
    }

    // ---------- playback metadata ----------

    func testHostileIdsCannotBreakOutOfTheirPathSegment() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("streams_degraded_360p_OK.json"))
        }
        _ = try await client.streams(videoId: "a/b?c=1")
        XCTAssertEqual(MockURLProtocol.requests[0].url?.path, "/streams/a%2Fb%3Fc%3D1")
    }

    func testStreamsExtractionFailureMapsToExtractionFailure() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("streams_error_login_required.json"), status: 500)
        }
        do {
            _ = try await client.streams(videoId: "someVideo")
            XCTFail("expected extractionFailure")
        } catch PipedError.extractionFailure(let message) {
            XCTAssertFalse(message.isEmpty)
        }
    }

    // ---------- social / metadata ----------

    func testCommentsParseWithNextpage() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("comments_OK.json"))
        }
        let response = try await client.comments(videoId: "dQw4w9WgXcQ")
        XCTAssertFalse(response.comments.isEmpty)
        XCTAssertNotNil(response.nextpage)
    }

    func testSponsorsEmptyObjectParsesAndCategoriesAreEncoded() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("sponsors_empty_OK.json"))
        }
        let response = try await client.sponsors(videoId: "dQw4w9WgXcQ", categories: ["sponsor", "selfpromo"])
        XCTAssertTrue(response.segments?.isEmpty ?? true)
        XCTAssertEqual(query(MockURLProtocol.requests[0], "category"), #"["sponsor","selfpromo"]"#)
    }

    func testSponsorsCategoriesWithQuotesStayValidJSON() async throws {
        MockURLProtocol.handler = { request in
            self.json(request.url!, self.fixtureData("sponsors_empty_OK.json"))
        }
        let nasty = ["sponsor", "we\"ird"]
        _ = try await client.sponsors(videoId: "dQw4w9WgXcQ", categories: nasty)
        let param = query(MockURLProtocol.requests[0], "category") ?? ""
        // must round-trip as JSON, not break out of the array
        let decoded = try JSONDecoder().decode([String].self, from: Data(param.utf8))
        XCTAssertEqual(decoded, nasty)
    }

    func testDearrowMapsVideoIdsToEntries() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("dearrow_OK.json"))
        }
        let response = try await client.dearrow(videoIds: ["dQw4w9WgXcQ"])
        XCTAssertNotNil(response["dQw4w9WgXcQ"])
    }

    func testChannelParsesSyntheticFixtureWithRelatedStreams() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("channel_OK_SYNTHETIC.json"))
        }
        let response = try await client.channel("UCXuqSBlHAE6Xw-yeJA0Tunw")
        XCTAssertEqual(response.name, "Linus Tech Tips")
        XCTAssertEqual(response.relatedStreams?.count, 1)
        XCTAssertEqual(response.relatedStreams?.first?.videoId, "syntheticVid1")
        XCTAssertNotNil(response.nextpage)
    }

    func testChannelAliasesUseTheCAndUserPaths() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("channel_OK_SYNTHETIC.json"))
        }
        _ = try await client.channel("LinusTechTips", path: .custom)
        _ = try await client.channel("LinusTechTips", path: .user)
        XCTAssertEqual(MockURLProtocol.requests[0].url?.path, "/c/LinusTechTips")
        XCTAssertEqual(MockURLProtocol.requests[1].url?.path, "/user/LinusTechTips")
    }

    func testPlaylistParsesSyntheticFixtureAndSendsNoAuthHeader() async throws {
        let client = try makeClient { request in
            self.json(request.url!, self.fixtureData("playlist_OK_SYNTHETIC.json"))
        }
        let response = try await client.playlist("synthetic-playlist-id-1")
        XCTAssertEqual(response.name, "Synthetic playlist")
        XCTAssertEqual(response.relatedStreams?.count, 2)
        XCTAssertNil(MockURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"))
    }

    // ---------- account ----------

    func testLoginPostsCredentialsAndStoresTheTokenUnderTheInstance() async throws {
        let store = InMemoryTokenStore()
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("login_OK_SYNTHETIC.json"))
        }
        let token = try await client.login(username: "olli", password: "hunter2")
        XCTAssertEqual(token, "synthetic-token-0123456789abcdef")
        XCTAssertEqual(store.token(for: baseURL), token)
        let request = MockURLProtocol.requests[0]
        XCTAssertEqual(request.url?.path, "/login")
        let body = String(data: request.httpBody ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains(#""username":"olli""#))
        XCTAssertTrue(body.contains(#""password":"hunter2""#))
    }

    func testLoginRejectedWithA200ErrorBodyMapsToAuthenticationFailure() async throws {
        let store = InMemoryTokenStore()
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, Data(#"{"error":"wrong password"}"#.utf8))
        }
        await XCTAssertThrowsErrorAsync(try await client.login(username: "olli", password: "wrong")) { error in
            XCTAssertEqual(error as? PipedError, .authenticationFailure)
        }
        XCTAssertNil(store.token(for: baseURL), "no token may be stored on a failed login")
    }

    func testAuthedEndpointsSendTheAuthorizationHeader() async throws {
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("subscriptions_OK_SYNTHETIC.json"))
        }
        let subs = try await client.subscriptions()
        XCTAssertEqual(subs.count, 2)
        XCTAssertEqual(subs[0].channelId, "UCXuqSBlHAE6Xw-yeJA0Tunw")
        XCTAssertEqual(MockURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"), "tok-1")
    }

    func testAuthedCallWithoutATokenFailsBeforeAnyRequestIsMade() async throws {
        let client = try makeClient { request in
            self.json(request.url!, Data("{}".utf8))
        }
        await XCTAssertThrowsErrorAsync(try await client.subscriptions()) { error in
            XCTAssertEqual(error as? PipedError, .authenticationFailure)
        }
        XCTAssertTrue(MockURLProtocol.requests.isEmpty, "no request may leave without a token")
    }

    func testLogoutPostsWithTheTokenAndClearsIt() async throws {
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("message_OK_SYNTHETIC.json"))
        }
        _ = try await client.logout()
        XCTAssertNil(store.token(for: baseURL))
        XCTAssertEqual(MockURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"), "tok-1")
    }

    func testFeedMirrorsTheVerifiedFrontendAuthTokenQueryParam() async throws {
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("feed_OK_SYNTHETIC.json"))
        }
        let feed = try await client.feed()
        XCTAssertEqual(feed.count, 1)
        XCTAssertEqual(query(MockURLProtocol.requests[0], "authToken"), "tok-1")
    }

    func testUserPlaylistsParseSyntheticFixture() async throws {
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("user_playlists_OK_SYNTHETIC.json"))
        }
        let playlists = try await client.userPlaylists()
        XCTAssertEqual(playlists.count, 2)
        XCTAssertEqual(playlists[0].name, "Watch Later")
        XCTAssertNil(playlists[1].thumbnail)
    }

    func testPlaylistManagementSendsTheDocumentedBodies() async throws {
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            if request.url?.path == "/user/playlists/create" {
                return self.json(request.url!, Data(#"{"playlistId":"new-id-1"}"#.utf8))
            }
            return self.json(request.url!, self.fixtureData("message_OK_SYNTHETIC.json"))
        }
        let newId = try await client.createPlaylist("My list")
        XCTAssertEqual(newId, "new-id-1")
        _ = try await client.deletePlaylist(newId)
        _ = try await client.renamePlaylist(newId, to: "Renamed")
        _ = try await client.addToPlaylist(newId, videoIds: ["v1", "v2"])
        _ = try await client.removeFromPlaylist(newId, index: 0)
        XCTAssertEqual(MockURLProtocol.requests.map { $0.url?.path }, [
            "/user/playlists/create", "/user/playlists/delete", "/user/playlists/rename",
            "/user/playlists/add", "/user/playlists/remove",
        ])
        for request in MockURLProtocol.requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "tok-1")
        }
    }

    func testPlaylistReadsHitTheAuthInstanceWithoutCredentials() async throws {
        // verified in usePlaylists.js getPlaylist + PlaylistPage.vue: playlist
        // content comes from authApiUrl() with no Authorization header
        let client = try makeClient(
            url: "https://discovery.example",
            authBaseURL: "https://accounts.example"
        ) { request in
            self.json(request.url!, self.fixtureData("playlist_OK_SYNTHETIC.json"))
        }
        _ = try await client.playlist("pl-1")
        _ = try await client.playlistNextPage("pl-1", nextpage: "tok")
        XCTAssertEqual(MockURLProtocol.requests[0].url?.host, "accounts.example")
        XCTAssertEqual(MockURLProtocol.requests[1].url?.host, "accounts.example")
        XCTAssertNil(MockURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"))
        XCTAssertNil(MockURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"))
    }

    func testRegisterStoresTheIssuedSessionToken() async throws {
        // verified in RegisterPage.vue: a successful register returns {token}
        // and the frontend stores it exactly like a login
        let store = InMemoryTokenStore()
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("login_OK_SYNTHETIC.json"))
        }
        let token = try await client.register(username: "olli", password: "hunter2")
        XCTAssertEqual(token, "synthetic-token-0123456789abcdef")
        XCTAssertEqual(store.token(for: baseURL), token)
    }

    func testRegisterRejectedWithA200ErrorBodyMapsToAuthenticationFailure() async throws {
        let store = InMemoryTokenStore()
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, Data(#"{"error":"username taken"}"#.utf8))
        }
        await XCTAssertThrowsErrorAsync(try await client.register(username: "olli", password: "hunter2")) { error in
            XCTAssertEqual(error as? PipedError, .authenticationFailure)
        }
        XCTAssertNil(store.token(for: baseURL))
    }

    func testPlaylistDescriptionUpdateUsesPatchLikeTheFrontend() async throws {
        // verified in usePlaylists.js changePlaylistDescription: method PATCH
        let store = InMemoryTokenStore()
        store.put("tok-1", for: baseURL)
        let client = try makeClient(tokenStore: store) { request in
            self.json(request.url!, self.fixtureData("message_OK_SYNTHETIC.json"))
        }
        _ = try await client.updatePlaylistDescription("pl-1", description: "new description")
        XCTAssertEqual(MockURLProtocol.requests[0].httpMethod, "PATCH")
        XCTAssertEqual(MockURLProtocol.requests[0].url?.path, "/user/playlists/description")
    }

    // ---------- error taxonomy ----------

    func testErrorTaxonomyMappings() async throws {
        func clientFor(status: Int, body: String = #"{"error":"x"}"#) throws -> PipedAPIClient {
            try makeClient { request in self.json(request.url!, Data(body.utf8), status: status) }
        }
        await XCTAssertThrowsErrorAsync(try await clientFor(status: 401).trending(region: "US")) {
            XCTAssertEqual($0 as? PipedError, .authenticationFailure)
        }
        await XCTAssertThrowsErrorAsync(try await clientFor(status: 404).streams(videoId: "gone")) {
            XCTAssertEqual($0 as? PipedError, .videoUnavailable)
        }
        await XCTAssertThrowsErrorAsync(try await clientFor(status: 429).trending(region: "US")) {
            XCTAssertEqual($0 as? PipedError, .rateLimited)
        }
        await XCTAssertThrowsErrorAsync(try await clientFor(status: 500).trending(region: "US")) {
            XCTAssertEqual($0 as? PipedError, .instanceUnavailable(httpStatus: 500))
        }
    }

    func testMalformedSuccessBodyMapsToMalformedResponse() async throws {
        let client = try makeClient { request in
            self.json(request.url!, Data("this is not json".utf8))
        }
        do {
            _ = try await client.trending(region: "US")
            XCTFail("expected malformedResponse")
        } catch PipedError.malformedResponse(_) {}
    }

    func testNetworkFailureMapsToNetworkUnavailable() async throws {
        let client = try makeClient { _ in
            throw URLError(.notConnectedToInternet)
        }
        await XCTAssertThrowsErrorAsync(try await client.trending(region: "US")) {
            XCTAssertEqual($0 as? PipedError, .networkUnavailable)
        }
    }

    func testRedirectsAreNotFollowedBlindly() async throws {
        let client = try makeClient { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 301,
                httpVersion: nil, headerFields: ["Location": "https://other.example/"]
            )!
            return (response, Data())
        }
        do {
            _ = try await client.trending(region: "US")
            XCTFail("expected instanceUnavailable")
        } catch PipedError.instanceUnavailable(_) {}
        XCTAssertEqual(MockURLProtocol.requests.count, 1, "the redirect must not be followed")
    }

    // ---------- instance URL validation ----------

    func testInstanceURLValidation() throws {
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "ftp://pipedapi.example.com"))
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "https://user:pass@pipedapi.example.com"))
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "https://pipedapi.example.com/?x=1"))
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "https://pipedapi.example.com/api/v1"))
        // trailing slash and host case normalize to the same instance
        let client = try PipedAPIClient(baseURL: "HTTPS://PipedAPI.Example.COM/")
        XCTAssertEqual(client.baseURL, "https://pipedapi.example.com")
        // self-hosted instances with ports keep working
        _ = try PipedAPIClient(baseURL: "http://192.168.1.222:8080")
        _ = try PipedAPIClient(baseURL: "http://localhost:3000")
        _ = try PipedAPIClient(baseURL: "http://100.64.1.5:8080") // tailnet
        // bracketed IPv6 loopback, compressed and expanded spellings
        _ = try PipedAPIClient(baseURL: "http://[::1]:8080")
        _ = try PipedAPIClient(baseURL: "http://[0:0:0:0:0:0:0:1]")
        // credentials and tokens are sent to the instance, so plain http to
        // a public host is rejected (would expose them on the wire)
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "http://pipedapi.example.com"))
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "http://8.8.8.8"))
        XCTAssertThrowsError(try PipedAPIClient(baseURL: "http://[2001:db8::1]"))
    }
}

final class TokenIsolationTests: XCTestCase {

    private func makeClient(
        url: String,
        store: TokenStore
    ) throws -> PipedAPIClient {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("[]".utf8))
        }
        MockURLProtocol.requests = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return try PipedAPIClient(baseURL: url, tokenStore: store, configuration: config)
    }

    func testATokenIsNeverSentToAnInstanceItWasNotIssuedBy() async throws {
        let store = InMemoryTokenStore()
        store.put("token-for-a", for: "https://instance-a.example.com")
        let clientB = try makeClient(url: "https://instance-b.example.com", store: store)
        await XCTAssertThrowsErrorAsync(try await clientB.subscriptions()) { error in
            XCTAssertEqual(error as? PipedError, .authenticationFailure)
        }
        XCTAssertTrue(MockURLProtocol.requests.isEmpty, "instance B must never see instance A's token")
    }

    func testTwoClientsOnDifferentInstancesShareAStoreWithoutLeaking() async throws {
        let store = InMemoryTokenStore()
        store.put("token-for-a", for: "https://instance-a.example.com")
        store.put("token-for-b", for: "https://instance-b.example.com")
        let clientA = try makeClient(url: "https://instance-a.example.com", store: store)
        _ = try await clientA.subscriptions()
        let clientB = try makeClient(url: "https://instance-b.example.com", store: store)
        _ = try await clientB.subscriptions()
        XCTAssertEqual(MockURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"), "token-for-a")
        XCTAssertEqual(MockURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "token-for-b")
    }

    func testASeparateAuthInstanceReceivesAccountCallsAndTheToken() async throws {
        let store = InMemoryTokenStore()
        MockURLProtocol.handler = { request in
            let body: Data
            switch request.url?.path {
            case "/login": body = Data(#"{"token":"tok-auth"}"#.utf8)
            default: body = Data("[]".utf8)
            }
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"]
            )!
            return (response, body)
        }
        MockURLProtocol.requests = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let client = try PipedAPIClient(
            baseURL: "https://pipedapi.example.com",
            tokenStore: store,
            authBaseURL: "https://auth.example.com",
            configuration: config
        )
        _ = try await client.login(username: "olli", password: "hunter2")
        _ = try await client.subscriptions()
        _ = try await client.trending(region: "US")
        XCTAssertEqual(MockURLProtocol.requests[0].url?.host, "auth.example.com")
        XCTAssertEqual(MockURLProtocol.requests[1].url?.host, "auth.example.com")
        XCTAssertEqual(MockURLProtocol.requests[2].url?.host, "pipedapi.example.com")
        XCTAssertEqual(MockURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "tok-auth")
        XCTAssertNil(MockURLProtocol.requests[2].value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(store.token(for: "https://auth.example.com"), "tok-auth")
        XCTAssertNil(store.token(for: "https://pipedapi.example.com"))
    }
}

/// Records every request and serves a caller-supplied response.
final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        MockURLProtocol.requests.append(request)
        guard let handler = MockURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

/// XCTest has no async assertion for thrown errors; small local helper.
func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ message: String = "",
    file: StaticString = #filePath,
    line: UInt = #line,
    _ errorHandler: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("expected an error to be thrown \(message)", file: file, line: line)
    } catch {
        errorHandler(error)
    }
}
