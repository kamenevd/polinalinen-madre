package com.polinalinen.madre.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Committed together with the phone state; never an optimistic transport acknowledgement. */
@Entity(tableName = "voice_command_receipts")
data class VoiceCommandReceiptEntity(
    @PrimaryKey val commandId: String,
    val fingerprint: String,
    val bakeKey: String,
    val expectedRevision: Long,
    val outcome: String,
    val newRevision: Long,
    val expiresAt: Long,
    val operation: String,
    val recipeId: String,
    @ColumnInfo(defaultValue = "0") val effectApplied: Boolean = false,
)
