package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.shelf.*
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.ui.screens.bakeWord

internal fun bookWord(n:Int)=when {
    n%10==1 && n%100!=11 -> "книга"
    n%10 in 2..4 && n%100 !in 12..14 -> "книги"
    else -> "книг"
}

/** Measure along the spine BEFORE rotation, never wrap a word into letter stacks. */
@Composable internal fun SpineTitle(name:String) {
    Text(name,Modifier.fillMaxSize().padding(vertical=12.dp).layout { measurable,constraints ->
        val title=measurable.measure(Constraints(maxWidth=constraints.maxHeight,maxHeight=constraints.maxWidth))
        layout(constraints.maxWidth,constraints.maxHeight) {
            title.placeWithLayer((constraints.maxWidth-title.width)/2,(constraints.maxHeight-title.height)/2) { rotationZ=90f }
        }
    },color=Color(0xFFF4EADC),fontFamily=Literature,fontSize=18.sp,lineHeight=23.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
}

/** The same stateless shelf is used by Room/ViewModel UI and isolated fixtures. */
@Composable internal fun ShelfPaper(members:List<ShelfMember>,ledger:List<ShelfLedgerRow>,familyName:String?,records:List<BakeRecordEntity>,unreachable:Boolean,onSettings:()->Unit,onOpenBook:(String)->Unit) {
    val p=LocalPaper.current
    val context=LocalContext.current
    val spines=members.ifEmpty{listOf(FamilyShelf.localMember("вы"))}
    LazyColumn(Modifier.fillMaxSize().background(p.bg).testTag(FamilyShelf.LIST_TAG),contentPadding=PaddingValues(horizontal=22.dp,vertical=8.dp)) {
        item {
            BookHeader("ПОЛКА СЕМЬИ",onAa=onSettings)
            BookText("${ledger.size} ${bakeWord(ledger.size)},",40)
            BookText("${spines.size} ${bookWord(spines.size)}",40,italic=true,color=p.acc)
            familyName?.takeIf{it.isNotBlank()}?.let{SmallLabel(it,Modifier.padding(top=8.dp))}
            Row(Modifier.fillMaxWidth().height(230.dp).horizontalScroll(rememberScrollState()).padding(top=22.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                spines.forEachIndexed { i,member ->
                    val colors=listOf(Color(0xFF7C3B22),Color(0xFF4F6140),Color(0xFF33424D),Color(0xFF8E6A3A))
                    Box(Modifier.width(56.dp).height((180+i%3*14).dp).background(colors[i%colors.size]).then(bookAction("Открыть книгу: ${member.displayName}"){onOpenBook(member.userId)}).testTag("shelf-spine-${member.userId}"),contentAlignment=Alignment.Center) {
                        SpineTitle(member.displayName)
                        if(member.isMe)Ribbon(Modifier.align(Alignment.TopCenter))
                    }
                }
                Box(Modifier.width(48.dp).height(150.dp).background(p.bg2).testTag(FamilyShelf.UNCUT_SPINE_TAG))
            }
            Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF8B6A4E)))
            SmallLabel(FamilyShelf.CAPTION,Modifier.padding(top=12.dp))
            Spacer(Modifier.height(18.dp))
            val photos=records.filter{!it.photoPath.isNullOrBlank()}.take(3)
            if(photos.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                photos.forEach{record->AsyncImage(model=record.photoPath?.let{if(it.startsWith("https://"))it else PhotoStore.resolve(context,it)},contentDescription=record.recipeName,modifier=Modifier.weight(1f).height(160.dp),contentScale=ContentScale.Crop)}
            }
            if(unreachable)SmallLabel("Полка сейчас не отвечает. Своя книга доступна на телефоне.",Modifier.padding(vertical=12.dp))
            BookText("Журнал",26,Modifier.padding(top=18.dp,bottom=8.dp))
            if(ledger.isEmpty())BookText("Пока ни одной выпечки на полке",17,italic=true)
        }
        items(ledger,key={"${it.userId}-${it.bakedAtMillis}-${it.chapter}"}){row->
            Column(Modifier.padding(vertical=10.dp)) {
                SmallLabel("${row.whenLabel} · ${row.who}")
                BookText(row.chapter,20)
            }
        }
    }
}
