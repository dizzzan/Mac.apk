# Clapper playback checkpoint — 2026-10-23

Clapper 14.6.1 reaches the feed, plays video continuously with sound, and switches
between clips. The owner confirmed **smooth playback and synchronized sound**
after the MediaCodec input-buffer fix. This is a fork checkpoint, not an upstream
PR or a claim of compatibility with all applications.

## Live playback work — 2026-10-24

Live support remains under validation. The original account showed Clapper's
own "Device Environment Abnormality" overlay, with copyright-protection text,
after video decoding had started. Its secure-window capture restriction remains
intact. At the owner's request the active debug app's private and external data
were cleared, and the owner completed setup with a test account.
The owner subsequently confirmed the same environment warning on the test
account, with live video visible behind it and no audible sound. Changing
accounts did not resolve that warning.

Generic media and animation fixes now cover three additional Android contracts:

- AVC/HEVC MediaCodec can start without format CSD, then accept parameter sets
  in queued buffers. VideoToolbox creates the session after complete headers
  arrive, handles header-only buffers and early EOS, and preserves existing
  format-CSD operation. Decoded frames go to the configured SurfaceTexture with
  their presentation timestamps, even when another texture was created later.
- AnimatorSet tracks each run and cancels only active children. Cancelling an
  idle/completed set is silent; end callbacks can cancel or restart the set
  without recursively notifying themselves or starting cancelled successors.
- View.animate() reuses a per-view ViewPropertyAnimator. Property batches start
  on the next animation frame without requiring explicit start(), fixing a live
  screen left at alpha zero. Cancellation retains the current value and does
  not run withEndAction; later batches do not replay previous actions.

References: [MediaCodec CSD](https://developer.android.com/reference/android/media/MediaCodec#CSD),
[AOSP AnimatorSet](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/animation/AnimatorSet.java),
[AOSP ViewPropertyAnimator](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewPropertyAnimator.java).
The generated VideoToolbox AVC/HEVC test exercises eight header-delivery cases,
pixel content, output identity, timestamps, flush and EOS. The animation tests
pass 23 and 14 lifecycle checks respectively.

Testing the new session exposed further native compatibility defects:

- A generated pthread routine returns with an aligned 16-byte stack allocation
  still present. Returning through Mac.apk's host trampoline restored registers
  from displaced slots and branched to the pthread handle. The trampoline now
  calls pthread_exit(result), matching Bionic's __pthread_start, after tid
  bookkeeping. Normal callbacks, join results and TLS destruction still work.
- Image names borrowed from Mach-O wrapper literals became dangling when dyld
  unloaded a wrapper while the guest image remained registered. Image now owns
  its name for both blob and path loads. Guarding the caller's name storage after
  loading deterministically reproduces the old crash and passes with the fix.
- Linux FIOCLEX/FIONCLEX requests now map to the host close-on-exec operations,
  including the ARM32 entry point and their no-argument semantics. Agora failed
  engine creation immediately after the unsupported FIOCLEX request; the rebuilt
  app initializes Agora, joins a live channel, and reports decoded audio/video.
- Branch lookup caches misses until a new guard/landing is published and caches
  host-return records with live/range validation. This avoids scanning thousands
  of executable pages on every call into libc. A 9,000-record fixture checks miss
  invalidation and collisions; 20,000 repeated host lookups fell from about
  302 ms to 0.12 ms in the local benchmark (not an app frame-rate measurement).

The thread-exit behavior follows
[Bionic pthread_create.cpp](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/pthread_create.cpp).
All four new native fixtures are included in debug/shadow_checkpoint_gate.sh.
Live video and controls were visually observed after engine initialization;
continuous playback, sound and reconnect behavior still need verification.
No app checks, account decisions, or secure-window behavior are modified.

Profiling the live SDK then found frequent far-branch traps: linked execution
copies of a large ELF can be more than the AArch64 B/BL range (128 MiB) apart.
Those transfers now use literal-address veneers through the existing landing
pads, preserving x16, LR, NZCV and the stack. Original guest return addresses
remain observable. Destinations without a registered landing retain the trap
fallback. The loader uses the same resolver when refreshing execution copies.

`FarShadowBranchTest` executes BL, B, B.cond, CBZ, TBZ and page fallthrough with
copies 224 MiB apart and non-executable original pages. All six paths preserve
the original LR, scratch registers, flags and stack over 1,000 repetitions
without a signal handler. The complete 13-fixture native gate passes. In the
updated live run Agora reported its first rendered frames after about two
seconds; continuous usable playback remains unverified because of the warning
and silent audio. This is not a claim that Clapper's environment check is fixed.

A subsequent fresh session with the same build showed live video and controls;
OpenSL ES opened a real 48 kHz stereo output and continuously consumed queued
audio buffers. The owner confirmed **"Video moves with sound."** No additional
audio implementation change was needed. An intervening session stopped audio
and left its channel while showing a content-warning screen, so that trace was
not used as evidence of an audio-backend defect. Live video and audio therefore
work in the confirmed session; the environment warning seen in other sessions
remains unresolved, and broad live-stream compatibility is not established.

## Loading overlay lifetime

Clapper's live loading window stayed over a playing stream and intercepted
every gesture. Its normal completion handler only calls WindowManager.removeView
when the loading view has a parent. WindowOverlayStack attached and drew the
root but left getParent() null, so the handler discarded its reference without
removing the window.

Each overlay layer now supplies the root's non-View ViewParent, assigned before
attachment and cleared after detachment. Parent invalidation/layout requests
schedule the compositor. This follows the parent lifetime in
[AOSP ViewRootImpl.setView/dispatchDetachedFromWindow](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/view/ViewRootImpl.java).
The app's own completion handler now removes LoaderView (the overlay stack
returns to zero), and the live screen visibly has no loading spinner. Touch
events reach LiveAudienceActivity. The owner confirmed that normal swiping now
switches live streams. The automated drag sends only one MOVE, which the
RecyclerView consumes while intercepting; it was not used as a successful
stream-switching test.

`debug/WindowOverlayParentTest.java` fails before the change and passes 20
checks afterward: guarded cleanup, attach/detach callback ordering, subtree
preservation, root-parent invalidation/layout scheduling, touch release,
independent windows, reuse and activity cleanup. DialogVisibilityTest's 13
checks also pass. WindowDecorContentTest passes with its required
`MACAPK_WINDOW_PRESERVE_DECOR=1` profile; without that opt-in, it fails the same
first assertion on both the previous and updated implementation.

## Login dialog visibility

The "Use Phone / Email" button started LoginActivity, but the previous sign-in
dialog remained above it and intercepted subsequent input. DialogFragment hides
its dialog onStop; our Dialog.hide only cleared mShowing without hiding the
decor. This also made dismiss return early and leave that window attached.

Dialog.hide now sets the attached decor to GONE while preserving its lifecycle
state. show restores an already-attached window without repeating onStart or
onShow, isShowing reports actual visibility, and dismiss removes hidden windows.
This follows [AOSP Dialog](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Dialog.java).
The rebuilt Clapper app now opens the phone form and switches to the email form.

A second failure kept host keystrokes routed to the phone field after the email
field gained focus. InputMethodManager had updated its served InputConnection,
but the direct TextView target from showSoftInput still intercepted text first.
Starting input for another view now drops that stale target, focus loss clears
it, and the direct-target query checks focus just like the connection query.
This follows the served-view handoff in AOSP InputMethodManager.checkFocus.
Typing and backspace were visually verified in Clapper's email field; the test
string was cleared. The owner confirmed that requesting an email code now works.

The verification field exposed two further framework gaps. TextView notified
registered TextWatchers but never invoked its protected onTextChanged override,
which custom editors use for completion. All text mutation paths now call the
subclass hook between watcher onTextChanged and afterTextChanged, with exactly
one notification per edit. This matches AOSP TextView.setText/handleTextChanged.

The compositor also passed a full-window bitmap Canvas to each child. Custom
drawing based on Canvas.getHeight consequently centered code digits outside the
field's clipped bounds. UI compositor canvases now report the current view's
recording dimensions during that view's draw, matching AOSP View's per-view
RenderNode.beginRecording(width, height). Nested scopes and exceptions restore
the parent dimensions; ordinary bitmap canvases retain their original behavior.
The owner confirmed that the digits now appear and email sign-in completes.
The next run exposed the post-login native-loader crash described below.

The regression fails on the previous implementation and passes all 13 checks
with the fix, including touch routing, hide/show lifecycle counts, dismiss while
hidden, and reuse after dismissal. Run after building the Java jars:

```sh
mkdir -p runtime/build/dialog-tests
"$JAVA_HOME/bin/javac" -cp 'runtime/build/lib/*' -d runtime/build/dialog-tests debug/DialogVisibilityTest.java debug/InputMethodFocusTargetTest.java
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp 'runtime/build/dialog-tests:runtime/build/lib/*' DialogVisibilityTest
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp 'runtime/build/dialog-tests:runtime/build/lib/*' InputMethodFocusTargetTest
```

The input-focus regression also fails before and passes after the fix (14 checks):
editor switching, restartInput, custom InputConnection routing, non-editor focus,
explicit hide, and resuming input.

Additional regressions (compile/run with the same classpath above):
- `debug/TextViewSubclassChangeTest.java`: 16 checks for callback ordering and
  ranges, setText, typed edits, deletion, in-place Editable edits, append, legacy
  input, and a four-digit editor completing exactly once with/without watchers.
  The previous implementation fails the first missing-hook assertion.
- `debug/ViewRecordingCanvasSizeTest.java`: six checks for child/parent canvas
  dimensions, actual center-mark pixels within the child's bounds, exception
  restoration, and unmodified ordinary bitmap drawing.

## Post-login loader capacity

After authentication, Clapper loads liblight-sdk.so. The execution-copy registry
already held 3,982 pages; publishing this library hit its process-wide 4,096-entry
limit and threw `linked shadow publication failed`, terminating the app.

Guard records now live in append-only blocks. Growing the registry does not move
published records or cached pointers. Readers, including signal handlers, use
atomic publication without locks or allocation. Inactive records remain valid
for existing readers. The loader's separate fixed-size pending-protection list
is now an image-owned vector, so a single large image also exceeds 4,096 pages.

A generated 4,101-page ELF reproduces the old failure and passes with the fix:
first/last-page execution, original bytes and read-only protections, duplicate
registration, and lookup-cache collisions. Concurrent publication of 8,193
records passes normally and under ThreadSanitizer. The native checkpoint gate
passes all eight fixtures with the new registry.

The rebuilt Clapper app restored the saved sign-in, loaded more than 5,900 guard
records, completed all 1,571 light-sdk initializers, and opened the authenticated
profile and video feed without the previous process termination. An unrelated
background SDK initialization still reports a missing
`com.tencent.xmagic.util.SSLHelperAndroid`; recording/effects are not validated
by this checkpoint.

## Playback failure and fix

MediaCodec returned reused input buffers with their previous position and limit.
Clapper correctly relied on Android's API-21+ automatic buffer clearing and put
the next compressed packet into the returned buffer without calling clear().
After one buffer-pool cycle, new bytes were appended after old data, but the
queued offset remained zero. VideoToolbox rejected subsequent packets. The
player then fell back to software decoding tens of seconds ahead of its audio
clock and spent a long time recovering synchronization.

Commit 796ee08 clears input buffers on synchronous/asynchronous ownership handoff
and on getInputBuffer. The before/after regression fails/passes respectively.
In the real app the hardware decoder stays active, and steady playback delivers
approximately 30 frames/s (33.3 ms intervals; recent windows had p95 about 34 ms).

## Native execution checkpoint

The opt-in execution-copy path leaves original guest instruction bytes readable,
relocates PC-relative data accesses to those originals, and preserves original
link-register values across guest calls. MRS TPIDR_EL0 and syscall instructions
run through platform bridges. Linked copies and indirect-branch/host-return
landing pads avoid the repeated instruction faults that previously consumed
most of the runtime's CPU. The TLS and branch helpers preserve GPRs, full SIMD
registers, NZCV, FPCR, and FPSR except their documented result/scratch registers.
Generated syscall pages can be refreshed after writes and invalidated on unmap.

The checkpoint also routes native SurfaceTexture windows through the existing
EGL14 producer registry, keeping rendered video in its TextureView and allowing
framework controls to remain visible during the software-rendering path.
Own-process /proc/.../root traversal uses the same guest path resolver.
Temporary allocation watchpoints, fault-freeze hooks, and periodic shadow-fault
logging used during diagnosis have been removed.

Earlier commits retain original installed ELF helper bytes (launching a Mach-O
sidecar when needed), preserve window decor, expose public nested interface
visibility, implement chmod/data parent paths, and report actual AudioTrack
latency. No application binaries or checks are patched by this checkpoint.

## Reproduce

Build Java, native runtime, and translator using the repository build setup.
The runtime and both Java jars must be staged together. The translator's shim
must be staged beside the runtime, with its ANGLE dependencies and the existing
JIT/x18 entitlements. Then:

```sh
bash debug/run_execution_copy_runtime.sh /path/to/macapk-runtime /path/to/app.apkm /path/to/android-root --width 540 --height 960
```

This profile enables the experimental code only for that process. The existing
TikTok installation and data were not changed. APKs, app data, local logs, and
signed test bundles are not included in this repository checkpoint.

## Validation

- `debug/MediaCodecInputReuseTest.java`: cached and per-index input buffers,
  repeated reuse, position/limit/mark reset, and async callbacks.
- `bash debug/shadow_checkpoint_gate.sh`: 18 relocation checks, distant
  execution copies, original LR, cross-page branches, host returns, thread-local
  TLS, x18, reprotection, generated syscalls, errno, self-modification, unmap/reuse,
  process-root traversal, register/FP/SIMD preservation, a 4,101-page ELF, and
  concurrent guard publication. Uses real arm64 code.
- `JAVA_HOME=/path/to/jdk python3 debug/native_texture_window_gate.py`: real
  embedded JVM with `-Xcheck:jni` and ANGLE; two independent surfaces, color-pixel
  checks, recreation, reference ownership, and null/released Surface inputs.
- Clapper stayed running over five minutes across multiple clips after the
  codec fix, with no hardware decode errors or fallback in that run. The owner
  confirmed smooth playback and audio sync. Earlier ELF-preservation testing
  also exceeded the previous ~150-second crash point.

## Remaining experimental scope

### Device environment warning diagnosis (2026-10-24)

The warning remains unresolved. Read-only inspection of the running 14.6.1
test session found the legacy emulator result true, the newer result false
(confidence 0), and runtime-tamper risk 20, below the mask threshold 80.
The active configuration selects protection level `Any` and disables the newer
emulator result. The legacy result activates the mask in protected live rooms;
the same handler also mutes their audio.

The legacy heuristic's reconstructed score is 8 (threshold >3): missing build
flavor and board-platform properties, seven sensors, a zero package-shell user
app count, no rear camera, no camera flash, no Bluetooth feature, and no default
light sensor. The current model/manufacturer/hardware strings do not match its
explicit emulator blacklist. Adding the two properties alone would still score
6. Camera direction and unsupported Bluetooth/light features must remain
accurate to their backends rather than claim hardware to change this score.

The separate constant-accelerometer diagnostic and unsupported abstract-socket
flag are not the inputs driving the inspected mask. No application checks,
configuration, capability flags, or identity values were changed during this
diagnosis. Comparison with the owner's working emulator and Clapper version is
still needed. Private inspection agents, app bytecode, account data and logs
remain outside this repository.

### User-selected display identity / Android version experiment (2026-10-24)

The owner tested Galaxy A52 display labels (`samsung`, `samsung`, `SM-A525F`)
with Android 14/API 34. The warning remained. The local launcher was restored
to Android 16/API 36 with the requested phone labels retained. The optional
label variables are documented in [DEVICE_IDENTITY.md](DEVICE_IDENTITY.md);
the shared execution profile does not select them. TikTok's bundle and data
remain unchanged.

The Java build and 13 fresh-JVM identity/initialization runs pass. Read-only
JNI inspection confirmed agreement between Java and native properties. Neither
the API experiment nor the labels resolved the cached legacy emulator result.
The owner then supplied working, warning, and black-with-audio live examples.
All three occurred under the same API 36 profile. The black-video issue below
was a rendering contract defect, rather than evidence of a version regression.

### External video textures and orientation (2026-10-24)

The working live example uses Agora. A second Agora room activates the app's
environment mask. A Tencent/LiteAV example receives and hardware-decodes frames
with that mask false, but samples a black external texture. SurfaceTexture's
upload tried to bind an existing GL_TEXTURE_EXTERNAL_OES name as GL_TEXTURE_2D,
which produces GL_INVALID_OPERATION on every frame. Its later upload modified
the unrelated 2D binding instead of the decoder's external image.

On backends with OES external-image support, the framework now uploads into
private 2D storage, creates a preserved EGLImage, and binds its external sibling
to the consumer texture. The temporary image/source handles are released after
binding; the external sibling keeps its storage alive. The upload preserves
2D bindings, pixel-unpack state, and the caller's GL error state. Backends without
external-image support retain their existing path. Decoded top-down buffers now
carry GLConsumer's vertical-flip sampling transform; direct Bitmap consumers
continue to copy top-down rows without applying that GL transform.

References: [AOSP SurfaceTexture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/SurfaceTexture.java),
[GLConsumer transform](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/gui/GLConsumerUtils.cpp),
[OES external images](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt),
[KHR image lifetime](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_image_base.txt).

`JAVA_HOME=... python3 debug/surface_texture_external_gate.py` passes 731 checks
using the real JNI/ANGLE Vulkan path and generated frames: sampled colors and
orientation, padded row strides, repeated size changes, texture reattachment,
timestamps, PBO/unpack/binding preservation, retained GL errors, and unchanged
software orientation. The old upload deterministically fails with error 0x502.
The existing `native_texture_window_gate.py` also passes.

The owner confirmed the previously black Tencent live now plays **upright with
sound**. The recurring GL errors are absent. The protection warning remains
active in the supplied protected Agora room and is a separate issue. These
changes do not modify app checks, protection settings, or secure-window capture.

Earlier API 36 sessions ended in `liteav_video_decoder` SIGSEGV/SIGBUS faults,
including a failure while restarting the decoder. The texture fix is not yet
proof that these independent native stability failures are resolved.

### Native runtime hardening

Execution copies remain opt-in. Before upstream submission, improve concurrent
code-rewrite/publication handling, reclamation of inactive guard records,
unload/address-reuse behavior, and unsupported instruction-page handling.
The registry grows as needed; its published records remain allocated until exit. The current linked reservation can use up to nine host pages per
original image page. Native TextureWindow buffer-geometry overrides and broader
producer lifecycle/API parity still need coverage; the tested default geometry
and EGL producer path work. These are reasons to retain a fork checkpoint and
explicit flags, rather than enable this path globally.
