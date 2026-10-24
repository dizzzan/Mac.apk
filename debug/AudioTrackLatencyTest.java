import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import com.macapk.loader.NativeAudio;

public class AudioTrackLatencyTest {
    static void invalid(Runnable query) {
        try { query.run(); throw new AssertionError("Expected IllegalStateException"); }
        catch (IllegalStateException expected) { }
    }
    public static void main(String[] args) {
        System.load(args[0]);
        AudioTrack small = new AudioTrack(AudioManager.STREAM_MUSIC, 48000,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                4800 * 4, AudioTrack.MODE_STREAM);
        AudioTrack large = new AudioTrack(AudioManager.STREAM_MUSIC, 48000,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                9600 * 4, AudioTrack.MODE_STREAM);
        if (small.getState() != AudioTrack.STATE_INITIALIZED || large.getState() != AudioTrack.STATE_INITIALIZED)
            throw new AssertionError("Real audio output required");
        int first = small.getLatency(), second = large.getLatency();
        int bufferDelta = (large.getBufferCapacityInFrames() - small.getBufferCapacityInFrames()) * 1000 / 48000;
        if (first < 100 || Math.abs(second - first - bufferDelta) > 1)
            throw new AssertionError("Latency must follow real buffer duration: " + first + "," + second);
        small.setBufferSizeInFrames(2400);
        if (small.getLatency() != first) throw new AssertionError("Fill threshold is not buffer capacity");
        small.release(); large.release();
        invalid(small::getLatency);
        invalid(() -> NativeAudio.nativeGetLatency(0));
        invalid(() -> NativeAudio.nativeGetLatency(Long.MAX_VALUE));
        System.out.println("Real output latency " + first + "/" + second + " ms; capacity and invalid/released handles pass");
    }
}
