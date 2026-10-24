#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
repo="$PWD"
out="${1:-$repo/runtime/build/shadow-checkpoint-tests}"
mkdir -p "$out"
shim="$repo/runtime/translator/build/libmacapk-bionic-shim.dylib"
export MACAPK_SYSCALL_TEXT_SHADOW=1 MACAPK_SVC_SHADOW_ALL=1
export MACAPK_TPIDR_SHADOW=1 MACAPK_TPIDR_SHADOW_CALL=1 MACAPK_SHADOW_ORIGINAL_LR=1
export MACAPK_NO_GUEST_TEXT_PATCH=1 MACAPK_X18_REWRITE_GENERIC=0
export MACAPK_SHADOW_HOST_RETURN=1 MACAPK_LINKED_SHADOWS=1
export MACAPK_JIT_FAULT_WX=1 MACAPK_DYNAMIC_SVC_BRK=1 MACAPK_DYNAMIC_SVC_SHADOW=1
python3 debug/make_linked_shadow_fixture.py "$out/liblinked.so"
python3 debug/make_shadow_register_probe.py "$out/ShadowRegisterProbe.S"
for test in A64ShadowPageTest TlsShadowTest DynamicShadowTest LinkedShadowTest ProcProcessRootTest ShadowRegistersTest; do
    if [[ "$test" == ShadowRegistersTest ]]; then
        clang++ -std=c++20 -Wl,-rpath,"$repo/thirdparty/angle-prebuilt/lib" "debug/$test.cpp" "$out/ShadowRegisterProbe.S" -o "$out/$test"
    else
        clang++ -std=c++20 -Wl,-rpath,"$repo/thirdparty/angle-prebuilt/lib" "debug/$test.cpp" -o "$out/$test"
    fi
    codesign --force --sign - --entitlements runtime/scripts/jit-entitlements.plist "$out/$test"
    case "$test" in
        A64ShadowPageTest) "$out/$test" ;;
        LinkedShadowTest) "$out/$test" "$shim" "$out/liblinked.so" ;;
        TlsShadowTest) FAR_SHADOW=1 "$out/$test" "$shim" ;;
        *) "$out/$test" "$shim" ;;
    esac
done
