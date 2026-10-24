package com.polinalinen.madre

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import com.polinalinen.madre.navigation.BookPreferences
import com.polinalinen.madre.navigation.PlannedBake
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same-certificate instrumentation of the actual R8 QA, not a debug substitute.
 * The host runs write/restart in separate app processes and retains both raw outputs.
 * Synthetic isolated namespace only; never family preferences or a family device.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedPlanRegressionTest {
    @Test fun legacyPlansAndFailedReads() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val negative=args.getString("negative")=="true"
        val phase=requireNotNull(args.getString("phase"))
        assertEquals("com.polinalinen.madre.qa",context.packageName)
        assertEquals(0,context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val raw=context.getSharedPreferences("qa_minified_plan_regression",Context.MODE_PRIVATE)
        val old=PlannedBake("ciabatta",2,2_000_000_000_000L)
        val second=PlannedBake("home_bread",3,2_000_000_060_000L)
        val added=PlannedBake("waffles",4,2_000_000_120_000L,3)
        val expected=listOf(old,second,added)
        val legacy="""[{"recipeId":"ciabatta","portions":2,"startAtMillis":2000000000000},{"recipeId":"home_bread","portions":3,"startAtMillis":2000000060000}]"""
        if(phase=="write") {
            assertTrue(raw.edit().clear().putString("planned_bakes",legacy).putString("unrelated","keep").commit())
            BookPreferences(context,"qa_minified_plan_regression").use { book ->
                assertEquals(if(negative)emptyList() else listOf(old,second),book.snapshotPlans())
                assertTrue(book.putPlan(added))
                assertEquals(if(negative)emptyList() else expected,book.snapshotPlans())
            }
            val valid=requireNotNull(raw.getString("planned_bakes",null))
            val persisted=JSONArray(valid)
            val persistedIds=(0 until persisted.length()).map { persisted.getJSONObject(it).getString("recipeId") }
            assertEquals(if(negative)listOf("waffles") else expected.map { it.recipeId },persistedIds)
            BookPreferences(context,"qa_minified_plan_regression").use { reread ->
                assertEquals(if(negative)emptyList() else expected,reread.snapshotPlans())
            }
            if(!negative) {
                for(broken in listOf("[{", "null", "[null]", "[{\"recipeId\":\"ciabatta\",\"portions\":0,\"startAtMillis\":2000000000000}]")) {
                    assertTrue(raw.edit().putString("planned_bakes",broken).putString("planned_action_nonce_${old.id}","synthetic-capability").commit())
                    val before=raw.all.toMap()
                    BookPreferences(context,"qa_minified_plan_regression").use { book ->
                        assertTrue(runCatching { book.snapshotPlans() }.isFailure)
                        assertFalse(book.putPlan(added))
                        assertFalse(book.putPlan(added,replacingId=old.id))
                        assertFalse(book.removePlan(old.id))
                        assertNull(book.notificationNonce(old.id))
                        assertFalse(book.consumeNotificationAction(old.id,"synthetic-capability"))
                    }
                    assertEquals("Read errors must not alter any original pref or capability",before,raw.all)
                }
                assertTrue(raw.edit().putString("planned_bakes",valid).commit())
            }
        } else {
            assertEquals("restart",phase)
            BookPreferences(context,"qa_minified_plan_regression").use { restored ->
                assertEquals(if(negative)emptyList() else expected,restored.snapshotPlans())
            }
        }
        assertEquals("keep",raw.getString("unrelated",null))
        val evidence=File(context.getExternalFilesDir(null),"minified-plan-regression").apply { mkdirs() }
        File(evidence,"$phase-prefs.json").writeText(requireNotNull(raw.getString("planned_bakes",null)))
        File(evidence,"$phase-result.txt").writeText("negative-before=$negative; phase=$phase; target=${context.packageName}; debuggable=false; pid=${android.os.Process.myPid()}; assertions=PASS\n")
    }
}
