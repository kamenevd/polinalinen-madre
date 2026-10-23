package com.polinalinen.madre.ui.razvorot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.*
import com.polinalinen.madre.shelf.*
import com.polinalinen.madre.sourdough.GrowthPhase
import kotlinx.coroutines.runBlocking

/** Explicit debug-only activity: no launcher entry, no production route or voice API. */
class RazvorotSceneActivity : ComponentActivity() {
    var scene by mutableStateOf("home-dawn")
    var captureWindow:android.view.Window?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scene=intent.getStringExtra("scene")?:"home-dawn"
        val recipes=runBlocking{RecipeRepository(this@RazvorotSceneActivity).getRecipes()}
        setContent { key(scene) { RazvorotDebugScene(scene,recipes) } }
    }
}

internal val RazvorotScenes=listOf("reader-title-dawn","reader-title-night","reader-text-16","reader-text-22","curl-top","curl-mid","curl-bottom","curl-back","reader-chrome","reading-sheet","home-dawn","home-night","spine","bake","alice-listening","alice-reading","alice-confirm","alice-night","starter-growing","starter-peak","starter-hungry","shelf","shelf-night","shelf-large","curl-mid-night","gles2-curl-mid","bake-start","bake-end","fulltimer-start","fulltimer-end","timer-weight-start","timer-weight-end")

@Composable internal fun RazvorotDebugScene(scene:String,recipes:List<Recipe>) {
    val context=LocalContext.current
    val preferences=remember(scene){
        context.getSharedPreferences("razvorot_fixture",0).edit().clear().commit()
        ReadingPreferences(context,"razvorot_fixture").apply{setSize(if(scene.endsWith("22"))22f else 16f)}
    }
    val debug=remember{PaperDebugState()}
    val night=scene.endsWith("night")
    val recipe=recipes.firstOrNull()?:return
    val density=LocalDensity.current
    CompositionLocalProvider(LocalPaperDebug provides debug,com.polinalinen.madre.ui.theme.LocalCalmMode provides false,LocalDensity provides Density(density.density,if(scene=="shelf-large")1.3f else density.fontScale)) {
        RazvorotTheme(preferences,force=if(night)Atmosphere.NIGHT else Atmosphere.DAWN) {
            Box(Modifier.fillMaxSize().background(LocalPaper.current.bg).safeDrawingPadding().testTag("scene-$scene").semantics{stateDescription="${debug.renderer}:${debug.renderedFrames}:${debug.cachedPages}:${debug.reason}"}) {
                when {
                    scene=="font-compat" -> FontCompatibilityPage()
                    scene=="catalog" -> Column {Box(Modifier.weight(1f)){ChapterCatalog(recipes,{},{},{})};SpineBar(0){}}
                    scene.startsWith("reader-title") -> TitleLeaf(if(scene.contains("last"))recipes.last() else recipe,if(scene.contains("last"))recipes.lastIndex else 0,recipes.size)
                    scene.startsWith("reader-text") -> DebugTextPage(recipe,preferences)
                    scene.startsWith("curl-") || scene.startsWith("gles2-curl-") -> DebugCurl(recipe,scene)
                    scene=="reader-chrome" -> RazvorotReader(recipes,recipe.id,preferences,{},{},{})
                    scene=="reading-sheet" -> { DebugTextPage(recipe,preferences);ReadingSettings(preferences,onWindow={(context as? RazvorotSceneActivity)?.captureWindow=it}){} }
                    scene.startsWith("home-") -> Column {
                        var catalog by remember{mutableStateOf(false)}
                        Box(Modifier.weight(1f)) { if(catalog)ChapterCatalog(recipes,{},{catalog=false},{}) else CoverPage(recipes,{catalog=true},{},{}) }
                        SpineBar(0){}
                    }
                    scene=="spine" -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){SpineBar(2){}}
                    scene=="bake" -> BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),1661L,{},{},{},{})
                    scene=="bake-start" || scene=="bake-end" -> BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),if(scene.endsWith("start"))recipe.timeline[9].durationMinutes*60L else 0L,{},{},{},{})
                    scene.startsWith("fulltimer-") -> FullTimer(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),if(scene.endsWith("start"))recipe.timeline[9].durationMinutes*60L else 0L){}
                    scene.startsWith("timer-weight-") -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {TimerNumber("27:41",if(scene.endsWith("start"))0f else 1f,72.sp,(-3).sp,LocalPaper.current.ink)}
                    scene.startsWith("alice-") -> {
                        BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),1661L,{},{},{},{})
                        DebugAliceSheet(scene,recipe)
                    }
                    scene.startsWith("starter-") -> {
                        val phase=when(scene){"starter-growing"->GrowthPhase.GROWING;"starter-peak"->GrowthPhase.PEAK;else->GrowthPhase.HUNGRY}
                        val hours=when(phase){GrowthPhase.GROWING->3f;GrowthPhase.PEAK->6f;else->28f}
                        Column {Box(Modifier.weight(1f)){StarterPaper("Мадре",42,phase,hours,50,{},{},{},24)};SpineBar(2){}}
                    }
                    scene.startsWith("shelf") -> Column {
                        Box(Modifier.weight(1f)){ShelfPaper(listOf(ShelfMember("fixture-me",if(scene=="shelf-large")"Александра Константиновна" else "Пекарь",true),ShelfMember("fixture-family","Семья",false)),listOf(ShelfLedgerRow(1L,"Пекарь",recipe.name,"fixture-me","23 октября")),"Демонстрационная полка",emptyList(),false,{},{})}

                        SpineBar(3){}
                    }
                    scene=="paper" -> PaperDebugControls(preferences,recipes,debug)
                }
            }
        }
    }
}
@Composable private fun DebugTextPage(recipe:Recipe,preferences:ReadingPreferences) {
    Column(Modifier.fillMaxSize().padding(top=22.dp)) {
        SmallLabel("ГЛАВА 01 · ${recipe.name.uppercase()}",Modifier.padding(start=26.dp,end=26.dp,bottom=22.dp))
        recipe.timeline.drop(1).take(if(preferences.textSize>20f)2 else 4).forEachIndexed { i,step ->
            LeafBlock("%02d · %s · %d мин".format(i+2,step.title,step.durationMinutes),RecipeScaler.scaledStepText(step.description,1.0,RecipeScaler.scalableBindings(recipe)),preferences,recipe)
        }
        Spacer(Modifier.weight(1f))
        PageFooter(3,3,5) // explicit fixture position; runtime uses actual leaves
    }
}
@Composable private fun DebugCurl(recipe:Recipe,scene:String) {
    val context=LocalContext.current;val paper=LocalPaper.current;val night=paper==NightPaper
    val debug=LocalPaperDebug.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Fold coordinates are actual viewport dp, like PaperTurn, not a stretched
        // 360x740 fixture. Textures use the same <=2x resolution cap as runtime.
        val w=maxWidth.value;val h=maxHeight.value
        val textureDensity=LocalDensity.current.density.coerceAtMost(2f)
        val front=remember(recipe,paper,w,h,textureDensity){PageRenderer(context).text(recipe,0,(w*textureDensity).toInt(),(h*textureDensity).toInt(),paper,viewportWidth=w)}
        val under=remember(recipe,paper,w,h,textureDensity){PageRenderer(context).text(recipe,0,(w*textureDensity).toInt(),(h*textureDensity).toInt(),paper,startStep=5,viewportWidth=w)}
        val texture=remember(front){PaperTexture(front,paper.bg.toArgb(),night)}
        DisposableEffect(texture){onDispose{texture.close();front.recycle();under.recycle()}}
        val grip=when(scene){"curl-top"->h*.027f;"curl-bottom"->h*.973f;else->h*.5f}
        val back=scene=="curl-back"
        val fold=PaperFold.fromFinger(w,h,if(back)-1 else 1,if(back)w/12f else w*35f/36f,grip,if(back)w*5f/12f else w/6f,grip)
        if(scene.startsWith("gles2-")) {
            val frame=remember(fold,front,under){PaperGlFrame(fold,front,under,paper.bg.toArgb(),night)}
            AndroidView(factory={PaperGlView(it,failure={reason->debug.renderer="SLIDE";debug.reason=reason},ready={debug.renderer="GLES2"},presented={debug.renderedFrames++})},modifier=Modifier.fillMaxSize(),update={it.submit(frame)})
        } else Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                val native=c.nativeCanvas;native.save();native.scale(size.width/front.width,size.height/front.height)
                texture.draw(native,fold,under);native.restore()
            }
        }
    }
}
/** All text below is visibly a fixture; no VoiceCoordinator, API or commands. */
@Composable private fun DebugAliceSheet(scene:String,recipe:Recipe) {
    val colors=NightPaper.copy(bg=Color(0xFF0D0A09))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.55f))) {
        CompositionLocalProvider(LocalPaper provides colors,LocalContentColor provides colors.ink) {
        MaterialTheme(colorScheme=paperScheme(colors)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg,androidx.compose.foundation.shape.RoundedCornerShape(topStart=28.dp,topEnd=28.dp)).padding(22.dp)) {
            SmallLabel("ИЗОЛИРОВАННАЯ DEBUG-СЦЕНА · БЕЗ СВЯЗИ",color=colors.ink2)
            Spacer(Modifier.height(100.dp))
            BookText(when(scene){"alice-listening"->"Алиса слушает";"alice-confirm","alice-night"->"Ответьте «да» или «нет»";else->"Алиса читает"},24,color=colors.acc)
            BookText(if(scene=="alice-confirm"||scene=="alice-night")"Поставить выпечку на паузу?" else "Повтори шаг",28,Modifier.padding(vertical=18.dp),color=colors.ink)
            BookText(RecipeScaler.scaledStepText(recipe.timeline[9].description,2.0,RecipeScaler.scalableBindings(recipe)),18,color=colors.ink)
            if(scene=="alice-confirm"||scene=="alice-night")Row(Modifier.padding(vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){PaperButton("Да",{},Modifier.weight(1f),true);PaperButton("Нет",{},Modifier.weight(1f))}
            SmallLabel("Фото, дневник и полка — только на телефоне",Modifier.padding(top=18.dp),colors.ink2)
        }
        }
        }
    }
}
@Composable private fun PaperDebugControls(preferences:ReadingPreferences,recipes:List<Recipe>,debug:PaperDebugState) {
    var revision by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        BookText("Бумага",36)
        SmallLabel("${debug.renderer} · ${debug.renderedFrames} КАДРОВ · FPS UI %.1f · ПЕРЕВОРОТ %.0f МС".format(debug.fps,debug.duration))
        Text("Показать сетку и оси");Switch(debug.grid,{debug.grid=it})
        @Composable fun parameter(label:String,value:Float,range:ClosedFloatingPointRange<Float>,change:(Float)->Unit) {
            Text("$label · %.1f".format(value));Slider(value,{change(it);revision++},valueRange=range)
        }
        key(revision) {
            parameter("Радиус максимум",Curl.R_MAX,60f..100f){Curl.R_MAX=it}
            parameter("Радиус минимум",Curl.R_MIN,20f..55f){Curl.R_MIN=it}
            parameter("Отставание мс",Curl.LAG_MS,10f..80f){Curl.LAG_MS=it}
            parameter("Пружина довести",Curl.K_DONE,20f..80f){Curl.K_DONE=it}
            parameter("Пружина смах",Curl.K_FAST,40f..100f){Curl.K_FAST=it}
            parameter("Пружина вернуть",Curl.K_CANCEL,60f..130f){Curl.K_CANCEL=it}
            parameter("Затухание довести",Curl.Z_DONE,.7f..1.2f){Curl.Z_DONE=it}
            parameter("Затухание вернуть",Curl.Z_CANCEL,.6f..1.2f){Curl.Z_CANCEL=it}
        }
        Box(Modifier.fillMaxWidth().height(500.dp)){RazvorotReader(recipes,recipes.first().id,preferences,{},{},{})}
    }
}
