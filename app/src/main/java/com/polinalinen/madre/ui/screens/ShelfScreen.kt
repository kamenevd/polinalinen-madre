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
) {
    val members by shelfViewModel.members.collectAsState()
    val ledger by shelfViewModel.ledger.collectAsState()
    val familyName by shelfViewModel.familyName.collectAsState()
    val unreachable by shelfViewModel.unreachable.collectAsState()

    LaunchedEffect(account?.familyId, account?.familyName, myName, localRecords) {
        shelfViewModel.refresh(account, myName, localRecords)
    }

    ShelfPaper(members, ledger, familyName, localRecords, unreachable, onBack, onOpenBook)
}
