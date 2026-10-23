import android.provider.Settings;

/** Legacy Secure callers must observe the same stored setting as Global callers. */
public class SettingsMovedKeyTest {
    public static void main(String[] args) throws Exception {
        String key = (String) Settings.Secure.class.getField("ADB_ENABLED").get(null);
        if (!key.equals(Settings.Global.ADB_ENABLED)) throw new AssertionError("public alias missing");
        Settings.Global.putInt(null, key, 1);
        if (Settings.Secure.getInt(null, key, 0) != 1) throw new AssertionError("read wrong namespace");
        Settings.Secure.putInt(null, key, 0);
        if (Settings.Global.getInt(null, key, 1) != 0) throw new AssertionError("write wrong namespace");
        Settings.Secure.putString(null, key, null);
        if (Settings.Secure.getInt(null, key, 7) != 7) throw new AssertionError("missing default lost");
        try {
            Settings.Secure.getInt(null, key);
            throw new AssertionError("missing setting should throw");
        } catch (Settings.SettingNotFoundException expected) { }
        if (!Settings.Secure.getUriFor(key).equals(Settings.Global.getUriFor(key)))
            throw new AssertionError("observer URI disagrees");
        Settings.Secure.putInt(null, "unrelated_key", 9);
        if (Settings.Global.getInt(null, "unrelated_key", -1) != -1)
            throw new AssertionError("unrelated key escaped secure namespace");
        System.out.println("legacy setting alias, shared storage, missing results and observer URI passed");
    }
}
