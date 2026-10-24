import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.LineHeightSpan;
import java.lang.reflect.Method;

/** Regression for reflective height queries and AOSP's last-visible-line geometry. */
public final class StaticLayoutCappedHeightTest {
    private static int checks;
    private static void equal(int expected, int actual) {
        checks++;
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    private static final class FixedPaint extends TextPaint {
        @Override public int getFontMetricsInt(Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.top = -12;
                fm.ascent = -10;
                fm.descent = 3;
                fm.bottom = 7;
                fm.leading = 0;
            }
            return 13;
        }
    }

    private static StaticLayout layout(CharSequence text, int limit, boolean pad,
            float spacing, TextUtils.TruncateAt ellipsize) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), new FixedPaint(), 1000)
                .setMaxLines(limit).setIncludePad(pad).setLineSpacing(spacing, 1)
                .setEllipsize(ellipsize).build();
    }

    public static void main(String[] args) throws Exception {
        Method height = StaticLayout.class.getDeclaredMethod("getHeight", boolean.class);
        String text = "one\ntwo\nthree\nfour";
        for (boolean pad : new boolean[]{false, true}) {
            for (float spacing : new float[]{0, 5, -2}) {
                for (int limit : new int[]{1, 2, 4, 6, Integer.MAX_VALUE}) {
                    StaticLayout full = layout(text, limit, pad, spacing, null);
                    equal(4, full.getLineCount());
                    equal(full.getHeight(), (Integer) height.invoke(full, false));
                    int visible = Math.min(limit, 4);
                    int expected = visible * 13 + (visible - 1) * (int) spacing
                            + (pad ? 6 : 0);
                    equal(expected, (Integer) height.invoke(full, true));
                    equal(4, full.getLineCount());
                    StaticLayout ellipsized = layout(text, limit, pad, spacing,
                            TextUtils.TruncateAt.END);
                    equal(ellipsized.getHeight(), ellipsized.getHeight(true));
                }
            }
        }

        SpannableString spanned = new SpannableString(text);
        spanned.setSpan((LineHeightSpan) (s, start, end, spanTop, lineTop, fm) -> {
            fm.top = -24;
            fm.ascent = -20;
            fm.descent = 6;
            fm.bottom = 14;
        }, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        StaticLayout withSpan = layout(spanned, 2, true, 5, null);
        equal(69, withSpan.getHeight(true)); // 2*26 + 5 spacing + 12 padding
        equal(131, withSpan.getHeight(false)); // 4*26 + 3*5 + 12
        equal(4, withSpan.getLineCount());
        equal(69, withSpan.getHeight(true));
        StaticLayout wrapper = new StaticLayout(text, new FixedPaint(), 1000,
                android.text.Layout.Alignment.ALIGN_NORMAL, 1, 0, false) {
            @Override public int getHeight() { return getHeight(true); }
        };
        equal(52, wrapper.getHeight());
        System.out.println("PASS: " + checks + " StaticLayout capped-height checks");
    }
}
