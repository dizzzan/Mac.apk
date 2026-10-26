package com.macapk.loader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Photo selection, clipping, pixel ownership, and hardware bitmap export. */
public class PhotoExportTest {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static ImageView image(Activity a, FrameLayout root, Bitmap b, int x, int y, int w, int h) {
        ImageView view = new ImageView(a); view.setImageBitmap(b); root.addView(view);
        view.layout(x, y, x + w, y + h); return view;
    }
    static void rejected(FrameLayout root, String message) {
        try { PhotoExport.capture(root); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        Activity a = new Activity(); FrameLayout root = new FrameLayout(a); root.layout(0, 0, 1000, 1600);
        Bitmap source = Bitmap.createBitmap(1189, 1212, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED); source.setPixel(1188, 1211, 0x80123456);
        ImageView photo = image(a, root, source, 0, 0, 1000, 1600);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap other = Bitmap.createBitmap(815, 1768, Bitmap.Config.ARGB_8888); other.eraseColor(Color.BLUE);
        ImageView neighbor = image(a, root, other, -1020, 0, 1000, 1600);
        image(a, root, other, 930, 20, 40, 40); // small UI icon cannot win
        PhotoExport.Snapshot snap = PhotoExport.capture(root);
        check(snap.width == 1189 && snap.height == 1212, "native bitmap size, not screen or crop size");
        check(snap.pixels[0] == Color.RED && snap.pixels[snap.pixels.length - 1] == 0x80123456,
                "correct photo, complete image, orientation and straight alpha");
        source.eraseColor(Color.GREEN);
        check(snap.pixels[0] == Color.RED, "snapshot remains stable while save panel is open");
        neighbor.layout(0, 0, 1000, 1600); neighbor.setVisibility(View.INVISIBLE);
        check(PhotoExport.capture(root).pixels[0] == Color.GREEN, "hidden photo excluded");
        neighbor.setVisibility(View.VISIBLE); neighbor.setAlpha(0f);
        check(PhotoExport.capture(root).pixels[0] == Color.GREEN, "transparent photo excluded");
        neighbor.setAlpha(1f); rejected(root, "ambiguous gallery rejected");
        neighbor.setVisibility(View.GONE);
        photo.setImageBitmap(source.copy(Bitmap.Config.HARDWARE, false));
        check(PhotoExport.capture(root).pixels[0] == Color.GREEN, "hardware bitmap export supported");
        photo.setImageBitmap(source); source.recycle(); rejected(root, "recycled photo rejected");
        root.removeAllViews(); rejected(root, "empty screen rejected");
        image(a, root, other, 0, 0, 50, 50); rejected(root, "avatar-only screen rejected");
        root.removeAllViews();
        FrameLayout invisibleParent = new FrameLayout(a); root.addView(invisibleParent);
        invisibleParent.layout(0, 0, 1000, 1600); invisibleParent.setAlpha(0f);
        image(a, invisibleParent, other, 0, 0, 1000, 1600); rejected(root, "transparent ancestor excluded");
        System.out.println("Photo export: " + checks + " checks passed");
    }
}
