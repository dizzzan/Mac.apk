#pragma once
#include <cstdlib>
#include <cstring>
#include <initializer_list>

namespace macapk {
inline bool ParseConfiguredWindowSize(const char* value, int& width, int& height) {
    if (!value || *value < '0' || *value > '9') return false;
    char* end = nullptr;
    long w = std::strtol(value, &end, 10);
    if (*end != 'x' || end[1] < '0' || end[1] > '9') return false;
    long h = std::strtol(end + 1, &end, 10);
    if (*end || w < 160 || h < 160 || w > 8192 || h > 8192) return false;
    width = (int)w; height = (int)h; return true;
}

// Named, per-launch presets replace diagnostic shell scripts. Explicit
// environment overrides always win, including an explicit "0".
inline void ApplyCompatibilityProfile() {
    const char* profile = std::getenv("MACAPK_COMPAT_PROFILE");
    if (!profile) return;
    const bool executionCopy = std::strcmp(profile, "execution-copy") == 0;
    if (!executionCopy && std::strcmp(profile, "native-video") != 0) return;
    for (const char* key : {"MACAPK_SYSCALL_TEXT_SHADOW", "MACAPK_SVC_INLINE_DATA", "MACAPK_QUIET_CLASSLOAD"})
        setenv(key, "1", 0);
    if (!executionCopy) return;
    for (const char* key : {
        "MACAPK_ELFEXEC_PRESERVE_FILES", "MACAPK_WINDOW_PRESERVE_DECOR",
        "MACAPK_SVC_SHADOW_ALL", "MACAPK_TPIDR_SHADOW", "MACAPK_TPIDR_SHADOW_CALL",
        "MACAPK_SHADOW_ORIGINAL_LR", "MACAPK_LINKED_SHADOWS", "MACAPK_SHADOW_HOST_RETURN",
        "MACAPK_NO_GUEST_TEXT_PATCH", "MACAPK_SVC_BRK_EXEC_ONLY", "MACAPK_JIT_FAULT_WX",
        "MACAPK_DYNAMIC_SVC_BRK", "MACAPK_DYNAMIC_SVC_SHADOW", "MACAPK_PAIRIP_TLS_CANARY",
        "MACAPK_NATIVE_TEXTURE_WINDOW"}) setenv(key, "1", 0);
    for (const char* key : {"MACAPK_X18_REWRITE_GENERIC", "MACAPK_RAW_SYSCALL_REDIRECT", "MACAPK_NULL_RECOVERY_NEARNULL"})
        setenv(key, "0", 0);
}
}
