package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.navigation.PlannedBake
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.BakeFinaleShareChoices
import com.polinalinen.madre.ui.screens.rememberFinaleShareSelection
import com.polinalinen.madre.account.yandex.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Functional changes only: real book assets, controls, state restoration and callbacks. No auth/network. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class Fixes7014UiTest {
    @get:Rule val rule=createComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val recipes by lazy{runBlocking{RecipeRepository(app).getRecipes()}}
    private val clock=Clock.fixed(Instant.parse("2026-10-24T13:40:00Z"),ZoneId.of("Europe/Moscow"))

    @Test fun actualIngredientWordsAreStemmedWithoutChangingTheirHumanNote() {
        val variants=listOf("мука","муки","муку","МУКА")
        val expected=BookCatalog.search(recipes,variants.first(),CatalogFilter.ALL).map{it.recipe.id}
        assertTrue(expected.size>1)
        variants.forEach{assertEquals(expected,BookCatalog.search(recipes,it,CatalogFilter.ALL).map{it.recipe.id})}
        val rye=BookCatalog.search(recipes,"рж",CatalogFilter.ALL)
        assertEquals(listOf("family_bread"),rye.map{it.recipe.id})
        assertEquals("ржаной муки",rye.single().ingredient)
        assertTrue(BookCatalog.search(recipes,"зефир",CatalogFilter.ALL).isEmpty())
    }

    @Test fun realTocExplainsRyeAndFlourMatchesAndRecoversFromAnEmptySearch() {
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){BookTocContent(recipes,emptyList(),null,{_,_->},{})}}
        rule.onNodeWithTag("toc-search").performTextReplacement("рж")
        rule.onNodeWithText("есть: ржаной муки",substring=true,useUnmergedTree=true).assertExists()
        rule.onNodeWithTag("toc-search").performTextReplacement("мука")
        rule.onNodeWithText("Такой главы нет").assertDoesNotExist()
        rule.onNodeWithTag("toc-search").performTextReplacement("зефир")
        rule.onNodeWithText("Такой главы нет").assertIsDisplayed()
        rule.onNodeWithText("Показать все главы").performClick()
        rule.onNodeWithText("Такой главы нет").assertDoesNotExist()
    }

    @Test fun futurePlanIsCountedInTheSameOvenAndDoesNothingOnComposition() {
        var starts=0;var snoozes=0
        val plan=PlannedBake(recipes.first().id,2,clock.millis()+3_600_000L)
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            BookOvenContent(recipes,emptyList(),listOf(plan),{},{},{},{},{},clock=clock,onStartPlan={starts++},onSnoozePlan={snoozes++})
        }}
        rule.onNodeWithText("Уже печём 1").assertIsDisplayed()
        rule.onNodeWithText("ЗАПЛАНИРОВАНО · ×2").assertExists()
        rule.onNodeWithText("начнём сегодня в 17:40").assertExists()
        rule.onNodeWithText("ПОКА НИЧЕГО НЕ ПЕЧЁМ").assertDoesNotExist()
        rule.onNodeWithText("Начать").assertDoesNotExist()
        assertEquals(0,starts);assertEquals(0,snoozes)
    }

    @Test fun overduePlanExposesSeparateActualCallbacksAndNeverStartsItself() {
        val plan=PlannedBake(recipes.first().id,2,clock.millis()-3_600_000L)
        var started:PlannedBake?=null;var snoozed:PlannedBake?=null
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT){
            BookOvenContent(recipes,emptyList(),listOf(plan),{},{},{},{},{},clock=clock,onStartPlan={started=it},onSnoozePlan={snoozed=it})
        }}
        rule.onNodeWithText("Уже печём 1").assertIsDisplayed()
        rule.onNodeWithText("ПОРА НАЧИНАТЬ · ×2").assertExists()
        assertNull(started);assertNull(snoozed)
        rule.onNodeWithText("Через 30 мин").performClick();assertEquals(plan,snoozed);assertNull(started)
        rule.onNodeWithText("Начать").performClick();assertEquals(plan,started)
    }

    @Test fun inFlightPlanActionsAreActuallyDisabledUntilTheMutationFinishes() {
        val busy=mutableStateOf(true)
        var calls=0
        val plan=PlannedBake(recipes.first().id,2,clock.millis()-3_600_000L)
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            BookOvenContent(recipes,emptyList(),listOf(plan),{},{},{},{},{},clock=clock,
                onStartPlan={calls++},onSnoozePlan={calls++},plansBusy=busy.value)
        }}
        rule.onNodeWithText("Начать").assertIsNotEnabled()
        rule.onNodeWithText("Через 30 мин").assertIsNotEnabled()
        assertEquals(0,calls)
        rule.runOnIdle{busy.value=false}
        rule.onNodeWithText("Через 30 мин").assertIsEnabled().performClick()
        rule.runOnIdle{assertEquals(1,calls)}
    }

    @Test fun photoDefaultTracksAvailabilityButExplicitPrivacyChoiceSurvivesRestoreAndPhotoUpdates() {
        val photo=mutableStateOf(false)
        val sharing=mutableStateOf(true)
        val restoration=StateRestorationTester(rule)
        restoration.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            val selection=rememberFinaleShareSelection("completion",sharing.value,photo.value)
            BakeFinaleShareChoices(selection.decision,photo.value,false,selection.choose)
        }}
        rule.onNodeWithText("С фото").assertIsNotEnabled()
        rule.onNodeWithText("Без фото").assertIsSelected()
        rule.runOnIdle{photo.value=true}
        rule.onNodeWithText("С фото").assertIsSelected().assertIsEnabled()
        rule.onNodeWithText("Полка увидит фото и запись").assertIsDisplayed()
        rule.onNodeWithText("Только мне").performClick()
        rule.runOnIdle{photo.value=false};rule.runOnIdle{photo.value=true}
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Только мне").assertIsSelected()
        rule.onNodeWithText("Запись увидите только вы").assertIsDisplayed()
        rule.onNodeWithText("Без фото").performClick()
        rule.runOnIdle{sharing.value=false};rule.runOnIdle{sharing.value=true;photo.value=false}
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle{photo.value=true}
        rule.onNodeWithText("Без фото").assertIsSelected()
        rule.onNodeWithText("Полка увидит запись без фото").assertIsDisplayed()
    }

    @Test fun chapterLastPageHasAVisibleSelectedTargetAndAllSixTargetsFit() {
        val prefs=ReadingPreferences(app,"fixes14-reader")
        rule.setContent{RazvorotTheme(prefs,force=Atmosphere.DAWN){
            RazvorotReader(recipes,recipes.first().id,prefs,{},{},{},initialPage=Int.MAX_VALUE,scaleFactor=2.0)
        }}
        rule.waitForIdle()
        val state=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val count=state.substringAfter(" из ").toInt()
        assertEquals("Страница $count из $count",state)
        assertEquals(6,count)
        for(index in 1..count) {
            val target=rule.onNode(SemanticsMatcher("page $index target"){
                it.config.getOrNull(SemanticsActions.OnClick)?.label=="Страница $index"
            }).assertIsDisplayed()
            if(index==count)target.assertIsSelected() else target.assertIsNotSelected()
        }
    }

    @Test @Config(qualifiers="ru-w393dp-h851dp-mdpi")
    fun hiddenReaderRecreationKeepsTheActualPageContentAndCount() {
        val prefs=ReadingPreferences(app,"fixes7014-hidden-recreate")
        val recipe=recipes.single{it.id=="home_bread"}
        val restoration=StateRestorationTester(rule)
        restoration.setContent{RazvorotTheme(prefs,force=Atmosphere.DAWN){
            RazvorotReader(recipes,recipe.id,prefs,{},{},{})
        }}
        rule.waitForIdle()
        val next=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsActions.CustomActions].first{it.label=="Следующая страница"}
        rule.runOnIdle{assertTrue(next.action())}
        rule.onNodeWithTag("paper-turn").performTouchInput{click(center)}
        rule.waitForIdle()
        rule.onNodeWithContentDescription("В оглавление").assertDoesNotExist()
        val before=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config
        val page=before[SemanticsProperties.StateDescription]
        val content=before[SemanticsProperties.ContentDescription]
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        val after=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config
        assertEquals(page,after[SemanticsProperties.StateDescription])
        assertEquals(content,after[SemanticsProperties.ContentDescription])
        rule.onNodeWithContentDescription("В оглавление").assertDoesNotExist()
    }

    @Test fun accountAndPairingStateSelectExactlyOneCurrentConnectionStep() {
        val name=mutableStateOf<String?>(null)
        val waiting=mutableStateOf(true)
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            com.polinalinen.madre.ui.screens.AliceConnectionSteps(waiting.value,{},accountStep={},verifiedName=name.value)
        }}
        fun current(index:Int) {
            for(n in 1..3)if(n==index)rule.onNodeWithTag("alice-connection-step-$n").assertIsSelected()
                else rule.onNodeWithTag("alice-connection-step-$n").assertIsNotSelected()
        }
        current(1)
        rule.runOnIdle{name.value="Тестовый пекарь";waiting.value=false};current(2)
        rule.runOnIdle{waiting.value=true};current(3)
    }

    @Test fun compactUnlinkedKeepsAllFactsAndCallbacksWithLargeText() {
        var logins=0;var help=0
        rule.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                RazvorotTheme(force=Atmosphere.DAWN) {PaperSheet({}) {
                    AliceSheetContent(AliceSheetMode.UNLINKED,
                        com.polinalinen.madre.voice.VoiceUiState(com.polinalinen.madre.voice.VoiceStatus.UNLINKED),
                        recipeName="Рецепт уже виден на фоне",onConnect={help++},
                        accountStep={NativeAliceSignedOutStep{browser->assertFalse(browser);logins++}})
                }}
            }
        }
        rule.onNodeWithText("Рецепт уже виден на фоне").assertDoesNotExist()
        rule.onNodeWithText("Один раз. Потом шаги можно слушать и спрашивать голосом.").assertDoesNotExist()
        for(n in 1..3) {
            val step=rule.onNodeWithTag("alice-connection-step-$n").assertExists()
            if(n==1)step.assertIsSelected() else step.assertIsNotSelected()
        }
        rule.onNodeWithText("тем же аккаунтом, что на Станции").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Войти с Яндекс ID").performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick){assertTrue(it())}
        rule.runOnIdle{assertEquals(1,logins);assertEquals(0,help)}
        rule.onNodeWithText("Только имя, портрет и номер аккаунта. Пароль Яндекс не передаёт. Книга остаётся на телефоне.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("«${com.polinalinen.madre.ui.screens.AliceCopy.LAUNCH_PHRASE}»").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("В приложении Яндекс · со Станции придёт пуш. Если появился код — подтвердите его на телефоне.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("НЕ ВЫХОДИТ?").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick){assertTrue(it())}
        rule.runOnIdle{assertEquals(1,help);assertEquals(1,logins)}
        rule.onNodeWithText("Работает, пока Мадре открыта на телефоне").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Вход с Яндекс ID пока недоступен",substring=true).assertDoesNotExist()
    }

    @Test fun baselineUnlinkedFixtureIsExplicitlySignedOutWithTheRealEnabledControl() {
        rule.setContent{RazvorotDebugScene("alice-sheet-unlinked",recipes,forceAtmosphere=Atmosphere.NIGHT)}
        rule.onNodeWithContentDescription("Войти с Яндекс ID").assertIsEnabled().assertIsDisplayed()
        rule.onNodeWithText("Вход с Яндекс ID пока недоступен",substring=true).assertDoesNotExist()
        rule.onNodeWithTag("alice-connection-step-1").assertIsSelected()
        rule.onNodeWithTag("alice-connection-step-2").assertIsNotSelected()
        rule.onNodeWithTag("alice-connection-step-3").assertIsNotSelected()
    }

    @Test fun threeAccountErrorsShowExactCopyAndRetryUsesItsRealAction() {
        val error=mutableStateOf(YandexAccountError.NETWORK)
        var attempts=0
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            YandexAccountPanel(YandexAccountState.Error(error.value),false,{attempts++},{},{})
        }}
        val cases=listOf(YandexAccountError.NETWORK to "Нет связи — попробуем, когда появится сеть",
            YandexAccountError.YANDEX to "Яндекс ID не ответил. Попробуйте ещё раз — книга работает и без входа",
            YandexAccountError.SERVER to "Вход прошёл, но сервер Мадре не ответил. Повторим сами")
        cases.forEach{(kind,copy)->
            rule.runOnIdle{error.value=kind}
            rule.onNodeWithText(copy).assertIsDisplayed()
            rule.onNodeWithText("Вход не удался").assertIsDisplayed()
        }
        assertEquals(0,attempts)
        rule.onNodeWithText("ПОВТОРИТЬ").performClick()
        rule.runOnIdle{assertEquals(1,attempts)}
    }
}
