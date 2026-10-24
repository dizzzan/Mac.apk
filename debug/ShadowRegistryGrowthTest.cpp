#include <cassert>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <initializer_list>
#include <mach/mach.h>
#include <mach/mach_vm.h>

int main(int argc, char** argv) {
    assert(argc == 3);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    assert(shim);
    auto load = (void*(*)(const char*, const char*))dlsym(shim, "__macapk_arm64_native_load_abs");
    auto symbol = (void*(*)(void*, const char*))dlsym(shim, "__macapk_arm64_native_dlsym");
    auto landing = (uintptr_t(*)(uintptr_t))dlsym(shim, "__macapk_shadow_landing_for");
    auto code = (uintptr_t(*)(uintptr_t))dlsym(shim, "__macapk_shadow_code_for");
    auto add = (bool(*)(uintptr_t,uintptr_t,uintptr_t,uintptr_t))dlsym(shim, "__macapk_try_set_syscall_guard_page");
    assert(load && symbol && landing && code && add);
    void* image = load(argv[2], "liblarge-shadow.so");
    assert(image);
    auto probe = (int(*)())symbol(image, "probe");
    assert(probe);
    uintptr_t first = reinterpret_cast<uintptr_t>(probe);
    uintptr_t last = first + 4100 * 0x4000;
    // Both old and new blocks must resolve; first/last also collide in the
    // direct-mapped cache with another page in this image.
    for (uintptr_t pc : {first, last, first + 4096 * 0x4000, first, last}) {
        assert(landing(pc));
        assert(code(pc) != pc);
        mach_vm_address_t address = pc;
        mach_vm_size_t size = 0;
        vm_region_basic_info_data_64_t info{};
        mach_msg_type_number_t count = VM_REGION_BASIC_INFO_COUNT_64;
        mach_port_t object = MACH_PORT_NULL;
        assert(mach_vm_region(mach_task_self(), &address, &size, VM_REGION_BASIC_INFO_64,
            reinterpret_cast<vm_region_info_t>(&info), &count, &object) == KERN_SUCCESS);
        if (object) mach_port_deallocate(mach_task_self(), object);
        assert(!(info.protection & VM_PROT_EXECUTE));
    }
    assert(*reinterpret_cast<uint32_t*>(last) == 0x52800920);
    assert(!add(first & ~uintptr_t(0x3fff), first + 0x4000, code(first), 0));
    for (int i = 0; i < 1000; ++i) assert(probe() == 73);
    puts("4101-page ELF: registry growth, original protection/bytes, cached lookups and execution PASS");
}
