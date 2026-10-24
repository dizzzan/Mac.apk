#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
result="$(mktemp -d "${TMPDIR:-/tmp}/macapk-recording.XXXXXX")"
trap 'rm -rf "$result"' EXIT
xcrun clang++ -std=c++17 -fobjc-arc -Wno-deprecated-declarations -I"$repo/runtime/include" \
    "$repo/debug/StreamRecorderTest.mm" "$repo/runtime/src/stream_recorder.mm" \
    -framework AppKit -framework AVFoundation -framework CoreMedia -framework CoreVideo \
    -framework AudioToolbox -framework UniformTypeIdentifiers -o "$result/test"
"$result/test" "$result"
