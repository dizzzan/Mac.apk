import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.view.Surface;
import android.opengl.*;
public class NativeTextureWindowTest {
 static native long from(Surface s); static native Surface to(long w); static native int width(long w);
 static native void release(long w); static native void acquire(long w); static native int draw(long d,long cfg,long ctx,long w,int color);
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void run(){
  SurfaceTexture a=new SurfaceTexture(0),b=new SurfaceTexture(0);a.setDefaultBufferSize(16,12);b.setDefaultBufferSize(8,6);
  check(from(null)==0,"null Surface");
  Surface retired=new Surface(new SurfaceTexture(0));retired.release();check(from(retired)==0,"released Surface");
  Surface sa=new Surface(a),sb=new Surface(b);long wa=from(sa),wb=from(sb);check(wa!=0&&wb!=0&&wa!=wb,"distinct windows");
  check(from(sa)==wa,"stable identity");release(wa);check(to(wa)==sa&&to(wb)==sb,"specific Java Surface");
  check(width(wa)==16&&width(wb)==8,"stream dimensions");acquire(wa);release(wa);
  EGLDisplay d=EGL14.eglGetDisplay(0);check(EGL14.eglInitialize(d,new int[1],0,new int[1],0),"initialize");
  EGLConfig[] cs=new EGLConfig[1];int[] count=new int[1];
  check(EGL14.eglChooseConfig(d,new int[]{0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038},0,cs,0,1,count,0)&&count[0]>0,"config");
  EGLContext ctx=EGL14.eglCreateContext(d,cs[0],EGL14.EGL_NO_CONTEXT,new int[]{0x3098,2,0x3038},0);check(ctx.getNativeHandle()!=0,"context");
  for(int i=0;i<3;i++){
   int color=i%2==0?0xffff0000:0xff00ff00;
   check(draw(d.getNativeHandle(),cs[0].getNativeHandle(),ctx.getNativeHandle(),wa,color)==0,"native render/reconnect");
   Bitmap frame=a.macapkLatchBitmap();check(frame!=null&&frame.getWidth()==16&&frame.getPixel(0,0)==color,"first stream pixels "+i);
  }
  check(draw(d.getNativeHandle(),cs[0].getNativeHandle(),ctx.getNativeHandle(),wb,0xff0000ff)==0,"second render");
  Bitmap frame=b.macapkLatchBitmap();check(frame!=null&&frame.getWidth()==8&&frame.getPixel(0,0)==0xff0000ff,"second stream pixels");
  release(wa);release(wb);sa.release();sb.release();a.release();b.release();
  EGL14.eglDestroyContext(d,ctx);EGL14.eglTerminate(d);
 }
}
