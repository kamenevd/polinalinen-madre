package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Настройки семьи — простая key-value таблица. DESIGN-V4.md Cycle 3, фича
 * «Экслибрис» (Bookplate): первая (и пока единственная) строка — family_name.
 * Key-value, а не отдельная колонка familyName в SourdoughConfig, — экслибрис
 * концептуально про семью-владельца книги, а не про конкретную закваску.
 */
@Entity(tableName = "family_settings")
data class FamilySettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

object FamilySettingKeys {
    const val FAMILY_NAME = "family_name"
}
