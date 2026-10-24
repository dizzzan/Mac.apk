#include <dlfcn.h>
#include <cassert>
#include <cstdio>
#include <cstdint>
__attribute__((noinline)) int host_fn(){asm volatile("" ::: "memory");return 73;}
int main(int argc,char**argv){assert(argc==3);void*h=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);assert(h);auto o=(void*(*)(const char*,const char*))dlsym(h,"__macapk_arm64_native_load_abs");assert(o);void*w=o(argv[2],"liblinked.so");assert(w);auto rawsym=(void*(*)(void*,const char*))dlsym(h,"__macapk_arm64_native_dlsym");assert(rawsym);auto f=(int(*)(void*,void*))rawsym(w,"JNI_OnLoad");assert(f);auto symbol=(void*(*)(void*,const char*))dlsym(h,"__macapk_arm64_native_dlsym");
auto protect=(int(*)(void*,size_t,int))dlsym(h,"__macapk_arm64_mprotect");
assert(symbol&&protect);uintptr_t entry=(uintptr_t)symbol(w,"JNI_OnLoad");assert(entry);*reinterpret_cast<uintptr_t*>(entry-0x200+0x10000)=(uintptr_t)&host_fn;
assert(protect((void*)(entry&~uintptr_t(0x3fff)),0xc000,5)==0);
for(int i=0;i<1000;i++){int v=f(nullptr,nullptr);if(v!=0x10006){printf("wrong value %#x\n",v);return 1;}}puts("linked direct call, original LR, indirect return, NZCV and scratch-register preservation PASS");}
