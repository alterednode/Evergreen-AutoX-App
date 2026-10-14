#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

GRADLE_FILE=app/build.gradle.kts
PROPS=release.properties
KEYSTORE=autoxlive-release.jks
KEY_ALIAS=autoxlive
BUILD_DIR=build/release
REMOTE_HOST=${REMOTE_HOST:-macmini}
REMOTE_DIR=${REMOTE_DIR:-Documents/GitHub/Evergreen-AutoX-App/server/android}

usage() {
    cat <<USAGE
Usage: ./deploy.sh [options] [build|patch|minor|major|X.Y.Z]
       ./deploy.sh make-keystore

Builds a signed release APK, writes release.json beside it, and uploads both
to $REMOTE_HOST:$REMOTE_DIR, where the server offers them at /android and
/api/android/release. The APK goes up first and release.json last, so the
app never sees a version whose file isn't there yet.

  build|patch|minor|major|X.Y.Z
                 How to bump the version (see ../Evergreen AutoX App iOS/bump-version.sh,
                 which also sets the iOS project's version). Asked
                 interactively when omitted.
  --skip-bump    Ship the version already in $GRADLE_FILE.
  --no-upload    Build the APK and release.json only.
  make-keystore  Create the release signing key and $PROPS.
  help           Show this message.

Signing needs $PROPS (gitignored) with storeFile, storePassword, keyAlias
and keyPassword. Losing the keystore means no installed copy can ever be
updated again, so keep a backup of it and its password outside this repo.
Environment variables REMOTE_HOST and REMOTE_DIR override the upload target.
USAGE
}

step() {
    echo
    echo "==> $*"
}

make_keystore() {
    if [ -f "$KEYSTORE" ] || [ -f "$PROPS" ]; then
        echo "$KEYSTORE or $PROPS already exists; refusing to replace the signing key." >&2
        exit 1
    fi
    command -v keytool >/dev/null || { echo "keytool not found; install a JDK." >&2; exit 1; }
    read -r -s -p "Keystore password (6+ characters): " password
    echo
    read -r -s -p "Again: " again
    echo
    [ "$password" = "$again" ] || { echo "Passwords differ." >&2; exit 1; }
    [ ${#password} -ge 6 ] || { echo "Too short." >&2; exit 1; }
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storetype PKCS12 \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$password" \
        -dname "CN=AutoX Live"
    cat >"$PROPS" <<PROPERTIES
storeFile=$KEYSTORE
storePassword=$password
keyAlias=$KEY_ALIAS
keyPassword=$password
PROPERTIES
    chmod 600 "$PROPS" "$KEYSTORE"
    echo
    echo "Created $KEYSTORE and $PROPS (both gitignored)."
    echo "BACK THEM UP NOW (and the password). Without this key no install can be updated."
}

gradle_value() {
    sed -n "s/^val $1 = \"*\([^\"]*\)\"*$/\1/p" "$GRADLE_FILE" | head -n 1
}

bump=""
skip_bump=false
upload=true
for arg in "$@"; do
    case "$arg" in
        --skip-bump) skip_bump=true ;;
        --no-upload) upload=false ;;
        make-keystore) make_keystore; exit 0 ;;
        help|-h|--help) usage; exit 0 ;;
        build|patch|minor|major|[0-9]*.[0-9]*) bump=$arg ;;
        *) echo "Unknown argument: $arg" >&2; usage >&2; exit 1 ;;
    esac
done

if [ ! -f "$PROPS" ]; then
    echo "No $PROPS, so the APK can't be signed. Run ./deploy.sh make-keystore first" >&2
    echo "(or restore the backed-up keystore and $PROPS if one exists)." >&2
    exit 1
fi

if ! $skip_bump; then
    step "Version"
    "../Evergreen AutoX App iOS/bump-version.sh" $bump
fi

version=$(gradle_value appVersionName)
build=$(gradle_value appBuild)
IFS=. read -r major minor _ <<<"$version"
version_code=$(( major * 10000 + ${minor:-0} * 100 + build ))
file_name="AutoX-Live-$version-$build.apk"
mkdir -p "$BUILD_DIR"
log="$BUILD_DIR/deploy-$version-$build.log"

step "Building $version ($build), versionCode $version_code (log: $log)"
./gradlew :app:assembleRelease >"$log" 2>&1 || {
    echo "Build failed. Last lines of $log:" >&2
    tail -n 30 "$log" >&2
    exit 1
}
apk="$BUILD_DIR/$file_name"
cp app/build/outputs/apk/release/app-release.apk "$apk"

apksigner=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner 2>/dev/null | tail -n 1)
if [ -n "$apksigner" ]; then
    "$apksigner" verify "$apk" || { echo "$apk is not properly signed." >&2; exit 1; }
fi

sha256=$(shasum -a 256 "$apk" | cut -d' ' -f1)
size=$(stat -f %z "$apk")
release_json="$BUILD_DIR/release.json"
cat >"$release_json" <<JSON
{
  "version_name": "$version",
  "version_code": $version_code,
  "sha256": "$sha256",
  "size": $size,
  "file_name": "$file_name"
}
JSON
echo "Built $apk ($size bytes, sha256 $sha256)"

$upload || { echo "Skipping upload (--no-upload)."; exit 0; }

step "Uploading to $REMOTE_HOST:$REMOTE_DIR"
ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR'"
scp "$apk" "$REMOTE_HOST:$REMOTE_DIR/$file_name"
scp "$release_json" "$REMOTE_HOST:$REMOTE_DIR/release.json.tmp"
ssh "$REMOTE_HOST" "mv '$REMOTE_DIR/release.json.tmp' '$REMOTE_DIR/release.json'"
echo "Released $version ($build). Phones see it on their next update check."
