package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyAccount
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.shelf.FamilyShelf
import com.polinalinen.madre.shelf.ShelfLedgerRow
import com.polinalinen.madre.shelf.ShelfMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние полки: корешки по user id и журнал выпечек семьи.
 * Локальная книга не зависит от этой загрузки — без сети остаётся свой корешок.
 */
class ShelfViewModel(app: Application) : AndroidViewModel(app) {

    private val madreApp = app as MadreApplication

    private val _members = MutableStateFlow<List<ShelfMember>>(emptyList())
    val members: StateFlow<List<ShelfMember>> = _members.asStateFlow()

    private val _ledger = MutableStateFlow<List<ShelfLedgerRow>>(emptyList())
    val ledger: StateFlow<List<ShelfLedgerRow>> = _ledger.asStateFlow()

    private val _familyName = MutableStateFlow<String?>(null)
    val familyName: StateFlow<String?> = _familyName.asStateFlow()

    private val _myUserId = MutableStateFlow<String?>(null)
    val myUserId: StateFlow<String?> = _myUserId.asStateFlow()

    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    private var remoteBakes: List<BakeStatRecord> = emptyList()
    private var currentRefreshGeneration = 0
    private var lastConfirmedMembers: List<ShelfMember> = emptyList()

    fun refresh(account: FamilyAccount?, localName: String, localRecords: List<BakeRecordEntity>) {
        val myGeneration = ++currentRefreshGeneration
        viewModelScope.launch {
            if (myGeneration != currentRefreshGeneration) return@launch  // older refresh cancelled by newer

            _myUserId.value = account?.userId
            _familyName.value = account?.familyName
            if (account == null || !account.hasFamily ||
                (com.polinalinen.madre.config.FeatureFlags.FAMILY_ROSTER_V2 && !com.polinalinen.madre.config.FeatureFlags.FAMILY_LINK_UI)) {
                _familyName.value = null
                val mine = FamilyShelf.localMember(localName)
                _members.value = listOf(mine)
                _ledger.value = FamilyShelf.ledgerFromLocal(localRecords, mine.displayName)
                remoteBakes = emptyList()
                lastConfirmedMembers = listOf(mine)
                _unreachable.value = false
                return@launch
            }
            val token = withContext(Dispatchers.IO) { madreApp.authTokenStore.read() }
                ?.takeIf { it.isNotBlank() }
            if (token == null) {
                val mine = FamilyShelf.localMember(account.displayName.ifBlank { localName })
                _members.value = listOf(mine)
                _ledger.value = FamilyShelf.ledgerFromLocal(localRecords, mine.displayName)
                remoteBakes = emptyList()
                lastConfirmedMembers = listOf(mine)
                _unreachable.value = false
                return@launch
            }
            if (com.polinalinen.madre.config.FeatureFlags.FAMILY_LINK_UI) {
                // No stale/offline family data. Personal Room records remain available independently.
                remoteBakes = emptyList(); lastConfirmedMembers = emptyList()
                _members.value = listOf(FamilyShelf.localMember(localName))
                _ledger.value = FamilyShelf.ledgerFromLocal(localRecords, localName)
                val verified = runCatching {
                    madreApp.familyAccountRepository.familyTransaction(madreApp.familyLinkApi) { session ->
                        val roster = madreApp.familyLinkApi.family(session)
                        require(roster.id == session.familyId && roster.members.any { it.id == session.userId })
                        roster to madreApp.madreApi.listBakeStats(session.credential).items
                    }
                }
                if (myGeneration != currentRefreshGeneration) return@launch
                verified.onSuccess { (reply, session) ->
                    val (roster, stats) = reply
                    if (roster.id != session.familyId || madreApp.familyAccountRepository.currentAccount()?.familyId != roster.id) return@onSuccess
                    _members.value = FamilyShelf.orderMembers(roster.members.map {
                        ShelfMember(it.id, it.name.ifBlank { "без имени" }, it.id == session.userId)
                    })
                    remoteBakes = stats.filter { stat -> roster.members.any { it.id == stat.userId } }
                    _ledger.value = FamilyShelf.ledgerFromStats(remoteBakes)
                    _familyName.value = roster.name
                }
                _unreachable.value = verified.isFailure
                return@launch
            }
            // users: on failure keep last confirmed (do not collapse to empty/local)
            val usersResult = runCatching {
                withContext(Dispatchers.IO) { madreApp.familyAccountRepository.listFamilyUsers() }
            }
            val statsResult = runCatching {
                withContext(Dispatchers.IO) { madreApp.madreApi.listBakeStats(token) }
            }
            if (myGeneration != currentRefreshGeneration) return@launch
            val users = usersResult.getOrNull() ?: emptyList()
            if (usersResult.isSuccess) {
                lastConfirmedMembers = FamilyShelf.membersFromUsers(users, account.userId).ifEmpty {
                    listOf(FamilyShelf.localMember(account.displayName.ifBlank { localName }))
                }
            }
            _unreachable.value = statsResult.isFailure || usersResult.isFailure
            if (statsResult.isSuccess) {
                remoteBakes = statsResult.getOrNull()?.items.orEmpty()
            } // else keep previous remoteBakes
            val members = if (usersResult.isSuccess) lastConfirmedMembers else lastConfirmedMembers.ifEmpty {
                listOf(FamilyShelf.localMember(account.displayName.ifBlank { localName }))
            }
            _members.value = members
            // ledger: if stats fail, mix local + kept remote, dedupe by record identity (bakedAt + user + chapter)
            val ledger = if (statsResult.isSuccess) {
                FamilyShelf.ledgerFromStats(remoteBakes)
            } else {
                val who = members.firstOrNull { it.isMe }?.displayName ?: localName
                val localLedger = FamilyShelf.ledgerFromLocal(localRecords, who)
                val remoteLedger = FamilyShelf.ledgerFromStats(remoteBakes)
                (localLedger + remoteLedger)
                    .distinctBy { Triple(it.bakedAtMillis, it.userId, it.chapter) }
                    .sortedByDescending { it.bakedAtMillis }
            }
            _ledger.value = ledger
        }
    }

    fun recordsFor(ownerId: String): List<BakeRecordEntity> {
        if (com.polinalinen.madre.config.FeatureFlags.FAMILY_ROSTER_V2 && !com.polinalinen.madre.config.FeatureFlags.FAMILY_LINK_UI) return emptyList()
        if (com.polinalinen.madre.config.FeatureFlags.FAMILY_LINK_UI) {
            val state = madreApp.familyLinkRepository.state.value
            if (state.busy || state.failure != null || state.family?.members?.none { it.id == ownerId } != false) return emptyList()
        }
        return FamilyShelf.recordsForUser(remoteBakes, ownerId)
    }

    fun labelFor(ownerId: String, fallback: String): String {
        if (FamilyShelf.isOwnBook(ownerId, _myUserId.value)) {
            return _members.value.firstOrNull { it.isMe }?.displayName ?: fallback
        }
        return _members.value.firstOrNull { it.userId == ownerId }?.displayName ?: fallback
    }
}
