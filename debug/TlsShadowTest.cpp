#include <cassert>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <sys/mman.h>
#include <thread>
#include <vector>
#include <cstdlib>
#include <mach/mach.h>
#include <mach/mach_vm.h>
int main(int argc,char**argv) {
 assert(argc==2);void* h=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);if(!h){puts(dlerror());return 1;}
 auto init=(void(*)())dlsym(h,"__macapk_install_segv_recovery");
 auto guard=(bool(*)(uintptr_t,uintptr_t,uintptr_t,uintptr_t))dlsym(h,"__macapk_try_set_syscall_guard_page");
 auto refresh=(bool(*)(uintptr_t,uintptr_t))dlsym(h,"__macapk_refresh_execution_shadow");
 auto protect=(int(*)(void*,size_t,int))dlsym(h,"__macapk_arm64_mprotect");
 auto gettp=(void*(*)())dlsym(h,"__macapk_guest_thread_pointer");
 auto* canary=(uintptr_t*)dlsym(h,"__stack_chk_guard");assert(init&&guard&&refresh&&protect&&gettp&&canary);init();
 constexpr size_t page=0x4000,span=8*1024*1024;
 auto*p=(uint32_t*)mmap(nullptr,span,3,MAP_PRIVATE|MAP_ANON,-1,0);assert(p!=MAP_FAILED);
 auto*s=(uint32_t*)((char*)p+4*1024*1024);
 if(std::getenv("FAR_SHADOW")){mach_vm_address_t address=0;bool mapped=false;for(uint64_t delta=0x10000000;delta<0x400000000;delta+=0x10000000){address=(uintptr_t)p+delta;auto kr=mach_vm_allocate(mach_task_self(),&address,3*page,VM_FLAGS_FIXED);if(kr==KERN_SUCCESS){mapped=true;break;}}assert(mapped);s=(uint32_t*)address;}
 std::fill(p,p+page/4,0xd503201f);
 p[0]=0xd53bd040;p[1]=0xf9401400;p[2]=0xd65f03c0; // mrs x0,tp; ldr x0,[x0,#40]; ret
 p[4]=0xd53bd040;p[5]=0xd65f03c0; // return thread pointer
 p[8]=0xd2824692;p[9]=0xd53bd040;p[10]=0xaa1203e0;p[11]=0xd2800012;p[12]=0xd65f03c0; // x18=0x1234; mrs; return x18; clear x18
 p[16]=0xa9bf7bfd;p[17]=0x94000007;p[18]=0xa8c17bfd;p[19]=0xd65f03c0;
 p[24]=0xaa1e03e0;p[25]=0xd65f03c0;
 p[32]=0xa9bf7bfd;p[33]=0x100000e9;p[34]=0xd63f0120;p[35]=0xa8c17bfd;p[36]=0xd65f03c0;
 p[40]=0xaa1e03e0;p[41]=0xd65f03c0;
 p[page/4]=0x528006e0;p[page/4+1]=0xd65f03c0; // page continuation returns55
 p[page/4+64]=0x52800bc0;p[page/4+65]=0xd65f03c0; // conditional off-page destination returns94
 p[48]=0xf100001f;p[49]=0x54000000u|((page/4+64-49)<<5);p[50]=0x528006e0;p[51]=0xd65f03c0;
 assert(mprotect(p+page/4,page,5)==0);
 assert(refresh((uintptr_t)p,(uintptr_t)s));assert(guard((uintptr_t)p,(uintptr_t)p+page,(uintptr_t)s,0));assert(mprotect(p,page,1)==0);
 auto f=(uintptr_t(*)())p;auto tp=(uintptr_t(*)())(p+4);auto x18=(uintptr_t(*)())(p+8);
 auto direct=(uintptr_t(*)())(p+16);auto indirect=(uintptr_t(*)())(p+32);
 uintptr_t lr1=direct(),lr2=indirect();
 printf("direct LR=%lx expected=%lx; indirect LR=%lx expected=%lx\n",lr1,(uintptr_t)(p+18),lr2,(uintptr_t)(p+35));
 if(lr1!=(uintptr_t)(p+18)||lr2!=(uintptr_t)(p+35))return 2;
 auto fall=(uintptr_t(*)())(p+page/4-1);auto cond=(uintptr_t(*)(uintptr_t))(p+48);
 assert(fall()==55);assert(cond(0)==94);assert(cond(1)==55);
 assert(f()==*canary);assert(tp()==(uintptr_t)gettp());
 assert(protect(p,page,3)==0);p[1]=0xf9400400;assert(protect(p,page,5)==0); // TLS slot 1 after reprotection
 assert(f()==(uintptr_t)pthread_self());
 std::vector<uintptr_t> ptrs(2);std::thread a([&]{ptrs[0]=tp();assert(f()==(uintptr_t)pthread_self());assert(x18()==0x1234);});std::thread b([&]{ptrs[1]=tp();assert(f()==(uintptr_t)pthread_self());});a.join();b.join();assert(ptrs[0]!=ptrs[1]);
 assert(p[0]==0xd53bd040 && p[4]==0xd53bd040);puts("TLS values, per-thread identity, original bytes, and reprotection PASS");
}
