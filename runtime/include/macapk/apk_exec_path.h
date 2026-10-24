#pragma once

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

namespace macapk {

// The installed ELF stays readable at its Android path. Only an exec request
// selects the adjacent host launcher, published by apk_native_exec.cpp.
inline bool ApkExecLauncher(const char* path, const char* cwd, char* out, size_t cap) {
    const int saved = errno;
    auto resolve = [&]() {
        if (!path || !*path || !std::strchr(path, '/')) return false;
        char input[PATH_MAX], original[PATH_MAX];
        int n = path[0] != '/' && cwd && *cwd
            ? std::snprintf(input, sizeof input, "%s/%s", cwd, path)
            : std::snprintf(input, sizeof input, "%s", path);
        if (n < 0 || size_t(n) >= sizeof input || !::realpath(input, original)) return false;
        auto magic = [](const char* p, const void* expected, size_t count) {
            int fd = ::open(p, O_RDONLY | O_CLOEXEC);
            if (fd < 0) return false;
            char bytes[32];
            ssize_t got = ::read(fd, bytes, count);
            ::close(fd);
            return got == ssize_t(count) && std::memcmp(bytes, expected, count) == 0;
        };
        if (!magic(original, "\177ELF", 4) || ::access(original, X_OK) != 0) return false;
        char* slash = std::strrchr(original, '/');
        if (!slash) return false;
        *slash = '\0';
        char marker[PATH_MAX];
        n = std::snprintf(marker, sizeof marker, "%s/.macapk-elfexec/.layout", original);
        constexpr char layout[] = "preserve-elf-v1\n";
        if (n < 0 || size_t(n) >= sizeof marker || !magic(marker, layout, sizeof layout - 1)) return false;
        n = std::snprintf(out, cap, "%s/.macapk-elfexec/%s", original, slash + 1);
        const unsigned char macho[] = {0xcf, 0xfa, 0xed, 0xfe};
        return n >= 0 && size_t(n) < cap && magic(out, macho, sizeof macho) && ::access(out, X_OK) == 0;
    };
    const bool found = resolve();
    errno = saved;
    return found;
}

} // namespace macapk
