package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.model.*
import com.polinalinen.madre.navigation.PlannedBake
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.photo.PhotoAttachmentActions
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.voice.*

/** Screenshot state boundaries ONLY: no account owner, SDK, coordinator, Room or network.
 * CONNECTED below is an explicitly synthetic rendering input, never live acceptance. */
internal val FixesNativeScenes=setOf("settings-account-out","settings-account-in","settings-in",
    "account-sheet","signout-dialog","toc-greeting","oven-empty","oven-planned","bake-two-chips",
    "bake-undo-snack","bake-fulltimer","bake-finale-photo","bake-finale-noaccount","sheet-start-now",
    "yandex-offer-busy","yandex-offer-error","yandex-offer-offline","alice-connect-0","alice-connect-1",
    "alice-connect-2","alice-linked","station-timer")

internal fun syntheticNativeBake(recipe:Recipe,id:Long=7):BakingSession {
    val step=9.coerceAtMost(recipe.timeline.lastIndex)
    val remaining=1661L.coerceAtMost(recipe.timeline[step].durationMinutes*60L)
    val elapsed=BakingClock.elapsed()
    return BakingSession(id,recipe,step,elapsed-(recipe.timeline[step].durationMinutes*60L-remaining)*1000L,
        BakingClock.wallClock(),scaleFactor=2.0)
}

@Composable internal fun FixesNativeScene(scene:String,recipes:List<Recipe>,preferences:ReadingPreferences,photos:List<GalleryPhoto>) {
    val recipe=recipes.first()
    val bake=remember(recipe){syntheticNativeBake(recipe)}
    val profile=remember{VerifiedYandexProfile("synthetic-render-only","fixture-baker","Пекарь",null,true,1792731600000L)}
    when {
        scene.startsWith("yandex-offer-")->YandexOfferContent(when(scene) {
            "yandex-offer-busy"->YandexAccountState.SigningIn(1,false)
            "yandex-offer-offline"->YandexAccountState.Error(YandexAccountError.NETWORK)
            else->YandexAccountState.Error(YandexAccountError.SERVER)
        },false,{},{},{})
        scene.startsWith("settings-account-") || scene in setOf("settings-in","account-sheet","signout-dialog")-> {
            val state=if(scene=="settings-account-out")YandexAccountState.SignedOut else YandexAccountState.SignedIn(profile)
            SettingsContent("Пекарь",{},{},{},"Мадре",{},12,42,24,{},true,{},true,{},"Тестовая полка",FamilyBookState.SignedOut,{},{},
                preferences=preferences,accountContent={YandexSettingsAccountContent(state,false,"Пекарь",{},{},{},{},{},{})})
            if(scene=="account-sheet")YandexAccountSheet(profile,{},{},{})
            if(scene=="signout-dialog")YandexSignOutDialog({},{})
        }
        scene=="toc-greeting"->Column {
            Box(Modifier.weight(1f).safeDrawingPadding()){BookTocContent(recipes,emptyList(),null,{_,_->},{},profileOverride=profile)}
            SpineBar(0){}
        }
        scene=="oven-empty" || scene=="oven-planned"->Column {
            val clock=LocalBookClock.current
            val plans=if(scene=="oven-planned")listOf(PlannedBake(recipe.id,2,clock.millis()+3_600_000L)) else emptyList()
            Box(Modifier.weight(1f).safeDrawingPadding()){BookOvenContent(recipes,emptyList(),plans,{},{},{},{},{})}
            SpineBar(1){}
        }
        scene=="sheet-start-now"->{
            BookOvenContent(recipes,emptyList(),emptyList(),{},{},{},{},{})
            BookStartSheet(recipe,2,{},{},onPlan={_,_->},initialMode=0,onChapter={})
        }
        scene=="bake-two-chips"->{
            val second=remember(recipes){syntheticNativeBake(recipes[1],8)}
            BakingPaper(bake,1661L,{},{},{},{},onOpenAlice={},sessions=listOf(bake,second),onSwitchSession={})
        }
        scene=="bake-fulltimer"->FullTimer(bake,1661L){}
        scene=="bake-undo-snack"->{
            val snackbar=remember{PaperSnackbarState()}
            LaunchedEffect(Unit){snackbar.showSnackbar("Шаг ${bake.currentStepIndex+1} · ${bake.currentStep.title}","Вернуть",indefinite=true)}
            Box(Modifier.fillMaxSize()) {
                BakingPaper(bake,1661L,{},{},{},{},onOpenAlice={})
                PaperSnackbar(snackbar,Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp,vertical=88.dp))
            }
        }
        scene.startsWith("bake-finale-")->NativeFinaleFixture(recipe,recipes,scene,photos)
        scene.startsWith("alice-connect-") || scene=="alice-linked"->{
            val linked=scene=="alice-connect-2" || scene=="alice-linked"
            BakingPaper(bake,1661L,{},{},{},{},onOpenAlice={})
            PaperSheet({}){AliceSheetContent(if(linked)AliceSheetMode.LISTENING else AliceSheetMode.UNLINKED,
                VoiceUiState(if(linked)VoiceStatus.CONNECTED else if(scene=="alice-connect-1")VoiceStatus.WAITING_LINK else VoiceStatus.UNLINKED,
                    selectedBake=if(linked)bake.bakeKey else null),recipeName=recipe.name,
                accountStep={YandexAccountPanel(YandexAccountState.SignedOut,false,{},{},{})},
                verifiedName=if(scene=="alice-connect-0")null else profile.displayName)}
        }
        scene=="station-timer"->{
            BakingPaper(bake,1661L,{},{},{},{},onOpenAlice={})
            StationTimerSheet(1661L,{})
        }
    }
}

/** Real photo-slot/share controls; only the renderer supplies synthetic private images. */
@Composable internal fun NativeFinaleFixture(recipe:Recipe,recipes:List<Recipe>,scene:String,photos:List<GalleryPhoto>) {
    val selected=if(scene=="bake-finale-photo")photos.firstOrNull()?.path else null
    var decision by remember{mutableStateOf(ShelfShareDecision.PUT_WITHOUT_PHOTO)}
    BakeFinalePage(BakeFinalePageState(recipe.name,1,2,RecipeScale.totalMinutes(recipe,2),recipe.timeline.size,
        1792731600000L,recipes.map{it.id==recipe.id},1),{},{},
        photo={BakeFinalePhoto(selected,photos,PhotoAttachmentActions({},{},{},{}),true,{})},
        extra={if(scene!="bake-finale-noaccount") {
            SmallLabel("НА ПОЛКУ «ТЕСТОВАЯ ПОЛКА»")
            BakeFinaleShareChoices(decision,{decision=it},selected!=null,false)
        }})
}
