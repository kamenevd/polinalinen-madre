package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity

interface RosterController {
    suspend fun persistedRoster(connection: String, revision: Long): BakeRoster
    suspend fun executeRoster(command: RosterCommand, serverTime: Long, receivedElapsed: Long,
        authority: () -> Boolean): VoiceCommandReceiptEntity?
}
