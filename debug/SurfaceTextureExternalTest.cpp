// Run the Java pixel regression with the actual runtime JNI and ANGLE bridge.
#include <jni.h>
#include <dlfcn.h>
#include <cassert>
#include <cstdio>
#include <string>
namespace macapk { void RegisterAllNativeMethods(JNIEnv*); }
int main(int argc, char** argv) {
    assert(argc == 3);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    std::string cp = std::string("-Djava.class.path=") + argv[2];
    JavaVMOption options[] = {{cp.data(), nullptr}, {(char*)"-Xcheck:jni", nullptr}};
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8; args.nOptions = 2; args.options = options;
    assert(JNI_CreateJavaVM(&vm, (void**)&env, &args) == JNI_OK);
    auto init = reinterpret_cast<void(*)(JavaVM*)>(dlsym(shim, "__macapk_init_assets"));
    assert(init); init(vm);
    macapk::RegisterAllNativeMethods(env);
    jclass test = env->FindClass("SurfaceTextureExternalTest");
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 1; }
    assert(test);
    auto run = env->GetStaticMethodID(test, "run", "()V");
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 1; }
    assert(run);
    env->CallStaticVoidMethod(test, run);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 1; }
    return 0;
}
