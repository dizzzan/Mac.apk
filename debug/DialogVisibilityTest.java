import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import com.macapk.loader.WindowOverlayStack;

/** DialogFragment hides its window onStop and shows that same window onStart. */
public class DialogVisibilityTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static class TestDialog extends Dialog {
        int creates, starts, stops, shows, dismissals;
        TestDialog(Activity activity) {
            super(activity);
            setContentView(new FrameLayout(activity));
            setOnShowListener(d -> shows++);
            setOnDismissListener(d -> dismissals++);
        }
        @Override protected void onCreate(Bundle state) { creates++; }
        @Override protected void onStart() { starts++; }
        @Override protected void onStop() { stops++; }
    }
    public static void main(String[] args) {
        TestDialog dialog = new TestDialog(new Activity());
        dialog.hide();
        check(!dialog.isShowing(), "hide before show must not attach a window");
        dialog.show();
        View decor = dialog.getWindow().getDecorView();
        check(dialog.isShowing() && WindowOverlayStack.size() == 1, "show attaches window");
        WindowOverlayStack.snapshot().get(0).bounds.set(0, 0, 100, 100);
        check(WindowOverlayStack.deliverTouch(0, 20, 20), "visible dialog receives touch");
        WindowOverlayStack.deliverTouch(1, 20, 20);
        dialog.hide();
        check(!dialog.isShowing() && decor.getVisibility() == View.GONE,
                "hide must make the decor invisible, not just change a flag");
        check(WindowOverlayStack.size() == 1 && decor.isAttachedToWindow(),
                "hide retains the attached window");
        check(!WindowOverlayStack.deliverTouch(0, 20, 20), "hidden dialog must not intercept touch");
        WindowOverlayStack.deliverTouch(1, 20, 20);
        dialog.show();
        dialog.show();
        check(dialog.isShowing() && decor.getVisibility() == View.VISIBLE
                && WindowOverlayStack.size() == 1, "show restores the same window once");
        check(dialog.creates == 1 && dialog.starts == 1 && dialog.shows == 1
                && dialog.stops == 0 && dialog.dismissals == 0,
                "hide/show must not restart lifecycle or notify listeners");
        dialog.hide();
        dialog.dismiss();
        check(!dialog.isShowing() && WindowOverlayStack.size() == 0
                && !decor.isAttachedToWindow(), "dismiss must remove a hidden window");
        check(dialog.stops == 1 && dialog.dismissals == 1, "hidden dismiss delivers lifecycle");
        dialog.dismiss();
        check(dialog.stops == 1 && dialog.dismissals == 1, "dismiss is idempotent");
        dialog.show();
        check(dialog.isShowing() && dialog.creates == 1 && dialog.starts == 2
                && dialog.shows == 2, "dismissed dialog can be reused");
        dialog.dismiss();
        check(WindowOverlayStack.size() == 0, "no leaked windows");
        System.out.println(checks + " dialog visibility/lifecycle checks passed");
    }
}
