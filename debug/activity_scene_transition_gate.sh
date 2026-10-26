#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
. "$repo/debug/jdk25_select.sh"
jdk="$(macapk_jdk_home)"
result="$(mktemp -d "${TMPDIR:-/tmp}/macapk-scene-transition.XXXXXX")"
trap 'rm -rf "$result"' EXIT
libs="$repo/runtime/build/lib/android-framework.jar:$repo/runtime/build/lib/macapk-loader.jar"
"$jdk/bin/javac" -cp "$libs" -d "$result" "$repo/debug/ActivitySceneTransitionTest.java"
MACAPK_LOG_LEVEL=99 MACAPK_REAL_LOOPER=0 "$jdk/bin/java" -cp "$result:$libs" ActivitySceneTransitionTest
