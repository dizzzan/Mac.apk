import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

/** Custom editors use onTextChanged for validation and completion callbacks. */
public class TextViewSubclassChangeTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static class Field extends EditText {
        List<String> events = new ArrayList<>();
        int completions;
        Field() { super(null); }
        @Override protected void onTextChanged(CharSequence text, int start, int before, int after) {
            if (events == null) return; // constructor-time setText
            events.add("hook:" + start + ":" + before + ":" + after);
            if (text.length() == 4) completions++;
        }
        void legacyEdit(String text) { macapkSetTextAndNotify(text); }
    }
    static void expect(Field field, int start, int before, int after, boolean watchers) {
        List<String> expected = new ArrayList<>();
        if (watchers) { expected.add("before"); expected.add("on"); }
        expected.add("hook:" + start + ":" + before + ":" + after);
        if (watchers) expected.add("after");
        check(field.events.equals(expected), "callback order/delta: " + field.events + " != " + expected);
        field.events.clear();
    }
    static void exercise(boolean watchers) {
        Field field = new Field();
        if (watchers) field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { field.events.add("before"); }
            public void onTextChanged(CharSequence s, int start, int before, int count) { field.events.add("on"); }
            public void afterTextChanged(Editable s) { field.events.add("after"); }
        });
        field.setText("a"); expect(field, 0, 0, 1, watchers);
        field.setSelection(1);
        field.macapkHandleSoftKey('b', 0); expect(field, 1, 0, 1, watchers);
        field.macapkHandleSoftKey(0, 67); expect(field, 1, 1, 0, watchers);
        field.getText().replace(0, 1, "xy"); expect(field, 0, 1, 2, watchers);
        field.append("z"); expect(field, 2, 0, 1, watchers);
        field.legacyEdit("q"); expect(field, 0, 3, 1, watchers);
        field.setText(""); field.events.clear(); field.completions = 0;
        field.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });
        for (char c : "1234".toCharArray()) field.macapkHandleSoftKey(c, 0);
        check(field.getText().toString().equals("1234") && field.completions == 1,
                "four-digit editor must complete exactly once");
        field.macapkHandleSoftKey('5', 0);
        check(field.completions == 1, "filtered-out input must not repeat completion");
    }
    public static void main(String[] args) {
        exercise(false); exercise(true);
        System.out.println(checks + " TextView subclass change checks passed");
    }
}
