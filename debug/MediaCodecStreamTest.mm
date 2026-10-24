#include <jni.h>
#include <dlfcn.h>
#include <cassert>
#include <cstdio>
#include <string>
#include <vector>
#import <VideoToolbox/VideoToolbox.h>
namespace macapk { void RegisterAllNativeMethods(JNIEnv*); }
struct Sample { bool hevc; std::vector<uint8_t> bytes; };
static void encoded(void* opaque, void*, OSStatus status, VTEncodeInfoFlags, CMSampleBufferRef sample) {
    assert(status == noErr && sample);
    auto& out = *static_cast<Sample*>(opaque);
    auto fmt = CMSampleBufferGetFormatDescription(sample);
    size_t count=0, len=0; const uint8_t* data=nullptr; int prefix=0;
    auto param = [&](size_t i) {
        return out.hevc ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fmt,i,&data,&len,&count,&prefix)
                        : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt,i,&data,&len,&count,&prefix);
    };
    assert(param(0)==noErr);
    for(size_t i=0;i<count;++i) {
        assert(param(i)==noErr);out.bytes.insert(out.bytes.end(),{0,0,0,1});
        out.bytes.insert(out.bytes.end(),data,data+len);
    }
    auto block=CMSampleBufferGetDataBuffer(sample);
    std::vector<uint8_t> frame(CMBlockBufferGetDataLength(block));
    assert(CMBlockBufferCopyDataBytes(block,0,frame.size(),frame.data())==noErr);
    for(size_t p=0;p<frame.size();) {
        size_t n=0;for(int i=0;i<prefix;++i)n=(n<<8)|frame[p++];
        assert(n && p+n<=frame.size());out.bytes.insert(out.bytes.end(),{0,0,0,1});
        out.bytes.insert(out.bytes.end(),frame.data()+p,frame.data()+p+n);p+=n;
    }
}
static jbyteArray sample(JNIEnv* e,jclass,jboolean hevc) {
    Sample out{bool(hevc),{}};VTCompressionSessionRef encoder=nullptr;
    assert(VTCompressionSessionCreate(nullptr,64,64,hevc?kCMVideoCodecType_HEVC:kCMVideoCodecType_H264,
        nullptr,nullptr,nullptr,encoded,&out,&encoder)==noErr);
    VTSessionSetProperty(encoder,kVTCompressionPropertyKey_RealTime,kCFBooleanTrue);
    VTSessionSetProperty(encoder,kVTCompressionPropertyKey_AllowFrameReordering,kCFBooleanFalse);
    CVPixelBufferRef pixel=nullptr;
    assert(CVPixelBufferCreate(nullptr,64,64,kCVPixelFormatType_32BGRA,nullptr,&pixel)==kCVReturnSuccess);
    CVPixelBufferLockBaseAddress(pixel,0);
    auto* data=static_cast<uint8_t*>(CVPixelBufferGetBaseAddress(pixel));size_t stride=CVPixelBufferGetBytesPerRow(pixel);
    for(int y=0;y<64;++y)for(int x=0;x<64;++x){auto*p=data+y*stride+x*4;p[0]=0;p[1]=0;p[2]=255;p[3]=255;}
    CVPixelBufferUnlockBaseAddress(pixel,0);
    assert(VTCompressionSessionEncodeFrame(encoder,pixel,CMTimeMake(0,30),CMTimeMake(1,30),nullptr,nullptr,nullptr)==noErr);
    assert(VTCompressionSessionCompleteFrames(encoder,kCMTimeInvalid)==noErr);
    CVPixelBufferRelease(pixel);VTCompressionSessionInvalidate(encoder);CFRelease(encoder);
    assert(!out.bytes.empty());jbyteArray bytes=e->NewByteArray((jsize)out.bytes.size());
    e->SetByteArrayRegion(bytes,0,(jsize)out.bytes.size(),reinterpret_cast<const jbyte*>(out.bytes.data()));return bytes;
}
int main(int argc,char**argv) {
    assert(argc==3);void*shim=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);assert(shim);
    JavaVM*vm;JNIEnv*e;std::string cp=std::string("-Djava.class.path=")+argv[2];
    JavaVMOption opts[]={{cp.data(),nullptr},{(char*)"-Xcheck:jni",nullptr},{(char*)"-Djava.awt.headless=true",nullptr}};
    JavaVMInitArgs a{};a.version=JNI_VERSION_1_8;a.nOptions=3;a.options=opts;
    assert(JNI_CreateJavaVM(&vm,(void**)&e,&a)==JNI_OK);
    auto init=(void(*)(JavaVM*))dlsym(shim,"__macapk_init_assets");assert(init);init(vm);
    macapk::RegisterAllNativeMethods(e);
    jclass c=e->FindClass("MediaCodecStreamTest");assert(c);
    JNINativeMethod m[]={{(char*)"sample",(char*)"(Z)[B",(void*)sample}};assert(e->RegisterNatives(c,m,1)==0);
    auto run=e->GetStaticMethodID(c,"run","()V");assert(run);e->CallStaticVoidMethod(c,run);
    if(e->ExceptionCheck()){e->ExceptionDescribe();return 1;}
    puts("MediaCodec AVC/HEVC in-band configuration and specific SurfaceTexture output PASS");return 0;
}
