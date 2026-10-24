# Optional device display identity

The runtime accepts three launch-time environment variables:

```sh
export MACAPK_DEVICE_BRAND=samsung
export MACAPK_DEVICE_MANUFACTURER=samsung
export MACAPK_DEVICE_MODEL=SM-A525F
```

`SM-A525F` is the [Samsung Galaxy A52](https://doc.samsungmobile.com/SM-A525F/018085210319/eng.html).
These labels configure `Build.BRAND`, `Build.MANUFACTURER` and `Build.MODEL`.
The existing property mirror publishes the same values through
`android.os.SystemProperties` and Bionic's native property APIs. The fingerprint
and system partition fingerprint derive from the selected brand and existing
build components. This is an optional display identity, not a Samsung firmware
image. Product/device, hardware, features, GPU, serial and attestation remain
under their existing implementations.

Unset or empty variables retain Mac.apk's normal labels. Labels are trimmed;
control characters and values exceeding the native property's 91 payload bytes
are rejected in favor of the default. Brand is a fingerprint component and
accepts 1–32 ASCII letters, digits, dots, underscores or hyphens. Model and
manufacturer permit spaces and Unicode within the native bridge's byte limit.
The labels are fixed when `Build` initializes, so changes require a restart.

Android version selection remains independent. To test Android 14 alongside
these labels, use the existing selector:

```sh
export MACAPK_SDK_INT=34
```

That selector changes API and resource selection as well as the reported
version. No device capabilities are added by selecting labels or an API level.
Do not combine this profile with the older diagnostic
`MACAPK_PAIRIP_DEVICE_IDENTITY` or `MACAPK_PAIRIP_DEV_PROPS` overrides: those
replace property values separately and can disagree with `Build`.

Validation after rebuilding Java:

```sh
JAVA_HOME=/path/to/jdk python3 debug/build_identity_gate.py
```

The gate starts fresh JVMs with both initialization orders, defaults, all three
labels, model-only selection, empty/invalid labels, native byte limits, and
independent API 34/36 selection. The phone-label case fails before the change.

## Optional simulated inputs

These inputs are explicit simulation for a virtual phone, not measurements of
host hardware. They are off unless selected at process launch:

- `MACAPK_MOCK_SENSORS=1`: add six environment/position sensors to the existing
  motion catalog. The same catalog backs Java, NDK and feature detection.
- `MACAPK_MOCK_BLUETOOTH=1`: expose a permanently powered-off virtual Bluetooth
  adapter. This provides no Bluetooth connections, discovery or advertising.

Sensor launch values can be configured as follows. Invalid, non-finite,
out-of-range or wrong-dimension inputs fall back to the documented default.

| Variable | Default | Units / accepted range |
| --- | --- | --- |
| `MACAPK_MOCK_SENSOR_LIGHT` | `200` | lux, 0–100000 |
| `MACAPK_MOCK_SENSOR_PROXIMITY` | `5` | cm, 0–5; 5 means far |
| `MACAPK_MOCK_SENSOR_MAGNETIC_FIELD` | `0,25,-40` | three device-axis values in µT, each −2000–2000 |
| `MACAPK_MOCK_SENSOR_PRESSURE` | `1013.25` | hPa, 1–1100 |
| `MACAPK_MOCK_SENSOR_TEMPERATURE` | `22` | °C, −40–85 |
| `MACAPK_MOCK_SENSOR_HUMIDITY` | `45` | percent, 0–100 |

Readings remain stable until changed; no random motion or invented steps are
injected. Loader/test controls can update an enabled input through
`android.hardware.MockSensors.setValues(type, values...)`. Java and native
listeners receive the updated sample. This profile does not advertise biometric,
heart-rate, step-counting or other unsupported specialized sensor protocols.

The design follows Android Emulator's configurable
[Additional Sensors](https://developer.android.com/studio/run/emulator-extended-controls)
and Android's [SensorEvent units](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorEvent.java).
Use `JAVA_HOME=/path/to/jdk python3 debug/mock_sensors_gate.py` to check catalogs
and Java/NDK delivery with the built runtime.
