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
internal class PaperGlView(context: Context, private val failure: (String) -> Unit, private val ready:()->Unit = {},private val presented:()->Unit = {}) : TextureView(context), TextureView.SurfaceTextureListener {
    private var stage = "awaiting-surface"
    private fun reportFailure(error: Throwable) {
        val reason="$stage: ${error.javaClass.simpleName}: ${error.message}; EGL=0x${EGL14.eglGetError().toString(16)}"
        android.util.Log.e("MadrePaperGL",reason,error)
        post { failure(reason) }
    }
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
    private val presentation=PaperPresentation()
    private var canStamp=false
    private var firstConsumerRedraw=false
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
    fun submit(value: PaperGlFrame?) { handler.post { if(frame!=value){frame = value; if (!stopped && program != 0) safeDraw()} } }
    fun prefetch(bitmaps: List<Bitmap>) { handler.post {
        pendingTextures=bitmaps
        if (!stopped && program != 0) runCatching {
            bitmaps.forEach { texture(it) }
            textures.keys.toList().filter { it !in bitmaps && it !== frame?.front && it !== frame?.under }.forEach { bitmap ->
                textures.remove(bitmap)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            }
        }.onFailure { reportFailure(it) }
    } }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            surfaceWidth = width; surfaceHeight = height
            firstConsumerRedraw=false
            runCatching {
                stage="egl-initialize"
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
                canStamp=EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS)?.split(' ')?.contains("EGL_ANDROID_presentation_time")==true
                stage="egl-config"
                val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
                val config = checkNotNull(configs[0])
                stage="egl-context"
                eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(eglContext != EGL14.EGL_NO_CONTEXT)
                stage="egl-window-${width}x$height"
                window = Surface(surface)
                eglSurface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
                fun shader(type: Int, source: String): Int {
                    stage=if(type==GLES20.GL_VERTEX_SHADER)"vertex-compile" else "fragment-compile"
                    val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                    val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0); check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }; return id
                }
                val vertex = shader(GLES20.GL_VERTEX_SHADER, VS); val fragment = shader(GLES20.GL_FRAGMENT_SHADER, FS)
                stage="shader-link"
                program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
                val ok = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { GLES20.glGetProgramInfoLog(program) }
                GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
                stage="texture-prefetch"
                pendingTextures.forEach{texture(it)}
                android.util.Log.i("MadrePaperGL","ready ${surfaceWidth}x$surfaceHeight stamp=$canStamp paper=${frame!=null}")
                post{ready()}
                safeDraw()
            }.onFailure { reportFailure(it) }
        }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { handler.post { surfaceWidth = width; surfaceHeight = height; safeDraw() } }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // SurfaceTexture's timestamp identifies the consumed buffer. A newer
        // transparent clear must never acknowledge an older skipped paper swap.
        val paper=presentation.consumed(surface.timestamp)
        if(com.polinalinen.madre.BuildConfig.DEBUG)android.util.Log.d("MadrePaperGL","consumed timestamp=${surface.timestamp} paper=$paper")
        if(paper)presented()
        if(surface.timestamp==0L)handler.post {
            // The first TextureView consumer latch can expose its initial empty
            // buffer. Re-present the CURRENT real GL frame once after that latch;
            // animations normally do this naturally, static pages must do it too.
            if(!stopped && program!=0 && !firstConsumerRedraw) {
                firstConsumerRedraw=true
                safeDraw()
            }
        }
    }
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
    private fun safeDraw() { stage="draw-swap";runCatching { draw() }.onFailure { reportFailure(it) } }
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
            one("uRimWidth", PaperLight.RIM_DP * resources.displayMetrics.density * g.width / surfaceWidth)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uPaper"), android.graphics.Color.red(f.paper) / 255f, android.graphics.Color.green(f.paper) / 255f, android.graphics.Color.blue(f.paper) / 255f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uT"), 0)
            fun pass(bitmap: Bitmap, under: Boolean) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture(bitmap)); one("uMode", if (under) 1f else 0f)
                indices.position(0); GLES20.glDrawElements(GLES20.GL_TRIANGLES, nx * ny * 6, GLES20.GL_UNSIGNED_SHORT, indices)
            }
            pass(f.under, true); pass(f.front, false)
            if (f.grid) {
                one("uGrid", 1f); indices.position(0); GLES20.glDrawElements(GLES20.GL_LINES, nx * ny * 6, GLES20.GL_UNSIGNED_SHORT, indices)
                val axes=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(floatArrayOf(g.px-g.nx*g.width,g.py-g.ny*g.width,g.px+g.nx*g.width,g.py+g.ny*g.width,
                        g.px+g.ny*g.height,g.py-g.nx*g.height,g.px-g.ny*g.height,g.py+g.nx*g.height));position(0)
                }
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);one("uMode",1f)
                GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,axes);GLES20.glDrawArrays(GLES20.GL_LINES,0,4)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);one("uGrid", 0f)
            }
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        }
        if(canStamp) {
            val timestamp=System.nanoTime()
            if(EGLExt.eglPresentationTimeANDROID(display,eglSurface,timestamp))presentation.queued(timestamp,frame!=null)
        }
        // Timestamp verification is diagnostic only, not a new GLES requirement:
        // unsupported drivers still render, but do not claim verified frame counts.
        check(EGL14.eglSwapBuffers(display, eglSurface))
        // A one-shot static scene has no animation to invalidate the owner.
        // Schedule the real TextureView draw that consumes its queued buffer.
        postInvalidateOnAnimation()
    }
    companion object {
        private const val VS = """
attribute vec2 a; uniform vec2 uP,uN;uniform mediump vec2 uS; uniform mediump float uR,uMode;
varying mediump vec2 vUv; varying mediump float vD,vTh;
void main(){vec2 v=a;float d=dot(v-uP,uN);vec3 pos=vec3(v,0.);float th=0.;
if(uMode<.5&&d>0.){float PR=3.14159265*uR;vec2 base=v-uN*d;
if(d<PR){th=d/uR;pos=vec3(base+uN*uR*sin(th),uR*(1.-cos(th)));}
else{th=3.14159265;pos=vec3(base-uN*(d-PR),2.*uR);}}
vUv=a/uS;vD=d;vTh=th;vec2 c=uS*.5;pos.xy=c+(pos.xy-c)*(1.+pos.z/3400.);
gl_Position=vec4(pos.x/uS.x*2.-1.,1.-pos.y/uS.y*2.,uMode>.5?.5:-pos.z/400.,1.);}
"""
        internal val FS = """
precision mediump float; uniform sampler2D uT; uniform vec3 uPaper;
uniform float uR,uMode,uNight,uGrid,uRimWidth;uniform vec2 uS; varying mediump vec2 vUv; varying mediump float vD,vTh;
void main(){if(uGrid>.5){gl_FragColor=vec4(.8,.25,.1,1.);return;}
vec3 t=texture2D(uT,vUv).rgb;float K=1.+uNight*${PaperLight.NIGHT};vec2 q=mod(floor(vUv*uS),16.);float grain=(mod(q.x*13.+q.y*29.+q.x*q.y*3.,16.)/15.*2.-1.)*${PaperLight.GRAIN};
if(uMode>.5){float e=vD-uR*${PaperLight.SHADOW_START};float s=${PaperLight.SHADOW}*K*exp(-max(e,0.)/(uR*${PaperLight.SHADOW_RADIUS}+${PaperLight.SHADOW_SOFT}));
if(e<0.)s*=smoothstep(-uR*${PaperLight.SHADOW_FADE},0.,e);gl_FragColor=vec4(t*(1.-min(s,${PaperLight.SHADOW_MAX}))+grain,1.);return;}
float th=vTh;float crease=1.-${PaperLight.AO}*K*exp(-abs(vD)/(uR*${PaperLight.AO_RADIUS}+${PaperLight.AO_SOFT}));float sh;vec3 base;
if(th<1.5708){sh=1.-${PaperLight.FACE}*(1.-cos(th));base=t;sh+=${PaperLight.HIGHLIGHT}*(1.-uNight*${PaperLight.HIGHLIGHT_NIGHT})*exp(-pow((th-${PaperLight.HIGHLIGHT_ANGLE})/${PaperLight.HIGHLIGHT_WIDTH},2.));}
else{sh=${PaperLight.VERSO_BASE}+${PaperLight.VERSO_RANGE}*(-cos(th));vec3 g=vec3(dot(uPaper,vec3(1./3.)));base=mix(mix(uPaper,g,${PaperLight.DESATURATION})*(${PaperLight.VERSO_TINT}+uNight*${PaperLight.VERSO_NIGHT})+uNight*${PaperLight.VERSO_NIGHT_LIFT},t,${PaperLight.PRINT});}
float rim=0.;if(uNight>.5&&vD>0.){float edge=uR*(1.-sin(th));rim=${PaperLight.RIM_LIGHT}*clamp(1.-edge/uRimWidth,0.,1.);}
if(vD<=0.)sh=1.;gl_FragColor=vec4(base*sh*crease+grain+rim,1.);}
"""
    }
}
