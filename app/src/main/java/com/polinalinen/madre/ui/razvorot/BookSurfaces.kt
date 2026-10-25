package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.*
import com.polinalinen.madre.R
import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.model.Recipe

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
@Composable fun BookHeader(title: String, onBack: (() -> Unit)? = null, onAa: (() -> Unit)? = null, onSettings:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) PaperIconButton(onBack, label = "Назад") { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
        SmallLabel(title, Modifier.weight(1f))
        if(onSettings!=null)SettingsMonogram(onSettings)
        if(onAa!=null)PaperTextButton(onAa, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("reading-settings"), label = "Как читать") { BookText("Aa", 21) }
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
            PaperAtmospherePicker(preferences)
            PaperNote("Авто — по тёмной теме телефона.")
            SmallLabel("РАЗМЕР ТЕКСТА · ${preferences.textSize.toInt()}",Modifier.padding(top=14.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {BookText("A",14);PaperSlider(preferences.textSize,preferences::setSize,valueRange=14f..22f,steps=7,modifier=Modifier.weight(1f).testTag("text-size"));BookText("A",24)}

            SmallLabel("ПЕРЕЛИСТЫВАНИЕ")
            PaperSegmented(listOf("Страницами","Прокруткой"), if(preferences.mode==ReadingMode.SCROLL)1 else 0,
                { preferences.chooseMode(if(it==0)ReadingMode.BOOK else ReadingMode.SCROLL) })
            Spacer(Modifier.height(24.dp))
        }
    }
}
