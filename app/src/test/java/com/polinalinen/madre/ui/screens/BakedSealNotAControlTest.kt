package com.polinalinen.madre.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakedSealNotAControlTest {
    @get:Rule val rule=createComposeRule()

    @Test
    fun `wax seal is rendered as stamp and has no click handler`() {
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT) {
            BakeFinalePage(BakeFinalePageState("Синтетическая выпечка",1,2,90,3,
                Instant.parse("2023-11-14T12:00:00Z").toEpochMilli(),listOf(true),1),{},{},photo={PaperNote("Фото не выбрано")})
        }}
        rule.onNodeWithContentDescription("Испечено · 14.XI · вторник")
            .assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
