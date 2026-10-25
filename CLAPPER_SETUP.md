# Clapper setup on Mac.apk

Tested on 2026-10-25 with the stock **Clapper 14.6.1** APK/APKM
(`com.newsclapper.video`) and the dizzzan/Mac.apk fork. This is the configuration
used for the confirmed email/code sign-in, feed playback, live video and sound,
live-stream switching, and title-bar recording. The Clapper APK was not modified.

## Restore after reinstalling Clapper

1. Install the Clapper APK/APKM in Mac.apk, then quit Clapper if it opens.
2. Right-click Clapper's tile in Mac.apk and choose **Get Info…**.
3. In **Environment overrides**, paste this complete configuration, one
   `KEY=VALUE` per line, and click **Apply**:

```text
MACAPK_COMPAT_PROFILE=execution-copy
MACAPK_WINDOW_SIZE=540x960
MACAPK_MOCK_SENSORS=1
MACAPK_MOCK_BLUETOOTH=1
MACAPK_SDK_INT=36
MACAPK_DEVICE_BRAND=samsung
MACAPK_DEVICE_MANUFACTURER=samsung
MACAPK_DEVICE_MODEL=SM-A525F
```

4. Click **Done**, then launch Clapper again. These per-app settings also apply
   when launching its Applications/Finder/Dock shortcut.

The Environment editor accepts plain assignments as shown, without `export` or
shell quotes. Set these on Clapper's own Get Info panel so they apply only to
Clapper. Existing unrelated overrides can remain; replace any conflicting values
for the eight keys above.

## What each setting does

| Setting | Value | Purpose / equivalent Compatibility control |
| --- | --- | --- |
| `MACAPK_COMPAT_PROFILE` | `execution-copy` | **Native playback → Extended native video**. Enables the native execution, TLS and texture-window compatibility settings used by Clapper's media SDKs. |
| `MACAPK_WINDOW_SIZE` | `540x960` | **Window size → 540 × 960 (xHDPI)**. The tested portrait layout. |
| `MACAPK_MOCK_SENSORS` | `1` | **Simulate missing phone sensors → On**. Adds simulated environment/position sensors missing on the Mac mini. |
| `MACAPK_MOCK_BLUETOOTH` | `1` | **Simulate a Bluetooth adapter → On**. Presents a powered-off virtual adapter; it cannot connect to devices. |
| `MACAPK_SDK_INT` | `36` | Selects Android 16 / API 36 behavior and resources. |
| `MACAPK_DEVICE_BRAND` | `samsung` | Tested phone brand label. |
| `MACAPK_DEVICE_MANUFACTURER` | `samsung` | Tested manufacturer label. |
| `MACAPK_DEVICE_MODEL` | `SM-A525F` | Samsung Galaxy A52 model label. |

The two simulation toggles were part of the confirmed fix for the
**Device Environment Abnormality** warning. The phone labels alone did not
resolve it. API 34 was an earlier unsuccessful experiment; **36** is the final
tested setting. Window size and phone labels preserve the tested configuration;
they are not independently established requirements for playback.

The named playback preset supplies the lower-level native flags automatically.
Individual overrides take precedence over preset defaults, so old diagnostic
overrides can change its behavior. The eight assignments above are the saved
normal-app configuration; debug-harness flags are not needed.

## Larger windows and Java heap capacity

For larger windows or sessions that exhaust the default 1 GiB Java heap, add
this per-app Environment override and restart Clapper:

```text
MACAPK_JVM_OPTS=-Xmx4g
```

This selects a 4 GiB maximum Java heap. It grows as needed; native video/GPU
buffers and other process memory are additional. It was selected on the test
Mac with 64 GiB of RAM. Keep this setting local to Clapper. If `MACAPK_JVM_OPTS`
already contains other options, preserve them and replace any existing `-Xmx`
option with `-Xmx4g`.

The 2026-10-25 performance investigation found Clapper's old generation at
99.95% occupancy, with about 7–8 full collections per second and roughly
75–80% of wall time spent collecting. Large image/rendering pixel arrays
occupied approximately 470 MiB. Increasing the heap removed that repeated
full-collection pattern in the initial live-playback checks; the UI compositor
returned to approximately 60 fps. A 30-second live-playback sample recorded
zero full collections and 44 ms of total collection time; old-generation use
rose only from 684 to 687 MiB during that sample. The source stream's frame rate
is independent of the compositor rate. This is a memory-capacity adjustment, not proof that
all long-session memory retention has been resolved.

## Required runtime and recording

Use a fork build containing the compatibility and recording changes. For the
subsequent crash while switching live streams, also include commit
[`b461d69`](https://github.com/dizzzan/Mac.apk/commit/b461d69)
(**Serialize MediaCodec callbacks on the requested looper**). This fixes callback
threading and lifecycle behavior; it requires no additional environment flag.
The owner confirmed that scrolling then stayed open and played correctly.

The published `v1.0.2305` release predates that crash fix. The locally installed
build `261025.1059-b461d69` includes it, despite sharing the 1.0.2305 version number.

Recording needs no extra flag. Start a stream, click the circle button in the
app title bar, choose the MP4 destination, and click it again to stop. Temporary
recording data is written beside the chosen destination; the final filename
appears after recording finishes. Android chat and controls are excluded;
graphics already embedded in the source video remain. See
[STREAM_RECORDING.md](STREAM_RECORDING.md) for source selection and limitations.

For sensor values and identity details, see
[debug/DEVICE_IDENTITY.md](debug/DEVICE_IDENTITY.md). Earlier implementation and
validation notes are in [debug/CLAPPER_CHECKPOINT.md](debug/CLAPPER_CHECKPOINT.md).
