import CryptoKit
import Foundation

struct APIError: LocalizedError {
    let message: String
    var status: Int?
    var errorDescription: String? { message }
}

struct APIClient {
    let baseURL: URL
    var deviceToken: String?
    var cache: ResponseCache?
    private var cacheReader: CacheReader?

    init(baseURL: URL, deviceToken: String? = nil, cache: ResponseCache? = nil) {
        self.baseURL = baseURL
        self.deviceToken = deviceToken
        self.cache = cache
    }

    // Short timeouts so an unreachable server fails fast at launch instead of
    // hanging behind URLSession's default 60s.
    private static let session = makeSession(requestTimeout: 8)

    // GGLC pages are scraped on demand server-side; a cold fetch can outlast
    // the fast-fail timeout, and timing out midway leaves stale results up.
    private static let gglcSession = makeSession(requestTimeout: 30)

    private static func makeSession(requestTimeout: TimeInterval) -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = requestTimeout
        config.timeoutIntervalForResource = 60
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }

    func org(orgID: Int) async throws -> SHOrg {
        try await get("api/orgs/\(orgID)", keep: true)
    }

    func events(orgID: Int, limit: Int = 200) async throws -> [SHEvent] {
        try await get("api/orgs/\(orgID)/events?limit=\(limit)", keep: true)
    }

    func sessions(eventID: Int) async throws -> [SHSession] {
        try await get("api/events/\(eventID)/sessions", keep: true)
    }

    func results(sessionID: Int) async throws -> [SHResultRow] {
        try await get("api/sessions/\(sessionID)/results", keep: true)
    }

    func laps(sessionID: Int) async throws -> [SHLapsRow] {
        try await get("api/sessions/\(sessionID)/laps", keep: true)
    }

    func gglcEvents(year: Int) async throws -> [GGLCEventStub] {
        try await get("api/gglc/events?year=\(year)", session: Self.gglcSession, keep: true)
    }

    func gglcEvent(date: String) async throws -> GGLCEvent {
        try await get("api/gglc/events/\(date)", session: Self.gglcSession, keep: true)
    }

    func leaderboardCourses() async throws -> [LBCourse] {
        try await get("api/leaderboard/courses", keep: true)
    }

    func leaderboardCourse(id: Int) async throws -> LBCourseDetail {
        try await get("api/leaderboard/courses/\(id)", keep: true)
    }

    func acceleration() async throws -> [AccelEntry] {
        try await get("api/acceleration", keep: true)
    }

    func createAcceleration(_ input: AccelEntryInput) async throws -> AccelEntry {
        try await send("api/acceleration", method: "POST", json: input)
    }

    func deleteAcceleration(id: Int) async throws {
        let _: Deleted = try await send("api/acceleration/\(id)", method: "DELETE")
    }

    func createCourse(_ input: LBCourseInput) async throws -> LBCourse {
        try await send("api/leaderboard/courses", method: "POST", json: input)
    }

    func updateCourse(id: Int, _ input: LBCourseInput) async throws -> LBCourse {
        try await send("api/leaderboard/courses/\(id)", method: "PATCH", json: input)
    }

    func deleteCourse(id: Int) async throws {
        let _: Deleted = try await send("api/leaderboard/courses/\(id)", method: "DELETE")
    }

    func joinCourse(code: String) async throws -> LBCourse {
        try await send("api/leaderboard/join", method: "POST", json: LBJoinInput(code: code))
    }

    func leaveCourse(id: Int) async throws {
        let _: Deleted = try await send("api/leaderboard/courses/\(id)/membership", method: "DELETE")
    }

    func createRun(courseID: Int, _ input: LBRunInput) async throws -> LBRun {
        try await send("api/leaderboard/courses/\(courseID)/runs", method: "POST", json: input)
    }

    func deleteRun(id: Int) async throws {
        let _: Deleted = try await send("api/leaderboard/runs/\(id)", method: "DELETE")
    }

    func report(_ input: LBReportInput) async throws {
        let _: Created = try await send("api/leaderboard/reports", method: "POST", json: input)
    }

    func block(_ input: LBBlockInput) async throws -> LBBlock {
        try await send("api/leaderboard/blocks", method: "POST", json: input)
    }

    func blocks() async throws -> [LBBlock] {
        try await get("api/leaderboard/blocks")
    }

    func unblock(id: Int) async throws {
        let _: Deleted = try await send("api/leaderboard/blocks/\(id)", method: "DELETE")
    }

    func account() async throws -> Account {
        try await get("api/account")
    }

    func signInWithApple(_ input: AppleSignInInput) async throws -> Account {
        try await send("api/account/apple", method: "POST", json: input)
    }

    func updateAccount(name: String) async throws -> Account {
        try await send("api/account", method: "PATCH", json: AccountNameInput(name: name))
    }

    func signOut() async throws -> Account {
        try await send("api/account/session", method: "DELETE")
    }

    func deleteAccount() async throws -> Account {
        try await send("api/account", method: "DELETE")
    }

    func setPush(_ setup: PushSetup) async throws -> PushStatus {
        try await send("api/push", method: "PUT", json: setup)
    }

    func clearPush() async throws -> PushStatus {
        try await send("api/push", method: "DELETE")
    }

    func submit(_ input: SubmissionInput) async throws -> Submission {
        try await send("api/submissions", method: "POST", json: input)
    }

    func submissions() async throws -> [Submission] {
        try await get("api/submissions")
    }

    func deleteSubmission(id: Int) async throws {
        let _: Deleted = try await send("api/submissions/\(id)", method: "DELETE")
    }

    func parseTrackAddict(csv: Data) async throws -> TAParsedLog {
        try await send("api/trackaddict/parse", method: "POST", body: csv, contentType: "text/csv")
    }

    private struct Deleted: Decodable { let deleted: Int }
    private struct Created: Decodable { let id: Int }

    // Runs `load` against the answers `cache` kept instead of the server.
    // `date` is when the oldest answer it used was fetched.
    func fromCache<T>(_ load: (APIClient) async throws -> T) async -> (value: T, date: Date)? {
        guard cache != nil else { return nil }
        var offline = self
        let reader = CacheReader()
        offline.cacheReader = reader
        guard let value = try? await load(offline), let date = reader.oldest else { return nil }
        return (value, date)
    }

    // Worth falling back to saved answers: the server didn't answer, or the
    // proxy in front of it did because it is down.
    static func isUnreachable(_ error: Error) -> Bool {
        if error is URLError { return true }
        if let status = (error as? APIError)?.status { return status >= 500 }
        return false
    }

    private func get<T: Decodable>(
        _ path: String, session: URLSession = APIClient.session, keep: Bool = false
    ) async throws -> T {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIError(message: "Bad URL: \(baseURL)\(path)")
        }
        if let cacheReader {
            guard keep, let saved = cache?.load(url) else { throw APIError(message: "Nothing saved for \(path)") }
            cacheReader.note(saved.date)
            return try JSONDecoder().decode(T.self, from: saved.data)
        }
        let data = try await data(path, method: "GET", session: session)
        let value = try JSONDecoder().decode(T.self, from: data)
        if keep { cache?.store(data, for: url) }
        return value
    }

    private func send<T: Decodable, Body: Encodable>(
        _ path: String, method: String, json: Body
    ) async throws -> T {
        try await send(path, method: method, body: JSONEncoder().encode(json), contentType: "application/json")
    }

    private func send<T: Decodable>(
        _ path: String,
        method: String,
        body: Data? = nil,
        contentType: String? = nil
    ) async throws -> T {
        let data = try await data(path, method: method, body: body, contentType: contentType)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func data(
        _ path: String,
        method: String,
        body: Data? = nil,
        contentType: String? = nil,
        session: URLSession = APIClient.session
    ) async throws -> Data {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIError(message: "Bad URL: \(baseURL)\(path)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        if let contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if let deviceToken {
            request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw APIError(
                message: Self.errorMessage(data) ?? "Server returned \(http.statusCode) for \(path)",
                status: http.statusCode
            )
        }
        return data
    }

    // FastAPI puts a string in `detail` for our own errors and a list of
    // field errors for validation failures.
    private static func errorMessage(_ data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let detail = object["detail"] as? String { return detail }
        if let errors = object["detail"] as? [[String: Any]] {
            let messages = errors.compactMap { error -> String? in
                guard let message = error["msg"] as? String else { return nil }
                let field = (error["loc"] as? [Any])?.last.map { "\($0)" }
                return field.map { "\($0): \(message)" } ?? message
            }
            if !messages.isEmpty { return messages.joined(separator: "\n") }
        }
        return nil
    }
}

// The last answer to each GET the app shows, so a phone that loses the server
// keeps its results. Keyed by the full URL, so another server's answers never
// mix in.
struct ResponseCache {
    let directory: URL

    static let shared = ResponseCache(directory: .cachesDirectory.appending(path: "responses", directoryHint: .isDirectory))

    func store(_ data: Data, for url: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: file(for: url), options: .atomic)
    }

    func load(_ url: URL) -> (data: Data, date: Date)? {
        let file = file(for: url)
        guard let data = try? Data(contentsOf: file),
              let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
        else { return nil }
        return (data, date)
    }

    func prune(olderThan cutoff: Date) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        for file in files {
            let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
            if (date ?? .distantPast) < cutoff {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    private func file(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
        return directory.appending(path: digest.map { String(format: "%02x", $0) }.joined() + ".json")
    }
}

private final class CacheReader {
    private(set) var oldest: Date?

    func note(_ date: Date) {
        oldest = min(oldest ?? date, date)
    }
}
