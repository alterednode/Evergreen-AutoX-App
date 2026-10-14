# AutoX Live for Android

Kotlin + Jetpack Compose port of the read side of the iOS app (see `../PRD.md`).
Package ID `com.romangarms.autoxlive` is permanent: changing it breaks updates for every install.

## Building

- `./gradlew :app:assembleDebug` builds `com.romangarms.autoxlive.debug`, which talks to
  `http://10.0.2.2:8321` (the emulator's alias for this Mac). For a phone on the LAN, pass
  `-PautoxDevBaseUrl=http://<mac-ip>:8321`; cleartext is allowed only for local addresses in
  debug builds (`src/debug/res/xml/network_security_config.xml`), so add the IP's domain there
  if it isn't `*.local`.
- `./gradlew :app:testDebugUnitTest` runs the unit tests.

## Releasing

The app is not on Google Play. It downloads updates from the public server:
`GET /api/android/release` says which version is current, `/android` serves the APK, and the
app checks the APK's SHA-256 before handing it to Android's installer.

1. Once: `./deploy.sh make-keystore` creates `autoxlive-release.jks` and `release.properties`
   (both gitignored). Back up both and the password somewhere safe. Without the key, no
   installed copy can ever be updated again.
2. Each release: `./deploy.sh [build|patch|minor|major|X.Y.Z]`. It bumps the version through
   `../Evergreen AutoX App iOS/bump-version.sh` (which keeps the Android and iOS versions
   equal), builds and verifies the signed APK, writes `release.json`, and uploads the APK and
   then `release.json` to `macmini:Documents/GitHub/Evergreen-AutoX-App/server/android/`.
   `--skip-bump` ships the current version, `--no-upload` stops after the build.

`versionCode` is `major * 10000 + minor * 100 + build`, so every build sorts after the one
before it. Patch versions are not part of it: 1.5.1 build 1 would collide with 1.5 build 1, so
bump the build number instead of shipping a patch version.
