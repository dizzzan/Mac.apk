import android.graphics.SurfaceTexture;
import android.opengl.*;
import java.nio.*;

/** Pixel regression using generated frames, the actual JNI bridge, and ANGLE. */
public class SurfaceTextureExternalTest {
    private static int checks;
    private static final int EXTERNAL = 0x8d65;
    private static final int UNPACK_BUFFER = 0x88ec;
    private static final int UNPACK_BUFFER_BINDING = 0x88ef;
    private static final int ROW_LENGTH = 0x0cf2, SKIP_ROWS = 0x0cf3, SKIP_PIXELS = 0x0cf4;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static int value(int name) {
        int[] result = new int[1];
        GLES20.glGetIntegerv(name, result, 0);
        return result[0];
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        check(status[0] != 0, "shader: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int[] textures(int count) {
        int[] result = new int[count];
        GLES20.glGenTextures(count, result, 0);
        return result;
    }

    public static void run() {
        EGLDisplay display = EGL14.eglGetDisplay(0);
        check(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0), "initialize");
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attributes = {0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8,
                            0x3022, 8, 0x3021, 8, 0x3038};
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                && count[0] > 0, "config");
        EGLContext context = EGL14.eglCreateContext(display, configs[0],
                EGL14.EGL_NO_CONTEXT, new int[]{0x3098, 3, 0x3038}, 0);
        EGLSurface pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{0x3057, 2, 0x3056, 2, 0x3038}, 0);
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "current");
        check(GLES20.glGetString(GLES20.GL_EXTENSIONS).contains("GL_OES_EGL_image_external"),
                "backend must support OES images");

        int program = GLES20.glCreateProgram();
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 a; uniform mat4 transform; varying vec2 uv;"
                + "void main(){gl_Position=vec4(a,0.0,1.0);"
                + "uv=(transform*vec4((a+1.0)*0.5,0.0,1.0)).xy;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; varying vec2 uv; uniform samplerExternalOES image;"
                + "void main(){gl_FragColor=texture2D(image,uv);}");
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        check(linked[0] != 0, "link");
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 3);
        int transform = GLES20.glGetUniformLocation(program, "transform");
        FloatBuffer vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertices.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).flip();
        int position = GLES20.glGetAttribLocation(program, "a");
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glViewport(0, 0, 2, 2);

        int[] names = textures(2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + 3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[1]);
        GLES20.glBindTexture(EXTERNAL, names[0]);
        int[] pbo = new int[1];
        GLES20.glGenBuffers(1, pbo, 0);
        GLES20.glBindBuffer(UNPACK_BUFFER, pbo[0]);
        GLES20.glBufferData(UNPACK_BUFFER, 256, null, GLES20.GL_STATIC_DRAW);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 8);
        GLES20.glPixelStorei(ROW_LENGTH, 9);
        GLES20.glPixelStorei(SKIP_ROWS, 2);
        GLES20.glPixelStorei(SKIP_PIXELS, 3);
        check(GLES20.glGetError() == 0, "initial GL state");

        SurfaceTexture stream = new SurfaceTexture(names[0]);
        ByteBuffer read = ByteBuffer.allocateDirect(16);
        float[] matrix = new float[16];
        for (int frame = 0; frame < 80; frame++) {
            int width = frame % 2 == 0 ? 2 : 4, height = width, stride = width + 3;
            int[] pixels = new int[stride * height];
            int[] corners = frame % 3 == 0
                    ? new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff}
                    : new int[]{0xff00ff00, 0xff0000ff, 0xffffffff, 0xffff0000};
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * stride + x] = corners[(y < height / 2 ? 0 : 2)
                            + (x < width / 2 ? 0 : 1)];
                }
            }
            stream.macapkQueueDecodedFrame(pixels, width, height, stride, 1000L + frame);
            stream.updateTexImage();
            check(GLES20.glGetError() == 0, "external upload frame " + frame);
            check(stream.getTimestamp() == 1000L + frame, "timestamp");
            check(value(0x8069) == names[1] && value(GLES20.GL_ACTIVE_TEXTURE) == 0x84c3
                    && value(0x8d67) == names[0], "texture bindings");
            check(value(UNPACK_BUFFER_BINDING) == pbo[0]
                    && value(GLES20.GL_UNPACK_ALIGNMENT) == 8 && value(ROW_LENGTH) == 9
                    && value(SKIP_ROWS) == 2 && value(SKIP_PIXELS) == 3, "unpack state");
            stream.getTransformMatrix(matrix);
            GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            read.clear();
            GLES20.glReadPixels(0, 0, 2, 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read);
            check(GLES20.glGetError() == 0, "draw/read frame " + frame);
            // GL readback starts at the bottom. Decoder memory starts at the
            // top: applying SurfaceTexture's matrix must keep the image upright.
            for (int i = 0; i < 4; i++) {
                int offset = i * 4;
                int actual = (read.get(offset + 3) & 255) << 24 | (read.get(offset) & 255) << 16
                        | (read.get(offset + 1) & 255) << 8 | (read.get(offset + 2) & 255);
                check(actual == corners[i ^ 2], "sample frame " + frame + " corner " + i
                        + ": " + Integer.toHexString(actual));
            }
            if (frame == 39) {
                stream.detachFromGLContext();
                int[] replacement = textures(1);
                stream.attachToGLContext(replacement[0]);
                GLES20.glDeleteTextures(1, names, 0);
                names[0] = replacement[0];
            }
        }
        GLES20.glEnable(0xdead); // An error belonging to the caller must survive.
        stream.macapkQueueDecodedFrame(new int[]{-1}, 1, 1, 1, 2000);
        stream.updateTexImage();
        check(GLES20.glGetError() == GLES20.GL_INVALID_ENUM, "caller error preserved");
        check(GLES20.glGetError() == 0, "no new error");
        SurfaceTexture software = new SurfaceTexture(false);
        software.macapkQueueDecodedFrame(new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, -1},
                2, 2, 2, 3000);
        check(software.macapkLatchBitmap().getPixel(0, 0) == 0xffff0000,
                "software top row unchanged");
        software.release();
        stream.release();
        GLES20.glBindBuffer(UNPACK_BUFFER, 0);
        GLES20.glDeleteBuffers(1, pbo, 0);
        GLES20.glDeleteTextures(2, names, 0);
        GLES20.glDeleteProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, pbuffer);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
        System.out.println("SurfaceTexture external-image checks PASS: " + checks);
    }
}
