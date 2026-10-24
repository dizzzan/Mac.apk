// Standalone JNI entry point for the process-launch regression fixture.
#include <jni.h>
#include "macapk/apk_native_exec.h"
#include <cstdio>
bool RegisterNativeProcessBridge(JNIEnv*);
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
 JNIEnv* env=nullptr;
 if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
 if(!RegisterNativeProcessBridge(env)){std::fprintf(stderr,"process bridge registration failed\n");return JNI_ERR;}
 return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT jlong JNICALL Java_PreservedExecTest_reconcile(JNIEnv* env,jclass,jstring root,jstring bin) {
 const char* r=env->GetStringUTFChars(root,nullptr);const char* b=env->GetStringUTFChars(bin,nullptr);
 auto n=macapk::ReconcileApkNativeExecutables(r,b,"","");
 env->ReleaseStringUTFChars(root,r);env->ReleaseStringUTFChars(bin,b);return n;
}
