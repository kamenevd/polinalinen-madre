package com.polinalinen.madre.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.fakes.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakedSealDateTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm:BakingViewModel
    @After fun cleanup(){if(::vm.isInitialized)rule.runOnIdle{ViewModelStore().apply{put("bake",vm);clear()}}}

    @Test
    fun `seal date is sourced from completion state and not from now`() {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        runCatching{WorkManager.initialize(app,Configuration.Builder().build())}
        val timestamp=Instant.parse("2023-11-14T12:00:00Z").toEpochMilli()
        val history=FakeBakeHistory().apply{put(BakeRecordEntity(99L,"home_bread","Синтетическая выпечка",2,timestamp))}
        val ledger=FakeBakeSessionLedger().apply{put(12L,99L)}
        vm=BakingViewModel(app,history,FakeShelfSync(),ledger)
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){BakingFinale(12L,{},vm,onBack={})}}
        rule.waitUntil(10_000){rule.onAllNodesWithContentDescription("Испечено · 14.XI · вторник").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(timestamp,vm.completions.value[12L]?.completedAtMillis)
        assertTrue(history.recordCalls.isEmpty())
    }
}
