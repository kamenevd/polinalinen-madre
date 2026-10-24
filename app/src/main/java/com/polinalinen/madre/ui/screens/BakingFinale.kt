@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.polinalinen.madre.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.shelf.ShelfSharePolicy
import com.polinalinen.madre.ui.components.TracingPaper
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.photo.PhotoAttachmentActions
import com.polinalinen.madre.ui.photo.rememberPhotoAttachmentActions
import com.polinalinen.madre.ui.razvorot.BookBakeSessionChips
import com.polinalinen.madre.ui.razvorot.BookCatalog
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.viewmodel.BakingViewModel
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/** FINALE is content of bake/{sessionId}, not another route or a second completion writer. */
@Composable fun BakingFinale(sessionId:Long?,onHome:()->Unit,viewModel:BakingViewModel=viewModel(),
    onBack:(()->Unit)?=null,recentPhotos:List<GalleryPhoto> = emptyList(),familyName:String?=null,
    onSwitchSession:(Long)->Unit={}) {
    Immersive()
    val sessions by viewModel.sessions.collectAsState()
    val session=sessions.firstOrNull{it.id==sessionId}
    val completions by viewModel.completions.collectAsState()
    val completion=sessionId?.let{completions[it]}
    val recipes by viewModel.recipes.collectAsState()
    val recipe=session?.recipe?:recipes.firstOrNull{it.id==completion?.recipeId}
    val photoPaths by viewModel.bakePhotoPaths.collectAsState()
    val photoPath=sessionId?.let{photoPaths[it]}
    val sharingAvailable by viewModel.sharingAvailable.collectAsState()
    val finishing by viewModel.finishing.collectAsState()
    val counts by viewModel.bakeCounts.collectAsState()
    val busy=sessionId!=null && sessionId in finishing
    val selection=rememberFinaleShareSelection(sessionId,sharingAvailable,photoPath!=null)
    val decision=selection.decision
    fun back() {
        if(busy)return
        if(onBack!=null)onBack()
        else if(sessionId!=null)viewModel.finish(sessionId,ShelfShareDecision.KEEP,onHome)
        else onHome()
    }
    BackHandler{back()}
    LaunchedEffect(sessionId){viewModel.restoreCompletion(sessionId)}
    val photos=rememberPhotoAttachmentActions(PhotoStore.PhotoKind.BAKE,sessionId?:0L) { path->
        sessionId?.let{viewModel.stageBakePhoto(it,path)}
    }
    val portions=session?.trustedPortions?:completion?.portions?.takeIf{it>0}
    val state=BakeFinalePageState(
        completion?.recipeName?:session?.recipe?.name?:"Испечено",
        recipes.indexOfFirst{it.id==recipe?.id}.takeIf{it>=0}?.plus(1),portions,
        recipe?.let{r->portions?.let{RecipeScale.totalMinutes(r,it)}},recipe?.timeline?.size,
        completion?.completedAtMillis?:session?.completedAt,
        recipes.map{(counts[it.id]?:0)>0},counts.values.sum(),
    )
    val note=TracingPaper.noteFor(state.totalBakes)
    BakeFinalePage(state,::back,{
        if(!busy && !(ShelfSharePolicy.wantsPhoto(decision) && photoPath==null)) {
            if(sessionId==null)onHome() else viewModel.finish(sessionId,decision,onHome)
        }
    },enabled=!busy && !(ShelfSharePolicy.wantsPhoto(decision) && photoPath==null),
        finishLabel=if(onBack!=null)"Вписать в книгу" else "На главную",
        animate=session?.isCompleted==true,reducedMotion=LocalCalmMode.current,
        sessions={if(sessionId!=null)BookBakeSessionChips(sessions.filter{!it.isCompleted || it.id==sessionId},sessionId,onSwitchSession)},
        photo={BakeFinalePhoto(photoPath,recentPhotos,photos,!busy){sessionId?.let{viewModel.unstageBakePhoto(it)}}},
        extra={
            if(session!=null && session.trustedPortions==null)UnknownPortionsConfirmation(session.id){viewModel.confirmPortions(session.id,it)}
            if(note!=null)PaperNote(note)
            if(sharingAvailable && (session!=null || completion!=null)) {
                SmallLabel(familyName?.takeIf{it.isNotBlank()}?.let{"На полку «$it»"}?:"На полку")
                BakeFinaleShareChoices(decision,photoPath!=null,busy,selection.choose)
                if(photoPath==null)PaperNote("Добавьте фото, чтобы показать кадр.")
            }
        })
}

internal data class BakeFinalePageState(val name:String,val chapter:Int?,val portions:Int?,val minutes:Int?,
    val steps:Int?,val completedAtMillis:Long?,val chapters:List<Boolean>,val totalBakes:Int)

/** This same stateless renderer is captured by the native CI fixture; it has no storage or auth effects. */
@Composable internal fun BakeFinalePage(state:BakeFinalePageState,onBack:()->Unit,onFinish:()->Unit,
    enabled:Boolean=true,finishLabel:String="Вписать в книгу",animate:Boolean=false,
    reducedMotion:Boolean=false,
    sessions:@Composable ()->Unit={},photo:@Composable ()->Unit,
    extra:@Composable ColumnScope.()->Unit={}) {
    val p=LocalPaper.current
    var entered by remember{mutableStateOf(!animate)}
    var sealEntered by remember{mutableStateOf(!animate)}
    LaunchedEffect(animate){entered=true;if(animate && !reducedMotion)delay(550);sealEntered=true}
    val reveal by animateFloatAsState(if(entered)1f else .65f,tween(if(!animate)0 else if(reducedMotion)200 else 900),label="finale-word")
    val stamp by animateFloatAsState(if(sealEntered)1f else 0f,tween(if(!animate)0 else if(reducedMotion)200 else 700),label="finale-seal")
    // Ornament remains within the page at large fontScale; all informative text scales normally.
    val displayScale=LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.fillMaxSize().paperBackground()
        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                PaperIconButton(onBack,label="Назад"){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null)}
                Box(Modifier.weight(1f)){sessions()}
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                val metadata=listOfNotNull(state.chapter?.let{"ГЛАВА %02d".format(it)},
                    state.portions?.let{"×$it СЕМЬИ"},state.minutes?.let{BookCatalog.duration(it)})
                if(metadata.isNotEmpty())SmallLabel(metadata.joinToString(" · "),color=p.acc)
                val titleStyle=PaperType.finale.copy(fontSize=(92f/displayScale).sp,lineHeight=(78f/displayScale).sp,letterSpacing=(-4f/displayScale).sp)
                val heading:@Composable ()->Unit={Text("Испе-\nчено.",style=titleStyle,
                    color=p.ink,modifier=Modifier.graphicsLayer{alpha=reveal;translationY=if(reducedMotion)0f else (1-reveal)*24.dp.toPx()}
                        .blur((if(reducedMotion)0f else (1-reveal)*5).dp).clearAndSetSemantics{contentDescription="Испечено"})}
                val seal:@Composable ()->Unit={state.completedAtMillis?.let{PaperBakeSeal(it,
                    Modifier.graphicsLayer{alpha=stamp;scaleX=if(reducedMotion)1f else 1f+(1f-stamp)*1.2f;scaleY=scaleX})}}
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val measurer=rememberTextMeasurer()
                    val density=LocalDensity.current
                    val room=with(density){(maxWidth-104.dp).roundToPx()}
                    val side=state.completedAtMillis!=null && measurer.measure("Испе-",titleStyle).size.width<=room
                    if(side)Row(verticalAlignment=Alignment.Bottom) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            heading();Text(state.name,style=PaperType.h2,color=p.ink)
                        }
                        seal()
                    } else Column {
                        heading()
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(state.name,style=PaperType.h2,color=p.ink,modifier=Modifier.weight(1f));seal()
                        }
                    }
                }
                photo()
                HairRule()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        val hours=state.minutes?.let{NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply{maximumFractionDigits=1}.format(it/60.0)}?:"—"
                        FinaleStat(hours,"часов по рецепту",Modifier.weight(1f))
                        FinaleStat(state.steps?.toString()?:"—","шагов в рецепте",Modifier.weight(1f))
                        FinaleStat(state.totalBakes.toString(),"всего в книге",Modifier.weight(1f))
                    }
                HairRule()
                extra()
                Spacer(Modifier.height(12.dp))
            }
            PaperButton(finishLabel,onFinish,Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=12.dp),
                enabled=enabled,variant=PaperButtonVariant.ACCENT,icon=Icons.Outlined.MenuBook)
        }
    }
}

@Composable private fun FinaleStat(value:String,label:String,modifier:Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value,style=PaperType.h2.copy(fontSize=26.sp,fontWeight=FontWeight.Medium),color=LocalPaper.current.ink)
        Text(label,style=PaperType.caption,color=LocalPaper.current.ink2)
    }
}

@Composable internal fun BakeFinalePhoto(path:String?,recent:List<GalleryPhoto>,actions:PhotoAttachmentActions,
    enabled:Boolean,onRemove:()->Unit) {
    val p=LocalPaper.current
    val context=LocalContext.current
    val ownedRecent=remember(recent){recent.filter{photo->runCatching {
        val file=PhotoStore.resolve(context,photo.path)
        file.isFile && file.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())
    }.getOrDefault(false)}.distinctBy{it.path}.take(4)}
    val shape=RoundedCornerShape(20.dp)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
    if(path!=null) {
        Box(Modifier.fillMaxWidth().height(230.dp).clip(shape)) {
            AsyncImage(PhotoStore.resolve(context,path),"Фото этой выпечки",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.glass).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PaperButton("Оформить",{actions.recent(path)},Modifier.weight(1f),enabled=enabled,variant=PaperButtonVariant.SECONDARY)
                PaperButton("Переснять",actions.camera,Modifier.weight(1f),enabled=enabled,variant=PaperButtonVariant.SECONDARY)
            }
        }
        PaperTextAction("Убрать фото",onRemove,enabled=enabled)
    } else {
        Column(Modifier.fillMaxWidth().heightIn(min=230.dp).clip(shape).background(p.bg2).drawBehind {
            val inset=.5.dp.toPx()
            drawRoundRect(p.line,topLeft=androidx.compose.ui.geometry.Offset(inset,inset),
                size=androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset),cornerRadius=CornerRadius(20.dp.toPx()-inset),
                style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),5.dp.toPx()))))
        }.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
            PaperButton("Снять",actions.camera,Modifier.width(144.dp),enabled=enabled,variant=PaperButtonVariant.ACCENT,icon=Icons.Outlined.PhotoCamera)
            if(ownedRecent.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ownedRecent.forEach { item->
                    AsyncImage(ImageRequest.Builder(context).data(PhotoStore.resolve(context,item.path)).size(120).build(),
                        "Недавнее фото: ${item.caption}",Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(enabled=enabled,onClickLabel="Вклеить недавнее фото"){actions.recent(item.path)},contentScale=ContentScale.Crop)
                }
            }
            PaperTextAction("ГАЛЕРЕЯ",actions.gallery,enabled=enabled)
        }
    }
    }
}

internal class FinaleShareSelection(val decision:ShelfShareDecision,val choose:(ShelfShareDecision)->Unit)

/** One saved explicit choice shared by production and fixtures. No photo update can publish anything. */
@Composable internal fun rememberFinaleShareSelection(key:Any?,sharingAvailable:Boolean,photoAvailable:Boolean):FinaleShareSelection {
    var explicit by rememberSaveable(key){mutableStateOf<ShelfShareDecision?>(null)}
    val requested=explicit ?: if(photoAvailable)ShelfShareDecision.PUT_WITH_PHOTO else ShelfShareDecision.PUT_WITHOUT_PHOTO
    val effective=if(!sharingAvailable)ShelfShareDecision.KEEP
        else if(!photoAvailable && requested==ShelfShareDecision.PUT_WITH_PHOTO)ShelfShareDecision.PUT_WITHOUT_PHOTO else requested
    return FinaleShareSelection(effective){explicit=it}
}

@Composable internal fun BakeFinaleShareChoices(decision:ShelfShareDecision,photoAvailable:Boolean,
    busy:Boolean,onDecision:(ShelfShareDecision)->Unit) {
    val options=listOf(ShelfShareDecision.PUT_WITH_PHOTO,ShelfShareDecision.PUT_WITHOUT_PHOTO,ShelfShareDecision.KEEP)
    PaperSegmented(listOf("С фото","Без фото","Только мне"),options.indexOf(decision),{onDecision(options[it])},
        enabled={index->!busy && (index!=0 || photoAvailable)})
    PaperNote(when(decision) {
        ShelfShareDecision.PUT_WITH_PHOTO->"Полка увидит фото и запись"
        ShelfShareDecision.PUT_WITHOUT_PHOTO->"Полка увидит запись без фото"
        ShelfShareDecision.KEEP->"Запись увидите только вы"
    })
}
