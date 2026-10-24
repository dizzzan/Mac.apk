#!/bin/bash
# Run after staging the matching runtime shim and macapk-elfexec in build/bin.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
bin=${1:-"$repo/runtime/build/bin"}
bin=$(cd "$bin" && pwd -P)
jdk=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/macapk-native-exec.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
tmp=$(cd "$tmp" && pwd -P)
export MACAPK_ELFEXEC=1
python3 "$repo/debug/make_native_exec_fixture.py" "$tmp/original.so"
mkdir -p "$tmp/lib/arm64"
cp "$tmp/original.so" "$tmp/lib/arm64/libexec_fixture.so"
clang++ -std=c++17 -dynamiclib -I"$repo/runtime/include" -I"$jdk/include" -I"$jdk/include/darwin" \
  "$repo/debug/NativeProcessTestBridge.cpp" "$repo/runtime/src/native_process.cpp" \
  "$repo/runtime/src/apk_native_exec.cpp" -o "$tmp/bridge.dylib"
"$jdk/bin/javac" -d "$tmp" "$repo/debug/PreservedExecTest.java"
if MACAPK_ELFEXEC_PRESERVE_FILES=0 "$jdk/bin/java" -cp "$tmp" PreservedExecTest \
  "$tmp/bridge.dylib" "$tmp/lib" "$bin" "$tmp/original.so" > "$tmp/before.log" 2>&1; then
  echo 'FAIL: legacy install unexpectedly preserved ELF bytes' >&2; exit 1
fi
if ! rg -q 'installed ELF bytes changed' "$tmp/before.log"; then cat "$tmp/before.log"; exit 1; fi
for pass in 1 2; do
  MACAPK_ELFEXEC_PRESERVE_FILES=1 "$jdk/bin/java" -cp "$tmp" PreservedExecTest \
    "$tmp/bridge.dylib" "$tmp/lib" "$bin" "$tmp/original.so"
done
clang++ -std=c++17 "$repo/debug/NativeExecLaunchTest.cpp" -o "$tmp/native"
codesign --force --sign - --entitlements "$repo/runtime/scripts/jit-entitlements.plist" "$tmp/native"
"$tmp/native" "$bin/libmacapk-bionic-shim.dylib" "$tmp/lib/arm64/libexec_fixture.so" "$tmp/lib"
