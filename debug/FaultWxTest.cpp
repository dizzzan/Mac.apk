// Real generated arm64 code exercises instruction-fetch and subsequent stores.
#include <cassert>
#include <cstdint>
#include <cstring>
#include <thread>
#include <cstdio>
#include <dlfcn.h>
#include <initializer_list>
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <sys/mman.h>
#include <unistd.h>

static int permissions(void* memory) {
    mach_vm_address_t address = uintptr_t(memory); mach_vm_size_t size = 0;
    vm_region_basic_info_data_64_t info{};
    mach_msg_type_number_t count = VM_REGION_BASIC_INFO_COUNT_64;
    mach_port_t object = MACH_PORT_NULL;
    assert(mach_vm_region(mach_task_self(), &address, &size,
        VM_REGION_BASIC_INFO_64, reinterpret_cast<vm_region_info_t>(&info),
        &count, &object) == KERN_SUCCESS);
    if (object) mach_port_deallocate(mach_task_self(), object);
    return info.protection;
}
int main(int argc, char** argv) {
    assert(argc == 2 || argc == 3);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    auto map = reinterpret_cast<void* (*)(void*, size_t, int, int, int, off_t)>(
        dlsym(shim, "__macapk_arm64_mmap"));
    auto protect = reinterpret_cast<int (*)(void*, size_t, int)>(
        dlsym(shim, "__macapk_arm64_mprotect"));
    auto unmap = reinterpret_cast<int (*)(void*, size_t)>(
        dlsym(shim, "__macapk_arm64_libunity_munmap"));
    assert(map && protect && unmap);
    size_t page = size_t(sysconf(_SC_PAGESIZE));
    if (argc == 3 && std::strcmp(argv[2], "disabled") == 0) {
        assert(map(nullptr, page, 7, 0x22, -1, 0) == MAP_FAILED);
        std::puts("disabled fault W^X retains the host's RWX mapping failure");
        return 0;
    }
    for (bool from_mprotect : {false, true}) {
        void* memory = map(nullptr, page, from_mprotect ? 3 : 7, 0x22, -1, 0);
        assert(memory != MAP_FAILED);
        if (from_mprotect) assert(protect(memory, page, 7) == 0);
        auto* words = static_cast<volatile uint32_t*>(memory);
        words[0] = 0x52800020; words[1] = 0xd65f03c0; // mov w0,#1; ret
        assert(permissions(memory) == (VM_PROT_READ | VM_PROT_WRITE));
        auto function = reinterpret_cast<int (*)()>(memory);
        assert(function() == 1);
        assert(permissions(memory) == (VM_PROT_READ | VM_PROT_EXECUTE));
        words[0] = 0x52800040; // mov w0,#2 (write faults and retries)
        assert(permissions(memory) == (VM_PROT_READ | VM_PROT_WRITE));
        assert(function() == 2);
        assert(permissions(memory) == (VM_PROT_READ | VM_PROT_EXECUTE));
        assert(protect(memory, page, 3) == 0);
        assert(permissions(memory) == (VM_PROT_READ | VM_PROT_WRITE));
        assert(unmap(memory, page) == 0);
    }
    auto concurrent = [&] {
        void* memory = map(nullptr, page, 7, 0x22, -1, 0);
        assert(memory != MAP_FAILED);
        auto* words = static_cast<volatile uint32_t*>(memory);
        words[1] = 0xd65f03c0;
        auto function = reinterpret_cast<int (*)()>(memory);
        for (uint32_t value = 1; value <= 100; ++value) {
            words[0] = 0x52800000 | (value << 5);
            assert(function() == int(value));
        }
        assert(unmap(memory, page) == 0);
    };
    std::thread a(concurrent), b(concurrent);
    a.join(); b.join();
    std::puts("generated code executes, modifies and executes again with host W^X preserved");
}
