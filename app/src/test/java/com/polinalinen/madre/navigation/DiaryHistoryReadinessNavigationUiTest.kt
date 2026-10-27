package com.polinalinen.madre.navigation

import androidx.activity.ComponentActivity
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.DefaultTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = MadreApplication::class)
class DiaryHistoryReadinessNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var nav: NavHostController
    private lateinit var owners: MutableStateFlow<SourdoughConfigEntity?>
    private lateinit var vm: SourdoughViewModel
    private lateinit var a: SourdoughConfigEntity
    private lateinit var b: SourdoughConfigEntity

    @Before fun setup() {
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
        a = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        b = runBlocking {
            val userId = app.database.userDao().upsert(UserEntity(name = "Книга B"))
            val config = SourdoughConfigEntity(userId = userId, name = "Закваска B")
            config.copy(id = app.sourdoughRepository.saveConfig(config))
        }
        owners = MutableStateFlow(a)
    }
    @After fun cleanup() { ArchTaskExecutor.getInstance().setDelegate(null); rule.activity.viewModelStore.clear() }
    private fun row(owner: SourdoughConfigEntity) = runBlocking(Dispatchers.IO) {
        app.sourdoughRepository.saveFeeding(SourdoughFeedingSaveRequest(owner.id, 24,
            System.currentTimeMillis(), 43, 113, 59, StorageLocation.KITCHEN, "История ${owner.id}", null, null)).feeding
    }
    private fun rows(owner: SourdoughConfigEntity) = runBlocking(Dispatchers.IO) {
        app.database.feedingDao().observeHistory(owner.id).first()
    }
    private fun bind(bootstrap: suspend () -> Unit = {}, replace: Boolean = false): SourdoughViewModel {
        val initial = requireNotNull(owners.value)
        val controller = SourdoughViewModel(app, loadConfig = { bootstrap(); initial },
            observeConfig = { owners }, describeWeather = { null }, afterInsert = { _, _, _, _ -> })
        rule.activityRule.scenario.onActivity { activity ->
            if (replace) {
                val old = activity.viewModelStore.keys().associateWith { activity.viewModelStore[it] }
                val key = old.keys.single { it.endsWith(SourdoughViewModel::class.java.canonicalName!!) }
                activity.viewModelStore.put(key, controller)
                old.filterKeys { it != key }.forEach { (k, value) -> assertSame(value, activity.viewModelStore[k]) }
            }
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = controller as T
            })[SourdoughViewModel::class.java]
            assertSame(app, controller.getApplication<MadreApplication>())
        }
        return controller
    }
    private fun pump() {
        rule.onRoot().fetchSemanticsNode()
        rule.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    private fun show(): StateRestorationTester {
        vm = bind()
        val restore = StateRestorationTester(rule)
        restore.setContent { nav = rememberNavController(); MadreNavHost(nav) }
        rule.waitUntil(10_000) { pump(); rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick()
        rule.waitForIdle(); assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        return restore
    }
    private fun change(owner: SourdoughConfigEntity) {
        rule.runOnIdle { owners.value = owner }
        rule.waitUntil(10_000) { pump(); vm.config.value == owner }; rule.waitForIdle()
    }
    private fun journal() {
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription("Раздел дневника, Журнал"))
        rule.onNodeWithContentDescription("Раздел дневника, Журнал").performScrollTo().assertIsSelected()
    }
    private fun noFirstFeedPromise() {
        journal()
        assertEquals("Unreleased current-owner Room history must not promise a first feeding", -1,
            rule.onNodeWithTag("diary-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("diary-empty"))
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.onNodeWithText("Дневник начнётся с первого кормления.", substring = true).assertDoesNotExist()
    }
    private fun empty(owner: SourdoughConfigEntity) {
        rule.waitUntil(10_000) { pump(); rule.onNodeWithTag("diary-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("diary-empty") >= 0 }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-empty"))
        rule.onNodeWithText("Дневник начнётся с первого кормления. ${owner.name} ждёт.").assertExists()
        rule.onNodeWithTag("diary-empty-feed").onChildAt(0).performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertTrue(rows(owner).isEmpty())
    }
    private fun visible(row: FeedingEntity) {
        rule.waitUntil(10_000) { pump(); vm.history.value == listOf(row) }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${row.id}"))
        rule.onNodeWithTag("diary-entry-${row.id}").assertIsDisplayed()
        noFirstFeedPromise()
    }
    // Hold Room's real disk executor. Neither the history nor readiness is injected.
    private fun heldRoom(block: (ConcurrentLinkedQueue<Runnable>) -> Unit) {
        val executor = DefaultTaskExecutor()
        val pending = ConcurrentLinkedQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) { pending.add(runnable) }
            override fun postToMainThread(runnable: Runnable) = executor.postToMainThread(runnable)
            override fun isMainThread() = executor.isMainThread
        })
        try { block(pending) } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
            while (true) executor.executeOnDiskIO(pending.poll() ?: break)
        }
    }
    @Test fun persistedBDoesNotBecomeFirstFeedWhileActualRoomQueryWaitsAfterOwnerSwitch() {
        val first = row(a); val second = row(b); show(); visible(first)
        heldRoom { pending ->
            change(b)
            rule.waitUntil(10_000) { pump(); pending.isNotEmpty() }
            assertEquals(listOf(first), vm.history.value)
            noFirstFeedPromise()
        }
        visible(second)
        assertEquals(listOf(first), rows(a)); assertEquals(listOf(second), rows(b))
    }
    @Test fun trulyEmptyBOnlyPromisesFirstFeedingAfterItsOwnRoomRelease() {
        val first = row(a); show(); visible(first)
        heldRoom { pending ->
            change(b)
            rule.waitUntil(10_000) { pump(); pending.isNotEmpty() }
            noFirstFeedPromise()
        }
        empty(b); assertEquals(listOf(first), rows(a))
    }
    @Test fun coldRestoredJournalWithPersistedRowWaitsForRealCurrentOwnerRoom() {
        val first = row(a); val restoration = show(); visible(first)
        val release = CompletableDeferred<Unit>()
        val old = vm
        vm = bind(bootstrap = { release.await() }, replace = true)
        assertNotSame(old, vm)
        try {
            restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
            assertNull(vm.config.value)
            rule.onNodeWithTag("diary-empty").assertDoesNotExist()
            heldRoom { pending ->
                rule.runOnIdle { release.complete(Unit) }
                rule.waitUntil(10_000) { pump(); vm.config.value == a && pending.isNotEmpty() }
                assertTrue(vm.history.value.isEmpty())
                noFirstFeedPromise()
            }
        } finally { release.complete(Unit) }
        visible(first); assertEquals(listOf(first), rows(a))
    }
    @Test fun loadedEmptyACannotReviveReadinessAcrossActualABAWithOldRoomWorkQueued() {
        val second = row(b); show(); empty(a)
        heldRoom { pending ->
            change(b)
            rule.waitUntil(10_000) { pump(); pending.isNotEmpty() }
            change(a)
            noFirstFeedPromise()
        }
        empty(a)
        // Draining the canceled B query must not install its rows or readiness in A.
        assertTrue(vm.history.value.isEmpty()); assertEquals(listOf(second), rows(b))
        change(b); visible(second)
        change(a); empty(a)
    }
}
