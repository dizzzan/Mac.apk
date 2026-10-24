import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/** Resource wrappers must be able to delegate to super without cycling back. */
public final class ResourcesTextDispatchTest {
    public static final class Index {
        public String stringForId(int id) {
            return switch (id) { case 1 -> "hello %s"; case 2 -> ""; default -> null; };
        }
        public String pluralForId(int id, String quantity) {
            return id == 3 ? ("one".equals(quantity) ? "%d item" : "%d items") : null;
        }
    }
    private static void equal(String expected, CharSequence actual) {
        if (!expected.contentEquals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        Resources.macapkSetResourceIndex(new Index());
        Resources wrapper = new Resources(null, new DisplayMetrics(), new Configuration()) {
            @Override public String getString(int id) { return getText(id).toString(); }
            @Override public CharSequence getText(int id) { return super.getText(id); }
            @Override public String getQuantityString(int id, int n) {
                return getQuantityText(id, n).toString();
            }
            @Override public CharSequence getQuantityText(int id, int n) {
                return super.getQuantityText(id, n);
            }
        };
        equal("hello %s", wrapper.getText(1));
        equal("hello reader", wrapper.getString(1, "reader"));
        equal("", wrapper.getText(2, "fallback"));
        equal("fallback", wrapper.getText(0, "fallback"));
        equal("fallback", wrapper.getText(99, "fallback"));
        equal("%d items", wrapper.getQuantityString(3, 4));
        equal("4 items", wrapper.getQuantityString(3, 4, 4));
        equal("hello %s", wrapper.getQuantityText(1, 4));
        Resources decorated = new Resources(null, new DisplayMetrics(), new Configuration()) {
            @Override public CharSequence getText(int id) { return "override"; }
            @Override public CharSequence getQuantityText(int id, int n) { return "count %d"; }
        };
        equal("override", decorated.getString(1));
        equal("count %d", decorated.getQuantityString(3, 4));
        equal("count 4", decorated.getQuantityString(3, 4, 4));
        System.out.println("PASS: text/plural wrappers, default values, and virtual dispatch");
    }
}
