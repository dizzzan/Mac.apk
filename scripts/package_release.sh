#!/usr/bin/env bash
# Build the fork's ad-hoc distribution, locally or in GitHub Actions.
# Output: build/release/{*.dmg,*.zip,*.tar.gz,SHA256SUMS.txt,BUILD.txt,...}.
# Does not install the app, modify app data, publish a release, or read .release.env.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ $# -ne 0 ]]; then
    echo 'Usage: scripts/package_release.sh' >&2
    exit 2
fi
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo 'Release packaging requires an Apple Silicon Mac.' >&2
    exit 2
fi
VERSION="$(tr -d '[:space:]' < VERSION)"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo 'Invalid VERSION' >&2; exit 2; }
COMMIT="$(git rev-parse HEAD)"
SUFFIX="${COMMIT:0:12}"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    SUFFIX="$SUFFIX-dirty"
fi
NAME="Mac.apk-$VERSION-$SUFFIX-macos-arm64-adhoc"

# Use the Homebrew JDK whose native dependencies the bundler knows how to stage.
# Ignore a hosted runner's default JAVA_HOME and local signing/update settings.
export MACAPK_JDK=21
MACAPK_JDK_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export MACAPK_JDK_HOME
export JAVA_HOME="$MACAPK_JDK_HOME"
export MACAPK_NO_BUNDLE_SYNC=1
export MACAPK_BUILD_TYPE=RelWithDebInfo
unset MACAPK_SIGN_IDENTITY MACAPK_RELEASE_VERSION

if [[ ! -f thirdparty/framework-res/framework-res-api36.apk ]]; then
    scripts/build_framework_res.sh --api 36 --tag android-16.0.0_r2 --version-name 16
fi

# This is the canonical build, signing, dependency-audit and dev/dist parity path.
scripts/build_dist.sh --dmg
APP="$ROOT/build/dist/Mac.apk.app"
codesign --verify --deep --strict "$APP"
hdiutil verify "$ROOT/build/dist/macapk.dmg"

STAGE="$(mktemp -d "$ROOT/build/.package-release.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir "$STAGE/assets"
ASSETS="$STAGE/assets"
cp "$ROOT/build/dist/macapk.dmg" "$ASSETS/$NAME.dmg"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$ASSETS/$NAME.zip"

# ZIP upload/download must preserve executable bits, symlinks and the signature.
ditto -x -k "$ASSETS/$NAME.zip" "$STAGE/extracted"
codesign --verify --deep --strict "$STAGE/extracted/Mac.apk.app"
rm -rf "$STAGE/extracted"

# Keep corresponding sources alongside the binary, using the exact linked kegs.
scripts/collect_lgpl_sources.sh "$STAGE/lgpl-sources"
tar -czf "$ASSETS/macapk-lgpl-sources.tar.gz" -C "$STAGE" lgpl-sources
cp "$APP/Contents/Resources/THIRD_PARTY_NOTICES.txt" "$ASSETS/"
cp LICENSE "$ASSETS/"
{
    printf 'Mac.apk %s\nCommit: %s\nBuild: %s\n' "$VERSION" "$COMMIT" "$SUFFIX"
    printf 'Built at: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'Signing: ad-hoc (not notarized)\nPlatform: macOS 26.4+, arm64\n'
    printf 'Automatic upstream update check: disabled\n\n'
    sw_vers
    xcrun swiftc --version
    brew list --versions cmake openjdk@21 capstone apktool libpng libvpx glib gettext
} > "$ASSETS/BUILD.txt"
(
    cd "$ASSETS"
    shasum -a 256 ./* > SHA256SUMS.txt
    shasum -a 256 -c SHA256SUMS.txt
)

# Replace the previous successful package set only after every check passes.
rm -rf "$ROOT/build/release"
mv "$ASSETS" "$ROOT/build/release"
echo "Release packages: $ROOT/build/release"
