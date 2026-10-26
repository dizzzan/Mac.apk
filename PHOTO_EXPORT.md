# Save a photo to your Mac

Open a photo in the Android app's full-screen viewer, wait for it to load, then
click **Save photo** (the photo/download icon beside Record in the Mac title
bar). Choose a destination and save. The dialog shows the image's dimensions.

This exports a lossless PNG of the **whole loaded image**, with its existing
orientation and transparency. Chat, controls, window borders, and the viewer's
zoom or crop are excluded. It works with Clapper's DM photo viewer without
changing its APK or requiring an environment flag.

The saved resolution is the decoded image's resolution, independent of the Mac
window size. It is not necessarily the sender's original camera file: an app
may download or decode a smaller copy. The export does not fetch another URL,
preserve original JPEG/EXIF metadata, upscale the image, or save animations.
It is a photo export, not a general screenshot of the app.

If no large photo is loaded, the button asks you to open one. Offscreen gallery
pages, invisible views, and small avatars/icons are excluded. If two large
images have similar visible areas, let the swipe finish or open a single photo
before saving. This first version supports bitmap-backed Android ImageViews,
including PhotoView; custom GL, WebView, and Compose-only image viewers may
not expose an exportable bitmap. Images above 32 megapixels are rejected.

The image is copied when you click the button, so changing photos afterward
cannot change the pending save. Canceling creates no file. PNG encoding and
disk writes run off the UI thread. Atomic file replacement uses the selected
destination directory and preserves an existing file if saving fails.

This is an explicit local export. It does not change the window's external
screen-sharing policy, access Photos, upload images, or maintain another image
cache. The PNG contains only the image pixels and color information.

## Validation

Build the runtime, then run `debug/photo_export_gate.sh`. Its Java gate covers
native image size, cropping independence, neighboring gallery pages, hidden
views, ambiguous selection, hardware bitmaps, and snapshot ownership. Its native
gate round-trips the actual PNG encoder and checks dimensions, row orientation,
RGB channels, alpha, and invalid-size guards.

The regular installed Clapper 14.6.1 app was tested with its stock DM viewer.
The visible photo exposed a 1189 × 1212 bitmap while a different gallery photo
was preloaded offscreen. After installation, the owner successfully saved a DM
photo using the new title-bar button (2026-10-26).
