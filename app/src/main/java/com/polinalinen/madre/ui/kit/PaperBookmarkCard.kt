package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.components.bookAction

@Composable fun PaperBookmarkCard(title:String,image:Int,page:Int,note:String,onClick:()->Unit) {
    val p=LocalPaper.current
    Box(Modifier.fillMaxWidth().then(bookAction("Продолжить: $title, страница $page",onClick=onClick))) {
        PaperCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource(image),null,Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    SmallLabel("ЗАКЛАДКА · СТР. $page",color=p.ink2)
                    Text(title,style=PaperType.title,color=p.ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(note,style=PaperType.note,color=p.ink2,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(20.dp),tint=p.ink2)
            }
        }
        Canvas(Modifier.align(Alignment.TopStart).padding(start=16.dp).size(10.dp,28.dp)) {
            drawPath(Path().apply {
                moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width,size.height)
                lineTo(size.width/2,size.height-5.dp.toPx());lineTo(0f,size.height);close()
            },p.rib)
        }
    }
}
