#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
int main(int argc,char**argv) {
 assert(argc==2);auto h=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);assert(h);
 auto raw=(long(*)(long,long,long,long,long,long,long))dlsym(h,"__macapk_arm64_raw_syscall");assert(raw);
 for(const auto& base:{std::string("/proc/self/root"),"/proc/"+std::to_string(getpid())+"/root"}) {
  char target[16]{};auto n=raw(78,-100,(long)base.c_str(),(long)target,15,0,0);if(n!=1||target[0]!='/'){std::printf("root readlink failed: %ld\n",n);return 1;}
  auto maps=base+"/proc/self/maps";long fd=raw(56,-100,(long)maps.c_str(),0,0,0,0);assert(fd>=0);char data[128]{};assert(raw(63,fd,(long)data,127,0,0,0)>0);assert(std::strchr(data,'-'));assert(raw(57,fd,0,0,0,0,0)==0);
  auto fds=base+"/proc/self/fd";fd=raw(56,-100,(long)fds.c_str(),0200000,0,0,0);assert(fd>=0);assert(raw(57,fd,0,0,0,0,0)==0);
 }
 puts("Own-process root readlink and procfs traversal PASS");
}
