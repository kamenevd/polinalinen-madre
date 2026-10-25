package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.account.FamilyAccount
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.ui.razvorot.ShelfPaper
import com.polinalinen.madre.viewmodel.ShelfViewModel

@Composable
fun ShelfScreen(
    myName: String,
    localRecords: List<BakeRecordEntity>,
    onBack: () -> Unit,
    onOpenBook: (ownerId: String) -> Unit,
    account: FamilyAccount?,
    shelfViewModel: ShelfViewModel = viewModel(),
    onOpenAlice: () -> Unit = {},
    onOpenFamilySettings: () -> Unit = onBack,
    onOpenSettings: () -> Unit = onBack,
) {
    val familyModel = LocalFamilyLinkModel.current
    if (com.polinalinen.madre.config.FeatureFlags.FAMILY_ROSTER_V2 && familyModel != null) {
        val verified by familyModel.state.collectAsState()
        val sdk = com.polinalinen.madre.account.yandex.LocalYandexAccountUi.current
        val sdkState = sdk?.model?.state?.collectAsState()?.value
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { familyModel.refresh() }
        if (verified.family == null) {
            com.polinalinen.madre.ui.kit.PaperScaffold(header = {
                com.polinalinen.madre.ui.kit.PaperHeader("", onOpenFamilySettings, "Полка семьи",
                    androidx.compose.ui.Modifier.then(androidx.compose.ui.Modifier))
            }) {
                item {
                    if (verified.busy) com.polinalinen.madre.ui.kit.PaperNote("Проверяем семью…")
                    else if (verified.failure != null) FamilyFailure(verified.failure!!, { familyModel.refresh() })
                    if (!verified.busy && verified.failure == null) FamilyEmptyContent(verified, { sdk?.signIn?.invoke(false) }, onOpenFamilySettings, sdkAvailable = sdk != null,
                        localBakes = localRecords.size, onLocalBook = { onOpenBook(com.polinalinen.madre.shelf.FamilyShelf.OWN_ID) },
                        sdkBusy = sdkState is com.polinalinen.madre.account.yandex.YandexAccountState.SigningIn || sdkState is com.polinalinen.madre.account.yandex.YandexAccountState.Binding,
                        sdkError = if (sdkState is com.polinalinen.madre.account.yandex.YandexAccountState.Error) "Вход не завершён. Повторите вход с Яндекс ID." else null)
                    else com.polinalinen.madre.ui.kit.PaperButton("Своя книга", { onOpenBook(com.polinalinen.madre.shelf.FamilyShelf.OWN_ID) },
                        variant = com.polinalinen.madre.ui.kit.PaperButtonVariant.SECONDARY)
                }
            }
            return
        }
    }
    val members by shelfViewModel.members.collectAsState()
    val ledger by shelfViewModel.ledger.collectAsState()
    val familyName by shelfViewModel.familyName.collectAsState()
    val unreachable by shelfViewModel.unreachable.collectAsState()

    LaunchedEffect(account?.familyId, account?.familyName, myName, localRecords) {
        shelfViewModel.refresh(account, myName, localRecords)
    }

    ShelfPaper(members, ledger, familyName, localRecords, unreachable, onOpenSettings, onOpenBook, onOpenAlice)
}
