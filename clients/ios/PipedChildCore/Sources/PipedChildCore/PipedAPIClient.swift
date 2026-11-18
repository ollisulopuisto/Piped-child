import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Stores Piped auth tokens scoped to the instance that issued them.
///
/// A token MUST never be sent to a different host than the one it was issued
/// by (handoff spec §10), so the store is keyed by normalized instance base
/// URL and the client looks tokens up only under its own auth URL.
///
/// This protocol is the M1-B seam; the Keychain-backed implementation lands
/// with the iPhone app in M4.
public protocol TokenStore {
    func token(for instanceBaseURL: String) -> String?
    func put(_ token: String, for instanceBaseURL: String)
    func clear(for instanceBaseURL: String)
}

/// Non-persistent store, used by tests and as the M1-B default.
public final class InMemoryTokenStore: TokenStore, @unchecked Sendable {
    private var tokens: [String: String] = [:]
    private let lock = NSLock()

    public init() {}

    public func token(for instanceBaseURL: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return tokens[(try? InstanceURL.normalize(instanceBaseURL)) ?? instanceBaseURL]
    }

    public func put(_ token: String, for instanceBaseURL: String) {
        lock.lock(); defer { lock.unlock() }
        tokens[(try? InstanceURL.normalize(instanceBaseURL)) ?? instanceBaseURL] = token
    }

    public func clear(for instanceBaseURL: String) {
        lock.lock(); defer { lock.unlock() }
        tokens.removeValue(forKey: (try? InstanceURL.normalize(instanceBaseURL)) ?? instanceBaseURL)
    }
}

public enum InstanceURLError: Error, Equatable {
    case notParseable
    case badScheme
    case missingHost
    case containsCredentials
    case containsPathQueryOrFragment
}

/// http is accepted only for private/loopback hosts: credentials and tokens
/// are sent to the instance, so plain http to a public host would expose
/// them on the wire. Private ranges keep self-hosted LAN/tailnet instances
/// working.
func isPrivateOrLoopbackHost(_ host: String) -> Bool {
    let h = host.lowercased()
    if h == "localhost" || h.hasSuffix(".local") || h.hasSuffix(".internal") {
        return true
    }
    // URL hosts may keep IPv6 brackets; classify bracketed/unbracketed and
    // compressed/expanded loopback spellings
    let bare = h.hasPrefix("[") && h.hasSuffix("]") ? String(h.dropFirst().dropLast()) : h
    if bare == "::1" || bare == "0:0:0:0:0:0:0:1" {
        return true
    }
    let parts = h.split(separator: ".").compactMap { Int($0) }
    guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
    switch parts[0] {
    case 10, 127: return true
    case 192: return parts[1] == 168
    case 172: return (16...31).contains(parts[1])
    case 169: return parts[1] == 254
    case 100: return (64...127).contains(parts[1]) // CGNAT / tailnet range
    default: return false
    }
}

/// Normalizes and validates a Piped instance base URL, mirroring the Android
/// normalizeInstanceUrl: http/https only, no userinfo, no path/query/fragment
/// (instances live at a host root; the retired `/api/v1` prefix 404s),
/// host case and trailing slash canonicalized so a token stored under one
/// spelling is found under another.
enum InstanceURL {
    static func normalize(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased() else {
            throw InstanceURLError.notParseable
        }
        guard scheme == "https" || scheme == "http" else {
            throw InstanceURLError.badScheme
        }
        guard let host = components.host, !host.isEmpty else {
            throw InstanceURLError.missingHost
        }
        // errors carry no raw input: an attacker-controlled URL should not
        // flow back into logs or UI verbatim
        guard scheme == "https" || isPrivateOrLoopbackHost(host) else {
            throw InstanceURLError.badScheme
        }
        guard components.user == nil, components.password == nil else {
            throw InstanceURLError.containsCredentials
        }
        let path = components.path
        guard (path.isEmpty || path == "/"), components.query == nil, components.fragment == nil else {
            throw InstanceURLError.containsPathQueryOrFragment
        }
        let port = components.port.map { ":\($0)" } ?? ""
        return "\(scheme)://\(host.lowercased())\(port)"
    }
}

/// Search filters accepted by GET /search (contracts/api/README.md).
public enum SearchFilter: String {
    case videos
    case channels
    case playlists
    case musicSongs = "music_songs"
    case musicVideos = "music_videos"
    case musicAlbums = "music_albums"
    case musicArtists = "music_artists"
}

/// Path aliases for channel pages (the frontend router uses all three).
public enum ChannelPath: String {
    case id = "/channel/"
    case custom = "/c/"
    case user = "/user/"
}

/// Refuses every redirect: a broken instance-list entry (adminforge.de) was
/// observed 301ing to a non-API host, and following could carry an
/// Authorization header across hosts. The 3xx surfaces as an error instead.
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

/// M1-B networking layer for the iPhone client, mirroring the Android
/// core-network module. Talks to one user-configured Piped instance (bare
/// paths, no /api/v1) plus, optionally, the separate authentication instance
/// the frontend supports (useApi.js authApiUrl()). Failures map to the shared
/// PipedError taxonomy: extraction failure (500 with bot-block body) is a
/// normal, actionable state, never a crash.
public final class PipedAPIClient {

    /// Normalized base URL of the Piped instance this client talks to.
    public let baseURL: String
    /// Account endpoints go here; defaults to baseURL like the frontend.
    public let authURL: String

    private let tokenStore: TokenStore
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    deinit {
        session.invalidateAndCancel()
    }

    public init(
        baseURL: String,
        tokenStore: TokenStore = InMemoryTokenStore(),
        authBaseURL: String? = nil,
        configuration: URLSessionConfiguration = .ephemeral
    ) throws {
        self.baseURL = try InstanceURL.normalize(baseURL)
        self.authURL = try authBaseURL.map(InstanceURL.normalize) ?? self.baseURL
        self.tokenStore = tokenStore
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 15
        self.session = URLSession(
            configuration: configuration,
            delegate: NoRedirectDelegate(),
            delegateQueue: nil
        )
    }

    // MARK: - Discovery

    public func trending(region: String) async throws -> [StreamItem] {
        try await call(.get, "\(baseURL)/trending", params: ["region": region])
    }

    public func search(query: String, filter: SearchFilter = .videos) async throws -> SearchResponse {
        try await call(.get, "\(baseURL)/search", params: ["q": query, "filter": filter.rawValue])
    }

    public func searchNextPage(query: String, filter: SearchFilter, nextpage: String) async throws -> SearchResponse {
        try await call(.get, "\(baseURL)/nextpage/search",
                       params: ["q": query, "filter": filter.rawValue, "nextpage": nextpage])
    }

    /// OpenSearch suggestions: the response is `["query", ["suggestion", ...]]`.
    public func suggestions(query: String) async throws -> [String] {
        let data = try await rawCall(.get, "\(baseURL)/opensearch/suggestions", params: ["query": query])
        let parsed = try JSONSerialization.jsonObject(with: data)
        guard let arr = parsed as? [Any], arr.count >= 2, let suggestions = arr[1] as? [String] else {
            throw PipedError.malformedResponse(detail: "opensearch array has unexpected shape")
        }
        return suggestions
    }

    // MARK: - Playback metadata

    public func streams(videoId: String) async throws -> StreamsResponse {
        try await call(.get, "\(baseURL)/streams/\(videoId.pathSegment())")
    }

    // MARK: - Social / metadata

    public func comments(videoId: String) async throws -> CommentsResponse {
        try await call(.get, "\(baseURL)/comments/\(videoId.pathSegment())")
    }

    public func commentsNextPage(videoId: String, nextpage: String) async throws -> CommentsResponse {
        try await call(.get, "\(baseURL)/nextpage/comments/\(videoId.pathSegment())",
                       params: ["nextpage": nextpage])
    }

    /// Instance-proxied SponsorBlock; never sponsor.ajay.app directly.
    public func sponsors(videoId: String, categories: [String]) async throws -> SponsorsResponse {
        let category = (try? String(data: encoder.encode(categories), encoding: .utf8)) ?? "[]"
        return try await call(.get, "\(baseURL)/sponsors/\(videoId.pathSegment())",
                              params: ["category": category])
    }

    /// Instance-proxied DeArrow.
    public func dearrow(videoIds: [String]) async throws -> [String: DeArrowEntry] {
        try await call(.get, "\(baseURL)/dearrow", params: ["videoIds": videoIds.joined(separator: ",")])
    }

    public func channel(_ idOrName: String, path: ChannelPath = .id) async throws -> ChannelResponse {
        try await call(.get, "\(baseURL)\(path.rawValue)\(idOrName.pathSegment())")
    }

    public func channelNextPage(id: String, nextpage: String) async throws -> ChannelResponse {
        try await call(.get, "\(baseURL)/nextpage/channel/\(id.pathSegment())",
                       params: ["nextpage": nextpage])
    }

    /// Playlist content reads come from the AUTH instance (verified in
    /// usePlaylists.js getPlaylist: authApiUrl) but carry NO credentials, so
    /// a user-created playlist resolves while nothing leaks.
    public func playlist(_ playlistId: String) async throws -> PlaylistResponse {
        try await call(.get, "\(authURL)/playlists/\(playlistId.pathSegment())")
    }

    public func playlistNextPage(_ playlistId: String, nextpage: String) async throws -> PlaylistResponse {
        try await call(.get, "\(authURL)/nextpage/playlists/\(playlistId.pathSegment())",
                       params: ["nextpage": nextpage])
    }

    public func config() async throws -> InstanceConfig {
        try await call(.get, "\(baseURL)/config")
    }

    // MARK: - Account (Authorization: <token> header)

    /// Logs in and stores the token under authURL. A wrong password is a 200
    /// with `{"error": ...}` (verified in LoginPage.vue) -> authenticationFailure.
    @discardableResult
    public func login(username: String, password: String) async throws -> String {
        let response: LoginResponse = try await call(.post, "\(authURL)/login",
                                                     body: LoginRequest(username: username, password: password))
        if let token = response.token {
            tokenStore.put(token, for: authURL)
            return token
        }
        if response.error != nil { throw PipedError.authenticationFailure }
        throw PipedError.malformedResponse(detail: "/login returned no token")
    }

    /// Registers and stores the issued session token under authURL
    /// (verified in RegisterPage.vue: a successful register returns {token}
    /// and the frontend stores it like a login). A 200 with `{"error": ...}`
    /// is a rejection and maps to authenticationFailure.
    @discardableResult
    public func register(username: String, password: String) async throws -> String {
        let response: LoginResponse = try await call(.post, "\(authURL)/register",
                                                     body: LoginRequest(username: username, password: password))
        if let token = response.token {
            tokenStore.put(token, for: authURL)
            return token
        }
        if response.error != nil { throw PipedError.authenticationFailure }
        throw PipedError.malformedResponse(detail: "/register returned no token")
    }

    /// Invalidates the session server-side and drops the local token.
    @discardableResult
    public func logout() async throws -> MessageResponse {
        let response: MessageResponse = try await call(.post, "\(authURL)/logout", auth: true)
        tokenStore.clear(for: authURL)
        return response
    }

    /// The local token is dropped only when the server confirms deletion.
    @discardableResult
    public func deleteAccount(password: String) async throws -> MessageResponse {
        let response: MessageResponse = try await call(.post, "\(authURL)/user/delete",
                                                       body: DeleteAccountRequest(password: password), auth: true)
        if response.error == nil { tokenStore.clear(for: authURL) }
        return response
    }

    /// Authenticated feed. Mirrors the VERIFIED frontend pattern: the token
    /// goes in the `authToken` query parameter (useSubscriptions.js
    /// fetchFeed). The contracts README prefers an Authorization header, but
    /// that variant was not verified against a live instance on 2026-11-18.
    public func feed() async throws -> [StreamItem] {
        guard let token = tokenStore.token(for: authURL) else { throw PipedError.authenticationFailure }
        return try await call(.get, "\(authURL)/feed", params: ["authToken": token])
    }

    public func subscriptions() async throws -> [SubscriptionChannel] {
        try await call(.get, "\(authURL)/subscriptions", auth: true)
    }

    public func subscribed(channelId: String) async throws -> Bool {
        let response: SubscribedResponse = try await call(.get, "\(authURL)/subscribed",
                                                          params: ["channelId": channelId], auth: true)
        return response.subscribed ?? false
    }

    /// A 200 can still carry `{"error": ...}` (frontend: `!resp.error`), so
    /// the body is returned for the caller to inspect.
    @discardableResult
    public func subscribe(channelId: String) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/subscribe", body: SubscribeRequest(channelId: channelId), auth: true)
    }

    @discardableResult
    public func unsubscribe(channelId: String) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/unsubscribe", body: SubscribeRequest(channelId: channelId), auth: true)
    }

    public func userPlaylists() async throws -> [UserPlaylist] {
        try await call(.get, "\(authURL)/user/playlists", auth: true)
    }

    /// Returns the new playlist's id.
    public func createPlaylist(_ name: String) async throws -> String {
        let response: CreatePlaylistResponse = try await call(.post, "\(authURL)/user/playlists/create",
                                                              body: CreatePlaylistRequest(name: name), auth: true)
        guard let id = response.playlistId else {
            throw PipedError.malformedResponse(detail: "create playlist returned no id")
        }
        return id
    }

    @discardableResult
    public func deletePlaylist(_ playlistId: String) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/user/playlists/delete",
                       body: DeletePlaylistRequest(playlistId: playlistId), auth: true)
    }

    @discardableResult
    public func renamePlaylist(_ playlistId: String, to newName: String) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/user/playlists/rename",
                       body: RenamePlaylistRequest(playlistId: playlistId, newName: newName), auth: true)
    }

    @discardableResult
    public func updatePlaylistDescription(_ playlistId: String, description: String) async throws -> MessageResponse {
        // verified in usePlaylists.js changePlaylistDescription: PATCH
        try await call(.patch, "\(authURL)/user/playlists/description",
                       body: PlaylistDescriptionRequest(playlistId: playlistId, description: description), auth: true)
    }

    @discardableResult
    public func addToPlaylist(_ playlistId: String, videoIds: [String]) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/user/playlists/add",
                       body: AddToPlaylistRequest(playlistId: playlistId, videoIds: videoIds), auth: true)
    }

    @discardableResult
    public func removeFromPlaylist(_ playlistId: String, index: Int) async throws -> MessageResponse {
        try await call(.post, "\(authURL)/user/playlists/remove",
                       body: RemoveFromPlaylistRequest(playlistId: playlistId, index: index), auth: true)
    }

    // MARK: - Plumbing

    private enum Method: String {
        case get = "GET"
        case post = "POST"
        case patch = "PATCH"
    }

    private func call<T: Decodable>(
        _ method: Method,
        _ url: String,
        params: [String: String] = [:],
        body: (any Encodable)? = nil,
        auth: Bool = false
    ) async throws -> T {
        let data = try await rawCall(method, url, params: params, body: body, auth: auth)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw PipedError.malformedResponse(detail: "unparseable response body")
        }
    }

    private func rawCall(
        _ method: Method,
        _ url: String,
        params: [String: String] = [:],
        body: (any Encodable)? = nil,
        auth: Bool = false
    ) async throws -> Data {
        guard var components = URLComponents(string: url) else {
            throw PipedError.malformedResponse(detail: "bad URL: \(url)")
        }
        if !params.isEmpty {
            components.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let finalURL = components.url else {
            throw PipedError.malformedResponse(detail: "bad URL: \(url)")
        }
        var request = URLRequest(url: finalURL)
        request.httpMethod = method.rawValue
        if auth {
            guard let token = tokenStore.token(for: authURL) else {
                throw PipedError.authenticationFailure
            }
            request.setValue(token, forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try encoder.encode(body)
        }
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // Connectivity loss, DNS failure, timeouts: one actionable state,
            // the remedy is retry / switch instance.
            throw PipedError.networkUnavailable
        }
        guard let http = response as? HTTPURLResponse else {
            throw PipedError.networkUnavailable
        }
        guard (200..<300).contains(http.statusCode) else {
            let errorBody = try? decoder.decode(ApiErrorBody.self, from: data)
            throw Self.mapError(status: http.statusCode, body: errorBody)
        }
        return data
    }

    /// Mirrors the Android PipedErrorMapper: same signals, same taxonomy.
    static func mapError(status: Int, body: ApiErrorBody?) -> PipedError {
        let message = body?.message ?? body?.error ?? ""
        let extractionSignals = [
            "LOGIN_REQUIRED",
            "not a bot",
            "Sign in to confirm",
            "unable to extract",
            "Could not get",
        ]
        if status == 401 || status == 403 { return .authenticationFailure }
        if status == 404 { return .videoUnavailable }
        if status == 429 { return .rateLimited }
        if status >= 500 {
            if extractionSignals.contains(where: { message.range(of: $0, options: .caseInsensitive) != nil }) {
                return .extractionFailure(serverMessage: message)
            }
            return .instanceUnavailable(httpStatus: status)
        }
        return .instanceUnavailable(httpStatus: status)
    }
}

extension String {
    /// Encodes one dynamic path segment so an id cannot break out of its
    /// path position ("/", "?", "#"). urlPathAllowed keeps "/", so it is
    /// removed explicitly. "."/".." survive percent-encoding, so they are
    /// rejected outright.
    func pathSegment() -> String {
        // mirrors the Kotlin require(): "."/".." survive percent-encoding,
        // so they are a programmer error, not a runtime error to surface
        precondition(self != "." && self != ".." && !isEmpty, "invalid id segment")
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}
