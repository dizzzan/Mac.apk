#include "macapk/stream_recorder.h"
#import <AVFoundation/AVFoundation.h>
#include <cmath>
#include <cstdio>
#include <unistd.h>
using namespace macapk;
static void require(bool value, const char* what) { if (!value) { fprintf(stderr, "FAIL: %s (%s)\n", what, StreamRecorderMessage().c_str()); exit(1); } }
static void idle() {
    double deadline = StreamRecorderHostSeconds() + 8;
    while (StreamRecorderState() && StreamRecorderHostSeconds() < deadline) usleep(10000);
    require(StreamRecorderState() == 0, "writer finalizes");
}
static void verify(NSString* path) {
    AVURLAsset* asset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:path] options:nil];
    NSArray<AVAssetTrack*>* video = [asset tracksWithMediaType:AVMediaTypeVideo];
    NSArray<AVAssetTrack*>* audio = [asset tracksWithMediaType:AVMediaTypeAudio];
    require(video.count == 1 && audio.count == 1, "one video and one audio track");
    require(video[0].naturalSize.width == 320 && video[0].naturalSize.height == 240, "source dimensions");
    require(CMTimeGetSeconds(asset.duration) > 1.7 && CMTimeGetSeconds(asset.duration) < 2.5, "movie duration");
    NSError* error = nil;
    AVAssetReader* reader = [[AVAssetReader alloc] initWithAsset:asset error:&error];
    AVAssetReaderTrackOutput* v = [AVAssetReaderTrackOutput assetReaderTrackOutputWithTrack:video[0] outputSettings:@{(id)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA)}];
    AVAssetReaderTrackOutput* a = [AVAssetReaderTrackOutput assetReaderTrackOutputWithTrack:audio[0] outputSettings:@{AVFormatIDKey:@(kAudioFormatLinearPCM), AVLinearPCMIsFloatKey:@YES, AVLinearPCMBitDepthKey:@32, AVLinearPCMIsNonInterleaved:@NO}];
    [reader addOutput:v]; [reader addOutput:a]; require([reader startReading], "open recorded movie");
    int frames = 0; double last = -1, firstVideo = -1;
    while (CMSampleBufferRef sample = [v copyNextSampleBuffer]) {
        double pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample));
        if (firstVideo < 0) firstVideo = pts;
        require(pts > last, "increasing video times"); last = pts;
        CVPixelBufferRef pb = CMSampleBufferGetImageBuffer(sample);
        CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        uint8_t* bytes = (uint8_t*)CVPixelBufferGetBaseAddress(pb);
        size_t stride = CVPixelBufferGetBytesPerRow(pb);
        uint8_t* top = bytes + 20 * stride + 20 * 4;
        uint8_t* bottom = bytes + 220 * stride + 20 * 4;
        require(top[2] > 180 && top[1] < 80, "upright red top");
        require(bottom[1] > 180 && bottom[2] < 80, "upright green bottom");
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly); CFRelease(sample); frames++;
    }
    size_t sampleCount = 0; double energy = 0, firstAudio = -1;
    while (CMSampleBufferRef sample = [a copyNextSampleBuffer]) {
        if (firstAudio < 0) firstAudio = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample));
        CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
        size_t length = CMBlockBufferGetDataLength(block);
        std::vector<float> values(length / 4);
        CMBlockBufferCopyDataBytes(block, 0, length, values.data());
        for (float value : values) energy += value * value;
        sampleCount += values.size(); CFRelease(sample);
    }
    require(frames >= 50, "continuous video frame delivery");
    require(sampleCount > 100000 && std::sqrt(energy / sampleCount) > 0.08, "audible decoded AAC samples");
    require(std::abs(firstAudio - firstVideo) < 0.12, "audio/video starts aligned");
    require(reader.status == AVAssetReaderStatusCompleted, "complete readable MP4");
    printf("PASS: %d frames, %zu audio samples, duration %.3fs, A/V offset %.3fs\n", frames, sampleCount, CMTimeGetSeconds(asset.duration), firstAudio - firstVideo);
}
int main(int argc, char** argv) { @autoreleasepool {
    require(argc == 2, "output folder argument");
    std::string folder = argv[1], error;
    require(!StreamRecorderStart(folder + "/empty.mp4", error), "cannot start without video");
    StreamRecorderObserve(101,320,240);
    require(!StreamRecorderStart(folder + "/missing/movie.mp4", error), "invalid destination rejected");
    for (int take = 0; take < 2; take++) {
        StreamRecorderObserve(101,320,240,take == 1); StreamRecorderObserve(202,160,120,take == 1);
        std::string path = folder + "/recording-" + std::to_string(take) + ".mp4";
        require(StreamRecorderStart(path,error), "start recording");
        require(!StreamRecorderStart(path,error), "duplicate start rejected");
        require(StreamRecorderObserve(202,160,120,take == 1) == 0, "second source excluded");
        AVAudioFormat* format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:48000 channels:2];
        AVAudioPCMBuffer* pcm = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:1600]; pcm.frameLength = 1600;
        const double start = StreamRecorderHostSeconds();
        for (int f = 0; f < 60; f++) {
            while (StreamRecorderHostSeconds() < start + f / 30.0) usleep(500);
            double host = StreamRecorderHostSeconds();
            auto ticket = StreamRecorderObserve(101,320,240,take == 1);
            std::vector<uint32_t> pixels(320*240);
            for (int y=0;y<240;y++) for(int x=0;x<320;x++) pixels[y*320+x] = y < 120 ? 0xffff0000 : 0xff00ff00;
            for (int y=50;y<190;y++) for(int x=70+f*2;x<80+f*2;x++) pixels[y*320+x] = 0xffffffff;
            StreamRecorderVideo(ticket,std::move(pixels),320,240,host);
            for (int c=0;c<2;c++) for (int n=0;n<1600;n++) pcm.floatChannelData[c][n] = .2 * sin(2*M_PI*440*(f*1600+n)/48000.0);
            AVAudioTime* time = [AVAudioTime timeWithHostTime:[AVAudioTime hostTimeForSeconds:host]];
            StreamRecorderAudio((__bridge void*)pcm,(__bridge void*)time);
        }
        StreamRecorderStop(); idle();
        require(StreamRecorderMessage().rfind("Saved ",0)==0,"save succeeds");
        verify([NSString stringWithUTF8String:path.c_str()]);
    }
    // A failed/no-frame take must preserve an existing destination.
    std::string preserved = folder + "/preserved.mp4";
    { FILE* f = fopen(preserved.c_str(), "wb"); fputs("existing-file", f); fclose(f); }
    StreamRecorderObserve(101,320,240);
    require(StreamRecorderStart(preserved,error), "start existing destination");
    StreamRecorderStop(); idle();
    NSData* old = [NSData dataWithContentsOfFile:[NSString stringWithUTF8String:preserved.c_str()]];
    require(old.length == 13, "failed recording preserves destination");
    // A raw codec source wins over a larger rendered surface.
    StreamRecorderObserve(101,320,240);
    StreamRecorderObserve(303,1920,1080,true);
    require(StreamRecorderStart(folder+"/priority.mp4",error), "start raw source priority");
    require(StreamRecorderObserve(303,1920,1080,true)==0, "raw codec wins over larger EGL surface");
    StreamRecorderStop(); idle();
    // Resolution changes finalize instead of stretching/mixing unrelated media.
    StreamRecorderObserve(101,320,240);
    require(StreamRecorderStart(folder+"/resize.mp4",error), "start resolution change test");
    auto ticket=StreamRecorderObserve(101,320,240);
    StreamRecorderVideo(ticket,std::vector<uint32_t>(320*240,0xff00ff00),320,240,StreamRecorderHostSeconds());
    usleep(100000);
    StreamRecorderVideo(ticket,std::vector<uint32_t>(640*480,0xffff0000),640,480,StreamRecorderHostSeconds());
    idle(); require(StreamRecorderMessage().find("Video size changed")!=std::string::npos,"resolution change is reported");
    puts("PASS: no-video, invalid path, duplicate start, source isolation, repeat start/stop, resolution change");
} }
