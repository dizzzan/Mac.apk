import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public class WindowDecorContentTest {
    static class TestActivity extends Activity {
        View installed;
        @Override public void macapkInstallContentDirect(View root) { installed = root; }
    }
    public static void main(String[] args) {
        TestActivity activity = new TestActivity();
        Window window = activity.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        ViewGroup decor = (ViewGroup) window.getDecorView();
        ViewGroup previousContent = (ViewGroup) window.findViewById(0x01020002);
        previousContent.setId(View.NO_ID); // AppCompat transfers the content ID.
        LinearLayout subDecor = new LinearLayout(activity);
        subDecor.setOrientation(LinearLayout.VERTICAL);
        FrameLayout content = new FrameLayout(activity);
        content.setId(0x01020002);
        subDecor.addView(content, new LinearLayout.LayoutParams(-1, -1));
        window.setContentView(subDecor);
        if (window.getDecorView() != decor || activity.installed != decor)
            throw new AssertionError("setContentView replaced the window decor");
        // Window-level inset observers have no intrinsic size and must overlay
        // the app's layout in the decor's FrameLayout, not occupy a linear row.
        decor.addView(new View(activity), 0);
        int exact = View.MeasureSpec.EXACTLY;
        decor.measure(View.MeasureSpec.makeMeasureSpec(540, exact), View.MeasureSpec.makeMeasureSpec(960, exact));
        decor.layout(0, 0, 540, 960);
        if (content.getHeight() != 960 || window.findViewById(0x01020002) != content)
            throw new AssertionError("Content must fill the window below overlays");
        FrameLayout replacement = new FrameLayout(activity);
        window.setContentView(replacement, new ViewGroup.LayoutParams(123, 234));
        if (window.getDecorView() != decor || subDecor.getParent() != null
                || replacement.getLayoutParams().height != 234) throw new AssertionError("replace/params");
        View extra = new View(activity);
        window.addContentView(extra, new ViewGroup.LayoutParams(12, 34));
        if (extra.getParent() != replacement.getParent() || extra.getLayoutParams().height != 34)
            throw new AssertionError("addContentView target/params");
        System.out.println("Stable decor, overlay geometry, content replacement and LayoutParams pass");
    }
}
