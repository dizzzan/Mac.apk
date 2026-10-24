#include <jni.h>
#include <dlfcn.h>
#include <cassert>
#include <cstdio>
#include <string>
namespace macapk { void RegisterAllNativeMethods(JNIEnv*); }
static void* shim;
template<class T> T sym(const char* n) { void*p=dlsym(shim,n);if(!p){fprintf(stderr,"missing %s: %s\n",n,dlerror());abort();}return reinterpret_cast<T>(p); }
static jlong from(JNIEnv*e,jclass,jobject s){return (jlong)sym<void*(*)(void*,void*)>("__macapk_arm64_ANativeWindow_fromSurface")(e,s);}
static jobject to(JNIEnv*e,jclass,jlong w){return (jobject)sym<void*(*)(void*,void*)>("__macapk_arm64_ANativeWindow_toSurface")(e,(void*)w);}
static jint width(JNIEnv*,jclass,jlong w){return sym<int(*)(void*)>("__macapk_arm64_ANativeWindow_getWidth")((void*)w);}
static void release(JNIEnv*,jclass,jlong w){sym<void(*)(void*)>("__macapk_arm64_ANativeWindow_release")((void*)w);}
static void acquire(JNIEnv*,jclass,jlong w){sym<void(*)(void*)>("__macapk_arm64_ANativeWindow_acquire")((void*)w);}
static jint draw(JNIEnv*,jclass,jlong d,jlong cfg,jlong ctx,jlong w,jint color){
 auto create=sym<void*(*)(void*,void*,void*,const int*)>("__macapk_arm64_eglCreateWindowSurface");
 auto current=sym<unsigned(*)(void*,void*,void*,void*)>("__macapk_arm64_eglMakeCurrent");
 auto swap=sym<unsigned(*)(void*,void*)>("__macapk_arm64_eglSwapBuffers");
 auto destroy=sym<unsigned(*)(void*,void*)>("__macapk_arm64_eglDestroySurface");
 auto error=sym<unsigned(*)()>("__macapk_arm64_eglGetError");
 int attrs[]={0x3038};void*s=create((void*)d,(void*)cfg,(void*)w,attrs);
 if(!s){fprintf(stderr,"create error %#x\n",error());return 1;}
 if(!current((void*)d,s,s,(void*)ctx))return 2;
 auto clearColor=(void(*)(float,float,float,float))dlsym(RTLD_DEFAULT,"glClearColor");
 auto clear=(void(*)(unsigned))dlsym(RTLD_DEFAULT,"glClear");assert(clear&&clearColor);
 clearColor(((color>>16)&255)/255.f,((color>>8)&255)/255.f,(color&255)/255.f,1);clear(0x4000);
 if(!swap((void*)d,s))return 3;
 if(!current((void*)d,nullptr,nullptr,nullptr))return 4;
 if(!destroy((void*)d,s))return 5;
 return 0;
}
int main(int argc,char**argv){assert(argc==3);shim=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);if(!shim){puts(dlerror());return 1;}
 JavaVM*vm;JNIEnv*e;std::string cp=std::string("-Djava.class.path=")+argv[2];
 JavaVMOption opts[]={{cp.data(),nullptr},{(char*)"-Xcheck:jni",nullptr}};
 JavaVMInitArgs a{};a.version=JNI_VERSION_1_8;a.nOptions=2;a.options=opts;assert(JNI_CreateJavaVM(&vm,(void**)&e,&a)==JNI_OK);
 sym<void(*)(JavaVM*)>("__macapk_init_assets")(vm);macapk::RegisterAllNativeMethods(e);
 jclass c=e->FindClass("NativeTextureWindowTest");assert(c);
 JNINativeMethod m[]={{(char*)"from",(char*)"(Landroid/view/Surface;)J",(void*)from},{(char*)"to",(char*)"(J)Landroid/view/Surface;",(void*)to},{(char*)"width",(char*)"(J)I",(void*)width},{(char*)"release",(char*)"(J)V",(void*)release},{(char*)"acquire",(char*)"(J)V",(void*)acquire},{(char*)"draw",(char*)"(JJJJI)I",(void*)draw}};
 assert(e->RegisterNatives(c,m,6)==0);auto run=e->GetStaticMethodID(c,"run","()V");assert(run);e->CallStaticVoidMethod(c,run);if(e->ExceptionCheck()){e->ExceptionDescribe();return 1;}puts("native TextureView producer checks PASS");return 0;
}
