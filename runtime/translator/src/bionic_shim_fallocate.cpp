#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>

extern "C" int __macapk_errno_mac2lin(int);

// Linux fallocate operates on a logical file range, including existing holes,
// with optional KEEP_SIZE, PUNCH_HOLE, ZERO_RANGE, etc. Darwin F_PREALLOCATE
// allocates from physical EOF (or a volume position), so substituting it is
// not equivalent: repeated calls can reserve more space each time. Until we
// can implement the requested range semantics, report the Linux filesystem's
// EOPNOTSUPP. Callers can then use their ordinary write/ftruncate fallback.
// Never report allocation success or abort for an unsupported operation.
// Contracts: man7.org/linux/man-pages/man2/fallocate.2.html;
// developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/fcntl.2.html
extern "C" int __macapk_arm64_fallocate(int fd, int /*mode*/, int64_t offset, int64_t length) {
    int flags = ::fcntl(fd, F_GETFL);
    if (flags == -1) { errno = __macapk_errno_mac2lin(errno); return -1; }
    if ((flags & O_ACCMODE) == O_RDONLY) { errno = 9; return -1; } // Linux EBADF
    if (offset < 0 || length <= 0) { errno = 22; return -1; } // Linux EINVAL
    if (offset > std::numeric_limits<int64_t>::max() - length) { errno = 27; return -1; } // EFBIG
    struct stat st{};
    if (::fstat(fd, &st) == -1) { errno = __macapk_errno_mac2lin(errno); return -1; }
    if (S_ISFIFO(st.st_mode)) { errno = 29; return -1; } // ESPIPE
    if (!S_ISREG(st.st_mode)) { errno = 19; return -1; } // ENODEV
    errno = 95; // Linux EOPNOTSUPP (Darwin ENOTSUP has a different value).
    return -1;
}

extern "C" int __macapk_arm64_fallocate64(int fd, int mode, int64_t offset, int64_t length) {
    return __macapk_arm64_fallocate(fd, mode, offset, length);
}
