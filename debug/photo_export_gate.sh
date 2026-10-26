#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
. "$repo/debug/jdk25_select.sh"
jdk="$(macapk_jdk_home)"
result="$(mktemp -d "${TMPDIR:-/tmp}/macapk-photo-export.XXXXXX")"
trap 'rm -rf "$result"' EXIT
libs="$repo/runtime/build/lib/android-framework.jar:$repo/runtime/build/lib/macapk-loader.jar"
"$jdk/bin/javac" -cp "$libs" -d "$result" "$repo/debug/PhotoExportTest.java"
MACAPK_LOG_LEVEL=99 MACAPK_REAL_LOOPER=0 "$jdk/bin/java" -cp "$result:$libs" com.macapk.loader.PhotoExportTest
xcrun clang++ -std=c++17 -fobjc-arc -I"$repo/runtime/include" -I"$jdk/include" -I"$jdk/include/darwin" \
    "$repo/debug/PhotoExportTest.mm" "$repo/runtime/src/photo_export.mm" \
    -framework AppKit -framework ImageIO -framework UniformTypeIdentifiers -o "$result/png-test"
"$result/png-test"
