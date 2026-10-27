import Foundation
#if !AUTOX_IDENTITY_TEST
import Security
#endif

// A random token minted on first launch is how the server recognises this
// device; signing in with Apple links it to an account, and the account then
// owns what it posts. It lives in the Keychain so a reinstall stays signed in.
enum DeviceIdentity {
    private static let service = "com.romangarms.Evergreen-AutoX-App-iOS.device"
    private static let account = "token"
    private static let defaultsKey = "deviceToken"

    #if AUTOX_IDENTITY_TEST
    private static let defaults = UserDefaults(suiteName: "AutoXIdentityRegressionTests")!
    private static var testStorage: [String: String] = [:]
    static func resetTestStorage(legacy: String? = nil) {
        testStorage = [:]
        defaults.removePersistentDomain(forName: "AutoXIdentityRegressionTests")
        if let legacy { testStorage["token"] = legacy; testStorage["appleUser"] = "legacy-user" }
    }
    #else
    private static let defaults = UserDefaults.standard
    #endif

    private static let lock = NSLock()
    static let officialOrigin = "https://autox.romangarms.com:443"

    // A server URL is an origin, never a URL carrying credentials or an API path.
    static func origin(_ value: String) -> String? {
        guard let parts = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = parts.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/" else { return nil }
        let port = parts.port ?? (scheme == "https" ? 443 : 80)
        guard (1...65535).contains(port) else { return nil }
        return "\(scheme)://\(host):\(port)"
    }

    static func token(for baseURL: String) -> String? {
        guard let origin = origin(baseURL) else { return nil }
        lock.lock()
        defer { lock.unlock() }
        let key = "token@" + origin
        if let saved = read(key) ?? defaults.string(forKey: key) { return saved }
        // The legacy token belongs only to the exact official origin.
        let legacy = origin == officialOrigin ? load() : nil
        let token = legacy ?? (UUID().uuidString + UUID().uuidString).replacingOccurrences(of: "-", with: "")
        write(token, key)
        // A Keychain failure must not cause a new token on the next request.
        defaults.set(token, forKey: key)
        return token
    }

    static func appleValue(_ key: String, for baseURL: String) -> String? {
        guard let origin = origin(baseURL) else { return nil }
        let scoped = key + "@" + origin
        if defaults.bool(forKey: scoped + ".signedOut") { return nil }
        if let value = read(scoped) { return value == "" ? nil : value }
        return origin == officialOrigin ? read(key) : nil
    }

    static func setAppleValue(_ value: String?, _ key: String, for baseURL: String) {
        guard let origin = origin(baseURL) else { return }
        // An empty value is a migration tombstone, so sign-out cannot resurrect
        // a legacy Apple identity on the next launch.
        let scoped = key + "@" + origin
        defaults.set(value == nil, forKey: scoped + ".signedOut")
        write(value ?? "", scoped)
    }

    private static func read(_ account: String) -> String? {
        #if AUTOX_IDENTITY_TEST
        return testStorage[account]
        #else
        var query = query(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
        #endif
    }

    private static func write(_ value: String?, _ account: String) {
        #if AUTOX_IDENTITY_TEST
        testStorage[account] = value
        #else
        SecItemDelete(query(account: account) as CFDictionary)
        guard let value else { return }
        var attributes = query(account: account)
        attributes[kSecValueData as String] = Data(value.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
        #endif
    }

    #if !AUTOX_IDENTITY_TEST
    private static func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    #endif

    private static func load() -> String? {
        if let token = read(account), !token.isEmpty { return token }
        return defaults.string(forKey: defaultsKey)
    }
}
