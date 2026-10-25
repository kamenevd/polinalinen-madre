package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.account.yandex.*

val LocalSettingsName=staticCompositionLocalOf { "" }

/** A real local/verified name, never a downloaded portrait or fabricated identity. */
@Composable fun PaperMonogram(name:String,size:Dp=36.dp) {
    val letter=name.trim().takeIf{it.isNotBlank()}?.let{String(Character.toChars(it.codePointAt(0))).uppercase()} ?: "М"
    Box(Modifier.size(size).background(LocalPaper.current.acc2,CircleShape),contentAlignment=Alignment.Center) {
        Text(letter,style=PaperType.h2.copy(fontSize=(size.value*.47f).sp),color=LocalPaper.current.acc)
    }
}
@Composable fun SettingsMonogram(onClick:()->Unit,nameOverride:String?=null) {
    val account=LocalYandexAccountUi.current?.model?.state?.collectAsState()?.value
    val profile=(account as? YandexAccountState.SignedIn)?.profile
    val name=nameOverride ?: LocalSettingsName.current.ifBlank{profile?.displayName.orEmpty()}
    PaperIconButton(onClick,modifier=Modifier.testTag("settings-monogram"),label="Настройки") { PaperMonogram(name) }
}
