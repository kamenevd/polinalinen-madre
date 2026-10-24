package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.BakeNumbers

enum class BakeNumberSize { Sm, Md, Lg, Display }

/** Decorative identity, not an independent button. Parent chip owns its 48dp hit area. */
@Composable fun PaperBakeNumber(number: Int, modifier: Modifier = Modifier, size: BakeNumberSize = BakeNumberSize.Md,
    planned: Boolean = false, accent: Boolean = false, ring: Boolean = false) {
    val p = LocalPaper.current
    val description = (if(planned) "Запланированная выпечка номер " else "Выпечка номер ") + BakeNumbers.word(number)
    if(size == BakeNumberSize.Display) {
        Text(BakeNumbers.label(number),modifier.clearAndSetSemantics { contentDescription=description },
            style=PaperType.display.copy(fontSize=88.sp,lineHeight=100.sp,fontWeight=FontWeight.ExtraLight),color=p.acc)
        return
    }
    val height=when(size){BakeNumberSize.Sm->22.dp;BakeNumberSize.Lg->40.dp;else->30.dp}
    val font=when(size){BakeNumberSize.Sm->13.sp;BakeNumberSize.Lg->22.sp;else->17.sp}
    val shape=RoundedCornerShape(50)
    val background=if(planned)Color.Transparent else if(accent)p.acc else p.ink
    val foreground=if(planned)p.ink else p.bg
    Row(modifier.heightIn(min=height).then(if(ring)Modifier.border(3.dp,p.card,shape).padding(3.dp) else Modifier)
        .background(background,shape).then(if(planned)Modifier.border(1.5.dp,p.ink,shape) else Modifier)
        .padding(horizontal=7.dp,vertical=2.dp).clearAndSetSemantics { contentDescription=description },
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        Text("№",style=PaperType.ui.copy(fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=9.sp),color=foreground.copy(alpha=.72f))
        Text(if(number in 1..9)number.toString() else "—",style=PaperType.ui.copy(fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=font,lineHeight=font),color=foreground)
    }
}

@Composable fun PaperBakeChip(number:Int,label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,planned:Boolean=false) {
    val p=LocalPaper.current
    Row(modifier.heightIn(min=48.dp).selectable(selected,role=Role.RadioButton,onClick=onClick)
        .background(if(selected)p.ink else p.bg2,RoundedCornerShape(24.dp)).padding(horizontal=8.dp,vertical=7.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        PaperBakeNumber(number,size=BakeNumberSize.Md,planned=planned,accent=selected)
        Text(label,style=PaperType.ui.copy(fontSize=13.sp),color=if(selected)p.bg else p.ink2)
    }
}
