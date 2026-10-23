package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.*
import com.polinalinen.madre.R
import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.screens.Home

fun recipePhoto(id: String): Int = when (id) {
    "home_bread" -> R.drawable.f_home_bread
    "family_bread" -> R.drawable.f_family_bread
    "pirozhki" -> R.drawable.f_pirozhki
    "belyashi" -> R.drawable.f_belyashi
    "ciabatta" -> R.drawable.f_ciabatta
    "focaccia" -> R.drawable.f_focaccia
    "pizza" -> R.drawable.f_pizza
    "waffles" -> R.drawable.f_waffles
    "pancakes" -> R.drawable.f_pancakes
    "cinnamon_buns" -> R.drawable.f_cinnamon_buns
    else -> R.drawable.f_garlic_buns
}
fun Recipe.totalTime(): String { val m = timeline.sumOf { it.durationMinutes }; return if (m < 60) "$m мин" else "${m / 60} ч${if (m % 60 > 0) " ${m % 60} мин" else ""}" }
internal val BookRibbonHeight=44.dp
@Composable fun Ribbon(modifier: Modifier = Modifier) {
    val color = LocalPaper.current.ribbon
    Canvas(modifier.width(18.dp).height(BookRibbonHeight)) { drawPath(Path().apply { moveTo(0f,0f); lineTo(size.width,0f); lineTo(size.width,size.height); lineTo(size.width/2,size.height*.8f); lineTo(0f,size.height); close() }, color) }
}
@Composable fun BookHeader(title: String, onBack: (() -> Unit)? = null, onAa: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) PaperIconButton(onBack, label = "Назад") { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
        SmallLabel(title, Modifier.weight(1f))
        if(onAa!=null)PaperTextButton(onAa, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("reading-settings"), label = "Как читать") { BookText("Aa", 21) }
    }
}
@Composable fun BookCover(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(350.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(x = 9.dp,y = 10.dp).size(204.dp,284.dp).rotate(-7f).background(Illustration.CoverPages, RoundedCornerShape(4.dp)))
        Box(Modifier.size(210.dp,300.dp).rotate(-7f).shadow(24.dp,RoundedCornerShape(5.dp)).background(Illustration.CoverLeather, RoundedCornerShape(5.dp)).then(bookAction("Открыть книгу",onClick=onOpen)).pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if(drag < -8f) onOpen() } }.testTag("book-cover")) {
            Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp)); BookText("МАДРЕ", 40, color = Illustration.CoverLettering)
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(146.dp,80.dp)) {
                    val loaf = Path().apply { moveTo(0f,size.height); lineTo(0f,size.height*.5f); cubicTo(0f,-size.height*.3f,size.width,-size.height*.3f,size.width,size.height*.5f); lineTo(size.width,size.height); close() }
                    drawPath(loaf, Brush.verticalGradient(listOf(Illustration.LoafCrust,Illustration.LoafFlour)))
                    drawLine(Illustration.LoafScore, Offset(size.width*.32f,size.height*.45f),Offset(size.width*.7f,size.height*.3f),4f)
                }
                Spacer(Modifier.height(24.dp)); BookText("книга рецептов\nна закваске", 12, italic = true, color = Illustration.CoverLettering)
                Spacer(Modifier.height(12.dp)); SmallLabel("11 ГЛАВ · ОДНА СЕМЬЯ", color = Illustration.CoverMuted)
            }
            Ribbon(Modifier.align(Alignment.BottomEnd).offset(x = (-32).dp,y = 42.dp))
        }
    }
}
@Composable fun CoverPage(recipes: List<Recipe>, onOpen: () -> Unit, onSettings: () -> Unit, onAa: () -> Unit, continuation: @Composable () -> Unit = {}) {
    val p = LocalPaper.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        BookHeader("${LocalAtmosphere.current.label} · атмосфера по часам", onAa = onAa)
        BookText(when(LocalAtmosphere.current) { Atmosphere.DAWN -> "Доброе утро,"; Atmosphere.DAY -> "Добрый день,"; Atmosphere.DUSK -> "Добрый вечер,"; else -> "Доброй ночи," },46,weight=FontWeight.Light,lineHeight=46.sp)
        BookText("пекарь",46,italic=true,color=p.acc,weight=FontWeight.Light,lineHeight=46.sp)
        BookCover(onOpen)
        SmallLabel("ПОТЯНИТЕ ОБЛОЖКУ ВЛЕВО — КНИГА ОТКРОЕТСЯ", Modifier.align(Alignment.CenterHorizontally))
        continuation()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PaperTextButton(onOpen, label = "Открыть оглавление") { Text("ОГЛАВЛЕНИЕ · ${recipes.size} глав", color = p.ink,style = PaperType.caption) }
            PaperIconButton(onSettings, label = "Настройки") { Icon(Icons.Outlined.Settings,"Настройки") }
        }
        Spacer(Modifier.height(20.dp))
    }
}
@Composable fun ChapterCatalog(recipes: List<Recipe>, onOpen: (String) -> Unit, onBack: () -> Unit, onAa: () -> Unit) {
    var grid by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        BookHeader("ОГЛАВЛЕНИЕ", onBack, onAa)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if(grid)BookText("${recipes.size} глав",38,Modifier.weight(1f))
            else Row(Modifier.weight(1f),verticalAlignment=Alignment.Bottom) {BookText("%02d".format(selected+1),78,weight=FontWeight.ExtraLight);BookText("/%02d".format(recipes.size),26,Modifier.padding(bottom=8.dp),color=LocalPaper.current.mut,weight=FontWeight.ExtraLight)}
            PaperIconButton({ grid = !grid }, label = if(grid) "Веер глав" else "Вся книга") { Icon(if(grid) Icons.Outlined.ViewCarousel else Icons.Outlined.GridView, if(grid) "Веер глав" else "Вся книга") }
        }
        if(grid) LazyVerticalGrid(GridCells.Fixed(3),Modifier.testTag(Home.LIST_TAG),horizontalArrangement = Arrangement.spacedBy(10.dp),verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom=24.dp)) {
            itemsIndexed(recipes) { i,r -> Column(Modifier.clip(RoundedCornerShape(10.dp)).then(bookAction("Открыть главу: ${r.name}"){onOpen(r.id)}).testTag(Home.chapterRowTag(r.id))) {
                Image(painterResource(recipePhoto(r.id)),r.name,Modifier.fillMaxWidth().aspectRatio(.95f),contentScale=ContentScale.Crop)
                BookText("%02d".format(i+1)+" "+r.name,13, Modifier.padding(top=5.dp)); SmallLabel(r.totalTime())
            } }
        } else {
            Box(Modifier.fillMaxWidth().height(380.dp).testTag("chapter-fan").semantics {
                customActions=listOf(CustomAccessibilityAction("Предыдущая глава"){if(selected>0){selected--;true}else false},CustomAccessibilityAction("Следующая глава"){if(selected<recipes.lastIndex){selected++;true}else false})
            }.pointerInput(recipes.size,selected) {
                var distance=0f
                detectHorizontalDragGestures(onDragStart={distance=0f}, onDragEnd={ selected=(selected+if(distance<0)1 else -1).coerceIn(0,(recipes.size-1).coerceAtLeast(0)) }) { change,dx -> change.consume();distance+=dx }
            }, contentAlignment=Alignment.Center) {
                for(offset in listOf(2,1,0)) {
                    val index=(selected+offset).coerceAtMost(recipes.lastIndex)
                    if(index>=0) { val r=recipes[index]
                        Column(Modifier.offset(x=(offset*18).dp,y=(offset*4).dp).rotate(offset*7f-3f).width(232.dp).height(332.dp).shadow(12.dp,RoundedCornerShape(17.dp)).background(LocalPaper.current.card,RoundedCornerShape(17.dp)).clip(RoundedCornerShape(17.dp)).then(bookAction("Открыть главу: ${r.name}"){onOpen(r.id)}).then(if(offset==0)Modifier.testTag(Home.chapterRowTag(r.id)) else Modifier)) {
                            Box { Image(painterResource(recipePhoto(r.id)),r.name,Modifier.fillMaxWidth().height(202.dp),contentScale=ContentScale.Crop); Ribbon(Modifier.padding(start=16.dp)); BookText("%02d".format(index+1),48,Modifier.align(Alignment.TopEnd).padding(12.dp),color=Color.White,weight=FontWeight.ExtraLight) }
                            BookText(r.name,22,Modifier.padding(14.dp,12.dp,14.dp,4.dp),weight=FontWeight.Medium);SmallLabel("${r.totalTime()} · ${r.timeline.size} шагов",Modifier.padding(horizontal=14.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) { recipes.forEachIndexed { i,_ -> Box(Modifier.padding(3.dp).size(if(i==selected)16.dp else 6.dp,6.dp).background(if(i==selected)LocalPaper.current.ink else LocalPaper.current.bg2,RoundedCornerShape(3.dp))) } }
            SmallLabel("Листайте, чтобы выбрать главу",Modifier.align(Alignment.CenterHorizontally).padding(top=14.dp))
        }
    }
}
@Composable fun ReadingSettings(preferences: ReadingPreferences, onWindow:((android.view.Window?)->Unit)?=null, onClose: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val activeAtmosphere=LocalAtmosphere.current
    PaperSheet(onDismissRequest=onClose) {
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window){onWindow?.invoke(window);onDispose{onWindow?.invoke(null)}}
        Column(Modifier.padding(horizontal=22.dp).verticalScroll(rememberScrollState())) {
            BookText("Как читать",28)
            SmallLabel("АТМОСФЕРА",Modifier.padding(top=16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(Atmosphere.DAWN,Atmosphere.DAY,Atmosphere.DUSK,Atmosphere.NIGHT).forEach { v ->
                    val colors=paperAt(v)
                    Column(Modifier.weight(1f).background(colors.bg,RoundedCornerShape(18.dp)).border(1.dp,if(preferences.atmosphere==v)LocalPaper.current.acc else LocalPaper.current.line,RoundedCornerShape(18.dp)).then(bookAction("Атмосфера: ${v.label}"){preferences.chooseAtmosphere(v)}).padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        BookText("Aa",24,color=colors.ink)
                        Text(v.label,style = PaperType.caption,fontSize=11.sp,color=colors.ink2)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {Text("По часам",Modifier.weight(1f),style = PaperType.caption);PaperSwitch(preferences.atmosphere==Atmosphere.AUTO,{preferences.chooseAtmosphere(if(it)Atmosphere.AUTO else activeAtmosphere)})}
            SmallLabel("РАЗМЕР ТЕКСТА · ${preferences.textSize.toInt()}",Modifier.padding(top=14.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {BookText("A",14);PaperSlider(preferences.textSize,preferences::setSize,valueRange=14f..22f,steps=7,modifier=Modifier.weight(1f).testTag("text-size"));BookText("A",24)}
            SmallLabel("НА СКОЛЬКО ПЕЧЁМ · ×${preferences.portions.toInt()}")
            PaperSlider(preferences.portions,preferences::choosePortions,valueRange=1f..5f,steps=3,modifier=Modifier.testTag("reader-portions"))
            SmallLabel("ПЕРЕЛИСТЫВАНИЕ")
            PaperSegmented(ReadingMode.entries.map { it.label }, ReadingMode.entries.indexOf(preferences.mode),
                { preferences.chooseMode(ReadingMode.entries[it]) })
            Row(Modifier.fillMaxWidth().heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically) { Text("Меньше движения",Modifier.weight(1f));PaperSwitch(preferences.reducedMotion||com.polinalinen.madre.ui.kit.LocalCalmMode.current,preferences::setReduced) }
            Row(Modifier.fillMaxWidth().heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically) { Text("Тактильные отклики",Modifier.weight(1f));PaperSwitch(preferences.haptics,preferences::chooseHaptics) }
            SmallLabel("На слабом устройстве и при выключенной системной анимации — плоский сдвиг.")
            if(BuildConfig.DEBUG)PaperTextButton({context.startActivity(android.content.Intent().setClassName(context.packageName,"com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity").putExtra("scene","paper"))},Modifier.fillMaxWidth().heightIn(min=48.dp),label="Посмотреть бумагу"){Text("Бумага")}
            Spacer(Modifier.height(24.dp))
        }
    }
}
