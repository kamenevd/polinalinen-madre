package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
@Composable internal fun SpineTitle(name:String,hasRibbon:Boolean=false) {
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    Box(Modifier.fillMaxSize().padding(top=if(hasRibbon)BookRibbonHeight+8.dp else 12.dp,bottom=12.dp)) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(if(hasRibbon)"shelf-own-title-area" else "shelf-title-area")) {
        val along=with(density){maxHeight.roundToPx()}
        val across=with(density){maxWidth.roundToPx()}
        var font=18f
        // Fit the natural single line, down to the designer's11sp minimum.
        // At the minimum, a genuinely long name uses ellipsis, never wrapping.
        while(font>11f) {
            val natural=measurer.measure(name,PaperType.body.copy(fontSize=font.sp,lineHeight=(font*23f/18f).sp),maxLines=1,softWrap=false)
            if(natural.multiParagraph.width<=along && natural.size.height<=across)break
            font-=.5f
        }
        Text(name,Modifier.fillMaxSize().layout { measurable,constraints ->
            // Reserve the whole longitudinal axis. Intrinsic fractional widths
            // otherwise round down (138.x→138) and produce real width overflow.
            val title=measurable.measure(Constraints(minWidth=constraints.maxHeight,maxWidth=constraints.maxHeight))
            layout(constraints.maxWidth,constraints.maxHeight) {
                title.placeWithLayer((constraints.maxWidth-title.width)/2,(constraints.maxHeight-title.height)/2) { rotationZ=90f }
            }
        },color=Illustration.CoverLettering,style = PaperType.body,fontSize=font.sp,lineHeight=(font*23f/18f).sp,textAlign=TextAlign.Center,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
    }
    }
}

/** The same stateless shelf is used by Room/ViewModel UI and isolated fixtures. */
@Composable internal fun ShelfPaper(members:List<ShelfMember>,ledger:List<ShelfLedgerRow>,familyName:String?,records:List<BakeRecordEntity>,unreachable:Boolean,onSettings:()->Unit,onOpenBook:(String)->Unit,onOpenAlice:()->Unit={}) {
    val p=LocalPaper.current
    val context=LocalContext.current
    val spines=members.ifEmpty{listOf(FamilyShelf.localMember("вы"))}
    LazyColumn(Modifier.fillMaxSize().background(p.bg).paperContentInsets().testTag(FamilyShelf.LIST_TAG),contentPadding=PaddingValues(horizontal=22.dp,vertical=8.dp)) {
        item(key="shelf-overview") { Column {
            BookHeader("ПОЛКА СЕМЬИ",onSettings=onSettings)
            BookText("${ledger.size} ${bakeWord(ledger.size)},",40)
            BookText("${spines.size} ${bookWord(spines.size)}",40,italic=true,color=p.acc)
            familyName?.takeIf{it.isNotBlank()}?.let{SmallLabel(it,Modifier.padding(top=8.dp))}
            Row(Modifier.fillMaxWidth().height(230.dp).horizontalScroll(rememberScrollState()).padding(top=22.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                spines.forEachIndexed { i,member ->
                    val colors=listOf(Illustration.SpineInks[0],Illustration.SpineInks[1],Illustration.SpineInks[2],Illustration.SpineInks[3])
                    Box(Modifier.width(56.dp).height((180+i%3*14).dp).background(colors[i%colors.size]).then(bookAction("Открыть книгу: ${member.displayName}"){onOpenBook(member.userId)}).testTag("shelf-spine-${member.userId}"),contentAlignment=Alignment.Center) {
                        SpineTitle(member.displayName,hasRibbon=member.isMe)
                        if(member.isMe)Ribbon(Modifier.align(Alignment.TopCenter).testTag("shelf-own-ribbon"))
                    }
                }
                Box(Modifier.width(48.dp).height(150.dp).background(p.bg2).testTag(FamilyShelf.UNCUT_SPINE_TAG))
            }
            Box(Modifier.fillMaxWidth().height(10.dp).background(Illustration.ShelfWood))
            Text(FamilyShelf.CAPTION,Modifier.padding(top=12.dp).testTag("shelf-caption"),style = PaperType.caption,fontSize=11.sp,lineHeight=17.sp,color=p.ink2)
            Spacer(Modifier.height(18.dp))
            val photos=records.filter{!it.photoPath.isNullOrBlank()}.take(3)
            if(photos.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                photos.forEach{record->AsyncImage(model=record.photoPath?.let{if(it.startsWith("https://"))it else PhotoStore.resolve(context,it)},contentDescription=record.recipeName,modifier=Modifier.weight(1f).height(160.dp),contentScale=ContentScale.Crop)}
            }
            if(unreachable)SmallLabel("Полка сейчас не отвечает. Своя книга доступна на телефоне.",Modifier.padding(vertical=12.dp))
        } }
        item(key="shelf-journal-title") {BookText("Журнал",26,Modifier.padding(top=18.dp,bottom=8.dp).testTag("shelf-journal-title"))}
        if(ledger.isEmpty())item {BookText("Пока ни одной выпечки на полке",17,italic=true)}
        items(ledger,key={"${it.userId}-${it.bakedAtMillis}-${it.chapter}"}){row->
            Column(Modifier.padding(vertical=10.dp).testTag("shelf-ledger-${row.bakedAtMillis}-${row.userId}")) {
                SmallLabel("${row.whenLabel} · ${row.who}")
                BookText(row.chapter,20)
            }
        }
        if(com.polinalinen.madre.config.FeatureFlags.ALICE)item(key="shelf-alice-footer") {
            Row(Modifier.fillMaxWidth().padding(vertical=22.dp).testTag("shelf-alice-footer"),verticalAlignment=Alignment.CenterVertically) {
                PaperTextAction("Подключить Алису", onOpenAlice, Modifier.weight(1f))
            }
        }
    }
}
