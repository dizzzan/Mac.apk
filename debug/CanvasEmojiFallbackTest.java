import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/** A physical Latin font lacks emoji; the run must use the host's color fallback. */
public class CanvasEmojiFallbackTest {
    public static void main(String[] args) {
        Bitmap bitmap = Bitmap.createBitmapWithCanvasBacking(200, 90);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(); paint.setTextSize(50); paint.setColor(0xffffffff);
        canvas.drawText("A🎭B", 5, 65, paint);
        int colored = 0;
        for (int y = 0; y < 90; y++) for (int x = 0; x < 200; x++) {
            int p = bitmap.getPixel(x, y);
            if ((p >>> 24) != 0 && ((p >> 16 & 255) != (p >> 8 & 255)
                    || (p >> 8 & 255) != (p & 255))) colored++;
        }
        if (colored < 20) throw new AssertionError("emoji rendered as monochrome missing glyph: " + colored);
        System.out.println("Color emoji fallback rendered " + colored + " colored pixels");
    }
}
