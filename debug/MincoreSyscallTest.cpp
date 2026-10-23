#include <cassert>
#include <cerrno>
#include <cstdio>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

int main(int argc, char** argv) {
    assert(argc == 2);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(shim);
    auto raw = reinterpret_cast<long (*)(long, long, long, long, long, long, long)>(
        dlsym(shim, "__macapk_arm64_raw_syscall"));
    assert(raw);
    size_t page = size_t(sysconf(_SC_PAGESIZE));
    auto* memory = static_cast<char*>(mmap(nullptr, 2 * page, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANON, -1, 0));
    assert(memory != MAP_FAILED);
    memory[0] = 1;
    memory[page] = 2;
    unsigned char vec[] = {0xa5, 0xa5, 0xa5, 0xa5};
    errno = EDOM;
    auto query = [&](void* address, size_t length, void* output) {
        return raw(232, reinterpret_cast<long>(address), length,
                   reinterpret_cast<long>(output), 0, 0, 0);
    };
    assert(query(memory, page + 1, vec + 1) == 0);
    assert((vec[1] & 1) && (vec[2] & 1));
    assert(vec[0] == 0xa5 && vec[3] == 0xa5 && errno == EDOM);
    assert(query(memory + 1, page, vec + 1) == -22);
    assert(query(memory, page, nullptr) == -14);
    assert(query(memory, 0, nullptr) == 0);
    assert(munmap(memory + page, page) == 0);
    assert(query(memory + page, page, vec + 1) == -12);
    assert(errno == EDOM);
    assert(munmap(memory, page) == 0);
    std::puts("mincore reports resident pages, respects vector bounds and preserves raw syscall errors");
}
