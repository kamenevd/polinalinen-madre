package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
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

    fun refresh(localName: String, localRecords: List<BakeRecordEntity>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { madreApp.familyAccountRepository.restore() }
            val account = madreApp.familyAccountRepository.currentAccount()
            _myUserId.value = account?.userId
            _familyName.value = account?.familyName
            if (account == null || !account.hasFamily) {
                val mine = FamilyShelf.localMember(localName)
                _members.value = listOf(mine)
                _ledger.value = FamilyShelf.ledgerFromLocal(localRecords, mine.displayName)
                remoteBakes = emptyList()
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
                _unreachable.value = false
                return@launch
            }
            val users = runCatching {
                withContext(Dispatchers.IO) { madreApp.familyAccountRepository.listFamilyUsers() }
            }.getOrDefault(emptyList())
            val stats = runCatching {
                withContext(Dispatchers.IO) { madreApp.madreApi.listBakeStats(token) }
            }
            _unreachable.value = stats.isFailure
            remoteBakes = stats.getOrNull()?.items.orEmpty()
            val members = FamilyShelf.membersFromUsers(users, account.userId).ifEmpty {
                listOf(FamilyShelf.localMember(account.displayName.ifBlank { localName }))
            }
            _members.value = members
            _ledger.value = FamilyShelf.ledgerFromStats(remoteBakes)
        }
    }

    fun recordsFor(ownerId: String): List<BakeRecordEntity> =
        FamilyShelf.recordsForUser(remoteBakes, ownerId)

    fun labelFor(ownerId: String, fallback: String): String {
        if (FamilyShelf.isOwnBook(ownerId, _myUserId.value)) {
            return _members.value.firstOrNull { it.isMe }?.displayName ?: fallback
        }
        return _members.value.firstOrNull { it.userId == ownerId }?.displayName ?: fallback
    }
}
