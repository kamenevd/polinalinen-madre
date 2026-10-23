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

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakingCompleteBackIsPrivateTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm:BakingViewModel
    @After fun cleanup(){if(::vm.isInitialized)rule.runOnIdle{ViewModelStore().apply{put("bake",vm);clear()}}}

    @Test
    fun `system back routes to keep decision`() {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        runCatching{WorkManager.initialize(app,Configuration.Builder().build())}
        val history=FakeBakeHistory().apply{put(BakeRecordEntity(99L,"home_bread","Синтетическая выпечка",2,1_700_000_000_000L))}
        val ledger=FakeBakeSessionLedger().apply{put(12L,99L)}
        val sync=FakeShelfSync()
        vm=BakingViewModel(app,history,sync,ledger)
        var exits=0
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT){BakingFinale(12L,{exits++},vm)}}
        rule.waitUntil(10_000){rule.onAllNodesWithText("Синтетическая выпечка").fetchSemanticsNodes().isNotEmpty()}
        assertNull(vm.session(12L))
        rule.runOnIdle{rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.waitUntil(10_000){rule.onAllNodesWithText("На главную").fetchSemanticsNodes();exits==1}
        assertTrue(sync.calls.isEmpty())
        assertTrue(history.recordCalls.isEmpty())
        assertNull(ledger.recordIdFor(12L))
    }
}
