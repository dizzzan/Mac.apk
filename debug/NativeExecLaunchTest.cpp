// Usage: test shim.dylib absolute-guest-elf absolute-working-directory
#include <dlfcn.h>
#include <sys/wait.h>
#include <unistd.h>
#include <cassert>
#include <cstdio>
#include <string>
#include <cerrno>
int main(int argc,char**argv){
 assert(argc==4);void*h=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);assert(h);
 auto run=(int(*)(const char*,char*const[],char*const[]))dlsym(h,"__macapk_arm64_execve");
 auto runv=(int(*)(const char*,char*const[]))dlsym(h,"__macapk_arm64_execv");
 auto raw=(long(*)(long,long,long,long,long,long,long))dlsym(h,"__macapk_arm64_raw_syscall");
 auto set=(int(*)(const char*,const char*,int))dlsym(h,"__macapk_guest_setenv");
 assert(run&&runv&&raw&&set);assert(set("EXEC_FIXTURE_VALUE","native environment",1)==0);
 for(int mode=0;mode<3;++mode){
  int p[2];assert(pipe(p)==0);pid_t pid=fork();assert(pid>=0);
  if(!pid){close(p[0]);dup2(p[1],1);close(p[1]);assert(chdir(argv[3])==0);
   char* a[]={(char*)"caller-specified-argv0",(char*)"native argument",nullptr};
   char* e[]={(char*)"EXEC_FIXTURE_VALUE=native environment",nullptr};
   if(mode==0)run(argv[2],a,e);else if(mode==1)runv(argv[2],a);else raw(221,(long)argv[2],(long)a,(long)e,0,0,0);
   perror("exec");_exit(111);
  }
  close(p[1]);std::string out;char b[512];ssize_t n;while((n=read(p[0],b,sizeof b))>0)out.append(b,n);close(p[0]);
  int s;assert(waitpid(pid,&s,0)==pid);assert(WIFEXITED(s)&&WEXITSTATUS(s)==73);
  std::string expected=std::string("caller-specified-argv0\nnative argument\nnative environment\n")+argv[3]+"\n";
  if(out!=expected){fprintf(stderr,"unexpected output %s",out.c_str());return 1;}
 }
 puts("PASS: native execve, execv, raw execve preserve argv/env/cwd and child status");
}
