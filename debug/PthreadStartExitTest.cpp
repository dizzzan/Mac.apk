#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/mman.h>

static pthread_key_t key;
static unsigned destroyed;
static void destroy(void* value) {
    assert(value == reinterpret_cast<void*>(0x1234));
    ++destroyed;
}
static void* normal(void* value) {
    assert(pthread_setspecific(key, value) == 0);
    return value;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!library) { puts(dlerror()); return 1; }
    auto create = reinterpret_cast<int (*)(unsigned long*, const void*, void* (*)(void*), void*)>(
        dlsym(library, "__macapk_arm64_pthread_create"));
    assert(create);
    assert(pthread_key_create(&key, destroy) == 0);
    void* expected = reinterpret_cast<void*>(0x1234);
    unsigned long thread = 0;
    void* result = nullptr;
    assert(create(&thread, nullptr, normal, expected) == 0);
    assert(pthread_join(reinterpret_cast<pthread_t>(thread), &result) == 0);
    assert(result == expected && destroyed == 1);

    // Bionic's __pthread_start exits directly after the callback. Exercise a
    // generated entry routine that leaves an aligned local stack allocation:
    // the wrapper must not return through a displaced host epilogue.
    auto* code = static_cast<uint32_t*>(mmap(nullptr, 16384, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANON, -1, 0));
    assert(code != MAP_FAILED);
    code[0] = 0xd10043ff; // sub sp,sp,#16
    code[1] = 0xd65f03c0; // ret (x0 is the thread result)
    __builtin___clear_cache(reinterpret_cast<char*>(code), reinterpret_cast<char*>(code + 2));
    assert(mprotect(code, 16384, PROT_READ | PROT_EXEC) == 0);
    for (int i = 0; i < 16; ++i) {
        assert(create(&thread, nullptr, reinterpret_cast<void* (*)(void*)>(code), expected) == 0);
        assert(pthread_join(reinterpret_cast<pthread_t>(thread), &result) == 0);
        assert(result == expected);
    }
    assert(munmap(code, 16384) == 0);
    assert(pthread_key_delete(key) == 0);
    puts("PASS: normal and generated pthread returns, join values, TLS destructor");
}
