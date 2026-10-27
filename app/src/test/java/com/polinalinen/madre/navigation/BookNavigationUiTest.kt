package com.polinalinen.madre.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.data.repository.activeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.razvorot.RazvorotReader
import com.polinalinen.madre.ui.screens.Home
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BookNavigationUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val recipes by lazy { runBlocking{RecipeRepository(app).getRecipes()} }
    @Before fun setup() { runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())} }
    @After fun clearControllers() { rule.activity.viewModelStore.clear() }

    private fun bindRealController(replaceStoredController:Boolean=false):BakingViewModel {
        lateinit var controller:BakingViewModel
        rule.activityRule.scenario.onActivity { activity ->
            assertSame(app,activity.application)
            // Bind the actual fixture owner, never a cached Application from another test.
            val factory=ViewModelProvider.AndroidViewModelFactory(app)
            if(replaceStoredController) {
                // Kill only the real baking VM: Navigation must retain its live store until onSave.
                val store=activity.viewModelStore
                val key=store.keys().single{it.endsWith(BakingViewModel::class.java.canonicalName!!)}
                val previous=requireNotNull(store[key])
                val retained=store.keys().filter{it!=key}.associateWith{store[it]}
                store.put(key,factory.create(BakingViewModel::class.java))
                assertFalse(previous.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
                retained.forEach{(retainedKey,viewModel)->assertSame(viewModel,store[retainedKey])}
            }
            controller=ViewModelProvider(activity,factory)[BakingViewModel::class.java]
        }
        assertSame(app,controller.getApplication<MadreApplication>())
        return controller
    }

    @Test fun realGraphOpensFifthChapterAndRestoresItsPageAndTocSearch() {
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        restoration.setContent { nav=rememberNavController();MadreNavHost(nav) }
        rule.waitUntil(10_000){rule.onAllNodesWithTag(Home.chapterRowTag("ciabatta")).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("book-cover").assertDoesNotExist();rule.onNodeWithTag("chapter-fan").assertDoesNotExist()
        rule.onNodeWithTag("toc-search").performTextReplacement("Чиабатта")
        rule.onNodeWithTag(Home.chapterRowTag("ciabatta"))
            .assertContentDescriptionEquals("Глава 5, Чиабатта, 7 часов 40 минут")
            .assertHasClickAction()
        rule.onNodeWithTag(Home.chapterRowTag("ciabatta")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.CHAPTER,nav.currentDestination?.route)
        assertEquals("ciabatta",nav.currentBackStackEntry?.arguments?.getString("recipeId"))
        assertEquals(-1,nav.currentBackStackEntry?.arguments?.getInt("page"))
        rule.onNodeWithTag("paper-turn").assertIsDisplayed()
        rule.onNode(SemanticsMatcher("page2 action") { it.config.getOrNull(SemanticsActions.OnClick)?.label=="Страница 2" }).performClick()
        rule.waitForIdle()
        val page=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(page.startsWith("Страница 2 из "))
        // Layout is now taller by the real ingredient title/portion block. Page count alone
        // cannot prove isolation/completeness: walk the actual chapter and require every
        // scaled ingredient and every complete instruction exactly once, without neighbours.
        val count=page.substringAfter(" из ").toInt()
        assertTrue(count>=3)
        val recipe=recipes.single{it.id=="ciabatta"}
        val factor=BookPreferences(app).use{it.portions(recipe.id).toDouble()}
        val bindings=RecipeScaler.scalableBindings(recipe)
        val expected=linkedMapOf<String,String>()
        recipe.ingredients.forEach{(section,items)->expected[com.polinalinen.madre.utils.ingredientSectionTitle(section)]=
            items.joinToString("\n"){RecipeScaler.scaledDisplayText(it,factor)}}
        recipe.timeline.forEachIndexed{index,step->expected["%02d · %s · %d мин".format(index+1,step.title,step.durationMinutes)]=
            RecipeScaler.scaledStepText(step.description,factor,bindings)}
        if(recipe.servingTips.isNotEmpty())expected["К столу"]=recipe.servingTips.joinToString("\n"){it.text}
        if(recipe.variations.isNotEmpty())expected["Варианты"]=recipe.variations.joinToString("\n"){it.text}
        val header=Regex("(?m)^("+expected.keys.joinToString("|"){Regex.escape(it)}+")(?: · продолжение)?\n")
        val actual=linkedMapOf<String,MutableList<String>>()
        fun turn(label:String) {
            val action=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsActions.CustomActions].first{it.label==label}
            rule.runOnIdle{assertTrue(action.action())};rule.waitForIdle()
        }
        for(index in 2..count) {
            val node=rule.onNodeWithTag("paper-turn").fetchSemanticsNode()
            assertEquals("Страница $index из $count",node.config[SemanticsProperties.StateDescription])
            val descriptions=node.config[SemanticsProperties.ContentDescription]
            assertFalse("Neighbouring title leaked onto page $index",recipes.filter{it.id!=recipe.id}.any{it.name in descriptions})
            val content=descriptions.first()
            if(index==count)assertEquals("Конец главы 5. Теперь — испечь.",content)
            else {
                val matches=header.findAll(content).toList()
                assertTrue("No real recipe block on page $index: $content",matches.isNotEmpty())
                assertEquals(0,matches.first().range.first)
                matches.forEachIndexed{i,match->actual.getOrPut(match.groupValues[1]){mutableListOf()}.add(
                    content.substring(match.range.last+1,matches.getOrNull(i+1)?.range?.first?:content.length).trim())}
            }
            if(index<count)turn("Следующая страница")
        }
        fun normalized(value:String)=value.replace(Regex("\\s+")," ").trim()
        assertEquals(expected.keys,actual.keys)
        expected.forEach{(heading,body)->assertEquals("Truncated/duplicated real block: $heading",
            normalized(body),normalized(actual.getValue(heading).joinToString(" ")))}
        // Restore page2 via real reader actions, then retain the original exact restoration test.
        repeat(count-2){turn("Предыдущая страница")}
        assertEquals(page,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(page,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithContentDescription("В оглавление").performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        rule.onNodeWithTag("toc-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Чиабатта")))
        assertEquals("ciabatta",BookPreferences(app).use{it.bookmark?.recipeId})
    }
    @Test fun chapterOpenedFromOvenReturnsToTocAndItsOwnStartSheetDoesNotDuplicateChapter() {
        BookPreferences(app).use{it.visit(1)}
        lateinit var nav:NavHostController
        rule.setContent{nav=rememberNavController();MadreNavHost(nav)}
        val recipe=recipes.first()
        val configure=SemanticsMatcher("configure actual oven tile"){
            it.config.getOrNull(SemanticsActions.OnClick)?.label=="Настроить выпечку: ${recipe.name}"
        }
        rule.waitUntil(10_000){rule.onAllNodes(configure).fetchSemanticsNodes().isNotEmpty()}
        rule.onNode(configure).performScrollTo().performClick()
        rule.onNodeWithText("ОТКРЫТЬ ГЛАВУ").performClick();rule.waitForIdle()
        assertEquals(MadreDestinations.CHAPTER,nav.currentDestination?.route)
        val entry=nav.currentBackStackEntry
        rule.onNodeWithContentDescription("Испечь ко времени").performClick()
        rule.onNodeWithText("Ко времени").assertIsSelected()
        rule.onNodeWithText("ОТКРЫТЬ ГЛАВУ").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack();rule.waitForIdle()
        assertSame(entry,nav.currentBackStackEntry)
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        assertEquals(0,nav.currentBackStackEntry?.savedStateHandle?.get<Int>("home_section"))
        rule.onNodeWithTag("toc-search").assertIsDisplayed()
    }

    @Test fun graphInitializesFromDefaultAndRestoresManualReaderAmount() {
        val recipe=recipes.first()
        BookPreferences(app).use{it.savePortions(recipe.id,3);it.saveDefaultPortions(4)}
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        restoration.setContent{nav=rememberNavController();MadreNavHost(nav)}
        rule.waitUntil(10_000){rule.onAllNodesWithTag(Home.chapterRowTag(recipe.id)).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag(Home.chapterRowTag(recipe.id)).performScrollTo().performClick()
        rule.onNodeWithText("Испечь · ×4",ignoreCase=true).assertExists()
        rule.onNodeWithText("+").performClick()
        rule.runOnIdle{BookPreferences(app).use{it.saveDefaultPortions(1)}}
        rule.onNodeWithText("Испечь · ×5",ignoreCase=true).assertExists()
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        rule.onNodeWithText("Испечь · ×5",ignoreCase=true).assertExists()
        assertEquals(MadreDestinations.CHAPTER,nav.currentDestination?.route)
        assertTrue(runBlocking{app.activeBakeRepository.all()}.isEmpty())
        BookPreferences(app).use{assertEquals(1,it.defaultPortions);assertEquals(1,it.portions(recipe.id))}
        assertEquals(5,app.getSharedPreferences("madre_prefs",0).getInt("chapter_portions_${recipe.id}",-1))
        rule.onNodeWithContentDescription("В оглавление").performClick()
        rule.onNodeWithTag(Home.chapterRowTag(recipe.id)).performScrollTo().performClick()
        rule.onNodeWithText("Испечь · ×1",ignoreCase=true).assertExists()
    }

    @Test fun historicalSheetAmountDoesNotOverrideNewOvenActionsAndLiveSheetKeepsManualAmount() {
        val recipe=recipes.first()
        val raw=app.getSharedPreferences("madre_prefs",0)
        val key="chapter_portions_${recipe.id}"
        BookPreferences(app).use{it.saveDefaultPortions(4);it.visit(1)}
        val controller=bindRealController()
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        restoration.setContent{nav=rememberNavController();MadreNavHost(nav)}
        val configure=SemanticsMatcher("configure actual oven tile"){
            it.config.getOrNull(SemanticsActions.OnClick)?.label=="Настроить выпечку: ${recipe.name}"
        }
        fun openSheet() {
            rule.waitUntil(10_000){rule.onAllNodes(configure).fetchSemanticsNodes().isNotEmpty()}
            rule.onNode(configure).performScrollTo().performClick()
        }
        fun startSheet(amount:Int) {
            rule.onNode(hasText("Начать",substring=true) and hasText("· ×$amount",substring=true))
                .performScrollTo().performClick()
        }
        fun awaitBakes(count:Int) {
            rule.waitUntil(10_000){
                val ready=rule.onAllNodesWithText("Дальше →").fetchSemanticsNodes().isNotEmpty()
                ready && controller.sessions.value.size==count
            }
            assertEquals(MadreDestinations.BAKE,nav.currentDestination?.route)
        }
        fun returnToOven() {
            rule.runOnIdle{assertTrue(nav.popBackStack())};rule.waitForIdle()
            assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        }
        openSheet()
        rule.onNodeWithText("×4").assertExists()
        startSheet(4) // No stepper interaction: the real success path still auto-saves chapter_portions.
        awaitBakes(1)
        val old=controller.sessions.value.single()
        assertEquals(4,old.trustedPortions);assertEquals(4,raw.getInt(key,-1))
        returnToOven()
        rule.runOnIdle{BookPreferences(app).use{it.saveDefaultPortions(1)}}
        openSheet();rule.onNodeWithText("×1").assertExists()
        androidx.test.espresso.Espresso.pressBack();rule.waitForIdle()
        BookPreferences(app).use {
            it.saveDefaultPortions(null)
            assertEquals(4,it.portions(recipe.id));assertEquals(4,raw.getInt(key,-1))
            it.saveDefaultPortions(1)
        }
        rule.onNodeWithContentDescription("Испечь ${recipe.name} сейчас").performScrollTo().performClick()
        awaitBakes(2)
        assertEquals(listOf(1,4),controller.sessions.value.map{requireNotNull(it.trustedPortions)}.sorted())
        assertEquals(4,raw.getInt(key,-1))
        returnToOven()
        openSheet();rule.onNodeWithText("×1").assertExists()
        rule.onNodeWithText("+").performClick()
        rule.runOnIdle{BookPreferences(app).use{it.saveDefaultPortions(5)}}
        rule.onNodeWithText("×2").assertExists()
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        rule.onNodeWithText("×2").assertExists()
        startSheet(2);awaitBakes(3)
        assertEquals(listOf(1,2,4),controller.sessions.value.map{requireNotNull(it.trustedPortions)}.sorted())
        assertEquals(listOf(1.0,2.0,4.0),runBlocking{app.activeBakeRepository.all()}.map{it.scaleFactor}.sortedBy{it})
        assertEquals(4,controller.session(old.id)!!.trustedPortions)
    }

    @Test fun chapterPlusUpdatesActualIngredientWithoutTurningThePage() {
        val recipe=recipes.single{it.id=="home_bread"}
        val ingredient=recipe.ingredients.values.flatten().first{it.scalable&&it.refType==null&&it.amount>0}
        val prefs=ReadingPreferences(app,"nav-ui-${UUID.randomUUID()}").apply{choosePortions(2f)}
        var factor by mutableStateOf(2.0)
        rule.setContent { RazvorotTheme(prefs,force=Atmosphere.DAWN) {
            RazvorotReader(recipes,recipe.id,prefs,{},{},{},initialPage=1,scaleFactor=factor,onScaleFactor={factor=it})
        } }
        rule.waitForIdle()
        val before=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        rule.onNodeWithText("+").performClick();rule.waitForIdle()
        assertEquals(3.0,factor,.00001)
        assertEquals(before,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val expected=RecipeScaler.scaledDisplayText(ingredient,3.0)
        rule.onNodeWithTag("paper-turn").assert(SemanticsMatcher("real scaled ingredient") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{line->line.contains(expected)}==true
        })
    }
    @Test fun notificationReturnsToOvenAndRestorationKeepsTheActualRoomBake() {
        val recipe=runBlocking{app.recipeRepository.getRecipes()}.first()
        val fixture=BakingSession.start(9001L,recipe,2.0).copy(recipeRevision=app.recipeRepository.revision)
        runBlocking{app.activeBakeRepository.commit(fixture.id,null,fixture.activeSnapshot())}
        val requested=MutableStateFlow<Long?>(fixture.id)
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        var controller=bindRealController()
        fun awaitActualBake() {
            rule.waitForIdle()
            try {
                rule.waitUntil(10_000) {
                    // Fetch semantics first: a short-circuited route check never synchronizes the host looper.
                    val ready=rule.onAllNodesWithText("Дальше →").fetchSemanticsNodes().isNotEmpty()
                    ready && nav.currentDestination?.route==MadreDestinations.BAKE
                }
            } catch(failure:Throwable) {
                println("Synthetic navigation fixture: route=${nav.currentDestination?.route}, pending=${requested.value}, controllerReady=${controller.restoreReady.value}, controllerSessions=${controller.sessions.value.map{it.id}}, rows=${runBlocking{app.activeBakeRepository.all()}.map{it.sessionId to it.recipeId}}")
                throw failure
            }
        }
        restoration.setContent {nav=rememberNavController();MadreNavHost(nav,pendingSessionId=requested)}
        awaitActualBake()
        assertEquals(fixture.id.toString(),nav.currentBackStackEntry?.arguments?.getString("sessionId"))
        assertNull(requested.value)
        val firstController=controller
        controller=bindRealController(replaceStoredController=true)
        assertNotSame(firstController,controller)
        restoration.emulateSavedInstanceStateRestore()
        awaitActualBake()
        assertEquals(fixture.id.toString(),nav.currentBackStackEntry?.arguments?.getString("sessionId"))
        assertEquals(fixture.bakeKey,runBlocking{app.activeBakeRepository.all()}.single{it.sessionId==fixture.id}.bakeKey)
        rule.runOnIdle{assertTrue(nav.popBackStack())}
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        assertEquals(1,nav.currentBackStackEntry?.savedStateHandle?.get<Int>("home_section"))
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
    }
    @Test fun overdueStartButtonOwnsTheActualBakeAndRemovesOnlyItsPlan() {
        val recipe=recipes.first()
        val plan=PlannedBake(recipe.id,2,System.currentTimeMillis()-3_600_000L)
        BookPreferences(app).use{assertTrue(it.putPlan(plan));it.visit(1)}
        val controller=bindRealController()
        lateinit var nav:NavHostController
        rule.setContent{nav=rememberNavController();MadreNavHost(nav)}
        rule.waitUntil(10_000){rule.onAllNodesWithText("ПОРА НАЧИНАТЬ · ×2").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(runBlocking{app.activeBakeRepository.all()}.isEmpty())
        rule.onNodeWithText("Начать").performClick()
        rule.waitUntil(10_000){
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithText("Дальше →").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(MadreDestinations.BAKE,nav.currentDestination?.route)
        val session=controller.sessions.value.single()
        assertEquals(recipe.id,session.recipe.id);assertEquals(2,session.trustedPortions)
        assertEquals(UUID.nameUUIDFromBytes("planned-bake:${plan.id}".toByteArray(Charsets.UTF_8)).toString(),session.bakeKey)
        assertSame(session,controller.sessionForPlan(plan.id))
        assertEquals(session.bakeKey,runBlocking{app.activeBakeRepository.all()}.single().bakeKey)
        BookPreferences(app).use{assertTrue(it.plans.isEmpty())}
    }

    @Test fun delayedPlanStartSurvivesCompositionRecreationAndSerializesNotificationSnooze() {
        val plan=PlannedBake(recipes.first().id,2,System.currentTimeMillis()-3_600_000L)
        val snoozeIntent=BookPreferences(app).use{prefs->
            assertTrue(prefs.putPlan(plan));prefs.visit(1)
            com.polinalinen.madre.notifications.PlannedBakeActions.intent(app,plan,
                com.polinalinen.madre.notifications.PlannedBakeAction.SNOOZE,prefs.notificationNonce(plan.id,plan))
        }
        val controller=bindRealController()
        val pending=MutableStateFlow<com.polinalinen.madre.notifications.PlannedBakeRequest?>(null)
        val restoration=StateRestorationTester(rule)
        restoration.setContent{MadreNavHost(pendingPlanned=pending)}
        rule.waitUntil(10_000){rule.onAllNodesWithText("ПОРА НАЧИНАТЬ · ×2").fetchSemanticsNodes().isNotEmpty() && controller.restoreReady.value}
        val locked=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val barrier=Thread{
            synchronized(app.activeBakeRepository.commitFence){
                locked.countDown();check(release.await(30,java.util.concurrent.TimeUnit.SECONDS))
            }
        }.apply{start()}
        assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        var notificationFinishedBeforeCommit=false
        try {
            rule.onNodeWithText("Начать").performClick();rule.waitForIdle()
            rule.onNodeWithText("Начать").assertIsNotEnabled()
            assertNull(controller.sessionForPlan(plan.id))
            restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
            val request=requireNotNull(com.polinalinen.madre.notifications.PlannedBakeActions.read(app,snoozeIntent))
            rule.runOnIdle{pending.value=request}
            try {
                rule.waitUntil(2_000){rule.onRoot().fetchSemanticsNode();pending.value==null}
                notificationFinishedBeforeCommit=true
            } catch(_:androidx.compose.ui.test.ComposeTimeoutException) { /* Correct: SNOOZE is waiting behind durable START. */ }
        } finally {release.countDown();barrier.join(10_000)}
        rule.waitUntil(10_000){rule.onRoot().fetchSemanticsNode();controller.sessionForPlan(plan.id)!=null && pending.value==null}
        assertFalse("Notification SNOOZE escaped the retained START transaction",notificationFinishedBeforeCommit)
        BookPreferences(app).use{assertTrue("A started bake must not leave a moved future plan",it.plans.isEmpty())}
        assertEquals(1,controller.sessions.value.size)
        assertEquals(1,runBlocking{app.activeBakeRepository.all()}.size)
        assertNull(com.polinalinen.madre.notifications.PlannedBakeActions.read(app,snoozeIntent))
        rule.runOnIdle{pending.value=com.polinalinen.madre.notifications.PlannedBakeRequest(plan.id,
            com.polinalinen.madre.notifications.PlannedBakeAction.START)}
        rule.waitUntil(10_000){rule.onRoot().fetchSemanticsNode();pending.value==null}
        assertEquals(1,runBlocking{app.activeBakeRepository.all()}.size)
    }

    @Test fun overdueSnoozeReadsFreshClockAndPersistsReminderWithoutCreatingABake() {
        val now=java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val clock=object:java.time.Clock(){
            override fun getZone()=java.time.ZoneId.of("Europe/Moscow")
            override fun withZone(zone:java.time.ZoneId)=java.time.Clock.fixed(instant(),zone)
            override fun instant()=java.time.Instant.ofEpochMilli(now.get())
        }
        val plan=PlannedBake(recipes.first().id,2,now.get()-3_600_000L)
        BookPreferences(app).use{assertTrue(it.putPlan(plan));it.visit(1)}
        bindRealController()
        rule.setContent{CompositionLocalProvider(com.polinalinen.madre.ui.razvorot.LocalBookClock provides clock){MadreNavHost()}}
        rule.waitUntil(10_000){rule.onAllNodesWithText("Через 30 мин").fetchSemanticsNodes().isNotEmpty()}
        now.addAndGet(20*60_000L)
        rule.onNodeWithText("Через 30 мин").performClick()
        rule.waitUntil(10_000){
            // The retained owner resumes on Main; prefs-only polling does not drain Robolectric's paused UI queue.
            rule.onRoot().fetchSemanticsNode()
            BookPreferences(app).use{it.plans.singleOrNull()?.id!=plan.id}
        }
        val moved=BookPreferences(app).use{it.plans.single()}
        assertEquals(now.get()+40*60_000L,moved.startAtMillis)
        assertEquals(now.get()+30*60_000L,BakePlanning.reminderAt(moved,now.get()))
        assertEquals(plan.recipeId,moved.recipeId);assertEquals(plan.portions,moved.portions)
        assertTrue(runBlocking{app.activeBakeRepository.all()}.isEmpty())
    }

    @Test fun actualAdvanceUndoFloatsAboveTheMeasuredActionBarAndKeepsTheRealRevision() {
        val recipe=recipes.first()
        val fixture=BakingSession.start(9101L,recipe,2.0).copy(currentStepIndex=9,recipeRevision=app.recipeRepository.revision)
        runBlocking{app.activeBakeRepository.commit(fixture.id,null,fixture.activeSnapshot())}
        val controller=bindRealController()
        val requested=MutableStateFlow<Long?>(fixture.id)
        rule.setContent{MadreNavHost(pendingSessionId=requested)}
        rule.waitUntil(10_000){rule.onAllNodesWithText("Дальше →").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Дальше →").performClick()
        // ADVANCE publishes only after asynchronous Room commit, never synchronously with the tap.
        rule.waitUntil(10_000){rule.onNodeWithText("Шаг 11 · Остывание").isDisplayed()}
        rule.onNodeWithText("Шаг 11 · Остывание").assertIsDisplayed()
        val oldRevision=controller.sessions.value.single().revision
        val snack=rule.onNodeWithTag("paper-snackbar").fetchSemanticsNode().boundsInRoot
        val bar=rule.onNodeWithTag("bake-action-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Undo overlaps measured pause/next bar",snack.bottom<bar.top)
        rule.onNodeWithText("ВЕРНУТЬ").performClick()
        rule.waitUntil(10_000){
            // Drain the paused Compose/Main queue as the real Undo action resumes its coroutine.
            rule.onAllNodesWithText("Дальше →").fetchSemanticsNodes()
            controller.sessions.value.single().currentStepIndex==9
        }
        assertTrue(controller.sessions.value.single().revision>oldRevision)
    }

    @Test fun plannedNotificationOpensOvenAndPauseKeepsItsSection() {
        val recipe=runBlocking{app.recipeRepository.getRecipes()}.first()
        val plan=PlannedBake(recipe.id,2,System.currentTimeMillis()+3_600_000L)
        BookPreferences(app).use{assertTrue(it.putPlan(plan))}
        bindRealController()
        val requested=MutableStateFlow<com.polinalinen.madre.notifications.PlannedBakeRequest?>(
            com.polinalinen.madre.notifications.PlannedBakeRequest(plan.id,com.polinalinen.madre.notifications.PlannedBakeAction.OPEN))
        lateinit var nav:NavHostController
        rule.setContent{nav=rememberNavController();MadreNavHost(nav,pendingPlanned=requested)}
        rule.waitUntil(10_000){rule.onAllNodesWithText("Убрать план").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        assertNull(requested.value)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        BookPreferences(app).use{assertEquals(1,it.initialTab())}
    }

    @Test fun overlaysAndFinaleStayOnBakeAndColdHomeKeepsTheExistingRecord() {
        val recipe=runBlocking{app.recipeRepository.getRecipes()}.first()
        val fixture=BakingSession.start(9001L,recipe,2.0).copy(currentStepIndex=recipe.timeline.lastIndex,
            recipeRevision=app.recipeRepository.revision)
        runBlocking{app.activeBakeRepository.commit(fixture.id,null,fixture.activeSnapshot())}
        val requested=MutableStateFlow<Long?>(fixture.id)
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        val firstController=bindRealController()
        restoration.setContent{nav=rememberNavController();MadreNavHost(nav,pendingSessionId=requested)}
        rule.waitUntil(10_000){rule.onAllNodesWithContentDescription("Открыть большой таймер").fetchSemanticsNodes().isNotEmpty()}
        val bakeRoute=MadreDestinations.BAKE
        rule.onNodeWithContentDescription("Открыть большой таймер").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher("close actual FullTimer"){
            it.config.getOrNull(SemanticsActions.OnClick)?.label=="Свернуть таймер"
        }).assertIsDisplayed()
        assertEquals(bakeRoute,nav.currentDestination?.route)
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Действия выпечки").performClick()
        rule.onNodeWithText("Бросить выпечку").assertExists()
        // A sheet owns another Window. Dispatch real Back to the focused window, not beneath it to Activity.
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithText("Бросить выпечку").assertDoesNotExist()
        assertEquals(bakeRoute,nav.currentDestination?.route)
        rule.onNodeWithText("Испечено").performClick()
        val ledger=com.polinalinen.madre.utils.PrefsBakeSessionLedger(app.getSharedPreferences("madre_prefs",0))
        rule.waitUntil(10_000){
            val ready=rule.onAllNodesWithText("Вписать в книгу").fetchSemanticsNodes().isNotEmpty()
            ready && ledger.recordIdFor(fixture.id)!=null
        }
        val recordId=ledger.recordIdFor(fixture.id)!!
        assertEquals(bakeRoute,nav.currentDestination?.route)
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        assertEquals(1,nav.currentBackStackEntry?.savedStateHandle?.get<Int>("home_section"))
        assertEquals(recordId,ledger.recordIdFor(fixture.id))
        rule.onNodeWithText("ИСПЕЧЕНО · ВПИШИТЕ В КНИГУ").assertExists()
        assertNotSame(firstController,bindRealController(replaceStoredController=true))
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(10_000){rule.onAllNodesWithText("Вписать в книгу").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("ИСПЕЧЕНО · ВПИШИТЕ В КНИГУ").assertExists()
        assertEquals(recordId,ledger.recordIdFor(fixture.id))
        assertEquals(listOf(recordId),runBlocking{app.bakeHistoryRepository.observeAll().first()}.map{it.id})
        rule.onNodeWithText("Вписать в книгу").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(bakeRoute,nav.currentDestination?.route)
        rule.onNodeWithText("Вписать в книгу").assertIsDisplayed().performClick()
        rule.waitUntil(10_000){
            rule.onAllNodesWithText("Что испечь",substring=true).fetchSemanticsNodes()
            nav.currentDestination?.route==MadreDestinations.HOME && ledger.recordIdFor(fixture.id)==null
        }
        assertEquals(listOf(recordId),runBlocking{app.bakeHistoryRepository.observeAll().first()}.map{it.id})
    }

    @Test fun fontScale200UsesTheSameChapterScrollRendererAndNotOtherChapters() {
        val recipe=recipes.single{it.id=="ciabatta"}
        val prefs=ReadingPreferences(app,"nav-large-${UUID.randomUUID()}")
        rule.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                RazvorotTheme(prefs,force=Atmosphere.NIGHT) {
                    RazvorotReader(recipes,recipe.id,prefs,{},{},{},scaleFactor=2.0)
                }
            }
        }
        rule.onNodeWithTag("reader-scroll").assertIsDisplayed()
        rule.onNodeWithTag("paper-turn").assertDoesNotExist()
        recipes.filter{it.id!=recipe.id}.forEach{rule.onAllNodesWithText(it.name).assertCountEquals(0)}
        rule.onNodeWithText("Как печём").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("редактировать от руки").assertDoesNotExist()
    }

    @Test fun largeTypeBookmarkPageOpensActualIngredientsRatherThanTheTitle() {
        val recipe=recipes.single{it.id=="ciabatta"}
        val prefs=ReadingPreferences(app,"nav-bookmark-scroll-${UUID.randomUUID()}")
        var bookmark:Int?=null
        rule.setContent {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                RazvorotTheme(prefs,force=Atmosphere.DAWN) {
                    RazvorotReader(recipes,recipe.id,prefs,{},{},{},initialPage=1,
                        onPageChanged={bookmark=it},scaleFactor=2.0)
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("reader-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()>0f)
        rule.onNode(hasText(recipe.name) and hasAnyAncestor(hasTestTag("reader-scroll"))).assertIsNotDisplayed()
        rule.waitUntil(5_000){rule.onNodeWithTag("reader-scroll").fetchSemanticsNode();bookmark==1}
    }

    @Test fun largeTypeStepSeekRestoresActualTextOffsetAndFooterMovesTheScrollContent() {
        val recipe=recipes.single{it.id=="ciabatta"}
        val prefs=ReadingPreferences(app,"nav-step-scroll-${UUID.randomUUID()}")
        val restoration=StateRestorationTester(rule)
        var actualNavigation:com.polinalinen.madre.ui.screens.ChapterScrollNavigation?=null
        restoration.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                RazvorotTheme(prefs,force=Atmosphere.NIGHT) {
                    RazvorotReader(recipes,recipe.id,prefs,{},{},{},initialStep=2,scaleFactor=2.0,
                        scrollContent={r,navigation->
                            SideEffect{actualNavigation=navigation}
                            com.polinalinen.madre.ui.screens.RecipeChapterContent(r,2.0,textSize=prefs.textSize,navigation=navigation)
                        })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(recipe.timeline[2].title.uppercase()).assertIsDisplayed()
        fun scrollOffset()=rule.onNodeWithTag("reader-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val position=scrollOffset();assertTrue(position>0f)
        fun cursorDescription()="${actualNavigation?.request}/${actualNavigation?.cursor?.processed}/${actualNavigation?.cursor?.geometry}/${actualNavigation?.anchor}/seek=${actualNavigation?.seekAnchor}/max=${actualNavigation?.cursor?.scroll?.maxValue}/pending=${actualNavigation?.cursor?.pendingRestore}"
        val before=cursorDescription()
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        val after=cursorDescription()
        assertEquals("Actual reader cursor before=$before after=$after",position,scrollOffset(),.01f)
        rule.onNodeWithText(recipe.timeline[2].title.uppercase()).assertIsDisplayed()
        rule.onNode(SemanticsMatcher("footer seeks actual title") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label=="Страница 1"
        }).performClick();rule.waitForIdle()
        assertTrue(scrollOffset()<position)
        rule.onNode(hasText(recipe.name) and hasAnyAncestor(hasTestTag("reader-scroll"))).assertIsDisplayed()
    }
}
