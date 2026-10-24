import android.app.Activity;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Host text must follow focus even after another editor requested the IME. */
public class InputMethodFocusTargetTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static void type(String text) {
        TextView direct = InputMethodManager.macapkActiveInput();
        if (direct != null) {
            for (char c : text.toCharArray()) direct.macapkHandleSoftKey(c, 0);
        } else {
            InputConnection connection = InputMethodManager.macapkActiveConnection();
            check(connection != null, "focused editor needs an input connection");
            connection.commitText(text, 1);
        }
    }
    static class CustomEditor extends View {
        String received = "";
        CustomEditor(Activity activity) { super(activity); setFocusableInTouchMode(true); }
        @Override public boolean onCheckIsTextEditor() { return true; }
        @Override public InputConnection onCreateInputConnection(EditorInfo out) {
            return new BaseInputConnection(this, true) {
                @Override public boolean commitText(CharSequence text, int position) {
                    received += text; return true;
                }
            };
        }
    }
    public static void main(String[] args) {
        Activity activity = new Activity();
        FrameLayout root = new FrameLayout(activity);
        EditText first = new EditText(activity), second = new EditText(activity);
        CustomEditor custom = new CustomEditor(activity);
        View nonEditor = new View(activity);
        nonEditor.setFocusableInTouchMode(true);
        root.addView(first); root.addView(second); root.addView(custom); root.addView(nonEditor);
        root.macapkDispatchAttachedToWindow();
        InputMethodManager imm = InputMethodManager.peekInstance();
        imm.showSoftInput(first, 0);
        type("1");
        check(first.getText().toString().equals("1"), "explicit IME target receives text");
        check(second.requestFocus(), "second field takes focus");
        check(InputMethodManager.macapkActiveInput() != first,
                "old explicit IME target must not override new focus");
        type("test");
        check(second.getText().toString().equals("test") && first.getText().toString().equals("1"),
                "typing goes only to the newly focused editor");
        imm.showSoftInput(first, 0);
        imm.restartInput(second);
        type("2");
        check(second.getText().toString().equals("test2"), "restartInput replaces stale target");
        imm.showSoftInput(first, 0);
        check(custom.requestFocus(), "custom editor takes focus");
        type("custom");
        check(custom.received.equals("custom") && first.getText().toString().equals("1"),
                "non-TextView input connection receives text after focus switch");
        imm.showSoftInput(first, 0);
        check(nonEditor.requestFocus(), "non-editor takes focus");
        check(InputMethodManager.macapkActiveInput() == null
                && InputMethodManager.macapkActiveConnection() == null,
                "non-editor focus releases text routing");
        imm.showSoftInput(first, 0);
        imm.hideSoftInputFromWindow(first.getWindowToken(), 0);
        check(InputMethodManager.macapkActiveInput() == null
                && InputMethodManager.macapkActiveConnection() == null, "explicit hide remains effective");
        imm.showSoftInput(second, 0);
        type("3");
        check(second.getText().toString().equals("test23"), "explicit show can resume input");
        imm.hideSoftInputFromWindow(second.getWindowToken(), 0);
        System.out.println(checks + " input focus target checks passed");
    }
}
