package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.polinalinen.madre.model.*
import com.polinalinen.madre.ui.kit.*

/** Real inline anchors: popup is positioned from the tapped word, never a sheet or screen modal. */
@Composable internal fun StepIngredientParagraph(session:BakingSession,addedState:MutableState<List<String>>,modifier:Modifier=Modifier) {
    val portions=session.trustedPortions
    if(portions==null || !session.restoredStepValid) {
        PaperStepParagraph("Порции не сохранены — подтвердите количество для этой выпечки.",modifier);return
    }
    val scope="${session.bakeKey}:${session.id}:${session.recipe.id}:${session.recipeRevision}:${session.recipe.hashCode()}:${session.currentStepIndex}:$portions"
    // BakingPaper owns the saveable marks even while FullTimer replaces this subtree.
    var added by addedState
    var selected by rememberSaveable(scope) { mutableStateOf<Int?>(null) }
    val parts=remember(session.recipe,session.currentStepIndex,portions){StepIngredients.parts(session.recipe,session.currentStepIndex,portions)}
    val p=LocalPaper.current;val density=LocalDensity.current;val measurer=rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style=PaperType.body
        val inline=mutableMapOf<String,InlineTextContent>()
        val paragraph=buildAnnotatedString {
            parts.forEachIndexed { index,part ->
                if(part.ref==null)append(part.text) else {
                    val key="$scope:${part.ref.section}:${part.ref.name}"
                    val done=key in added;val active=selected==index
                    val label="${if(done)"✓ " else ""}${part.text} ${part.amount}"
                    val pill=buildAnnotatedString {
                        append(label);addStyle(PaperType.ui.toSpanStyle().copy(fontWeight=FontWeight.Bold),label.length-part.amount.length,label.length)
                    }
                    val available=with(density){(maxWidth.toPx()-8.sp.toPx()).toInt().coerceAtLeast(1)}
                    val measured=measurer.measure(pill,style,constraints=Constraints(maxWidth=available))
                    val width=(with(density){measured.size.width.toSp()}.value+8f).sp
                    val height=(with(density){measured.size.height.toSp()}.value+2f).sp
                    appendInlineContent("ingredient-$index",label)
                    inline["ingredient-$index"]=InlineTextContent(Placeholder(width,height,PlaceholderVerticalAlign.TextCenter)) {
                        Box(Modifier.fillMaxSize().background(if(done)p.sage.copy(alpha=.16f) else if(active)p.acc else p.acc2,RoundedCornerShape(7.dp))
                            .clickable(onClickLabel="Количество: ${part.text}"){selected=index}
                            .testTag("bake-ingredient-$index").semantics(mergeDescendants=true){stateDescription=if(done)"Добавлено" else "Не добавлено"},contentAlignment=Alignment.Center) {
                            Text(pill,style=style.copy(textDecoration=if(done)TextDecoration.LineThrough else null),color=if(done)p.sage else if(active)p.bg else p.ink)
                            if(active) {
                                var above by remember { mutableStateOf(false) }
                                var pointer by remember { mutableStateOf(18.dp) }
                                val position=remember { object:PopupPositionProvider {
                                    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
                                        val margin=with(density){8.dp.roundToPx()}
                                        val x=anchorBounds.left.coerceIn(margin,(windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin))
                                        above=anchorBounds.bottom+popupContentSize.height>windowSize.height-margin
                                        pointer=with(density){(anchorBounds.center.x-x-margin).coerceIn(margin,(popupContentSize.width-margin*3).coerceAtLeast(margin)).toDp()}
                                        val y=if(!above)anchorBounds.bottom else anchorBounds.top-popupContentSize.height
                                        return IntOffset(x,y.coerceAtLeast(margin))
                                    }
                                } }
                                Popup(popupPositionProvider=position,onDismissRequest={selected=null},properties=PopupProperties(focusable=true)) {
                                    val arrow:@Composable ()->Unit={ Canvas(Modifier.padding(start=pointer).size(14.dp,8.dp)) {
                                        drawPath(Path().apply{moveTo(0f,if(above)0f else size.height);lineTo(size.width/2,if(above)size.height else 0f);lineTo(size.width,if(above)0f else size.height);close()},p.ink)
                                    } }
                                    Column(Modifier.widthIn(max=260.dp).testTag("bake-ingredient-popup")) {
                                        if(!above)arrow()
                                        Column(Modifier.shadow(8.dp,RoundedCornerShape(14.dp)).background(p.ink,RoundedCornerShape(14.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                                Text(part.text,Modifier.weight(1f),style=PaperType.title,color=p.bg)
                                                Text(part.amount,style=PaperType.ui.copy(fontWeight=FontWeight.Bold),color=p.bg)
                                            }
                                            Text(if(part.base=="по вкусу")"По вкусу · без пересчёта" else "${part.base} × $portions → ${part.amount}",style=PaperType.caption,color=p.bg)
                                            Box(Modifier.fillMaxWidth().heightIn(min=40.dp).background(p.acc2,RoundedCornerShape(22.dp))
                                                .clickable(onClickLabel="Добавил ${part.text}"){if(!done)added=added+key;selected=null}.testTag("bake-ingredient-add"),contentAlignment=Alignment.Center) {
                                                Text("✓ Добавил",style=PaperType.ui,color=p.ink)
                                            }
                                        }
                                        if(above)arrow()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Typography only: numbers in free prose are never scaled or bound by this expression.
            Regex("\\b\\d+(?:[,.]\\d+)?(?:\\s*[–—-]\\s*\\d+)?\\s*(?:[°º][cCсС]?|мин(?:ут[а-я]*)?|мл|кг|г)(?=\\b|\\s|[.,;]|$)").findAll(toString()).forEach {
                addStyle(PaperType.ui.toSpanStyle().copy(fontWeight=FontWeight.Bold,color=p.ink),it.range.first,it.range.last+1)
            }
        }
        Text(paragraph,modifier.fillMaxWidth(),style=style,color=p.ink2,inlineContent=inline)
    }
}
