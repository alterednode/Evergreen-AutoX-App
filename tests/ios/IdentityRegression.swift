import Foundation

@main struct IdentityRegression {
    static func main() {
        let official = "https://autox.romangarms.com"
        let a = "https://custom.example"
        let b = "https://second.example"
        DeviceIdentity.resetTestStorage(legacy: "legacy-official-token")
        // Visit custom first: migration must never copy the official token here.
        let custom = DeviceIdentity.token(for: a)!
        let second = DeviceIdentity.token(for: b)!
        let migrated = DeviceIdentity.token(for: official)!
        assert(migrated == "legacy-official-token")
        assert(Set([custom, second, migrated]).count == 3)
        assert(DeviceIdentity.token(for: a) == custom)
        assert(DeviceIdentity.token(for: "HTTPS://CUSTOM.EXAMPLE:443/") == custom)
        assert(DeviceIdentity.token(for: a + ":444") != custom)
        assert(DeviceIdentity.token(for: "http://custom.example") != custom)
        for invalid in ["file:///tmp/a", "https://user:pass@custom.example", a + "/api", a + "?q=1", a + "#x", "custom.example"] {
            assert(DeviceIdentity.token(for: invalid) == nil)
        }
        assert(DeviceIdentity.appleValue("appleUser", for: a) == nil)
        assert(DeviceIdentity.appleValue("appleUser", for: official) == "legacy-user")
        DeviceIdentity.setAppleValue("custom-user", "appleUser", for: a)
        assert(DeviceIdentity.appleValue("appleUser", for: official) == "legacy-user")
        DeviceIdentity.setAppleValue(nil, "appleUser", for: official)
        assert(DeviceIdentity.appleValue("appleUser", for: official) == nil)
        assert(DeviceIdentity.appleValue("appleUser", for: a) == "custom-user")
        // No credential is printed by the harness.
        print("Origin identity regressions passed")
        DeviceIdentity.resetTestStorage()
    }
}
