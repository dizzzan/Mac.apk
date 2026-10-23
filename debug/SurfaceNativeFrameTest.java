import android.graphics.SurfaceTexture;
import android.view.Surface;

public class SurfaceNativeFrameTest {
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    public static void main(String[] args) {
        SurfaceTexture first = new SurfaceTexture(false);
        SurfaceTexture second = new SurfaceTexture(false);
        Surface a = new Surface(first), b = new Surface(second);
        check(a.macapkPostNativeFrame(new int[]{0xff112233}, 1, 1));
        check(b.macapkPostNativeFrame(new int[]{0xff445566}, 1, 1));
        check(first.macapkLatchBitmap().getPixel(0, 0) == 0xff112233);
        check(second.macapkLatchBitmap().getPixel(0, 0) == 0xff445566);
        check(a.macapkPostNativeFrame(new int[]{0xff778899}, 1, 1));
        check(first.macapkLatchBitmap().getPixel(0, 0) == 0xff778899);
        check(!a.macapkPostNativeFrame(new int[1], 2, 1));
        a.release();
        check(!a.macapkPostNativeFrame(new int[1], 1, 1));
        second.release();
        check(!b.macapkPostNativeFrame(new int[1], 1, 1));
        first.release();
        System.out.println(checks + " Surface native-frame checks passed");
    }
}
