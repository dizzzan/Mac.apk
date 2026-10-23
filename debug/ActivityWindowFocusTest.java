import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;

/** Focus listeners registered before first traversal must receive gain/loss. */
public class ActivityWindowFocusTest {
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    static class Root extends FrameLayout {
        int gained, lost;
        Root() { super(null); }
        @Override public void onWindowFocusChanged(boolean focus) { if (focus) gained++; else lost++; }
    }
    public static void main(String[] args) throws Exception {
        Activity activity = new Activity();
        View decor = activity.getWindow().getDecorView();
        Root root = new Root(), child = new Root();
        root.addView(child);
        Field content = Activity.class.getDeclaredField("contentView");
        content.setAccessible(true); content.set(activity, root);
        int[] events = new int[4];
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(f -> events[f ? 0 : 1]++);
        root.getViewTreeObserver().addOnWindowFocusChangeListener(f -> events[f ? 2 : 3]++);
        activity.macapkDispatchWindowFocusToViews(true);
        activity.macapkDispatchWindowFocusToViews(false);
        check(events[0] == 1 && events[1] == 1);
        check(events[2] == 1 && events[3] == 1);
        check(root.gained == 1 && root.lost == 1);
        check(child.gained == 1 && child.lost == 1);
        content.set(activity, decor);
        activity.macapkDispatchWindowFocusToViews(true);
        check(events[0] == 2 && events[2] == 1);
        System.out.println(checks + " activity window-focus checks passed");
    }
}
