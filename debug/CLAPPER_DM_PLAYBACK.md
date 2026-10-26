# Clapper DM playback and recording — 2026-10-26

Tested with the stock Clapper 14.6.1 APK and the settings in
[CLAPPER_SETUP.md](../CLAPPER_SETUP.md). No APK modification or app-specific
runtime dispatch is involved.

## Playback stall

The message viewer displayed its preview and spinner but had not created its
GSY/Media3 player. Its fragment was resumed, its preview had loaded, and its
opening-transition completion state was still false. The app waits for its
window's shared-element enter transition to end before configuring playback.

Mac.apk discarded scene-transition launch options, returned null for all
Window transition getters, and never dispatched the enter lifecycle. Delivering
the missing completion callback in a diagnostic session immediately initialized
Media3 and played the video with sound.

The runtime now carries scene metadata through the queued activity launch,
installs transition objects from the window theme, and dispatches the cloned
enter transitions after launch/resume. Postponement and explicit null transitions
are respected. The host still switches windows without a cross-window visual
animation; completion uses the no-animation lifecycle. Return/exit animation and
shared-element snapshot mapping remain outside this change.

Reference contracts:

- [PhoneWindow theme transition installation and getters](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/com/android/internal/policy/PhoneWindow.java)
- [ActivityTransitionState enter readiness and postponement](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ActivityTransitionState.java)

## Recording

The title-bar recorder can be armed before playback. It waits indefinitely,
claims the first eligible new media frame, and starts the MP4 timeline at zero.
Waiting can be canceled without an empty file or an error alert. A previously
paused source cannot reserve the recorder ahead of a new video. Temporary data
continues to use the selected destination folder.

Media3 DM playback already supplies the same decoded-surface and app-audio
recording inputs used by other players. An actual DM recording was decoded and
checked: 3,494 video frames, approximately 30 fps, 116.5 seconds, audible AAC,
and both tracks starting at zero. Source dimensions were 960 × 540, independent
of the portrait Android window. The user confirmed DM playback with sound and
successful recording in the rebuilt app.

## Validation

```sh
debug/build_java_only.sh
bash debug/activity_scene_transition_gate.sh
bash debug/stream_recorder_gate.sh
```

The 15-check scene test covers options, window configuration, parent/child
callbacks, postponement, ordinary launches, disabled transitions, duplicate
readiness, and finishing activities. It fails against the previous installed
runtime at the scene-options assertion.

The recorder gate encodes and decodes real H.264/AAC MP4s, including waiting
longer than the active-recording timeout, cancellation, a new rendered producer
after a paused raw source, first-frame timestamps, source isolation, A/V timing,
and preservation of existing destination files. All three sample takes retained
60 frames and aligned video/audio starts. The full runtime build also passed.
