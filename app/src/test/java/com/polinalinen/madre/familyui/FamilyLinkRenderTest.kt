package com.polinalinen.madre.familyui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.ui.razvorot.SpineBar
import com.polinalinen.madre.viewmodel.FamilyOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Optional K suite. Production Compose renderers, synthetic state, not emulator/e2e/Huawei. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = Application::class)
class FamilyLinkRenderTest {
    @get:Rule val rule = createComposeRule()
    @Test fun actualKFrames() {
        val cases = buildList<Pair<String, Atmosphere>> {
            for (scene in listOf("shelf-family-empty", "family", "family-invite", "family-join"))
                for (theme in listOf(Atmosphere.DAWN, Atmosphere.NIGHT)) add(scene to theme)
            for (scene in listOf("family-join-signin", "family-member", "family-remove-dialog", "family-removed-snack",
                "family-leave", "family-link-dead", "family-migrate-email", "family-pending", "family-full", "family-switch",
                "family-removed-banner", "family-transfer-leave", "family-solo-delete", "family-network-error")) add(scene to Atmosphere.DAWN)
        }
        var current by mutableStateOf<Pair<String, Atmosphere>?>(null)
        rule.setContent { current?.let { (scene, theme) -> key(scene, theme) { FamilyFixtureTheme(theme) { render(scene) } } } }
        val module = if (File("app/src/main/AndroidManifest.xml").isFile) File("app") else File(".")
        val directory = File(module, "build/outputs/roborazzi/family-link").apply { mkdirs() }
        cases.forEach { (scene, theme) ->
            rule.runOnIdle { current = null }; rule.waitForIdle()
            rule.runOnIdle { current = scene to theme }; rule.waitForIdle()
            val output = File(directory, "$scene-${if (theme == Atmosphere.NIGHT) "night" else "dawn"}.png")
            captureScreenRoboImage(filePath = output.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = requireNotNull(BitmapFactory.decodeFile(output.path))
            assertEquals(412, image.width); assertEquals(915, image.height); image.recycle()
        }
        assertEquals(cases.size, directory.listFiles { f -> f.extension == "png" }!!.size)
        File(directory, "README.txt").writeText("K01–K10 native Compose Roborazzi renders; synthetic fixture state, no SDK/backend/e2e/Huawei claim. ${cases.size} PNG at412x915; optional suite; core20 and committed baselines untouched.\n")
    }
    @Composable private fun render(scene: String) {
        val actions = fixtureActions()
        val panel = panelFixture()
        when (scene) {
            "shelf-family-empty", "family-migrate-email", "family-removed-banner" -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { PaperScaffold(header = { PaperHeader("", overline = "Полка семьи", modifier = Modifier.padding(horizontal = 22.dp)) }) {
                    item { FamilyEmptyContent(FamilyPanelState(migration = scene == "family-migrate-email",
                        removedFamilyName = if (scene == "family-removed-banner") "Каменевы" else null), {}, {}, localBakes = 1) }
                } }
                SpineBar(3) {}
            }
            "family", "family-invite", "family-member", "family-remove-dialog", "family-removed-snack", "family-leave", "family-transfer-leave", "family-solo-delete", "family-network-error" -> {
                val state = when (scene) {
                    "family-removed-snack" -> panel.copy(family = panel.family!!.copy(members = panel.family.members.filter { it.id != "member2" }), undoName = "Бабушка Галя")
                    "family-leave" -> panel.copy(userId = "member1", accountName = "Полина")
                    "family-solo-delete" -> panel.copy(family = panel.family!!.copy(members = panel.family.members.take(1)))
                    "family-network-error" -> panel.copy(family = null, invite = null, failure = ContractFailure.Kind.NETWORK)
                    else -> panel
                }
                FamilyPanelContent(state, when (scene) {
                    "family-invite" -> FamilyOverlay.Invite
                    "family-member" -> FamilyOverlay.Member("member2")
                    "family-remove-dialog" -> FamilyOverlay.Remove("member2")
                    "family-leave", "family-transfer-leave", "family-solo-delete" -> FamilyOverlay.Leave
                    else -> null
                }, actions)
            }
            else -> FamilyInvitationContent(InvitationFlow.State(when (scene) {
                "family-join-signin" -> InvitationFlow.Phase.SIGN_IN
                "family-pending" -> InvitationFlow.Phase.PENDING
                "family-switch" -> InvitationFlow.Phase.SWITCH_CONFIRMATION
                "family-link-dead", "family-full" -> InvitationFlow.Phase.ERROR
                else -> InvitationFlow.Phase.READY
            }, previewFixture(), failure = when (scene) {
                "family-link-dead" -> ContractFailure.Kind.DEAD_INVITE
                "family-full" -> ContractFailure.Kind.FAMILY_FULL
                else -> null
            }), "Марина", {}, {}, {}, {}, {}, {})
        }
    }
}
