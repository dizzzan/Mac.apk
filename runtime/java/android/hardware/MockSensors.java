package android.hardware;

import java.util.ArrayList;
import java.util.List;

/** Optional, explicitly simulated environment inputs for a virtual phone.
 * Values use SensorEvent's Android units, not host hardware measurements.
 * Like Android Emulator's Additional Sensors controls, each input is stable
 * until configured. The normal runtime catalog remains unchanged. */
public final class MockSensors {
    private MockSensors() {}
    private static final boolean ENABLED = "1".equals(System.getenv("MACAPK_MOCK_SENSORS"));

    private static final class Input {
        final Sensor sensor;
        final float min;
        final float max;
        volatile float[] values;

        Input(int type, String label, String stringType, String key, float min,
                float max, float resolution, int mode, float... defaults) {
            this.min = min;
            this.max = max;
            sensor = new Sensor(type, "Mac.apk Simulated " + label, stringType,
                    max, resolution, 0f, mode == Sensor.REPORTING_MODE_CONTINUOUS ? 10000 : 0, mode);
            values = defaults;
            String text = System.getenv("MACAPK_MOCK_SENSOR_" + key);
            if (text != null) {
                try {
                    String[] parts = text.split(",", -1);
                    float[] parsed = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) parsed[i] = Float.parseFloat(parts[i].trim());
                    validate(parsed);
                    values = parsed;
                } catch (IllegalArgumentException invalid) {
                    System.err.println("[mock-sensors] Invalid " + key + "; using simulated default");
                }
            }
        }

        void validate(float[] v) {
            if (v == null || v.length != values.length) throw new IllegalArgumentException("sensor dimensions");
            for (float n : v) if (!Float.isFinite(n) || n < min || n > max)
                throw new IllegalArgumentException("sensor value out of range");
        }
    }

    private static final Input[] INPUTS = ENABLED ? new Input[] {
        new Input(Sensor.TYPE_MAGNETIC_FIELD, "Magnetic Field", Sensor.STRING_TYPE_MAGNETIC_FIELD,
                "MAGNETIC_FIELD", -2000f, 2000f, 0.1f, Sensor.REPORTING_MODE_CONTINUOUS, 0f, 25f, -40f),
        new Input(Sensor.TYPE_LIGHT, "Light", Sensor.STRING_TYPE_LIGHT,
                "LIGHT", 0f, 100000f, 1f, Sensor.REPORTING_MODE_ON_CHANGE, 200f),
        new Input(Sensor.TYPE_PRESSURE, "Pressure", Sensor.STRING_TYPE_PRESSURE,
                "PRESSURE", 1f, 1100f, 0.01f, Sensor.REPORTING_MODE_CONTINUOUS, 1013.25f),
        new Input(Sensor.TYPE_PROXIMITY, "Proximity", Sensor.STRING_TYPE_PROXIMITY,
                "PROXIMITY", 0f, 5f, 1f, Sensor.REPORTING_MODE_ON_CHANGE, 5f),
        new Input(Sensor.TYPE_RELATIVE_HUMIDITY, "Relative Humidity", Sensor.STRING_TYPE_RELATIVE_HUMIDITY,
                "HUMIDITY", 0f, 100f, 0.1f, Sensor.REPORTING_MODE_ON_CHANGE, 45f),
        new Input(Sensor.TYPE_AMBIENT_TEMPERATURE, "Ambient Temperature", Sensor.STRING_TYPE_AMBIENT_TEMPERATURE,
                "TEMPERATURE", -40f, 85f, 0.1f, Sensor.REPORTING_MODE_ON_CHANGE, 22f),
    } : new Input[0];

    static List<Sensor> catalog() {
        List<Sensor> result = new ArrayList<>();
        for (Input input : INPUTS) result.add(input.sensor);
        return result;
    }

    static SensorEvent sample(int type) {
        for (Input input : INPUTS) if (input.sensor.getType() == type) {
            float[] values = input.values;
            SensorEvent event = SensorEvent.macapkNew(values.length);
            System.arraycopy(values, 0, event.values, 0, values.length);
            return event;
        }
        return null;
    }

    /** Loader/test control. Each update replaces a complete immutable sample;
     * Java listeners and NDK queues consume the same input on their next tick. */
    public static void setValues(int type, float... values) {
        for (Input input : INPUTS) if (input.sensor.getType() == type) {
            input.validate(values);
            input.values = values.clone();
            return;
        }
        throw new IllegalArgumentException("mock sensor is not enabled: " + type);
    }
}
