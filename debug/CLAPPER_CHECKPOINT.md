# Clapper playback checkpoint — 2026-10-23

Clapper 14.6.1 reaches the feed, plays video continuously with sound, and switches
between clips. The owner confirmed **smooth playback and synchronized sound**
after the MediaCodec input-buffer fix. This is a fork checkpoint, not an upstream
PR or a claim of compatibility with all applications.

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
Authenticated sign-in still requires verification with the owner's account.

The regression fails on the previous implementation and passes all 13 checks
with the fix, including touch routing, hide/show lifecycle counts, dismiss while
hidden, and reuse after dismissal. Run after building the Java jars:

```sh
mkdir -p runtime/build/dialog-tests
"$JAVA_HOME/bin/javac" -cp 'runtime/build/lib/*' -d runtime/build/dialog-tests debug/DialogVisibilityTest.java
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp 'runtime/build/dialog-tests:runtime/build/lib/*' DialogVisibilityTest
```

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
  process-root traversal, and register/FP/SIMD preservation. Uses real arm64 code.
- `JAVA_HOME=/path/to/jdk python3 debug/native_texture_window_gate.py`: real
  embedded JVM with `-Xcheck:jni` and ANGLE; two independent surfaces, color-pixel
  checks, recreation, reference ownership, and null/released Surface inputs.
- Clapper stayed running over five minutes across multiple clips after the
  codec fix, with no hardware decode errors or fallback in that run. The owner
  confirmed smooth playback and audio sync. Earlier ELF-preservation testing
  also exceeded the previous ~150-second crash point.

## Remaining experimental scope

Execution copies remain opt-in. Before upstream submission, improve concurrent
code-rewrite/publication handling, capacity reclamation in the bounded 4096-entry
guard registry, unload/address-reuse behavior, and unsupported instruction-page
handling. The current linked reservation can use up to nine host pages per
original image page. Native TextureWindow buffer-geometry overrides and broader
producer lifecycle/API parity still need coverage; the tested default geometry
and EGL producer path work. These are reasons to retain a fork checkpoint and
explicit flags, rather than enable this path globally.
