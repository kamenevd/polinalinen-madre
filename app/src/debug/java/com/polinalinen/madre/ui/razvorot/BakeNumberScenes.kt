package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.*
import com.polinalinen.madre.navigation.PlannedBake
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.voice.*

/** Explicit synthetic data, exact production renderers; no storage, backend or account actions. */
internal val BakeNumberScenes=setOf("oven-numbers","sheet-start-number","bake-number","bake-fulltimer-number",
    "bake-finale-number","chapter-active-number","alice-linked-number","station-number","oven-nine-snack")

@Composable internal fun BakeNumberScene(scene:String,recipes:List<Recipe>,preferences:ReadingPreferences) {
    val bread=recipes.first()
    val ciabatta=recipes.first{it.id=="ciabatta"}
    val pizza=recipes.first{it.id=="pizza"}
    val clock=LocalBookClock.current
    fun session(recipe:Recipe,id:Long,number:Int,remaining:Long):BakingSession {
        val step=4.coerceAtMost(recipe.timeline.lastIndex)
        return BakingSession(id,recipe,step,BakingClock.elapsed()-(recipe.timeline[step].durationMinutes*60L-remaining)*1000L,
            clock.millis(),scaleFactor=2.0,bakeNumber=number)
    }
    val first=session(bread,811,1,1660)
    val current=session(ciabatta,872,2,1100)
    val sessions=listOf(first,current)
    val plan=PlannedBake(pizza.id,2,LocalBookClock.current.millis()+10_200_000L,number=3)
    when(scene) {
        "oven-numbers","oven-nine-snack" -> Box(Modifier.fillMaxSize()) {
            val rows=if(scene=="oven-nine-snack") (1..9).map{current.copy(id=900L+it,bakeNumber=it)} else sessions
            Column {
                Box(Modifier.weight(1f)){BookOvenContent(recipes,rows,if(scene=="oven-nine-snack")emptyList() else listOf(plan),{},{},{},{},{})}
                SpineBar(1){}
            }
            if(scene=="oven-nine-snack") {
                val snackbar=remember{PaperSnackbarState()}
                LaunchedEffect(Unit){snackbar.showSnackbar(BakeNumbers.LIMIT_MESSAGE,indefinite=true)}
                PaperSnackbar(snackbar,Modifier.align(Alignment.BottomCenter).padding(bottom=80.dp))
            }
        }
        "sheet-start-number" -> {
            BookOvenContent(recipes,sessions,listOf(plan),{},{},{},{},{})
            BookStartSheet(recipes.first{it.id=="focaccia"},2,{},{},onPlan={_,_->},bakeNumber=4,onChapter={})
        }
        "bake-number" -> BakingPaper(current,1100,{},{},{},{},onOpenAlice={},sessions=sessions,plans=listOf(plan),onPreviousStep={})
        "bake-fulltimer-number" -> FullTimer(current,1100,sessions=sessions,plans=listOf(plan)){}
        "bake-finale-number" -> BakeFinalePage(BakeFinalePageState(ciabatta.name,recipes.indexOf(ciabatta)+1,2,
            RecipeScale.totalMinutes(ciabatta,2),ciabatta.timeline.size,LocalBookClock.current.millis(),recipes.map{it.id==ciabatta.id},24,bakeNumber=2),{},{},photo={PaperNote("Фото пока нет")})
        "chapter-active-number" -> RazvorotReader(recipes,ciabatta.id,preferences,{},{},{},initialPage=1,
            scaleFactor=2.0,onOpenAlice={},activeSessions=listOf(current),onContinueBake={})
        "alice-linked-number" -> AlicePage(VoiceUiState(VoiceStatus.CONNECTED,selectedBake=current.bakeKey),sessions,{},{},{},{},{})
        "station-number" -> {
            BakingPaper(current,1100,{},{},{},{},onOpenAlice={})
            com.polinalinen.madre.account.yandex.StationTimerSheet(1100,bakeNumber=2,onDismiss={})
        }
    }
}
