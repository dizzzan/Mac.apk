import com.macapk.dex.ClassFileWriter;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

public class NestedPublicInterfaceTest {
    static class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
    static Class<?> nested(Loader loader, String name, int declared) {
        ClassFileWriter writer = new ClassFileWriter(0x2600, name,
                "java/lang/Object", new String[]{"java/lang/annotation/Annotation"});
        writer.addInnerClass(name, "fixture/Outer", name.substring(name.indexOf('$') + 1), declared | 0x2608);
        writer.addMethod(0x401, "value", "()I");
        return loader.define(name.replace('/', '.'), writer.toByteArray());
    }
    public static void main(String[] args) throws Exception {
        Loader loader = new Loader();
        Class<?> publicType = nested(loader, "fixture/Outer$Public", Modifier.PUBLIC);
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{publicType},
                (p, method, values) -> 73);
        if (!Modifier.isPublic(publicType.getModifiers())
                || !publicType.getMethod("value").invoke(proxy).equals(73)) throw new AssertionError();
        Class<?> packageType = nested(loader, "fixture/Outer$Package", 0);
        Object packageProxy = Proxy.newProxyInstance(loader, new Class<?>[]{packageType},
                (p, method, values) -> 91);
        if (Modifier.isPublic(packageType.getModifiers())
                || !packageProxy.getClass().getPackageName().equals("fixture")) throw new AssertionError();
        Class<?> privateType = nested(loader, "fixture/Outer$Private", Modifier.PRIVATE);
        if (!Modifier.isPrivate(privateType.getModifiers())) throw new AssertionError();
        System.out.println("Nested annotation proxies preserve declared public and non-public visibility");
    }
}
