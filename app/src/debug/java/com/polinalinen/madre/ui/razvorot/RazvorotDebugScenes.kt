package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
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
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.sourdough.GrowthPhase
import kotlinx.coroutines.runBlocking

/** Explicit debug-only activity: no launcher entry, no production route or voice API. */
class RazvorotSceneActivity : ComponentActivity(), ImmersiveHost {
    override val immersiveController = ImmersiveController()
    var scene by mutableStateOf("home-dawn")
    var atmosphere by mutableStateOf<Atmosphere?>(null)
    var captureWindow:android.view.Window?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersiveController.attach(window)
        scene=savedInstanceState?.getString("debug_scene") ?: intent.getStringExtra("scene") ?: "home-dawn"
        atmosphere = savedInstanceState?.getString("debug_atmosphere")?.let { value -> Atmosphere.entries.firstOrNull { it.name == value } }
        val recipes=runBlocking{RecipeRepository(this@RazvorotSceneActivity).getRecipes()}
        setContent { key(scene,atmosphere) { RazvorotDebugScene(scene,recipes,atmosphere) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("debug_scene", scene)
        outState.putString("debug_atmosphere", atmosphere?.name)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); immersiveController.reapply(window) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersiveController.reapply(window)
    }
    override fun onDestroy() { immersiveController.detach(window); super.onDestroy() }

}

internal val RazvorotScenes=listOf("reader-title-dawn","reader-title-night","reader-text-16","reader-text-22","curl-top","curl-mid","curl-bottom","curl-back","reader-chrome","reading-sheet","home-dawn","home-night","spine","bake","alice-listening","alice-reading","alice-confirm","alice-night","starter-growing","starter-peak","starter-hungry","shelf","shelf-night","shelf-large","curl-mid-night","gles2-curl-mid","bake-start","bake-end","fulltimer-start","fulltimer-end","timer-weight-start","timer-weight-end","toc","oven","chapter-title","chapter-ingr","sheet-calc","bake-more","start-later")

@Composable internal fun RazvorotDebugScene(scene:String,recipes:List<Recipe>,forceAtmosphere:Atmosphere?=null) {
    val context=LocalContext.current
    val preferences=remember(scene){
        context.getSharedPreferences("razvorot_fixture",0).edit().clear().commit()
        ReadingPreferences(context,"razvorot_fixture").apply{setSize(if(scene.endsWith("22"))22f else 16f)}
    }
    val debug=remember{PaperDebugState()}
    val night=scene.endsWith("night")
    val recipe=recipes.firstOrNull()?:return
    val density=LocalDensity.current
    CompositionLocalProvider(LocalPaperDebug provides debug,com.polinalinen.madre.ui.kit.LocalCalmMode provides false,LocalDensity provides Density(density.density,if(scene=="shelf-large")1.3f else density.fontScale)) {
        RazvorotTheme(preferences,force=forceAtmosphere ?: if(night)Atmosphere.NIGHT else Atmosphere.DAWN) {
            Immersive(enabled = (scene.startsWith("reader-") && scene != "reader-chrome") || scene.startsWith("curl-") || scene.startsWith("gles2-curl-") || scene.startsWith("fulltimer-") || scene == "bake" || scene == "reading-sheet")
            Box(Modifier.fillMaxSize().background(LocalPaper.current.bg).testTag("scene-$scene").semantics{stateDescription="${debug.renderer}:${debug.renderedFrames}:${debug.cachedPages}:${debug.reason}"}) {
                when {
                    scene=="alice-yandex-consent" -> {
                        // Actual production page, synthetic selected bake/signed-in boundary; no account/network effects.
                        val active=remember(recipe){BakingSession.start(7,recipe)}
                        AlicePage(com.polinalinen.madre.voice.VoiceUiState(
                            com.polinalinen.madre.voice.VoiceStatus.WAITING_LINK, active.bakeKey),
                            listOf(active),{},{},{},{},{},yandexSignedIn=true)
                    }
                    scene=="yandex-offer" -> {
                        // Exact production offer, synthetic SignedOut state; no SDK launch or backend.
                        com.polinalinen.madre.account.yandex.YandexOfferContent(
                            com.polinalinen.madre.account.yandex.YandexAccountState.SignedOut,false,{},{},{})
                    }
                    scene=="toc" || scene=="catalog" -> Column {
                        Box(Modifier.weight(1f).safeDrawingPadding()) { BookTocContent(recipes,emptyList(),
                            com.polinalinen.madre.navigation.BookBookmark(recipe.id,1),{_,_->},{},onAa={}) }
                        SpineBar(0){}
                    }
                    scene=="oven" -> Column {
                        val now=BakingClock.elapsed()
                        val active=remember(recipe){BakingSession(7,recipe,9,now-(recipe.timeline[9].durationMinutes*60L-1661L)*1000L,
                            BakingClock.wallClock(),scaleFactor=2.0)}
                        Box(Modifier.weight(1f).safeDrawingPadding()){BookOvenContent(recipes,listOf(active),emptyList(),{},{},{},{},{})}
                        SpineBar(1){}
                    }
                    scene.startsWith("chapter-") -> {
                        val selected=if(scene=="chapter-title")recipes.getOrNull(4)?:recipe else recipe
                        RazvorotReader(recipes,selected.id,preferences,{},{},{},initialPage=if(scene=="chapter-ingr")1 else -1,
                            scaleFactor=2.0,onScaleFactor={},onOpenAlice={})
                    }
                    scene=="sheet-calc" -> {
                        RazvorotReader(recipes,recipe.id,preferences,{},{},{},initialPage=1,scaleFactor=2.0,onOpenAlice={})
                        BookPortionsSheet(recipe,2.0,{},{})
                    }
                    scene=="bake-finale" -> {
                        // Stateless native fixture: one synthetic completed record, no Room write/auth/permissions.
                        BakeFinalePage(BakeFinalePageState(recipe.name,1,2,RecipeScale.totalMinutes(recipe,2),
                            recipe.timeline.size,1792731600000L,recipes.map{it.id==recipe.id},1),{},{},
                            photo={BakeFinalePhoto(null,emptyList(),com.polinalinen.madre.ui.photo.PhotoAttachmentActions({},{},{},{}),true,{})})
                    }
                    scene=="kit" -> KitShowcase()
                    scene=="photo-viewer" -> PaperPhotoViewer({}) { BookText("Фото · локальная тестовая поверхность", 18) }
                    scene.startsWith("alice-sheet-") -> {
                        val mode = runCatching { AliceSheetMode.valueOf(scene.removePrefix("alice-sheet-").uppercase()) }.getOrDefault(AliceSheetMode.UNLINKED)
                        CompositionLocalProvider(LocalPaper provides NightPaper) {
                            PaperSheet({}) { AliceSheetContent(mode, com.polinalinen.madre.voice.VoiceUiState(
                                if (mode == AliceSheetMode.OFFLINE) com.polinalinen.madre.voice.VoiceStatus.OFFLINE else com.polinalinen.madre.voice.VoiceStatus.UNLINKED),
                                recipeName = "Демонстрационная выпечка", stepText = recipe.timeline[0].description) }
                        }
                    }
                    scene=="settings" -> SettingsContent("Пекарь", {}, {}, {}, "Мадре", {}, 12, 42, 24, {}, true, {}, true, {}, "Демонстрационная семья", FamilyBookState.SignedOut, {}, {})
                    scene=="feeding" || scene=="feeding-error" -> FeedingFormScreen({ _, _, _, _, _, _ -> }, {}, if (scene.endsWith("error")) FeedingSaveState.Error("Не удалось записать кормление. Попробуйте ещё раз.") else FeedingSaveState.Idle, 50)
                    scene=="gallery-empty" -> PhotoGalleryScreen(emptyList(), {})
                    scene=="diary" -> StarterDiaryScreen(42, GrowthPhase.GROWING, profileForInterval(24),
                        emptyList(), listOf(FeedingEntity(id=1, sourdoughConfigId=1, timestampMillis=1792731600000L,
                            flourGrams=100, waterGrams=50, retainedStarterGrams=50, finalHydrationPercent=50,
                            storageLocation=StorageLocation.KITCHEN)), {}, {}, {}, starterName="Мадре", currentYear=2026, nowMillis=1792735200000L)
                    scene=="font-compat" -> FontCompatibilityPage()

                    scene.startsWith("reader-title") -> TitleLeaf(if(scene.contains("last"))recipes.last() else recipe,if(scene.contains("last"))recipes.lastIndex else 0,recipes.size)
                    scene.startsWith("reader-text") -> DebugTextPage(recipe,preferences)
                    scene.startsWith("curl-") || scene.startsWith("gles2-curl-") -> DebugCurl(recipe,scene)
                    scene=="reader-chrome" -> RazvorotReader(recipes,recipe.id,preferences,{},{},{})
                    scene=="reading-sheet" -> { DebugTextPage(recipe,preferences);ReadingSettings(preferences,onWindow={(context as? RazvorotSceneActivity)?.captureWindow=it}){} }
                    scene.startsWith("home-") -> Column {
                        Box(Modifier.weight(1f).safeDrawingPadding()){BookTocContent(recipes,emptyList(),null,{_,_->},{})}
                        SpineBar(0){}
                    }
                    scene=="spine" -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){SpineBar(2){}}
                    scene=="bake" -> BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),1661L,{},{},{},{},onOpenAlice={})
                    scene=="start-later" -> {
                        // Explicit screenshot-only date, not a real user plan or a scheduled Worker.
                        val zone=java.time.ZoneId.systemDefault()
                        val day=java.time.LocalDate.of(2026,10,23)
                        val now=day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
                        BookOvenContent(recipes,emptyList(),emptyList(),{},{},{},{},{})
                        BookStartSheet(recipe,2,{},{},onPlan={_,_->},initialMode=1,
                            existingPlan=com.polinalinen.madre.navigation.PlannedBake(recipe.id,2,day.atTime(20,0).atZone(zone).toInstant().toEpochMilli()),nowMillis=now)
                    }
                    scene=="bake-more" -> {
                        val bake=remember(recipe){BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0)}
                        BakingPaper(bake,1661L,{},{},{},{},onOpenAlice={})
                        BookBakeMoreSheet(bake,true,{},{},{},{})
                    }
                    scene=="bake-start" || scene=="bake-end" -> BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),if(scene.endsWith("start"))recipe.timeline[9].durationMinutes*60L else 0L,{},{},{},{},onOpenAlice={})
                    scene.startsWith("fulltimer-") -> FullTimer(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),if(scene.endsWith("start"))recipe.timeline[9].durationMinutes*60L else 0L){}
                    scene.startsWith("timer-weight-") -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {TimerNumber("27:41",if(scene.endsWith("start"))0f else 1f,72.sp,(-3).sp,LocalPaper.current.ink)}
                    scene.startsWith("alice-") -> {
                        BakingPaper(BakingSession(7,recipe,9,0L,0L,scaleFactor=2.0),1661L,{},{},{},{},onOpenAlice={})
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
internal fun Atmosphere.captureName()=if(this==Atmosphere.DUSK)"evening" else name.lowercase()
internal fun debugCurlFold(w:Float,h:Float,scene:String):PaperFold {
    val pose=scene.removePrefix("gles2-").removeSuffix("-night")
    val grip=when(pose){"curl-top"->h*.027f;"curl-bottom"->h*.973f;else->h*.5f}
    val back=pose=="curl-back"
    return PaperFold.fromFinger(w,h,if(back)-1 else 1,if(back)w/12f else w*35f/36f,grip,if(back)w*5f/12f else w/6f,grip)
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
        val texture=remember(front,textureDensity){PaperTexture(front,paper.bg.toArgb(),night,density=textureDensity)}
        DisposableEffect(texture){onDispose{texture.close();front.recycle();under.recycle()}}
        val fold=debugCurlFold(w,h,scene)
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
        Text("Показать сетку и оси");PaperSwitch(debug.grid,{debug.grid=it})
        @Composable fun parameter(label:String,value:Float,range:ClosedFloatingPointRange<Float>,change:(Float)->Unit) {
            Text("$label · %.1f".format(value));PaperSlider(value,{change(it);revision++},valueRange=range)
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
