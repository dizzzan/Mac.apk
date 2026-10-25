import android.media.*;
import android.graphics.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.*;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MediaCodecStreamTest {
    static native byte[] sample(boolean hevc);
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static List<byte[]> nals(byte[] b) {
        List<Integer> starts=new ArrayList<>();
        for(int i=0;i+4<b.length;i++) if(b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1) starts.add(i);
        List<byte[]> out=new ArrayList<>();
        for(int i=0;i<starts.size();i++) out.add(Arrays.copyOfRange(b,starts.get(i),i+1<starts.size()?starts.get(i+1):b.length));
        return out;
    }
    static void queue(MediaCodec c, byte[] bytes, int flags, long pts) {
        int i=c.dequeueInputBuffer(0);check(i>=0,"available input");
        c.getInputBuffer(i).put(bytes);c.queueInputBuffer(i,0,bytes.length,pts,flags);
    }
    static void decode(boolean hevc, byte[] all, int mode) throws Exception {
        ByteArrayOutputStream config=new ByteArrayOutputStream(), frame=new ByteArrayOutputStream();
        List<byte[]> headers=new ArrayList<>();
        for(byte[] n:nals(all)) {
            int type=hevc?(n[4]>>1)&63:n[4]&31;
            boolean header=hevc?(type>=32&&type<=34):(type==7||type==8);
            (header?config:frame).write(n);if(header)headers.add(n);
        }
        String mime=hevc?"video/hevc":"video/avc";
        MediaFormat f=MediaFormat.createVideoFormat(mime,64,64);
        if(mode==3) f.setByteBuffer("csd-0",ByteBuffer.wrap(config.toByteArray()));
        SurfaceTexture target=new SurfaceTexture(0);target.setDefaultBufferSize(64,64);
        Surface surface=new Surface(target);
        SurfaceTexture other=new SurfaceTexture(0);Surface unrelated=new Surface(other);
        MediaCodec c=MediaCodec.createDecoderByType(mime);c.configure(f,surface,null,0);c.start();
        long pts=1234567;
        if(mode==0) queue(c,all,0,pts);
        else {
            if(mode==1)queue(c,config.toByteArray(),MediaCodec.BUFFER_FLAG_CODEC_CONFIG,0);
            if(mode==2)for(byte[] h:headers)queue(c,h,MediaCodec.BUFFER_FLAG_CODEC_CONFIG,0);
            queue(c,frame.toByteArray(),0,pts);
        }
        queue(c,new byte[0],MediaCodec.BUFFER_FLAG_END_OF_STREAM,pts);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int frames=0;boolean eos=false;
        long deadline=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<deadline&&!eos) {
            int i=c.dequeueOutputBuffer(info,0);
            if(i<0){Thread.sleep(1);continue;}
            eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            if(info.size>0){check(info.presentationTimeUs==pts,"decoded PTS");++frames;}
            c.releaseOutputBuffer(i,info.size>0);
        }
        check(eos&&frames==1,"one decoded picture and EOS, mode="+mode+" "+mime);
        Bitmap b=target.macapkLatchBitmap();check(b!=null,"configured surface receives frame");
        int color=b.getPixel(32,32);check(((color>>16)&255)>200&&(color&255)<40,"real red decoded pixels");
        check(target.getTimestamp()==pts*1000,"surface timestamp");
        check(other.macapkLatchBitmap()==null,"unrelated surface does not steal frame");
        c.flush();c.stop();c.release();surface.release();unrelated.release();target.release();other.release();
    }
    static void decodeAsync(boolean hevc, byte[] bytes, Handler handler) throws Exception {
        MediaCodec codec = MediaCodec.createDecoderByType(hevc ? "video/hevc" : "video/avc");
        SurfaceTexture texture = new SurfaceTexture(0);
        Surface surface = new Surface(texture);
        codec.configure(MediaFormat.createVideoFormat(hevc ? "video/hevc" : "video/avc", 64, 64), surface, null, 0);
        // Resume the same decoder after flush as well as alternating codecs.
        try {
            for (int pass = 0; pass < 2; ++pass) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                final long pts = 1234567 + pass;
                codec.setCallback(new MediaCodec.Callback() {
                    int inputs, frames;
                    boolean format;
                    void thread() { check(Thread.currentThread() == handler.getLooper().getThread(), "async callback thread"); }
                    public void onInputBufferAvailable(MediaCodec c, int index) {
                        try {
                            thread();
                            if (inputs++ == 0) {
                                c.getInputBuffer(index).put(bytes);
                                c.queueInputBuffer(index, 0, bytes.length, pts, 0);
                            } else if (inputs == 2) c.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } catch (Throwable t) { failure.set(t); done.countDown(); }
                    }
                    public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
                        try { thread(); format = true; }
                        catch (Throwable t) { failure.set(t); done.countDown(); }
                    }
                    public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
                        try {
                            thread(); check(format, "format before async output");
                            if (info.size > 0) { ++frames; check(info.presentationTimeUs == pts, "async PTS"); }
                            c.releaseOutputBuffer(index, info.size > 0);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                check(frames == 1, "one async decoded frame");
                                done.countDown();
                            }
                        } catch (Throwable t) { failure.set(t); done.countDown(); }
                    }
                    public void onError(MediaCodec c, MediaCodec.CodecException e) { failure.set(e); done.countDown(); }
                }, handler);
                codec.start();
                check(done.await(5, TimeUnit.SECONDS), "async decode stalled");
                if (failure.get() != null) throw new AssertionError("async decode", failure.get());
                Bitmap bitmap = texture.macapkLatchBitmap();
                check(bitmap != null && ((bitmap.getPixel(32, 32) >> 16) & 255) > 200, "async decoded red pixels");
                codec.flush();
            }
        } finally { codec.release(); surface.release(); texture.release(); }
    }
    public static void run() throws Exception {
        for(boolean hevc:new boolean[]{false,true}) {
            byte[] all=sample(hevc);
            for(int mode=0;mode<4;mode++)decode(hevc,all,mode);
            MediaCodec empty=MediaCodec.createDecoderByType(hevc?"video/hevc":"video/avc");
            empty.configure(MediaFormat.createVideoFormat(hevc?"video/hevc":"video/avc",64,64),null,null,0);
            empty.start();empty.flush();queue(empty,new byte[0],MediaCodec.BUFFER_FLAG_END_OF_STREAM,0);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int i=empty.dequeueOutputBuffer(info,0);
            if(i<0)i=empty.dequeueOutputBuffer(info,0);
            check(i>=0&&(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0,"EOS before headers");
            empty.releaseOutputBuffer(i,false);empty.stop();empty.release();
        }
        HandlerThread callbacks = new HandlerThread("stream-test-callbacks");
        callbacks.start();
        try {
            Handler handler = new Handler(callbacks.getLooper());
            byte[] avc = sample(false), hevc = sample(true);
            for (int i = 0; i < 16; ++i) decodeAsync(i % 2 != 0, i % 2 == 0 ? avc : hevc, handler);
        } finally { callbacks.quit(); callbacks.join(5000); }
    }
}
