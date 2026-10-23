package com.polinalinen.madre.ui.razvorot

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** Down is inert; threshold and cancel are independent of rendering. */
data class PaperGesture(val page: Int, val count: Int, val width: Float, val dx: Float = 0f) {
    val direction: Int get() = if (dx < 0) 1 else -1
    val target: Int get() = (page + direction).coerceIn(0, (count - 1).coerceAtLeast(0))
    val progress: Float get() = if (target == page || width <= 0f) 0f else (abs(dx) / width).coerceIn(0f, 1f)
    fun release(cancelled: Boolean): Int = if (!cancelled && progress >= .34f) target else page
}
object PaperGeometry {
    fun foldX(width: Float, progress: Float) = width * (1f - progress.coerceIn(0f, 1f))
    fun radius(width: Float, progress: Float) = width * (.09f + .07f * sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat())
    fun canCurl(lowRam: Boolean, reduced: Boolean, renderer: Boolean, systemMotion: Boolean) = !lowRam && !reduced && renderer && systemMotion
}
@Composable fun PaperTurn(page: Int, count: Int, preferences: ReadingPreferences, onPage: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val context=LocalContext.current
    val lowRam=remember{(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice}
    val systemMotion=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    val hardware=androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    var rendererFailed by remember{mutableStateOf(false)}
    val curl=preferences.mode==ReadingMode.BOOK&&PaperGeometry.canCurl(lowRam,preferences.reducedMotion,hardware&&!rendererFailed,systemMotion)
    val layer=rememberGraphicsLayer()
    var texture by remember{mutableStateOf<PaperTexture?>(null)}
    var width by remember{mutableFloatStateOf(1f)}
    var drag by remember(page){mutableFloatStateOf(0f)}
    var grip by remember{mutableFloatStateOf(.5f)}
    var settling by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val gesture=PaperGesture(page,count,width,drag)
    val latestGesture by rememberUpdatedState(gesture)
    val latestPage by rememberUpdatedState(onPage)
    val latestSettling by rememberUpdatedState(settling)
    val p=LocalPaper.current
    DisposableEffect(Unit){onDispose{texture?.close()}}
    fun settle(cancelled:Boolean){
        if(latestSettling)return
        val state=latestGesture;val selected=state.release(cancelled)
        scope.launch{
            settling=true
            Animatable(drag).animateTo(if(selected==page)0f else if(drag<0f)-width else width,tween(if(preferences.reducedMotion)180 else 850)){drag=value}
            latestPage(selected);drag=0f;settling=false
            texture?.close();texture=null
        }
    }
    Box(modifier.fillMaxSize().onSizeChanged{width=it.width.toFloat()}.semantics{
        stateDescription="Страница ${page+1} из $count"
        customActions=listOf(
            CustomAccessibilityAction("Следующая страница"){if(page+1<count){latestPage(page+1);true}else false},
            CustomAccessibilityAction("Предыдущая страница"){if(page>0){latestPage(page-1);true}else false},
        )
    }.pointerInput(page,count,curl){
        detectDragGestures(onDragStart={position->
            grip=(position.y/size.height).coerceIn(0f,1f)
            if(curl&&!latestSettling)scope.launch{
                runCatching{layer.toImageBitmap().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888,false)}
                    .onSuccess{bitmap->if(bitmap!=null){texture?.close();texture=PaperTexture(bitmap,p.bg.toArgb())}else rendererFailed=true}
                    .onFailure{rendererFailed=true}
            }
        },onDragCancel={settle(true)},onDragEnd={settle(false)}){change,amount->if(!latestSettling){change.consume();drag+=amount.x}}
    }){
        if(gesture.progress>0f)Box(Modifier.fillMaxSize()){content(gesture.target)}
        Box(Modifier.fillMaxSize().drawWithContent{
            if(gesture.progress==0f){layer.record{this@drawWithContent.drawContent()};drawLayer(layer)}
            else if(curl&&texture!=null){
                // Soft contact shadow under the bent paper, with radius linked to the fold.
                val fold=PaperGeometry.foldX(size.width,gesture.progress)
                val x=if(drag>0)size.width-fold else fold
                val radius=PaperGeometry.radius(size.width,gesture.progress)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.24f),Color.Transparent),x-radius,x+radius))
                drawIntoCanvas{texture?.draw(it.nativeCanvas,PaperCylinder(size.width,size.height,gesture.progress,grip,drag>0))}
            }else translate(left=if(gesture.target==page)0f else drag){this@drawWithContent.drawContent()}
        }){content(page)}
    }
}
