# Recording video on a Mac

Click the circle button at the right of an Android app's title bar, choose an
MP4 destination, and click Save. The button turns orange while waiting for video.
Start the video: recording begins with the next frame and the button turns red.
You can also start recording a video that is already playing.

Click again to stop recording or cancel waiting. Waiting has no timeout and
creates no empty file. The tooltip shows the current state or saved path. Closing
the app attempts to finish an active take, with a four-second timeout.

The MP4 contains H.264 video and AAC stereo audio from the app's audio mixer.
It does not capture the desktop, microphone, title bar, Android window bitmap,
chat, or controls drawn over the video by Android views. Graphics already baked
into the video source remain. No screen-recording permission is used. This fork's
explicit recording feature operates independently of FLAG_SECURE; existing
screenshot and window-sharing policy is unchanged.

Recording selects the first eligible video source to deliver a new frame after
Save. When a player renders into its own TextureView, that surface can be used
instead. Raw codec frames arriving after recording is armed take priority over
rendered copies; a previously paused video does not reserve the recorder. In a
multi-participant live room this currently records one video source, with the
room's mixed audio; it does not assemble multiple participant tiles. The selected
source stays fixed during a take. A resolution change or three seconds without
frames finishes the take; start another for the next stream.

Video and audio use the host monotonic clock. Encoding runs on a serial queue
with bounded pending work so a slow disk/encoder cannot block playback. An
adjacent temporary file replaces the destination only after successful
finalization. A crash or forced kill can leave an unfinished temporary file.

## Normal launcher compatibility settings

For the complete tested Clapper configuration, including a paste-ready set of
environment overrides, see [CLAPPER_SETUP.md](CLAPPER_SETUP.md).

Open an installed app's Get Info panel in Mac.apk and use Compatibility. Changes
apply on the next launch, including Finder/Dock shortcuts:

Window size can also be saved per app (for example, 540 × 960). It uses
`MACAPK_WINDOW_SIZE=540x960`; explicit command-line dimensions take priority.

- **Native video** enables the lightweight native-code settings used by TikTok Lite.
- **Extended native video** enables execution-copy/TLS handling and native texture
  windows used by Clapper. These are optional per-app presets, not package checks.
- **Simulate missing phone sensors** and **Simulate a Bluetooth adapter** enable
  the explicit mocks described in [DEVICE_IDENTITY.md](debug/DEVICE_IDENTITY.md).
  They default off.

Presets set defaults; individual Environment overrides win. Preset values are
`MACAPK_COMPAT_PROFILE=native-video` and `execution-copy`. Standard leaves runtime
defaults unchanged. The tested Clapper setup additionally uses `MACAPK_SDK_INT=36`,
`MACAPK_DEVICE_BRAND=samsung`, `MACAPK_DEVICE_MANUFACTURER=samsung`, and
`MACAPK_DEVICE_MODEL=SM-A525F`. Android version and display identity are independent
of the playback preset. Finder shortcuts load saved global defaults and the
version-specific app overrides before starting the shared runtime.

## Checks

```sh
bash debug/stream_recorder_gate.sh
python3 debug/compat_profile_gate.py
JAVA_HOME=/path/to/jdk python3 debug/surface_texture_external_gate.py
```

The recorder test writes and decodes real H.264/AAC MP4s, checking frame delivery,
orientation, audible samples, timing, source selection, repeated start/stop,
resolution changes, waiting/cancellation, capture from the first frame, and
preservation of an existing destination on failure.
