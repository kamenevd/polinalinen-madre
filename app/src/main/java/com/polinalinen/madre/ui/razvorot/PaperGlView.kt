package com.polinalinen.madre.ui.razvorot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PaperGlFrame(val fold: PaperFold, val front: Bitmap, val under: Bitmap, val paper: Int, val night: Boolean, val grid: Boolean = false)

/** GLES2 only; TextureView composes with native reader chrome. No AGSL/WebView. */
internal class PaperGlView(context: Context, private val failure: () -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    private val worker = HandlerThread("MadrePaperGL").apply { start() }
    private val handler = Handler(worker.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var window: Surface? = null
    private var program = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var stopped = false
    private var frame: PaperGlFrame? = null
    private var pendingTextures:List<Bitmap> = emptyList()
    private val textures = linkedMapOf<Bitmap, Int>()
    private val nx = 40
    private val ny = 80
    private val vertices = ByteBuffer.allocateDirect((nx + 1) * (ny + 1) * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val indices = ByteBuffer.allocateDirect(nx * ny * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
        for (y in 0 until ny) for (x in 0 until nx) {
            val a = y * (nx + 1) + x
            put(a.toShort()); put((a + 1).toShort()); put((a + nx + 1).toShort())
            put((a + 1).toShort()); put((a + nx + 2).toShort()); put((a + nx + 1).toShort())
        }; position(0)
    }
    init { isOpaque = false; surfaceTextureListener = this }
    fun submit(value: PaperGlFrame?) { handler.post { frame = value; if (!stopped && program != 0) safeDraw() } }
    fun prefetch(bitmaps: List<Bitmap>) { handler.post {
        pendingTextures=bitmaps
        if (!stopped && program != 0) runCatching {
            bitmaps.forEach { texture(it) }
            textures.keys.toList().filter { it !in bitmaps && it !== frame?.front && it !== frame?.under }.forEach { bitmap ->
                textures.remove(bitmap)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            }
        }.onFailure { post { failure() } }
    } }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            surfaceWidth = width; surfaceHeight = height
            runCatching {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
                val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
                val config = checkNotNull(configs[0])
                eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(eglContext != EGL14.EGL_NO_CONTEXT)
                window = Surface(surface)
                eglSurface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
                fun shader(type: Int, source: String): Int {
                    val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                    val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0); check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }; return id
                }
                val vertex = shader(GLES20.GL_VERTEX_SHADER, VS); val fragment = shader(GLES20.GL_FRAGMENT_SHADER, FS)
                program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
                val ok = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0)
                GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
                pendingTextures.forEach{texture(it)}
                safeDraw()
            }.onFailure { post { failure() } }
        }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { handler.post { surfaceWidth = width; surfaceHeight = height; safeDraw() } }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        handler.post { releaseGl(); surface.release(); worker.quitSafely() }
        return false
    }
    private fun releaseGl() {
        if(stopped)return
        stopped = true
        if (display != EGL14.EGL_NO_DISPLAY) {
            textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }; textures.clear()
            if (program != 0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        window?.release(); frame = null; pendingTextures=emptyList(); program = 0
    }
    override fun onDetachedFromWindow() {
        if(!isAvailable)handler.post{releaseGl();worker.quitSafely()}
        super.onDetachedFromWindow()
    }
    private fun texture(bitmap: Bitmap): Int = textures.getOrPut(bitmap) {
        val id = IntArray(1); GLES20.glGenTextures(1, id, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        id[0]
    }
    private fun safeDraw() { runCatching { draw() }.onFailure { post { failure() } } }
    private fun draw() {
        if (stopped || program == 0) return
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight); GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        frame?.let { f ->
            val g = f.fold
            vertices.clear()
            for (y in 0..ny) for (x in 0..nx) { vertices.put(g.width * x / nx); vertices.put(g.height * y / ny) }; vertices.position(0)
            GLES20.glUseProgram(program)
            val a = GLES20.glGetAttribLocation(program, "a"); GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, vertices)
            fun one(name: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), v)
            fun two(name: String, x: Float, y: Float) = GLES20.glUniform2f(GLES20.glGetUniformLocation(program, name), x, y)
            two("uP", g.px, g.py); two("uN", g.nx, g.ny); two("uS", g.width, g.height); one("uR", g.radius); one("uNight", if (f.night) 1f else 0f)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uPaper"), android.graphics.Color.red(f.paper) / 255f, android.graphics.Color.green(f.paper) / 255f, android.graphics.Color.blue(f.paper) / 255f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uT"), 0)
            fun pass(bitmap: Bitmap, under: Boolean) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture(bitmap)); one("uMode", if (under) 1f else 0f)
                indices.position(0); GLES20.glDrawElements(GLES20.GL_TRIANGLES, nx * ny * 6, GLES20.GL_UNSIGNED_SHORT, indices)
            }
            pass(f.under, true); pass(f.front, false)
            if (f.grid) {
                one("uGrid", 1f); indices.position(0); GLES20.glDrawElements(GLES20.GL_LINES, nx * ny * 6, GLES20.GL_UNSIGNED_SHORT, indices); one("uGrid", 0f)
            }
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        }
        check(EGL14.eglSwapBuffers(display, eglSurface))
    }
    companion object {
        private const val VS = """
attribute vec2 a; uniform vec2 uP,uN,uS; uniform float uR,uMode;
varying vec2 vUv; varying float vD,vTh;
void main(){vec2 v=a;float d=dot(v-uP,uN);vec3 pos=vec3(v,0.);float th=0.;
if(uMode<.5&&d>0.){float PR=3.14159265*uR;vec2 base=v-uN*d;
if(d<PR){th=d/uR;pos=vec3(base+uN*uR*sin(th),uR*(1.-cos(th)));}
else{th=3.14159265;pos=vec3(base-uN*(d-PR),2.*uR);}}
vUv=a/uS;vD=d;vTh=th;vec2 c=uS*.5;pos.xy=c+(pos.xy-c)*(1.+pos.z/3400.);
gl_Position=vec4(pos.x/uS.x*2.-1.,1.-pos.y/uS.y*2.,uMode>.5?.5:-pos.z/400.,1.);}
"""
        private const val FS = """
precision mediump float; uniform sampler2D uT; uniform vec3 uPaper;
uniform float uR,uMode,uNight,uGrid; varying vec2 vUv; varying float vD,vTh;
void main(){if(uGrid>.5){gl_FragColor=vec4(.8,.25,.1,1.);return;}
vec3 t=texture2D(uT,vUv).rgb;float K=1.+uNight*.6;
if(uMode>.5){float e=vD-uR*.9;float s=.58*K*exp(-max(e,0.)/(uR*1.1+20.));
if(e<0.)s*=smoothstep(-uR*1.75,0.,e);gl_FragColor=vec4(t*(1.-min(s,.8)),1.);return;}
float th=vTh;float crease=1.-.24*K*exp(-abs(vD)/(uR*.45+6.));float sh;vec3 base;
if(th<1.5708){sh=1.-.40*(1.-cos(th));base=t;sh+=.035*(1.-uNight*.6)*exp(-pow((th-1.12)/.06,2.));}
else{sh=.60+.20*(-cos(th));vec3 g=vec3(dot(uPaper,vec3(.333)));base=mix(mix(uPaper,g,.45)*(.92+uNight*.3),t,.07);}
if(vD<=0.)sh=1.;gl_FragColor=vec4(base*sh*crease,1.);}
"""
    }
}
