// Headless host for SurfaceControlProducerTest. Only SurfaceView construction
// touches these host APIs; its test subclass consumes frames before presentation.
#include <jni.h>
JNIEXPORT jint JNICALL Java_com_macapk_loader_NativeCocoa_phoneWidth(
        JNIEnv *env, jclass type) { (void)env; (void)type; return 540; }
JNIEXPORT jint JNICALL Java_com_macapk_loader_NativeCocoa_phoneHeight(
        JNIEnv *env, jclass type) { (void)env; (void)type; return 960; }
JNIEXPORT jlong JNICALL Java_com_macapk_loader_NativeCocoa_createMetalSurfaceViewForGl(
        JNIEnv *env, jclass type, jint width, jint height) {
    (void)env; (void)type; (void)width; (void)height; return 0;
}
