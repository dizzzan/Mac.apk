// User-controlled media recording. Source pixels come from a media surface before the
// Android UI is composited; audio is this process's mixer, not desktop capture.
// FLAG_SECURE still governs window sharing/screenshots. This explicit local
// export feature is independent of that window policy.
#include "macapk/stream_recorder.h"
#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <mach/mach_time.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <unordered_map>
#include <memory>

namespace macapk {
namespace {
struct Source { int width, height; double seen; bool renderedSurface; };
std::mutex metadataMutex;
std::unordered_map<uint64_t, Source> sources;
std::string message;
std::atomic<int> state{0}, pendingVideo{0}, pendingAudio{0};
std::atomic<uint64_t> activeSource{0}, activeTicket{0};
uint64_t nextTicket = 0; // writer queue owns everything below
AVAssetWriter* writer;
AVAssetWriterInput* videoInput;
AVAssetWriterInput* audioInput;
AVAssetWriterInputPixelBufferAdaptor* adaptor;
NSURL* outputURL;
NSURL* temporaryURL;
dispatch_source_t watchdog;
double origin = -1, lastVideo = -1, lastAudioEnd = -1, lastArrival = 0;
int videoWidth = 0, videoHeight = 0;
bool hasVideo = false;

dispatch_queue_t Queue() {
    static dispatch_queue_t q = dispatch_queue_create("org.macapk.stream-recording", DISPATCH_QUEUE_SERIAL);
    return q;
}
void Publish(const std::string& value) {
    { std::lock_guard<std::mutex> lock(metadataMutex); message = value; }
    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter] postNotificationName:@"MacApkRecordingChanged" object:nil];
    });
}
std::string Error(NSError* error) {
    return error ? std::string(error.localizedDescription.UTF8String) : "The media encoder could not write the recording.";
}
void Clear() {
    writer = nil; videoInput = nil; audioInput = nil; adaptor = nil;
    outputURL = nil; temporaryURL = nil;
    origin = lastVideo = lastAudioEnd = -1; hasVideo = false;
    state.store(0);
}
void Finish(const std::string& reason = "") {
    if (state.load() == 0 || state.load() == 2) return;
    activeTicket.store(0); activeSource.store(0); state.store(2);
    if (watchdog) { dispatch_source_cancel(watchdog); watchdog = nil; }
    Publish("Finishing recording…");
    if (!hasVideo || writer.status != AVAssetWriterStatusWriting) {
        std::string failure = reason.empty() ? (hasVideo ? Error(writer.error) : "No video frames arrived.") : reason;
        [writer cancelWriting];
        if (temporaryURL) [[NSFileManager defaultManager] removeItemAtURL:temporaryURL error:nil];
        Clear(); Publish("Recording failed: " + failure); return;
    }
    // A mixer tap can already have queued audio beyond the last video. End the
    // movie at the last frame so leaving a stream cannot produce an audio tail.
    [writer endSessionAtSourceTime:CMTimeMakeWithSeconds(lastVideo + 1.0 / 30.0, 1000000)];
    [videoInput markAsFinished]; [audioInput markAsFinished];
    AVAssetWriter* finishingWriter = writer;
    NSURL* destination = outputURL;
    NSURL* temporary = temporaryURL;
    const std::string completionReason = reason;
    [finishingWriter finishWritingWithCompletionHandler:^{
        dispatch_async(Queue(), ^{
            std::string result;
            if (finishingWriter.status == AVAssetWriterStatusCompleted) {
                // The save panel authorizes replacement. Never touch an existing
                // destination until the new movie has finalized successfully.
                if (::rename(temporary.fileSystemRepresentation, destination.fileSystemRepresentation) == 0)
                    result = "Saved " + std::string(destination.path.UTF8String) + (completionReason.empty() ? "" : " — " + completionReason);
                else result = "Recording saved at " + std::string(temporary.path.UTF8String) + "; could not replace the destination: " + strerror(errno);
            } else {
                result = "Recording failed: " + Error(finishingWriter.error);
                [[NSFileManager defaultManager] removeItemAtURL:temporary error:nil];
            }
            Clear(); Publish(result);
        });
    }];
}
bool Configure(double firstFrame) {
    NSError* error = nil;
    writer = [[AVAssetWriter alloc] initWithURL:temporaryURL fileType:AVFileTypeMPEG4 error:&error];
    if (!writer) { Finish(Error(error)); return false; }
    // Keep encoder scratch files on the chosen volume too, rather than
    // letting AVFoundation select a system temporary directory.
    writer.directoryForTemporaryFiles = outputURL.URLByDeletingLastPathComponent;
    const int w = (videoWidth + 1) & ~1, h = (videoHeight + 1) & ~1;
    videoInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo outputSettings:@{
        AVVideoCodecKey: AVVideoCodecTypeH264, AVVideoWidthKey: @(w), AVVideoHeightKey: @(h),
        AVVideoCompressionPropertiesKey: @{ AVVideoAverageBitRateKey: @(std::max(1500000, w * h * 5)),
                                            AVVideoMaxKeyFrameIntervalKey: @60 }}];
    audioInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio outputSettings:@{
        AVFormatIDKey: @(kAudioFormatMPEG4AAC), AVSampleRateKey: @48000,
        AVNumberOfChannelsKey: @2, AVEncoderBitRateKey: @128000 }];
    videoInput.expectsMediaDataInRealTime = YES; audioInput.expectsMediaDataInRealTime = YES;
    adaptor = [AVAssetWriterInputPixelBufferAdaptor assetWriterInputPixelBufferAdaptorWithAssetWriterInput:videoInput
        sourcePixelBufferAttributes:@{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
                                      (id)kCVPixelBufferWidthKey: @(w), (id)kCVPixelBufferHeightKey: @(h),
                                      (id)kCVPixelBufferIOSurfacePropertiesKey: @{} }];
    if (![writer canAddInput:videoInput] || ![writer canAddInput:audioInput]) {
        Finish("H.264/AAC encoding is unavailable."); return false;
    }
    [writer addInput:videoInput]; [writer addInput:audioInput];
    writer.shouldOptimizeForNetworkUse = YES;
    if (![writer startWriting]) { Finish(Error(writer.error)); return false; }
    [writer startSessionAtSourceTime:kCMTimeZero]; origin = firstFrame;
    return true;
}
}

double StreamRecorderHostSeconds() {
    static const double scale = [] { mach_timebase_info_data_t t; mach_timebase_info(&t); return double(t.numer) / t.denom / 1e9; }();
    return mach_absolute_time() * scale;
}
uint64_t StreamRecorderObserve(uint64_t source, int width, int height, bool renderedSurface) {
    if (width < 16 || height < 16 || width > 8192 || height > 8192) return 0;
    const double now = StreamRecorderHostSeconds();
    { std::lock_guard<std::mutex> lock(metadataMutex);
      sources[source] = {width, height, now, renderedSurface};
      // Keep observation memory bounded across long scrolling sessions.
      for (auto it = sources.begin(); it != sources.end();) {
          if (now - it->second.seen > 3) it = sources.erase(it); else ++it;
      }
    }
    return activeSource.load() == source ? activeTicket.load() : 0;
}
void StreamRecorderVideo(uint64_t ticket, std::vector<uint32_t> bgra, int width, int height, double hostSeconds) {
    if (!ticket || activeTicket.load() != ticket || bgra.size() != size_t(width) * height) return;
    if (pendingVideo.fetch_add(1) >= 4) { pendingVideo.fetch_sub(1); return; }
    auto pixels = std::make_shared<std::vector<uint32_t>>(std::move(bgra));
    dispatch_async(Queue(), ^{ @autoreleasepool {
        pendingVideo.fetch_sub(1);
        if (activeTicket.load() != ticket) return;
        if (width != videoWidth || height != videoHeight) { Finish("Video size changed; start a new recording to continue."); return; }
        lastArrival = hostSeconds;
        if (!writer && !Configure(hostSeconds)) return;
        const double pts = hostSeconds - origin;
        if (pts <= lastVideo || !videoInput.readyForMoreMediaData) return;
        CVPixelBufferRef pixel = nullptr;
        if (!adaptor.pixelBufferPool || CVPixelBufferPoolCreatePixelBuffer(nullptr, adaptor.pixelBufferPool, &pixel) != kCVReturnSuccess) {
            Finish("Could not allocate a video encoding buffer."); return;
        }
        CVPixelBufferLockBaseAddress(pixel, 0);
        auto* dest = static_cast<uint8_t*>(CVPixelBufferGetBaseAddress(pixel));
        const size_t stride = CVPixelBufferGetBytesPerRow(pixel);
        memset(dest, 0, stride * CVPixelBufferGetHeight(pixel));
        for (int y = 0; y < height; ++y) memcpy(dest + y * stride, pixels->data() + y * width, width * 4);
        CVPixelBufferUnlockBaseAddress(pixel, 0);
        bool ok = [adaptor appendPixelBuffer:pixel withPresentationTime:CMTimeMakeWithSeconds(pts, 1000000)];
        CVPixelBufferRelease(pixel);
        if (!ok) { Finish(Error(writer.error)); return; }
        lastVideo = pts; hasVideo = true;
    }});
}
void StreamRecorderAudio(void* pcmBuffer, void* audioTime) {
    const uint64_t ticket = activeTicket.load();
    if (!ticket) return;
    AVAudioPCMBuffer* buffer = (__bridge AVAudioPCMBuffer*)pcmBuffer;
    AVAudioTime* time = (__bridge AVAudioTime*)audioTime;
    const auto frames = buffer.frameLength;
    const double rate = buffer.format.sampleRate;
    const auto channels = buffer.format.channelCount;
    if (!frames || !channels || !buffer.floatChannelData || rate <= 0 || frames > rate) return;
    if (pendingAudio.fetch_add(1) >= 12) { pendingAudio.fetch_sub(1); return; }
    double host = time.hostTimeValid ? [AVAudioTime secondsForHostTime:time.hostTime] : StreamRecorderHostSeconds() - frames / rate;
    auto samples = std::make_shared<std::vector<float>>(frames * 2);
    bool planar = !buffer.format.interleaved;
    for (unsigned f = 0; f < frames; ++f) for (unsigned c = 0; c < 2; ++c) {
        unsigned source = std::min(c, channels - 1);
        (*samples)[2 * f + c] = planar ? buffer.floatChannelData[source][f] : buffer.floatChannelData[0][f * channels + source];
    }
    dispatch_async(Queue(), ^{ @autoreleasepool {
        pendingAudio.fetch_sub(1);
        if (ticket != activeTicket.load() || !hasVideo || !audioInput.readyForMoreMediaData) return;
        double pts = host - origin;
        // A tap buffer can straddle the first video frame, or arrive late after
        // an output route change. Trim overlaps; never move audio backwards.
        unsigned skip = (unsigned)std::min<double>(frames, std::max(0.0, std::ceil((std::max(0.0, lastAudioEnd) - pts) * rate)));
        if (skip == frames) return;
        pts += skip / rate;
        const unsigned count = frames - skip;
        AudioStreamBasicDescription asbd{};
        asbd.mSampleRate = rate; asbd.mFormatID = kAudioFormatLinearPCM;
        asbd.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        asbd.mChannelsPerFrame = 2; asbd.mBitsPerChannel = 32;
        asbd.mFramesPerPacket = 1; asbd.mBytesPerPacket = asbd.mBytesPerFrame = 8;
        CMAudioFormatDescriptionRef format = nullptr;
        CMBlockBufferRef block = nullptr; CMSampleBufferRef sample = nullptr;
        OSStatus status = CMAudioFormatDescriptionCreate(nullptr, &asbd, 0, nullptr, 0, nullptr, nullptr, &format);
        const size_t bytes = count * 8;
        if (!status) status = CMBlockBufferCreateWithMemoryBlock(nullptr, nullptr, bytes, nullptr, nullptr, 0, bytes, 0, &block);
        if (!status) status = CMBlockBufferReplaceDataBytes(samples->data() + skip * 2, block, 0, bytes);
        if (!status) status = CMAudioSampleBufferCreateReadyWithPacketDescriptions(nullptr, block, format, count,
            CMTimeMakeWithSeconds(pts, 1000000), nullptr, &sample);
        bool ok = !status && [audioInput appendSampleBuffer:sample];
        if (sample) CFRelease(sample); if (block) CFRelease(block); if (format) CFRelease(format);
        if (!ok) Finish(status ? "Could not create an audio encoding buffer." : Error(writer.error));
        else lastAudioEnd = pts + count / rate;
    }});
}
bool StreamRecorderStart(const std::string& path, std::string& error) {
    __block bool ok = false;
    __block std::string failure;
    dispatch_sync(Queue(), ^{ @autoreleasepool {
        if (state.load()) { failure = "A recording is already active or finishing."; return; }
        uint64_t selected = 0; Source best{};
        const double now = StreamRecorderHostSeconds();
        { std::lock_guard<std::mutex> lock(metadataMutex);
          for (const auto& entry : sources) if (now - entry.second.seen < 1.5 &&
              (!selected || (best.renderedSurface && !entry.second.renderedSurface) ||
               (best.renderedSurface == entry.second.renderedSurface &&
                entry.second.width * entry.second.height > best.width * best.height))) {
              selected = entry.first; best = entry.second;
          }
        }
        if (!selected) { failure = "Play a video first, then start recording."; return; }
        outputURL = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path.c_str()]];
        NSURL* parent = outputURL.URLByDeletingLastPathComponent;
        if (path.empty() || ![[NSFileManager defaultManager] isWritableFileAtPath:parent.path]) {
            Clear(); failure = "Choose a writable folder for the recording."; return;
        }
        // A visible sibling lets the user see the in-progress recording in
        // Finder. Rename it only after finalization to preserve an existing file.
        temporaryURL = [parent URLByAppendingPathComponent:[NSString stringWithFormat:@"Mac.apk recording %@.mp4", NSUUID.UUID.UUIDString]];
        videoWidth = best.width; videoHeight = best.height; lastArrival = now;
        state.store(1); activeSource.store(selected); activeTicket.store(++nextTicket);
        watchdog = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, Queue());
        dispatch_source_set_timer(watchdog, dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), NSEC_PER_SEC, NSEC_PER_SEC / 10);
        dispatch_source_set_event_handler(watchdog, ^{ if (StreamRecorderHostSeconds() - lastArrival > 3) Finish("Video ended or changed; start a new recording to continue."); });
        dispatch_resume(watchdog);
        Publish("Recording video and app audio…"); ok = true;
    }});
    error = failure; return ok;
}
void StreamRecorderStop() {
    // Leave the ticket valid until previously enqueued media is appended.
    dispatch_async(Queue(), ^{ @autoreleasepool { Finish(); } });
}
int StreamRecorderState() { return state.load(); }
std::string StreamRecorderMessage() { std::lock_guard<std::mutex> lock(metadataMutex); return message; }
void StreamRecorderFinishOnExit() {
    if (!state.load()) return;
    StreamRecorderStop();
    // The process exits with _exit, so an ordinary destructor cannot finalize.
    // No main-thread callbacks are needed by the writer completion handler.
    const double deadline = StreamRecorderHostSeconds() + 4;
    while (state.load() && StreamRecorderHostSeconds() < deadline) usleep(10000);
}
}

@interface MacApkRecordingAccessory : NSTitlebarAccessoryViewController
@property NSButton* recordButton;
@property BOOL choosingDestination;
@property NSString* lastErrorShown;
@end
@implementation MacApkRecordingAccessory
- (void)dealloc { [[NSNotificationCenter defaultCenter] removeObserver:self]; }
- (void)updateRecording:(NSNotification*)notification {
    const int state = macapk::StreamRecorderState();
    NSString* label = state == 1 ? @"Stop recording" : state == 2 ? @"Finishing recording" : @"Start recording";
    self.recordButton.image = [NSImage imageWithSystemSymbolName:state == 1 ? @"stop.circle.fill" : @"record.circle" accessibilityDescription:label];
    self.recordButton.contentTintColor = state == 1 ? NSColor.systemRedColor : nil;
    self.recordButton.enabled = state != 2 && !self.choosingDestination;
    self.recordButton.accessibilityLabel = label;
    std::string message = macapk::StreamRecorderMessage();
    self.recordButton.toolTip = message.empty() ? @"Record video and app audio to an MP4 file" : [NSString stringWithUTF8String:message.c_str()];
    NSString* notice = [NSString stringWithUTF8String:message.c_str()];
    if (state == 0 && notification && message.rfind("Recording failed:", 0) == 0 &&
            ![self.lastErrorShown isEqualToString:notice]) {
        self.lastErrorShown = notice;
        NSAlert* alert = [[NSAlert alloc] init]; alert.messageText = @"Recording could not be saved";
        alert.informativeText = [NSString stringWithUTF8String:message.c_str()];
        [alert beginSheetModalForWindow:self.view.window completionHandler:nil];
    }
}
- (void)record:(id)sender {
    if (macapk::StreamRecorderState() == 1) { macapk::StreamRecorderStop(); return; }
    if (macapk::StreamRecorderState() || self.choosingDestination) return;
    NSSavePanel* panel = [NSSavePanel savePanel];
    panel.title = @"Save video recording"; panel.message = @"Saves the playing video and this app’s audio, without chat or interface overlays.";
    panel.allowedContentTypes = @[UTTypeMPEG4Movie]; panel.canCreateDirectories = YES;
    panel.directoryURL = [[NSFileManager defaultManager] URLsForDirectory:NSMoviesDirectory inDomains:NSUserDomainMask].firstObject;
    NSDateFormatter* date = [[NSDateFormatter alloc] init]; date.dateFormat = @"yyyy-MM-dd HH-mm-ss";
    panel.nameFieldStringValue = [NSString stringWithFormat:@"%@ %@.mp4", self.view.window.title ?: @"Video", [date stringFromDate:NSDate.date]];
    self.choosingDestination = YES; [self updateRecording:nil];
    [panel beginSheetModalForWindow:self.view.window completionHandler:^(NSModalResponse response) {
        self.choosingDestination = NO;
        if (response == NSModalResponseOK) {
            std::string error;
            if (!macapk::StreamRecorderStart(panel.URL.path.UTF8String, error)) {
                NSAlert* alert = [[NSAlert alloc] init]; alert.messageText = @"Could not start recording";
                alert.informativeText = [NSString stringWithUTF8String:error.c_str()];
                [alert beginSheetModalForWindow:self.view.window completionHandler:nil];
            }
        }
        [self updateRecording:nil];
    }];
}
@end

namespace macapk {
void StreamRecorderInstallTitlebar(void* window) {
    NSWindow* w = (__bridge NSWindow*)window;
    if (!w) return;
    MacApkRecordingAccessory* accessory = [[MacApkRecordingAccessory alloc] init];
    NSButton* button = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"record.circle" accessibilityDescription:@"Start recording"] target:accessory action:@selector(record:)];
    button.bordered = NO; button.translatesAutoresizingMaskIntoConstraints = NO;
    accessory.recordButton = button;
    NSView* host = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 36, 28)];
    [host addSubview:button];
    [NSLayoutConstraint activateConstraints:@[
        [button.centerYAnchor constraintEqualToAnchor:host.centerYAnchor],
        [button.centerXAnchor constraintEqualToAnchor:host.centerXAnchor],
        [button.widthAnchor constraintEqualToConstant:26], [button.heightAnchor constraintEqualToConstant:26]]];
    accessory.view = host; accessory.layoutAttribute = NSLayoutAttributeTrailing;
    [w addTitlebarAccessoryViewController:accessory];
    [[NSNotificationCenter defaultCenter] addObserver:accessory selector:@selector(updateRecording:) name:@"MacApkRecordingChanged" object:nil];
    [accessory updateRecording:nil];
}
}
