import com.macapk.loader.BinaryXmlParser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.w3c.dom.Element;

/** Generated resource-map-only AXML regression; no APK fixture required. */
public class BinaryXmlAttributeNamesTest {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static byte[] chunk(int type, int header, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)type).putShort((short)header).putInt(b.capacity()).put(body);
        return b.array();
    }
    static byte[] fixture(String idName) throws Exception {
        String[] strings = {idName, "FrameLayout", "http://schemas.android.com/apk/res/android", "", "", "android"};
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer pool = ByteBuffer.allocate(20 + strings.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        pool.putInt(strings.length).putInt(0).putInt(0x100).putInt(28 + strings.length * 4).putInt(0);
        for (String s : strings) {
            pool.putInt(data.size()); byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            data.write(s.length()); data.write(u.length); data.write(u); data.write(0);
        }
        while ((data.size() & 3) != 0) data.write(0);
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        ByteArrayOutputStream sp = new ByteArrayOutputStream(); sp.write(pool.array()); sp.write(data.toByteArray());
        chunks.write(chunk(1, 28, sp.toByteArray()));
        ByteBuffer map = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        map.putInt(0x010100d0).putInt(0).putInt(0).putInt(0x010100f4).putInt(0x010100f5);
        chunks.write(chunk(0x180, 8, map.array()));
        ByteBuffer ns = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        ns.putInt(1).putInt(-1).putInt(5).putInt(2);
        chunks.write(chunk(0x100, 16, ns.array()));
        ByteBuffer el = ByteBuffer.allocate(28 + 3 * 20).order(ByteOrder.LITTLE_ENDIAN);
        el.putInt(1).putInt(-1).putInt(-1).putInt(1);
        el.putShort((short)20).putShort((short)20).putShort((short)3).putShort((short)1).putShort((short)0).putShort((short)0);
        int[] names = {0, 3, 4}, values = {0x7f09005f, -1, -2};
        for (int i = 0; i < 3; i++) {
            el.putInt(2).putInt(names[i]).putInt(-1).putShort((short)8).put((byte)0).put((byte)(i == 0 ? 1 : 0x10)).putInt(values[i]);
        }
        chunks.write(chunk(0x102, 16, el.array()));
        ByteBuffer end = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(1).putInt(-1).putInt(-1).putInt(1);
        chunks.write(chunk(0x103, 16, end.array())); chunks.write(chunk(0x101, 16, ns.array()));
        return chunk(3, 8, chunks.toByteArray());
    }
    public static void main(String[] args) throws Exception {
        BinaryXmlParser.Names names = new BinaryXmlParser.Names() {
            public String typeName(int id) { return switch (id) {
                case 0x010100d0 -> "attr/id";
                case 0x010100f4 -> "attr/layout_width";
                case 0x010100f5 -> "attr/layout_height";
                case 0x7f09005f -> "id/content";
                default -> null;
            }; }
            public String enumText(int id, int type, int data) { return data == -1 ? "match_parent" : data == -2 ? "wrap_content" : null; }
        };
        String ns = "http://schemas.android.com/apk/res/android";
        Element e = BinaryXmlParser.parse(fixture(""), names).getDocumentElement();
        check(e.getAttributeNS(ns, "id").equals("@id/content"), "resource-map ID restores id attr");
        check(e.getAttributeNS(ns, "layout_width").equals("match_parent"), "layout width restored");
        check(e.getAttributeNS(ns, "layout_height").equals("wrap_content"), "layout height restored");
        check(e.getTagName().equals("FrameLayout"), "element name preserved");
        check(BinaryXmlParser.toText(e.getOwnerDocument()).contains("android:id=\"@id/content\""), "serialized DOM retains restored attr");
        e = BinaryXmlParser.parse(fixture("explicit"), names).getDocumentElement();
        check(e.hasAttributeNS(ns, "explicit") && !e.hasAttributeNS(ns, "id"), "present raw name preserved");
        e = BinaryXmlParser.parse(fixture("")).getDocumentElement();
        check(!e.hasAttributeNS(ns, "id"), "unknown names remain absent without resolver");
        System.out.println(checks + " binary XML attribute-name checks passed");
    }
}
