package com.polinalinen.madre.familyui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.viewmodel.FamilyOverlay

internal fun familyFixture() = FamilyRoster("family1", "Каменевы", "owner1", false,
    listOf("Дима", "Полина", "Бабушка Галя", "Миша").mapIndexed { index, name ->
        FamilyMember(if (index == 0) "owner1" else "member$index", name,
            if (index == 0) FamilyRole.OWNER else FamilyRole.MEMBER, MembershipStatus.JOINED,
            "2026-10-20T12:00:00Z", listOf(14L, 9L, 3L, 0L)[index])
    }, emptyList())
internal fun panelFixture() = FamilyPanelState(signedIn = true, userId = "owner1", accountName = "Дима", family = familyFixture(),
    invite = ActiveFamilyInvite(requireNotNull(FamilyInvitation.parse("https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP")), "2026-10-31T12:00:00Z", 4))
internal fun previewFixture() = InvitePreview("Каменевы", "Дима", 4, 26, listOf("Д", "П", "Г", "М"), false)
internal fun fixtureActions(show: (FamilyOverlay?) -> Unit = {}, remove: (String, Boolean) -> Unit = { _, _ -> },
    share: () -> Unit = {}, copy: () -> Unit = {}, memberName: (String, String) -> Unit = { _, _ -> }) =
    FamilyActions({}, {}, {}, show, {}, {}, {}, memberName, remove, {}, {}, { _, _ -> }, {}, {}, {}, {}, share, copy)

@Composable internal fun FamilyFixtureTheme(theme: Atmosphere = Atmosphere.DAWN, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ReadingPreferences(context, "family_ui_fixture_only") }
    RazvorotTheme(preferences, force = theme) { content() }
}
