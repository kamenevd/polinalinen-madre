package com.polinalinen.madre.navigation

import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.ui.razvorot.BookCatalog
import com.polinalinen.madre.ui.razvorot.CatalogFilter
import com.polinalinen.madre.ui.razvorot.chapterTurnDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class BookNavigationStateTest {
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()

    @Test fun preferencesSurviveReconstructionWithoutTouchingUnrelatedKeys() {
        val namespace="book-test-${UUID.randomUUID()}"
        val raw=app.getSharedPreferences(namespace,0)
        raw.edit().putString("my_name","Синтетическая книга").putString("margin_notes","Не менять").commit()
        BookPreferences(app,namespace).use { state ->
            state.saveBookmark(BookBookmark("ciabatta",3));state.savePortions("ciabatta",3);state.visit(1,100_000)
            assertTrue(state.putPlan(PlannedBake("ciabatta",2,1_000_000)))
            assertTrue(state.putPlan(PlannedBake("home_bread",3,2_000_000)))
            assertTrue(state.putPlan(PlannedBake("ciabatta",4,1_000_000)))
        }
        BookPreferences(app,namespace).use { restored ->
            assertEquals(BookBookmark("ciabatta",3),restored.bookmark)
            assertEquals(3,restored.portions("ciabatta"));assertEquals(2,restored.portions("waffles"))
            assertEquals(1,restored.initialTab(100_001))
            assertEquals(listOf(PlannedBake("ciabatta",4,1_000_000),PlannedBake("home_bread",3,2_000_000)),restored.plans)
            assertTrue(restored.removePlan("ciabatta:1000000"))
            assertEquals(listOf(PlannedBake("home_bread",3,2_000_000)),restored.plans)
        }
        assertEquals("Синтетическая книга",raw.getString("my_name",null))
        assertEquals("Не менять",raw.getString("margin_notes",null))
    }
    @Test fun unreadablePlansCannotAuthorizeAnyMutationOrLoseCapabilities() {
        val namespace="book-corrupt-${UUID.randomUUID()}"
        val raw=app.getSharedPreferences(namespace,0)
        val plan=PlannedBake("ciabatta",2,2_000_000_000_000L)
        for(broken in listOf("[{", "null", "[null]", "[{\"recipeId\":\"ciabatta\",\"portions\":0,\"startAtMillis\":10}]")) {
            raw.edit().putString("planned_bakes",broken).putString("unrelated","keep")
                .putString("planned_action_nonce_${plan.id}","synthetic").commit()
            val before=raw.all.toMap()
            BookPreferences(app,namespace).use { book ->
                assertNotNull(book.plansReadError)
                assertTrue(runCatching { book.snapshotPlans() }.isFailure)
                assertTrue(runCatching { book.currentPlan(plan.id) }.isFailure)
                assertFalse(book.putPlan(plan));assertFalse(book.putPlan(plan,replacingId=plan.id))
                assertFalse(book.removePlan(plan.id));assertNull(book.notificationNonce(plan.id))
                assertFalse(book.consumeNotificationAction(plan.id,"synthetic"))
            }
            assertEquals(before,raw.all)
        }
        raw.edit().putInt("planned_bakes",42).commit()
        val wrongType=raw.all.toMap()
        BookPreferences(app,namespace).use { assertFalse(it.putPlan(plan));assertFalse(it.removePlan(plan.id)) }
        assertEquals(wrongType,raw.all)
    }
    @Test fun legacy15PlansSurviveNewPlanReadBackAndNumberBackfill() {
        val namespace="book-legacy-${UUID.randomUUID()}"
        val raw=app.getSharedPreferences(namespace,0)
        raw.edit().putString("planned_bakes","""[{"recipeId":"ciabatta","portions":2,"startAtMillis":2000000000000}]""").commit()
        val legacy=PlannedBake("ciabatta",2,2_000_000_000_000L)
        val added=PlannedBake("home_bread",3,2_000_000_060_000L,2)
        BookPreferences(app,namespace).use { book ->
            assertEquals(listOf(legacy),book.snapshotPlans());assertNull(book.plansReadError)
            val nonce=book.notificationNonce(legacy.id)
            assertTrue(book.putPlan(legacy.copy(number=1)));assertEquals(nonce,book.notificationNonce(legacy.id))
            assertTrue(book.putPlan(added))
        }
        BookPreferences(app,namespace).use { assertEquals(listOf(legacy.copy(number=1),added),it.snapshotPlans()) }
    }
    @Test fun corruptPlansDoNotTriggerPartialNumberReconciliation() {
        val raw=app.getSharedPreferences("madre_prefs",0)
        raw.edit().clear().putString("planned_bakes","[{").putInt("bake_number_session_777",4).commit()
        val before=raw.all.toMap()
        BookPreferences(app).use { BakeNumberStore(app).reconcile(emptyList(),emptySet(),it) }
        assertEquals(before,raw.all)
        raw.edit().clear().commit()
    }
    @Test fun recentTabBoundaryRollbackAndMalformedBookmarkAreSafe() {
        val now=100_000_000L
        assertEquals(2,BookStartup.tab(2,now-43_199_999,now))
        assertEquals(0,BookStartup.tab(2,now-43_200_000,now))
        assertEquals(0,BookStartup.tab(2,now+1,now))
        assertEquals(0,BookStartup.tab(20,now,now))
        assertNull(BookBookmark.decode("ciabatta:-1"));assertNull(BookBookmark.decode("broken"))
        assertEquals(BookBookmark("id:with:colon",2),BookBookmark.decode("id:with:colon:2"))
    }
    @Test fun realAssetsSearchAndFlourCalculationMatchNavigationContracts()=runBlocking {
        val recipes=RecipeRepository(app).getRecipes()
        assertEquals(11,recipes.size)
        val rye=BookCatalog.search(recipes,"рж",CatalogFilter.ALL)
        assertEquals(listOf("family_bread"),rye.map{it.recipe.id})
        assertTrue(rye.single().ingredient.orEmpty().contains("ржан",ignoreCase=true))
        assertTrue(BookCatalog.search(recipes,"зефир",CatalogFilter.ALL).isEmpty())
        val bread=recipes.single{it.id=="home_bread"}
        assertEquals(2.0,RecipeScaler.scaleByFlour(bread,1220.0),.00001)
        val flour=RecipeScaler.totalFlourGrams(bread)
        val water=bread.ingredients.values.flatten().filter{it.refType==null&&it.category=="water"}.sumOf{it.gramsValue()}
        assertEquals("64,8",BookCatalog.number(water/flour*100,1))
        assertEquals(4,BookCatalog.search(recipes,"",CatalogFilter.BREAD).size)
    }
    @Test fun backwardSwipeWorksAcrossPageAndForwardStillNeedsRightEdge() {
        assertEquals(-1,chapterTurnDirection(25f,40f,0f,412f))
        assertEquals(-1,chapterTurnDirection(200f,40f,0f,412f))
        assertEquals(0,chapterTurnDirection(200f,-40f,0f,412f))
        assertEquals(1,chapterTurnDirection(400f,-40f,0f,412f))
        assertEquals(-1,chapterTurnDirection(400f,40f,0f,412f))
        assertEquals(0,chapterTurnDirection(400f,-2f,40f,412f))
    }
}
