package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Запись о кормлении закваски. Экран 5 «Кормление» (v4-screen-inventory.md):
 * фото, мука/вода в граммах, место хранения, заметка.
 *
 * ВАЖНО (pitfall из CLAUDE.md/v3): photoPath хранит File.absolutePath, НЕ uri.path.
 */
@Entity(
    tableName = "feedings",
    foreignKeys = [
        ForeignKey(
            entity = SourdoughConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourdoughConfigId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FeedingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourdoughConfigId: Long,
    val timestampMillis: Long = System.currentTimeMillis(),
    val flourGrams: Int,
    val waterGrams: Int,
    val storageLocation: StorageLocation,
    val notes: String? = null,
    val photoPath: String? = null,
)

enum class StorageLocation { KITCHEN, FRIDGE }
