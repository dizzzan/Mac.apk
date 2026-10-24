# Recording video on a Mac

The circle button at the right of an Android app's title bar starts recording.
Play a video, click the button, choose an MP4 destination, and click Save. The
button turns red while recording. Click it again to stop; its tooltip shows the
saved path. Closing the app also attempts to finish, with a four-second timeout.

The MP4 contains H.264 video and AAC stereo audio from the app's audio mixer.
It does not capture the desktop, microphone, title bar, Android window bitmap,
chat, or controls drawn over the video by Android views. Graphics already baked
into the video source remain. No screen-recording permission is used. This fork's
explicit recording feature operates independently of FLAG_SECURE; existing
screenshot and window-sharing policy is unchanged.

Recording selects the largest recently displayed decoded-video source. When a
player uses software decoding and renders into its own TextureView, that surface
is used instead. Raw codec frames take priority over rendered surfaces. In a
multi-participant live room this currently records one video source, with the
room's mixed audio; it does not assemble multiple participant tiles. The selected
source stays fixed during a take. A resolution change or three seconds without
frames finishes the take; start another for the next stream.

Video and audio use the host monotonic clock. Encoding runs on a serial queue
with bounded pending work so a slow disk/encoder cannot block playback. An
adjacent temporary file replaces the destination only after successful
finalization. A crash or forced kill can leave an unfinished temporary file.

## Normal launcher compatibility settings

Open an installed app's Get Info panel in Mac.apk and use Compatibility. Changes
apply on the next launch, including Finder/Dock shortcuts:

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
resolution changes, and preservation of an existing destination on failure.
