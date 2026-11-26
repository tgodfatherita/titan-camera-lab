package it.carmine.titancamera;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.IOException;
import java.nio.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static android.opengl.GLES20.*;

/** Draws directly from decoder to encoder. Pixel centers follow the reference warp. */
final class GyroSurface implements AutoCloseable {
    private static final int RECORDABLE_ANDROID = 0x3142;
    private static final String VERTEX =
            "attribute vec2 pos; uniform mat3 rotation; uniform vec2 size; uniform float focal,zoom; uniform bool storeNative,reverseTurn; varying highp vec3 ray;\n" +
            "void main(){gl_Position=vec4(pos,0.,1.); vec2 axis=storeNative?(reverseTurn?-pos.yx:pos.yx):vec2(pos.x,-pos.y); vec2 pixel=axis*size*.5-vec2(.5);" +
            "ray=rotation*vec3(pixel/(focal*zoom),1.);}";
    // Nine filtered samples implement the separable A=-0.6 bicubic kernel of the Marcata-era renderer.
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\nprecision highp float;\n" +
            "uniform samplerExternalOES video; uniform mat4 textureMatrix; uniform vec2 size; uniform float focal; uniform bool reverseTurn; varying highp vec3 ray;\n" +
            "vec4 sampleAt(float x,float y){vec2 uv=(vec2(x,y)+.5)/size;" +
            "vec2 nativeUv=reverseTurn?vec2(1.)-uv.yx:uv.yx; return texture2D(video,(textureMatrix*vec4(nativeUv,0.,1.)).xy);}\n" +
            "void main(){vec2 p=focal*ray.xy/ray.z+size*.5; vec2 b=floor(p), t=p-b, s=1.-t;" +
            "vec2 w0=-.6*t*s*s,w3=-.6*t*t*s;" +
            "vec2 w2=(1.4*s-2.4)*s*s+1.; vec2 mid=1.-w0-w3, c=b+w2/mid;" +
            "vec3 wx=vec3(w0.x,mid.x,w3.x), wy=vec3(w0.y,mid.y,w3.y);" +
            "vec4 a=wx.x*sampleAt(b.x-1.,b.y-1.)+wx.y*sampleAt(c.x,b.y-1.)+wx.z*sampleAt(b.x+2.,b.y-1.);" +
            "vec4 d=wx.x*sampleAt(b.x-1.,c.y)+wx.y*sampleAt(c.x,c.y)+wx.z*sampleAt(b.x+2.,c.y);" +
            "vec4 e=wx.x*sampleAt(b.x-1.,b.y+2.)+wx.y*sampleAt(c.x,b.y+2.)+wx.z*sampleAt(b.x+2.,b.y+2.);" +
            "gl_FragColor=vec4((a*wy.x+d*wy.y+e*wy.z).rgb,1.);}";

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface target = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private final HandlerThread callbacks = new HandlerThread("TitanTexture");
    private final Semaphore ready = new Semaphore(0);
    private final float[] textureMatrix = new float[16];
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(6*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    Surface decoderSurface;
    private int program, rotationUniform, matrixUniform;

    GyroSurface(Surface encoderSurface, int width, int height, float focal,boolean storeNative,int sourceRotation) throws IOException {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            require(EGL14.eglInitialize(display,version,0,version,1), "initialize");
            int[] attributes = { EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                    RECORDABLE_ANDROID,1, EGL14.EGL_NONE };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            require(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0,"config");
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            target = EGL14.eglCreateWindowSurface(display,configs[0],encoderSurface,new int[]{EGL14.EGL_NONE},0);
            require(EGL14.eglMakeCurrent(display,target,target,context),"makeCurrent");
            // A codec window has no display refresh. Synchronous queueing prevents
            // Android from replacing pending buffers when rendering faster than playback.
            require(EGL14.eglSwapInterval(display,1),"synchronous encoder window");
            program = glCreateProgram();
            int vs = shader(GL_VERTEX_SHADER,VERTEX), fs = shader(GL_FRAGMENT_SHADER,FRAGMENT);
            glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
            glDeleteShader(vs); glDeleteShader(fs);
            int[] ok = new int[1];
            glGetProgramiv(program,GL_LINK_STATUS,ok,0);
            if (ok[0]==0) throw new IOException(glGetProgramInfoLog(program));
            glUseProgram(program);
            vertices.put(new float[]{-1,-1,3,-1,-1,3}).flip();
            int position = glGetAttribLocation(program,"pos");
            glEnableVertexAttribArray(position);
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,vertices);
            glViewport(0,0,storeNative?height:width,storeNative?width:height);
            glUniform1i(glGetUniformLocation(program,"storeNative"),storeNative?1:0);
            glUniform1i(glGetUniformLocation(program,"reverseTurn"),sourceRotation==270?1:0);
            glUniform2f(glGetUniformLocation(program,"size"),width,height);
            glUniform1f(glGetUniformLocation(program,"focal"),focal);
            glUniform1f(glGetUniformLocation(program,"zoom"),(float)GyroMath.ZOOM);
            matrixUniform = glGetUniformLocation(program,"textureMatrix");
            rotationUniform = glGetUniformLocation(program,"rotation");
            int[] textureId = new int[1];
            glGenTextures(1,textureId,0);
            int type = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
            glActiveTexture(GL_TEXTURE0); glBindTexture(type,textureId[0]);
            glTexParameteri(type,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
            glTexParameteri(type,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(type,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(type,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId[0]);
            callbacks.start();
            texture.setOnFrameAvailableListener(unused -> ready.release(),new Handler(callbacks.getLooper()));
            decoderSurface = new Surface(texture);
            if (glGetError()!=GL_NO_ERROR) throw new IOException("Inizializzazione grafica non riuscita");
        } catch (RuntimeException | IOException e) { close(); throw e; }
    }

    void draw(float[] rotations,int frame,long ptsUs) throws Exception {
        if (!ready.tryAcquire(3,TimeUnit.SECONDS)) throw new IOException("Fotogramma non ricevuto dalla grafica");
        texture.updateTexImage();
        texture.getTransformMatrix(textureMatrix);
        glUniformMatrix4fv(matrixUniform,1,false,textureMatrix,0);
        glUniformMatrix3fv(rotationUniform,1,false,rotations,frame*9);
        glDrawArrays(GL_TRIANGLES,0,3);
        require(EGLExt.eglPresentationTimeANDROID(display,target,ptsUs*1000),"timestamp");
        require(EGL14.eglSwapBuffers(display,target),"swap");
    }
    private static int shader(int type,String source) throws IOException {
        int shader = glCreateShader(type);
        glShaderSource(shader,source); glCompileShader(shader);
        int[] ok = new int[1];
        glGetShaderiv(shader,GL_COMPILE_STATUS,ok,0);
        if (ok[0]==0) throw new IOException(glGetShaderInfoLog(shader));
        return shader;
    }
    private static void require(boolean ok,String operation) throws IOException {
        if (!ok) throw new IOException("EGL "+operation+" "+Integer.toHexString(EGL14.eglGetError()));
    }
    @Override public void close() {
        if (decoderSurface!=null) decoderSurface.release();
        if (texture!=null) texture.release();
        if (callbacks.isAlive()) callbacks.quitSafely();
        if (display!=EGL14.EGL_NO_DISPLAY) {
            if (program!=0) glDeleteProgram(program);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if (target!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,target);
            if (context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }
}
