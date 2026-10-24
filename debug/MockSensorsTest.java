import android.hardware.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class MockSensorsTest {
    static int checks;
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
    public static void run() throws Exception {
        boolean enabled = "1".equals(System.getenv("MACAPK_MOCK_SENSORS"));
        SensorManager sm = SensorManager.macapkNew();
        var pm = new com.macapk.loader.MacApkPackageManager("org.macapk.sensor.test");
        boolean bluetooth = "1".equals(System.getenv("MACAPK_MOCK_BLUETOOTH"));
        var adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        check((adapter != null) == bluetooth, "optional Bluetooth adapter");
        check(pm.hasSystemFeature("android.hardware.bluetooth") == bluetooth, "Bluetooth feature");
        check(!pm.hasSystemFeature("android.hardware.bluetooth_le"), "no unsupported BLE feature");
        if (adapter != null) {
            check(!adapter.isEnabled() && adapter.getState() == 10, "powered-off state");
            check(!adapter.enable() && !adapter.startDiscovery() && !adapter.isDiscovering(), "no simulated connections");
            check(adapter.getBluetoothLeScanner() == null && adapter.getBluetoothLeAdvertiser() == null, "off BLE APIs");
            check(adapter.getBondedDevices().isEmpty(), "no fabricated bonds");
        }
        check(sm.getSensorList(-1).size() == (enabled ? 13 : 7), "catalog size");
        check(SensorManager.macapkNativeCatalog().length == (enabled ? 39 : 21), "NDK catalog size");
        int[] types = {2, 5, 6, 8, 12, 13};
        String[] features = {"compass", "light", "barometer", "proximity", "relative_humidity", "ambient_temperature"};
        for (int i = 0; i < types.length; i++) {
            Sensor s = sm.getDefaultSensor(types[i]);
            check((s != null) == enabled, "default sensor " + types[i]);
            check(SensorManager.macapkSensorFeatures().contains("android.hardware.sensor." + features[i]) == enabled,
                    "feature " + types[i]);
            check(pm.hasSystemFeature("android.hardware.sensor." + features[i]) == enabled, "PackageManager feature");
            if (enabled) {
                check(s.getName().contains("Simulated"), "label");
                float[] data = new float[16]; long[] ts = new long[1];
                check(SensorManager.macapkNativeSample(types[i], data, ts) == (types[i] == 2 ? 3 : 1), "sample dimensions");
                check(ts[0] > 0 && Math.abs(SystemClock.elapsedRealtimeNanos() - ts[0]) < 1_000_000_000L, "timestamp base");
            }
        }
        if (!enabled) { System.out.println("PASS mock sensors disabled: " + checks); return; }
        float[] data = new float[3];
        SensorManager.macapkNativeSample(5, data, null);
        float expected = Float.parseFloat(System.getProperty("test.light", "200"));
        check(data[0] == expected, "configured light");
        for (float bad : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, 100001}) {
            try { MockSensors.setValues(5, bad); throw new AssertionError("accepted bad light"); }
            catch (IllegalArgumentException right) { checks++; }
        }
        try { MockSensors.setValues(5, 1, 2); throw new AssertionError("bad dimension"); }
        catch (IllegalArgumentException right) { checks++; }
        float[] update = {321}; MockSensors.setValues(5, update); update[0] = 999;
        SensorManager.macapkNativeSample(5, data, null);
        check(data[0] == 321, "defensive copy and native update");
        HandlerThread thread = new HandlerThread("mock-sensor-test"); thread.start();
        AtomicInteger count = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BlockingQueue<Float> received = new LinkedBlockingQueue<>();
        SensorEventListener listener = new SensorEventListener() {
            public void onAccuracyChanged(Sensor s, int accuracy) {}
            public void onSensorChanged(SensorEvent e) {
                if (Thread.currentThread() != thread) failure.set(new AssertionError("wrong callback thread"));
                count.incrementAndGet(); received.add(e.values[0]);
            }
        };
        try {
            Sensor light = sm.getDefaultSensor(5);
            check(light.getReportingMode() == Sensor.REPORTING_MODE_ON_CHANGE, "light mode");
            check(sm.registerListener(listener, light, 10000, new Handler(thread.getLooper())), "registration");
            check(Float.valueOf(321).equals(received.poll(2, TimeUnit.SECONDS)), "initial sample");
            check(received.poll(150, TimeUnit.MILLISECONDS) == null, "no duplicate on-change events");
            MockSensors.setValues(5, 654);
            check(Float.valueOf(654).equals(received.poll(2, TimeUnit.SECONDS)), "changed sample");
            check(failure.get() == null, "listener thread");
            sm.unregisterListener(listener);
            int stopped = count.get(); MockSensors.setValues(5, 777);
            check(received.poll(100, TimeUnit.MILLISECONDS) == null && count.get() == stopped, "unregistered");
            sm.registerListener(listener, light, 10000, new Handler(thread.getLooper()));
            check(Float.valueOf(777).equals(received.poll(2, TimeUnit.SECONDS)), "fresh registration initial sample");
        } finally { sm.unregisterListener(listener); thread.quit(); thread.join(2000); }
        System.out.println("PASS mock sensors enabled: " + checks);
    }
    public static void main(String[] args) throws Exception { run(); }
}
