package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.account.FamilyAccount
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.shelf.FamilyShelf
import com.polinalinen.madre.shelf.ShelfLedgerRow
import com.polinalinen.madre.shelf.ShelfMember
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.ShelfViewModel

/**
 * Полка семьи: корешки книг и журнал выпечек. Своя книга — с красным ляссе.
 * Пустой корешок справа не книга и не кнопка.
 */
@Composable
fun ShelfScreen(
    myName: String,
    localRecords: List<BakeRecordEntity>,
    onBack: () -> Unit,
    onOpenBook: (ownerId: String) -> Unit,
    account: FamilyAccount?,
    shelfViewModel: ShelfViewModel = viewModel(),
) {
    val colors = AppColors.current
    val members by shelfViewModel.members.collectAsState()
    val ledger by shelfViewModel.ledger.collectAsState()
    val familyName by shelfViewModel.familyName.collectAsState()
    val unreachable by shelfViewModel.unreachable.collectAsState()

    LaunchedEffect(account?.familyId, account?.familyName, myName, localRecords) {
        shelfViewModel.refresh(account, myName, localRecords)
    }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.statusBarsPadding().testTag(FamilyShelf.LIST_TAG),
        ) {
            item {
                BackLabel("Первая полоса", onClick = onBack, modifier = Modifier.padding(horizontal = 22.dp))
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text("Полка", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                    Text(
                        familyName?.takeIf { it.isNotBlank() } ?: "ваша полка",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                ShelfBoard(members = members, onOpenBook = onOpenBook)
                Text(
                    FamilyShelf.CAPTION,
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
                if (unreachable) {
                    Text(
                        "полка сейчас не отвечает — своя книга на телефоне открыта",
                        color = colors.terracotta,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                }
                HairRule(Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                PageLabel("Журнал", color = colors.espresso, modifier = Modifier.padding(horizontal = 22.dp))
                if (ledger.isNotEmpty()) {
                    LedgerHeader()
                } else {
                    Text(
                        "пока ни одной выпечки на полке",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
            }
            items(ledger, key = { "${it.userId}-${it.bakedAtMillis}-${it.chapter}" }) { row ->
                LedgerRow(row)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun ShelfBoard(members: List<ShelfMember>, onOpenBook: (String) -> Unit) {
    val colors = AppColors.current
    val spines = members.ifEmpty { listOf(FamilyShelf.localMember("вы")) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            spines.forEachIndexed { index, member ->
                if (index > 0) Spacer(Modifier.width(SPINE_GAP))
                NamedSpine(
                    member = member,
                    height = spineHeight(index, member.isMe),
                    onClick = { onOpenBook(member.userId) },
                )
            }
            Spacer(Modifier.width(SPINE_GAP))
            UncutSpine()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind { drawRect(colors.amberDeep) },
        )
        Row {
            spines.forEachIndexed { index, member ->
                if (index > 0) Spacer(Modifier.width(SPINE_GAP))
                Box(
                    Modifier.width(SPINE_WIDTH).height(36.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (member.isMe) {
                        Box(
                            Modifier
                                .width(7.dp)
                                .height(32.dp)
                                .drawBehind { drawRect(colors.terracotta) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(SPINE_GAP))
            Spacer(Modifier.width(SPINE_WIDTH))
        }
    }
}

@Composable
private fun NamedSpine(member: ShelfMember, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = AppColors.current
    val fill = if (member.isMe) colors.amberDeep else colors.crust
    Box(
        Modifier
            .width(SPINE_WIDTH)
            .height(height)
            .testTag("shelf-spine-${member.userId}")
            .then(bookAction("Открыть книгу: ${member.displayName}", onClick = onClick))
            .drawBehind {
                drawRect(fill)
                if (member.isMe) {
                    drawRect(colors.espresso, style = Stroke(width = 2.dp.toPx()))
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            member.displayName,
            color = colors.paper,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun UncutSpine() {
    val colors = AppColors.current
    Box(
        Modifier
            .width(SPINE_WIDTH)
            .height(96.dp)
            .testTag(FamilyShelf.UNCUT_SPINE_TAG)
            .drawBehind {
                drawRoundRect(
                    color = colors.parchment,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = colors.flour,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    ),
                )
            },
    )
}

@Composable
private fun LedgerHeader() {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Когда", color = colors.cocoa, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("Кто", color = colors.cocoa, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("Глава", color = colors.cocoa, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
    }
    HairRule(Modifier.padding(horizontal = 22.dp))
}

@Composable
private fun LedgerRow(row: ShelfLedgerRow) {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(row.whenLabel, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(row.who, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.chapter, color = colors.espresso, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HairRule()
    }
}

private val SPINE_WIDTH = 44.dp
private val SPINE_GAP = 10.dp

private fun spineHeight(index: Int, isMe: Boolean): androidx.compose.ui.unit.Dp {
    val heights = listOf(142.dp, 118.dp, 134.dp, 124.dp, 148.dp)
    val base = heights[index % heights.size]
    return if (isMe) 148.dp else base
}
