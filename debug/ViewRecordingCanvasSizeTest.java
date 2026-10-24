import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.FrameLayout;

/** Custom hardware-view drawing queries the view's recording dimensions. */
public class ViewRecordingCanvasSizeTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static class CenterMark extends View {
        int width, height;
        CenterMark() { super(null); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas canvas) {
            width = canvas.getWidth(); height = canvas.getHeight();
            Paint paint = new Paint(); paint.setColor(0xffff0000);
            canvas.drawRect(width / 2, height / 2, width / 2 + 4, height / 2 + 4, paint);
        }
    }
    static class Parent extends FrameLayout {
        int beforeWidth, afterWidth;
        Parent() { super(null); }
        @Override protected void dispatchDraw(Canvas canvas) {
            beforeWidth = canvas.getWidth();
            super.dispatchDraw(canvas);
            afterWidth = canvas.getWidth();
        }
    }
    public static void main(String[] args) {
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Parent root = new Parent();
        CenterMark child = new CenterMark();
        root.addView(child, new FrameLayout.LayoutParams(40, 20));
        root.layout(0, 0, 120, 80); child.layout(15, 20, 55, 40);
        canvas.macapkSetViewRecording(true);
        canvas.macapkDrawView(root);
        check(child.width == 40 && child.height == 20, "child sees its own recording size");
        check(bitmap.getPixel(35, 30) == 0xffff0000, "center drawing stays inside child bounds");
        check(root.beforeWidth == 120 && root.afterWidth == 120, "child scope restores parent dimensions");
        check(canvas.getWidth() == 400 && canvas.getHeight() == 300, "root scope restores bitmap dimensions");
        View throwing = new View(null) {
            @Override public void draw(Canvas c) { throw new IllegalStateException("test"); }
        };
        throwing.layout(0, 0, 11, 12);
        try { canvas.macapkDrawView(throwing); throw new AssertionError("expected throw"); }
        catch (IllegalStateException expected) { }
        check(canvas.getWidth() == 400 && canvas.getHeight() == 300, "exception restores dimensions");
        canvas.macapkSetViewRecording(false);
        canvas.macapkDrawView(root);
        check(child.width == 400 && child.height == 300, "ordinary bitmap drawing retains bitmap dimensions");
        System.out.println(checks + " view recording canvas size checks passed");
    }
}
