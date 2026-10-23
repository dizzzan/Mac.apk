import android.view.View;
import com.macapk.loader.ViewCanvasDriver;
import java.lang.reflect.Method;

public class SurfaceResizeTraversalTest {
    static class Root extends View {
        int measures, layouts;
        Root() { super(null); }
        @Override protected void onMeasure(int w, int h) { measures++; super.onMeasure(w, h); }
        @Override protected void onLayout(boolean c, int l, int t, int r, int b) { layouts++; }
    }
    public static void main(String[] args) throws Exception {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) throw new AssertionError("real looper required");
        Root root = new Root();
        ViewCanvasDriver driver = new ViewCanvasDriver(root);
        Method resize = ViewCanvasDriver.class.getDeclaredMethod("doSurfaceChanged", int.class, int.class);
        resize.setAccessible(true); resize.invoke(driver, 1080, 1920);
        resize.invoke(driver, 1920, 1080);
        if (root.measures != 0 || root.layouts != 0 || root.isAttachedToWindow())
            throw new AssertionError("surface notification eagerly traversed the view tree");
        System.out.println("Surface resizes defer attach/measure/layout to frame traversal");
    }
}
