package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.ui.kit.*

/** Y10–Y12 shared by the real sheet and Settings. Login never grants bake access. */
@Composable internal fun AliceConnectionSteps(
    waiting: Boolean, onHelp: () -> Unit,
    accountStep: (@Composable () -> Unit)? = null,
    verifiedName: String? = null,
    compact: Boolean = false,
) {
    val p = LocalPaper.current
    val owner = LocalYandexAccountUi.current
    val account = owner?.model?.state?.collectAsState()?.value
    val profile = (account as? YandexAccountState.SignedIn)?.profile
    val name = profile?.displayName?.ifBlank { profile.login } ?: verifiedName
    if(!compact)Text("Один раз. Потом шаги можно слушать и спрашивать голосом.",style=PaperType.note,color=p.ink2)
    val current=if(name==null)1 else if(waiting)3 else 2
    AliceConnectionStep(1,if(name!=null) "Вы вошли · $name" else "Войдите с Яндекс ID",done=name!=null,current=current==1,compact=compact) {
        if(name==null) Text("тем же аккаунтом, что на Станции",style=PaperType.note,color=p.ink2)
        if(name==null) {
            if(accountStep!=null) accountStep() else YandexAliceAccountStep(onHelp,compactAlice=compact)
        }
    }
    AliceConnectionStep(2,"Скажите Алисе",done=current==3,current=current==2,compact=compact) {
        SmallLabel("НА СТАНЦИИ ИЛИ В ПРИЛОЖЕНИИ ЯНДЕКС",color=p.ink2)
        Box(Modifier.fillMaxWidth().background(p.bg2,RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("«${AliceCopy.LAUNCH_PHRASE}»",style=PaperType.body.copy(fontStyle=androidx.compose.ui.text.font.FontStyle.Italic),color=p.ink)
        }
    }
    AliceConnectionStep(3,"Нажмите «Авторизоваться»",current=current==3,compact=compact) {
        Text(if(compact)"В приложении Яндекс · со Станции придёт пуш. Если появился код — подтвердите его на телефоне."
            else "в приложении Яндекс (со Станции придёт пуш). Войдите тем же аккаунтом. Если появился код — подтвердите его на телефоне.",
            style=PaperType.note,color=p.ink2)
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        if(current==3) { PaperProgress(); Text("Ждём подтверждения подключения",style=PaperType.note,color=p.ink2,modifier=Modifier.weight(1f)) }
        else Spacer(Modifier.weight(1f))
        PaperTextAction("НЕ ВЫХОДИТ?",onHelp)
    }
}

@Composable private fun AliceConnectionStep(number:Int,title:String,done:Boolean=false,current:Boolean=false,compact:Boolean=false,
    content:@Composable ColumnScope.()->Unit) {
    val p=LocalPaper.current
    Row(Modifier.fillMaxWidth().padding(top=if(compact)4.dp else 8.dp).testTag("alice-connection-step-$number").semantics{selected=current},horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(if(done)p.sage2 else if(current)p.acc else p.bg2,CircleShape),
            contentAlignment=Alignment.Center) {
            Text(if(done)"✓" else number.toString(),style=PaperType.ui,color=if(done)p.sage else if(current)p.card else p.ink2)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(if(compact)4.dp else 8.dp)) {
            Text(title,style=PaperType.title,color=if(done)p.sage else p.ink)
            content()
        }
    }
}
