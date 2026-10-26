package com.macapk.loader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.util.ArrayList;

/** Explicit, local export of the image in a photo viewer, independent of window sharing.
 * Uses the loaded bitmap, not a screenshot or a private image-loader cache. No network
 * requests, permanent image cache, app-specific class names, or APK changes are needed. */
public final class PhotoExport {
    private PhotoExport() {}
    private static final long MAX_PIXELS = 32L * 1024 * 1024;
    private static native void complete(long request, int[] pixels, int width, int height, String error);

    /** Called from AppKit; all view inspection and pixel copying belongs to Android main. */
    public static void request(long request) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Activity activity = ActivityHost.peek();
                View decor = activity == null ? null : activity.getWindow().getDecorView();
                Snapshot image = capture(decor);
                complete(request, image.pixels, image.width, image.height, null);
            } catch (Throwable error) {
                // Do not disclose view contents, image URLs, or private app paths in errors.
                String message = error instanceof IllegalArgumentException ? error.getMessage()
                        : "The photo could not be copied. Wait for it to finish loading and try again.";
                complete(request, null, 0, 0, message);
            }
        });
    }

    static final class Snapshot {
        final int width, height;
        final int[] pixels;
        Snapshot(int w, int h, int[] p) { width = w; height = h; pixels = p; }
    }
    private static final class Candidate {
        final Bitmap bitmap;
        final long visibleArea;
        Candidate(Bitmap b, long area) { bitmap = b; visibleArea = area; }
    }

    /** Package-visible for the offline gate. Snapshot owns its pixels: recycling or reusing
     * the app's bitmap while the macOS save panel is open cannot change the export. */
    static Snapshot capture(View root) {
        Rect viewport = new Rect();
        if (root == null || !root.getGlobalVisibleRect(viewport)) throw noPhoto();
        long screenArea = (long) viewport.width() * viewport.height();
        ArrayList<Candidate> candidates = new ArrayList<>();
        collect(root, viewport, screenArea, candidates, 0);
        candidates.sort((a, b) -> Long.compare(b.visibleArea, a.visibleArea));
        if (candidates.isEmpty()) throw noPhoto();
        Candidate chosen = candidates.get(0);
        // A mid-swipe gallery or grid has no unambiguous current photo. Never silently
        // export its neighboring item. Offscreen preloaded pages are excluded by clipping.
        if (candidates.size() > 1 && candidates.get(1).bitmap != chosen.bitmap
                && candidates.get(1).visibleArea >= chosen.visibleArea * 0.8) {
            throw new IllegalArgumentException("More than one photo is visible. Open one photo full-screen and try again.");
        }
        Bitmap bitmap = chosen.bitmap;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("This photo is too large to export (maximum 32 megapixels).");
        }
        // A hardware bitmap cannot be read by getPixels; Android allows a software copy.
        Bitmap software = bitmap.getConfig() == Bitmap.Config.HARDWARE
                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
        if (software == null) throw noPhoto();
        try {
            int[] pixels = new int[width * height];
            software.getPixels(pixels, 0, width, 0, 0, width, height);
            return new Snapshot(width, height, pixels);
        } finally {
            if (software != bitmap) software.recycle();
        }
    }

    private static IllegalArgumentException noPhoto() {
        return new IllegalArgumentException("Open a photo full-screen and wait for it to load, then click Save photo again.");
    }
    private static void collect(View view, Rect viewport, long screenArea,
                                ArrayList<Candidate> candidates, int depth) {
        if (depth > 64 || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) return;
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible) || !visible.intersect(viewport)) return;
        if (view instanceof ImageView image) {
            Drawable drawable = image.getDrawable();
            if (drawable != null) drawable = drawable.getCurrent();
            if (drawable instanceof BitmapDrawable bd) {
                Bitmap bitmap = bd.getBitmap();
                long area = (long) visible.width() * visible.height();
                // Reject avatars, icons, and thumbnails. A deliberate full-screen viewer
                // supplies a large ImageView even for a small or panoramic source image.
                if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() >= 96
                        && bitmap.getHeight() >= 96 && area >= screenArea * 0.25) {
                    candidates.add(new Candidate(bitmap, area));
                }
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), viewport, screenArea, candidates, depth + 1);
            }
        }
    }
}
