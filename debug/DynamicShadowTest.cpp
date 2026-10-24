#include <cassert>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
int main(int argc,char**argv){
 assert(argc==2);void*h=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);assert(h);
 auto map=(void*(*)(void*,size_t,int,int,int,off_t))dlsym(h,"__macapk_arm64_mmap");
 auto unmap=(int(*)(void*,size_t))dlsym(h,"__macapk_arm64_libunity_munmap");assert(map&&unmap);
 constexpr uint32_t code[]={0xaa0003e8,0xaa0103e0,0xaa0203e1,0xaa0303e2,0xaa0403e3,0xaa0503e4,0xaa0603e5,0xd4000001,0xd65f03c0};
 void*hint=nullptr;
 for(int n=0;n<10;++n){
  auto*p=(uint32_t*)map(hint,0x4000,7,0x22,-1,0);assert(p!=MAP_FAILED);hint=p;
  std::memcpy(p,code,sizeof(code));auto f=(long(*)(long,long,long,long,long,long,long))p;
  errno=EDOM;assert(f(172,0,0,0,0,0,0)==getpid());assert(errno==EDOM);
  assert(std::memcmp(p,code,sizeof(code))==0);assert(f(63,-1,0,0,0,0,0)==-9);
  p[0]=0x52800920;p[1]=0xd65f03c0; // mov w0,#73; ret (write fault refreshes copy)
  assert(f(0,0,0,0,0,0,0)==73);assert(p[0]==0x52800920);
  assert(unmap(p,0x4000)==0);
 }
 puts("Generated syscalls preserve source bytes, errno, self-modification, and mapping reuse");
}
