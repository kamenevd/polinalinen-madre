package com.polinalinen.madre.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BookDefaultPortionsTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()

    @Test fun optionalDefaultPersistsClampsAndClearsWithoutChangingOtherData() {
        val namespace="default-portions-${UUID.randomUUID()}"
        val raw=context.getSharedPreferences(namespace,0)
        raw.edit().putString("my_name","Книга").putString("margin_notes","Сохранить").commit()
        val original=raw.all.toMap()
        BookPreferences(context,namespace).use { book ->
            assertNull(book.defaultPortions)
            assertEquals(2,book.portions("untouched")) // Existing recipe fallback, not a new ×1 default.
            assertEquals(original,raw.all)
            book.saveDefaultPortions(4)
        }
        BookPreferences(context,namespace).use { restored ->
            assertEquals(4,restored.defaultPortions)
            assertEquals(4,restored.portions("new-recipe"))
            restored.saveDefaultPortions(99);assertEquals(5,restored.defaultPortions)
            restored.saveDefaultPortions(0);assertEquals(1,restored.defaultPortions)
            restored.saveDefaultPortions(null)
        }
        BookPreferences(context,namespace).use {
            assertNull(it.defaultPortions);assertEquals(2,it.portions("new-recipe"))
        }
        assertEquals(original,raw.all)
    }

    @Test fun explicitDefaultWinsForNewEntriesWithoutRewritingHistoricalChoicesOrPlans() {
        val namespace="recipe-portions-${UUID.randomUUID()}"
        val raw=context.getSharedPreferences(namespace,0)
        BookPreferences(context,namespace).use { book ->
            book.savePortions("bread",3);book.savePortions("waffles",1)
            val plan=PlannedBake("bread",2,2_000_000_000_000)
            assertTrue(book.putPlan(plan))
            val before=raw.all.toMap()
            assertEquals(3,book.portions("bread"));assertEquals(1,book.portions("waffles"))
            book.saveDefaultPortions(5)
            assertEquals(5,book.portions("bread"));assertEquals(5,book.portions("waffles"))
            assertEquals(5,book.portions("new"));assertEquals(plan,book.currentPlan(plan.id))
            assertEquals(before,raw.all.filterKeys{it!="default_portions"})
            book.saveDefaultPortions(null)
            assertEquals(before,raw.all)
        }
        BookPreferences(context,namespace).use {
            assertEquals(3,it.portions("bread"));assertEquals(1,it.portions("waffles"))
        }
    }
}
