// Explicit local photo export; no screen-capture permission or window-sharing changes.
#include "macapk/photo_export.h"
#import <AppKit/AppKit.h>
#import <ImageIO/ImageIO.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <memory>

namespace macapk {
std::vector<uint8_t> PhotoExportEncodePNG(const uint32_t* pixels, int width, int height) {
    if (!pixels || width <= 0 || height <= 0 || (int64_t)width * height > 32LL * 1024 * 1024) return {};
    CGColorSpaceRef color = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGDataProviderRef provider = CGDataProviderCreateWithData(nullptr, pixels, (size_t)width * height * 4, nullptr);
    CGImageRef image = CGImageCreate(width, height, 8, 32, (size_t)width * 4, color,
        kCGBitmapByteOrder32Little | kCGImageAlphaFirst, provider, nullptr, false, kCGRenderingIntentDefault);
    NSMutableData* png = [NSMutableData data];
    CGImageDestinationRef destination = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)png,
        (__bridge CFStringRef)UTTypePNG.identifier, 1, nullptr);
    bool ok = false;
    if (image && destination) {
        CGImageDestinationAddImage(destination, image, nullptr);
        ok = CGImageDestinationFinalize(destination);
    }
    if (destination) CFRelease(destination);
    if (image) CGImageRelease(image);
    if (provider) CGDataProviderRelease(provider);
    if (color) CGColorSpaceRelease(color);
    if (!ok) return {};
    const auto* bytes = static_cast<const uint8_t*>(png.bytes);
    return {bytes, bytes + png.length};
}
}

@interface MacApkPhotoAccessory : NSTitlebarAccessoryViewController
@property NSButton* photoButton;
@property BOOL busy;
@property uint64_t requestID;
- (void)failed:(NSString*)message;
- (void)chooseDestination:(NSData*)png width:(int)width height:(int)height;
@end

static __weak MacApkPhotoAccessory* photoAccessory;
static JavaVM* photoVM = nullptr;
static jclass photoClass = nullptr;
static jmethodID photoRequest = nullptr;

@implementation MacApkPhotoAccessory
- (void)failed:(NSString*)message {
    self.busy = NO; self.photoButton.enabled = YES;
    self.photoButton.toolTip = @"Save the open photo at its loaded resolution, without interface overlays";
    NSAlert* alert = [[NSAlert alloc] init];
    alert.messageText = @"Photo could not be saved";
    alert.informativeText = message;
    if (self.view.window) [alert beginSheetModalForWindow:self.view.window completionHandler:nil];
}
- (void)savePhoto:(id)sender {
    if (self.busy) return;
    self.busy = YES; self.photoButton.enabled = NO;
    self.photoButton.toolTip = @"Preparing photo…";
    const uint64_t request = ++self.requestID;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (photoVM) {
        if (photoVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
            attached = photoVM->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
        }
    }
    if (!env || !photoClass || !photoRequest) {
        if (attached) photoVM->DetachCurrentThread();
        [self failed:@"Photo export is not ready. Wait for the app to open and try again."];
        return;
    }
    env->CallStaticVoidMethod(photoClass, photoRequest, (jlong)request);
    bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionClear();
    if (attached) photoVM->DetachCurrentThread();
    if (failed) { [self failed:@"The app could not prepare the photo. Try again after it finishes loading."]; return; }
    // The guest may be hung; a late response must not open a panel for another request.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        if (self.busy && self.requestID == request) {
            ++self.requestID;
            [self failed:@"The app did not respond. Wait for it to finish loading and try again."];
        }
    });
}
- (void)chooseDestination:(NSData*)png width:(int)width height:(int)height {
    ++self.requestID; // preparation completed: disarm its timeout
    NSSavePanel* panel = [NSSavePanel savePanel];
    panel.title = @"Save photo";
    panel.message = [NSString stringWithFormat:@"Save the loaded photo at %d × %d pixels, without chat or controls. This preserves the whole image regardless of zoom.", width, height];
    panel.allowedContentTypes = @[UTTypePNG]; panel.canCreateDirectories = YES;
    panel.directoryURL = [[NSFileManager defaultManager] URLsForDirectory:NSPicturesDirectory inDomains:NSUserDomainMask].firstObject;
    NSDateFormatter* date = [[NSDateFormatter alloc] init]; date.dateFormat = @"yyyy-MM-dd HH-mm-ss";
    panel.nameFieldStringValue = [NSString stringWithFormat:@"%@ Photo %@.png", self.view.window.title ?: @"App", [date stringFromDate:NSDate.date]];
    [panel beginSheetModalForWindow:self.view.window completionHandler:^(NSModalResponse response) {
        if (response != NSModalResponseOK) {
            self.busy = NO; self.photoButton.enabled = YES;
            self.photoButton.toolTip = @"Save the open photo at its loaded resolution, without interface overlays";
            return;
        }
        NSURL* destination = panel.URL;
        self.photoButton.toolTip = @"Saving photo…";
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSError* error = nil;
            // Atomic replacement stages in the selected destination directory and
            // leaves an existing file intact if the write fails. Cancel creates no file.
            BOOL saved = [png writeToURL:destination options:NSDataWritingAtomic error:&error];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (!saved) { [self failed:error.localizedDescription ?: @"The destination could not be written."]; return; }
                self.busy = NO; self.photoButton.enabled = YES;
                self.photoButton.toolTip = [NSString stringWithFormat:@"Saved %@ (%d × %d)", destination.lastPathComponent, width, height];
            });
        });
    }];
}
@end

namespace macapk {
static void JNICALL CompletePhoto(JNIEnv* env, jclass, jlong request, jintArray pixels,
                                  jint width, jint height, jstring error) {
    NSString* problem = nil;
    if (error) {
        const char* text = env->GetStringUTFChars(error, nullptr);
        if (text) { problem = [NSString stringWithUTF8String:text]; env->ReleaseStringUTFChars(error, text); }
    }
    std::shared_ptr<std::vector<uint32_t>> copy;
    int64_t count = (int64_t)width * height;
    if (!problem && pixels && width > 0 && height > 0 && count <= 32LL * 1024 * 1024
            && env->GetArrayLength(pixels) >= count) {
        copy = std::make_shared<std::vector<uint32_t>>((size_t)count);
        env->GetIntArrayRegion(pixels, 0, (jsize)count, reinterpret_cast<jint*>(copy->data()));
        if (env->ExceptionCheck()) { env->ExceptionClear(); copy.reset(); }
    }
    if (!problem && !copy) problem = @"The photo did not contain readable image pixels.";
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkPhotoAccessory* accessory = photoAccessory;
        if (!accessory.busy || accessory.requestID != (uint64_t)request) return;
        if (problem) { ++accessory.requestID; [accessory failed:problem]; return; }
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            auto encoded = PhotoExportEncodePNG(copy->data(), width, height);
            NSData* png = encoded.empty() ? nil : [NSData dataWithBytes:encoded.data() length:encoded.size()];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (!accessory.busy || accessory.requestID != (uint64_t)request) return;
                if (!png) { ++accessory.requestID; [accessory failed:@"The photo could not be encoded as PNG."]; return; }
                [accessory chooseDestination:png width:width height:height];
            });
        });
    });
}

void RegisterPhotoExportMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/PhotoExport");
    if (!cls) { env->ExceptionClear(); return; } // tolerate an older installed jar
    JNINativeMethod method = {const_cast<char*>("complete"), const_cast<char*>("(J[IIILjava/lang/String;)V"), reinterpret_cast<void*>(CompletePhoto)};
    jmethodID request = env->GetStaticMethodID(cls, "request", "(J)V");
    if (!request || env->RegisterNatives(cls, &method, 1) != JNI_OK) {
        env->ExceptionClear(); env->DeleteLocalRef(cls); return;
    }
    JavaVM* vm = nullptr; env->GetJavaVM(&vm);
    jclass global = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    // AppKit owns publication and all reads of these handles.
    dispatch_async(dispatch_get_main_queue(), ^{ photoVM = vm; photoClass = global; photoRequest = request; });
}

void PhotoExportInstallTitlebar(void* window) {
    NSWindow* w = (__bridge NSWindow*)window;
    if (!w) return;
    MacApkPhotoAccessory* accessory = [[MacApkPhotoAccessory alloc] init];
    NSButton* button = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"photo.badge.arrow.down" accessibilityDescription:@"Save photo"] target:accessory action:@selector(savePhoto:)];
    button.bordered = NO; button.translatesAutoresizingMaskIntoConstraints = NO;
    button.accessibilityLabel = @"Save photo";
    button.toolTip = @"Save the open photo at its loaded resolution, without interface overlays";
    accessory.photoButton = button;
    NSView* host = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 36, 28)];
    [host addSubview:button];
    [NSLayoutConstraint activateConstraints:@[
        [button.centerYAnchor constraintEqualToAnchor:host.centerYAnchor],
        [button.centerXAnchor constraintEqualToAnchor:host.centerXAnchor],
        [button.widthAnchor constraintEqualToConstant:26], [button.heightAnchor constraintEqualToConstant:26]]];
    accessory.view = host; accessory.layoutAttribute = NSLayoutAttributeTrailing;
    [w addTitlebarAccessoryViewController:accessory];
    photoAccessory = accessory;
}
}
