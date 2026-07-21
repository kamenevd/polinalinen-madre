package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v4 decision #13: "Multi-user: один starter на пользователя, несколько
 * пользователей на устройстве." Этой сущности не было в v3 — новое в v4.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
