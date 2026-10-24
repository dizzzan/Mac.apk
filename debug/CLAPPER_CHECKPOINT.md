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

Execution copies remain opt-in. Before upstream submission, improve concurrent
code-rewrite/publication handling, reclamation of inactive guard records,
unload/address-reuse behavior, and unsupported instruction-page handling.
The registry grows as needed; its published records remain allocated until exit. The current linked reservation can use up to nine host pages per
original image page. Native TextureWindow buffer-geometry overrides and broader
producer lifecycle/API parity still need coverage; the tested default geometry
and EGL producer path work. These are reasons to retain a fork checkpoint and
explicit flags, rather than enable this path globally.
