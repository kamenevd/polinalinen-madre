package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Один starter на пользователя (v4 decision #13).
 * intervalHours соответствует ключам profileForInterval() в SourdoughProfile.kt (12/24/48/72/168).
 */
@Entity(
    tableName = "sourdough_configs",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SourdoughConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val name: String = "Мадре",
    val intervalHours: Int = 24,
    val remindersEnabled: Boolean = true,
    val lastFeedingMillis: Long? = null,
)
