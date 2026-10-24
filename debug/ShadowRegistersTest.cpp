#include <array>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <cassert>
extern "C" void shadow_register_probe(void*,void*,void*,uintptr_t);
int main(int argc,char**argv){
 assert(argc==2);auto h=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);assert(h);
 uintptr_t tp; asm volatile("mrs %0, tpidr_el0" : "=r"(tp)); tp |= 1ull << 48; asm volatile("msr tpidr_el0, %0" :: "r"(tp));
 std::array<uint64_t,100> a{},b{};
 for(auto n:{"__macapk_shadow_tls_preserve","__macapk_shadow_branch_preserve"}) {
  auto f=dlsym(h,n);assert(f);shadow_register_probe(f,a.data(),b.data(),0);
  for(int i=0;i<99;++i){if(i==16||i==30||i==31)continue;
   if(a[i]!=b[i]){printf("%s slot %d: %llx != %llx\n",n,i,a[i],b[i]);return 1;}}
  printf("%s: non-result GPRs, full SIMD lanes, NZCV, FPCR and FPSR preserved\n",n);
 }
}
