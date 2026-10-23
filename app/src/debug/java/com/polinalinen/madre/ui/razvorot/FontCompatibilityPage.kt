package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Real Android text rendering; explicitly not evidence from a HarmonyOS phone. */
@Composable internal fun FontCompatibilityPage() {
    val p=LocalPaper.current
    Column(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SmallLabel("Статические шрифты · Android native")
        SmallLabel("Не проверка физического HarmonyOS")
        for(w in listOf(200,300,400,500))BookText("$w · Ёжик любит тёплый хлеб",20,weight=FontWeight(w))
        BookText("300 italic · Доброе утро, пекарь",20,italic=true,weight=FontWeight.Light)
        BookText("400 italic · Живая закваска",20,italic=true)
        BookText("500 italic · Ждём подъёма",20,italic=true,weight=FontWeight.Medium)
        androidx.compose.material3.Text("Manrope400 · Печь, Ёё, Йй",fontFamily=Manrope,fontWeight=FontWeight.Normal,color=p.ink)
        androidx.compose.material3.Text("Manrope600 · Полка семьи",fontFamily=Manrope,fontWeight=FontWeight.SemiBold,color=p.ink)
        SmallLabel("Manrope700 · АБВГД · Яя Ёё")
        SmallLabel("Статический fallback200 и800, тот же текст")
        TimerNumber("27:41",0f,64.sp,(-2).sp,p.ink,forceStatic=true)
        TimerNumber("27:41",1f,64.sp,(-2).sp,p.ink,forceStatic=true)
    }
}
