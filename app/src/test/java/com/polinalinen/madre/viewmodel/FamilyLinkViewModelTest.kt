package com.polinalinen.madre.viewmodel

import android.app.Application
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.yandex.YandexLoginToken
import com.polinalinen.madre.integration.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FamilyLinkViewModelTest {
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun model(f: FamilyWireFixture, handle: SavedStateHandle = SavedStateHandle(), autoRestore: Boolean = false) =
        FamilyLinkViewModel(app, handle, f.repository, f.accounts, autoRestore)

    @Test fun createReadbackAutomaticallyLoadsInviteAndRetryNeverCreatesAnotherFamily() = runBlocking {
        FamilyWireFixture().use { f ->
            f.familyId = null; f.errorPath = "/api/madre/family/invite"
            val vm = model(f); vm.create("Наша семья")?.join()
            assertEquals(FamilyOverlay.CreatedInvite, vm.overlay.value)
            assertNotNull(vm.state.value.failure); assertNull(vm.state.value.invite)
            assertEquals(1, f.calls.count { it.second.endsWith("/family/create") })
            f.errorPath = null; vm.refresh()?.join()
            assertEquals("Наша семья", vm.state.value.family!!.name)
            assertEquals(f.url, vm.state.value.invite!!.invitation.savedInviteUrl())
            assertEquals(1, f.calls.count { it.second.endsWith("/family/create") })
        }
    }
    @Test fun failedCreateDoesNotOpenOrLoadInvitation() = runBlocking {
        FamilyWireFixture().use { f ->
            f.familyId = null; f.errorPath = "/api/madre/family/create"
            val vm = model(f); vm.create("Наша семья")?.join()
            assertNull(vm.overlay.value); assertNotNull(vm.state.value.failure)
            assertTrue(f.calls.none { it.second.endsWith("/family/invite") })
        }
    }
    @Test fun explicitSdkProvisioningReturnsToSameInvitationButNeverJoinsAutomatically() = runBlocking {
        FamilyWireFixture().use { f ->
            f.tokens.verified = false
            val handle = SavedStateHandle(); val vm = model(f, handle)
            vm.openInvitation(f.url).join()
            assertEquals(InvitationFlow.Phase.SIGN_IN, vm.invitation.value.phase)
            val bearer = YandexLoginToken.takeSdkResult("SYNTHETIC_OAUTH")
            try { f.accounts.authenticateYandex(bearer, f.api) { true } } finally { bearer.close() }
            vm.signedInReturned()?.join()
            assertEquals(InvitationFlow.Phase.READY, vm.invitation.value.phase)
            assertEquals(f.url, handle.get<String>(FamilyLinkViewModel.INVITE))
            assertTrue(f.calls.none { it.second.endsWith("/join") })
            vm.join()?.join(); assertEquals(InvitationFlow.Phase.MEMBER, vm.invitation.value.phase)
            assertEquals(1, f.calls.count { it.second.endsWith("/join") })
            assertNotNull(vm.state.value.family)
        }
    }
    @Test fun processRestoreKeepsOnlyValidatedInviteNotSessionOauthOrUndoAndDoesNotJoin() = runBlocking {
        FamilyWireFixture().use { f ->
            val saved = SavedStateHandle(); val first = model(f, saved)
            first.openInvitation(f.url).join()
            assertEquals(setOf(FamilyLinkViewModel.INVITE), saved.keys())
            val bundle = saved.savedStateProvider().saveState()
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeBundle(bundle); parcel.marshall() } finally { parcel.recycle() }
            val text = String(bytes, Charsets.UTF_16LE)
            assertFalse(text.contains("SYNTHETIC_PB")); assertFalse(text.contains("SYNTHETIC_OAUTH")); assertFalse(text.contains("SYNTHETIC_RESTORE"))
            val restored = model(f, SavedStateHandle(mapOf(FamilyLinkViewModel.INVITE to saved.get<String>(FamilyLinkViewModel.INVITE))), true)
            withTimeout(10_000) { restored.invitation.first { it.phase == InvitationFlow.Phase.READY } }
            assertTrue(f.calls.none { it.second.endsWith("/join") })
            restored.closeInvitation()
        }
    }
    @Test fun pendingAndApprovalRefreshDoNotRejoinAndRequireAuthoritativeMembershipReadback() = runBlocking {
        FamilyWireFixture().use { f ->
            f.userId = "applicant3"; f.familyId = null; f.requests.clear(); f.joinStatus = "pending"
            val vm = model(f); vm.openInvitation(f.url).join(); vm.join()?.join()
            assertEquals(InvitationFlow.Phase.PENDING, vm.invitation.value.phase)
            assertNull(vm.invitation.value.family)
            vm.refreshPending()?.join(); assertEquals(InvitationFlow.Phase.PENDING, vm.invitation.value.phase)
            f.statusReply = "joined"; vm.refreshPending()?.join()
            assertEquals(InvitationFlow.Phase.MEMBER, vm.invitation.value.phase)
            assertEquals(1, f.calls.count { it.second.endsWith("/join") })
            assertEquals(2, f.calls.count { it.second.endsWith("/status") })
        }
    }
    @Test fun switchNeedsSecondExplicitClickAndNoSeparateLeaveCall() = runBlocking {
        FamilyWireFixture().use { f ->
            f.joinError = "already_in_family"
            val vm = model(f); vm.openInvitation(f.url).join(); vm.join()?.join()
            assertEquals(InvitationFlow.Phase.SWITCH_CONFIRMATION, vm.invitation.value.phase)
            assertEquals(1, f.calls.count { it.second.endsWith("/join") })
            f.joinError = null; vm.join(true)?.join()
            assertEquals(InvitationFlow.Phase.MEMBER, vm.invitation.value.phase)
            assertTrue(f.bodies.contains("{\"switch_family\":false}")); assertTrue(f.bodies.contains("{\"switch_family\":true}"))
            assertTrue(f.calls.none { it.second.endsWith("/leave") })
        }
    }
    @Test fun joinedAckWithoutReadableRosterCannotExposeFamilyAndNewLinkFencesOldPending() = runBlocking {
        FamilyWireFixture().use { f ->
            val vm = model(f); vm.openInvitation(f.url).join(); f.errorPath = "/api/madre/family"
            vm.join()?.join(); assertEquals(InvitationFlow.Phase.ERROR, vm.invitation.value.phase); assertNull(vm.invitation.value.family)
            f.errorPath = null; f.joinStatus = "pending"; vm.openInvitation(f.url).join(); vm.join()?.join()
            vm.openInvitation("https://kdnfx.space/madre/f/QRSTUVWXYZabcdef").join()
            assertEquals(InvitationFlow.Phase.READY, vm.invitation.value.phase)
            assertNull(vm.invitation.value.family)
        }
    }
    @Test fun fullDeadAndDeclinedRemainHonestErrorsAndNeverClaimMembership() = runBlocking {
        FamilyWireFixture().use { f ->
            val vm = model(f); vm.openInvitation(f.url).join(); f.joinError = "family_full"; vm.join()?.join()
            assertEquals(ContractFailure.Kind.FAMILY_FULL, vm.invitation.value.failure)
            f.malformed = 404 to "{\"error\":\"invite_unavailable\"}"; vm.openInvitation(f.url).join()
            assertEquals(ContractFailure.Kind.DEAD_INVITE, vm.invitation.value.failure)
            assertNull(vm.invitation.value.family)
            f.malformed = null; f.joinError = null; f.joinStatus = "pending"; f.statusReply = "declined"
            vm.openInvitation(f.url).join(); vm.join()?.join(); vm.refreshPending()?.join()
            assertEquals(InvitationFlow.Phase.DECLINED, vm.invitation.value.phase); assertNull(vm.invitation.value.family)
        }
    }
    @Test fun invalidLinkNeverEntersSavedStateOrNetworkAndCloseClearsOnlyInvite() = runBlocking {
        FamilyWireFixture().use { f ->
            val saved = SavedStateHandle(); val vm = model(f, saved)
            vm.openInvitation("https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP?redirect=evil").join()
            assertFalse(saved.contains(FamilyLinkViewModel.INVITE)); assertTrue(f.calls.isEmpty())
            vm.openInvitation(f.url).join(); vm.closeInvitation()
            assertEquals(InvitationFlow.Phase.EMPTY, vm.invitation.value.phase); assertTrue(saved.keys().isEmpty()); assertNotNull(f.tokens.read())
        }
    }
}
