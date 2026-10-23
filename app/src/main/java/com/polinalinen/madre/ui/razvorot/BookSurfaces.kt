package com.polinalinen.madre.ui.razvorot

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
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.polinalinen.madre.R
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
@Composable fun BookText(text: String, size: Int = 20, modifier: Modifier = Modifier, italic: Boolean = false, color: Color = LocalPaper.current.ink) {
    Text(text, modifier, color = color, fontFamily = Literature, fontSize = size.sp, lineHeight = (size * 1.27f).sp, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)
}
@Composable fun SmallLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalPaper.current.secondary) {
    Text(text, modifier, color = color, fontFamily = Manrope, fontSize = 11.sp, letterSpacing = 1.1.sp, lineHeight = 17.sp)
}
@Composable fun Ribbon(modifier: Modifier = Modifier) {
    val color = LocalPaper.current.ribbon
    Canvas(modifier.width(18.dp).height(44.dp)) { drawPath(Path().apply { moveTo(0f,0f); lineTo(size.width,0f); lineTo(size.width,size.height); lineTo(size.width/2,size.height*.8f); lineTo(0f,size.height); close() }, color) }
}
@Composable fun PaperButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val p = LocalPaper.current
    Button(onClick, modifier.heightIn(min = 50.dp), enabled = enabled, shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = if (primary) p.acc else p.bg2, contentColor = if (primary) Color.White else p.ink)) { Text(text, fontFamily = Manrope, fontSize = 14.sp) }
}
@Composable fun BookHeader(title: String, onBack: (() -> Unit)? = null, onAa: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
        SmallLabel(title, Modifier.weight(1f))
        TextButton(onAa, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("reading-settings")) { BookText("Aa", 21) }
    }
}
@Composable fun BookCover(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(350.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(x = 9.dp,y = 10.dp).size(204.dp,284.dp).rotate(-7f).background(Color(0xFFE6DCCD), RoundedCornerShape(4.dp)))
        Box(Modifier.size(210.dp,300.dp).rotate(-7f).shadow(24.dp,RoundedCornerShape(5.dp)).background(Color(0xFF1C1714), RoundedCornerShape(5.dp)).clickable(onClickLabel = "Открыть книгу", onClick = onOpen).pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if(drag < -8f) onOpen() } }.testTag("book-cover")) {
            Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp)); BookText("МАДРЕ", 40, color = Color(0xFFF4EADC))
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(146.dp,80.dp)) {
                    val loaf = Path().apply { moveTo(0f,size.height); lineTo(0f,size.height*.5f); cubicTo(0f,-size.height*.3f,size.width,-size.height*.3f,size.width,size.height*.5f); lineTo(size.width,size.height); close() }
                    drawPath(loaf, Brush.verticalGradient(listOf(Color(0xFFB98547),Color(0xFFE7C99D))))
                    drawLine(Color(0xFF8C5A26), Offset(size.width*.32f,size.height*.45f),Offset(size.width*.7f,size.height*.3f),4f)
                }
                Spacer(Modifier.height(24.dp)); BookText("семейная книга рецептов\nна закваске", 12, italic = true, color = Color(0xFFF4EADC))
                Spacer(Modifier.height(12.dp)); SmallLabel("11 ГЛАВ · ОДНА СЕМЬЯ", color = Color(0xFFBCA88D))
            }
            Ribbon(Modifier.align(Alignment.BottomEnd).offset(x = (-32).dp,y = 42.dp))
        }
    }
}
@Composable fun CoverPage(recipes: List<Recipe>, onOpen: () -> Unit, onSettings: () -> Unit, onAa: () -> Unit, continuation: @Composable () -> Unit = {}) {
    val p = LocalPaper.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        BookHeader("${atmosphereAt(java.time.LocalTime.now().hour).label} · атмосфера по часам", onAa = onAa)
        BookText("Добрый день,", 40)
        BookText("пекарь", 44, italic = true, color = p.acc)
        BookCover(onOpen)
        SmallLabel("ПОТЯНИТЕ ОБЛОЖКУ ВЛЕВО — КНИГА ОТКРОЕТСЯ", Modifier.align(Alignment.CenterHorizontally))
        continuation()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onOpen) { Text("ОГЛАВЛЕНИЕ · ${recipes.size} глав", color = p.ink, fontFamily = Manrope) }
            IconButton(onSettings) { Icon(Icons.Outlined.Settings,"Настройки") }
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
            BookText(if(grid) "${recipes.size} глав" else "%02d / %02d".format(selected+1,recipes.size), if(grid) 38 else 58, Modifier.weight(1f))
            IconButton({ grid = !grid }) { Icon(if(grid) Icons.Outlined.ViewCarousel else Icons.Outlined.GridView, if(grid) "Веер глав" else "Вся книга") }
        }
        if(grid) LazyVerticalGrid(GridCells.Fixed(3),Modifier.testTag(Home.LIST_TAG),horizontalArrangement = Arrangement.spacedBy(10.dp),verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom=24.dp)) {
            itemsIndexed(recipes) { i,r -> Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpen(r.id) }.testTag(Home.chapterRowTag(r.id))) {
                Image(painterResource(recipePhoto(r.id)),r.name,Modifier.fillMaxWidth().aspectRatio(.95f),contentScale=ContentScale.Crop)
                BookText("%02d".format(i+1)+" "+r.name,13, Modifier.padding(top=5.dp)); SmallLabel(r.totalTime())
            } }
        } else {
            Box(Modifier.fillMaxWidth().height(380.dp).pointerInput(recipes.size,selected) {
                var distance=0f
                detectHorizontalDragGestures(onDragStart={distance=0f}, onDragEnd={ selected=(selected+if(distance<0)1 else -1).coerceIn(0,(recipes.size-1).coerceAtLeast(0)) }) { change,dx -> change.consume();distance+=dx }
            }, contentAlignment=Alignment.Center) {
                for(offset in listOf(2,1,0)) {
                    val index=(selected+offset).coerceAtMost(recipes.lastIndex)
                    if(index>=0) { val r=recipes[index]
                        Column(Modifier.offset(x=(offset*18).dp,y=(offset*4).dp).rotate(offset*7f-3f).width(232.dp).height(332.dp).shadow(12.dp,RoundedCornerShape(17.dp)).background(LocalPaper.current.card,RoundedCornerShape(17.dp)).clip(RoundedCornerShape(17.dp)).clickable { onOpen(r.id) }.then(if(offset==0)Modifier.testTag(Home.chapterRowTag(r.id)) else Modifier)) {
                            Box { Image(painterResource(recipePhoto(r.id)),r.name,Modifier.fillMaxWidth().height(202.dp),contentScale=ContentScale.Crop); Ribbon(Modifier.padding(start=16.dp)); BookText("%02d".format(index+1),48,Modifier.align(Alignment.TopEnd).padding(12.dp),color=Color.White) }
                            BookText(r.name,22,Modifier.padding(14.dp,12.dp,14.dp,4.dp));SmallLabel("${r.totalTime()} · ${r.timeline.size} шагов",Modifier.padding(horizontal=14.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) { recipes.forEachIndexed { i,_ -> Box(Modifier.padding(3.dp).size(if(i==selected)16.dp else 6.dp,6.dp).background(if(i==selected)LocalPaper.current.ink else LocalPaper.current.bg2,RoundedCornerShape(3.dp))) } }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { TextButton({selected=(selected-1).coerceAtLeast(0)}) {Text("Назад")};TextButton({grid=true}) {Text("Все главы")};TextButton({selected=(selected+1).coerceAtMost(recipes.lastIndex.coerceAtLeast(0))}) {Text("Дальше")} }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReadingSettings(preferences: ReadingPreferences, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest=onClose,containerColor=LocalPaper.current.card) {
        Column(Modifier.padding(horizontal=22.dp).verticalScroll(rememberScrollState())) {
            BookText("Как читать",28)
            SmallLabel("АТМОСФЕРА",Modifier.padding(top=16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { Atmosphere.entries.forEach { v -> FilterChip(preferences.atmosphere==v,{preferences.setAtmosphere(v)},label={Text(v.label,fontSize=12.sp)},modifier=Modifier.padding(end=6.dp)) } }
            SmallLabel("РАЗМЕР ТЕКСТА · ${preferences.textSize.toInt()}",Modifier.padding(top=14.dp))
            Slider(preferences.textSize,preferences::setSize,valueRange=16f..26f,steps=9,modifier=Modifier.testTag("text-size"))
            SmallLabel("ПЕРЕЛИСТЫВАНИЕ")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { ReadingMode.entries.forEach { v -> FilterChip(preferences.mode==v,{preferences.setMode(v)},label={Text(v.label)},modifier=Modifier.weight(1f)) } }
            Row(Modifier.fillMaxWidth().heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically) { Text("Меньше движения",Modifier.weight(1f));Switch(preferences.reducedMotion,preferences::setReduced) }
            SmallLabel("На слабом устройстве и при выключенной системной анимации — плоский сдвиг.")
            Spacer(Modifier.height(24.dp))
        }
    }
}
