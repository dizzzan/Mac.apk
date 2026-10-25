import android.content.res.Resources;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import java.util.Arrays;

/** Plain JVM regression; run with MACAPK_LOG_LEVEL=99 and runtime/build/lib/*. */
public final class ResourcesArrayDispatchTest {
    public static final class Index {
        public String[] stringArrayForId(int id) {
            return id == 1 ? new String[]{"first", "second", "@null"} : new String[0];
        }
    }

    public static void main(String[] args) {
        Resources.macapkSetResourceIndex(new Index());
        Resources wrapper = new Resources(null, new DisplayMetrics(), new Configuration()) {
            @Override public String[] getStringArray(int id) {
                CharSequence[] text = getTextArray(id);
                String[] strings = new String[text.length];
                for (int i = 0; i < text.length; i++)
                    strings[i] = text[i] == null ? null : text[i].toString();
                return strings;
            }
            @Override public CharSequence[] getTextArray(int id) {
                return super.getTextArray(id);
            }
        };
        String[] expected = {"first", "second", null};
        if (!Arrays.equals(wrapper.getStringArray(1), expected)
                || !Arrays.equals(wrapper.getTextArray(1), expected))
            throw new AssertionError("Wrapper lost array values or null reference");
        if (wrapper.getStringArray(2).length != 0)
            throw new AssertionError("Empty array lookup changed");
        System.out.println("Resource array wrappers resolve without virtual recursion");
    }
}
