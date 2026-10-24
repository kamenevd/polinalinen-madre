package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.FamilySettingEntity
import com.polinalinen.madre.data.db.entities.FamilySettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.polinalinen.madre.model.BookDisplaySettings
import kotlinx.coroutines.withContext

/**
 * Настройки семьи (key-value) — DESIGN-V4.md Cycle 3, фича «Экслибрис»
 * (Bookplate). IO — на Dispatchers.IO (баг v3 #6).
 */
class FamilySettingsRepository(db: MadreDatabase) {
    private val dao = db.familySettingDao()

    fun observeFamilyName(): Flow<String?> =
        dao.observe(FamilySettingKeys.FAMILY_NAME).map { it?.value }

    suspend fun setFamilyName(name: String) = withContext(Dispatchers.IO) {
        dao.upsert(FamilySettingEntity(FamilySettingKeys.FAMILY_NAME, name))
    }

    fun observeBookDisplaySettings(): Flow<BookDisplaySettings> = combine(
        dao.observe(FamilySettingKeys.KEEP_SCREEN_ON), dao.observe(FamilySettingKeys.LOCK_SCREEN_PROGRESS),
    ) { keep, lock -> BookDisplaySettings(
        keep?.value?.toBooleanStrictOrNull() ?: true,
        lock?.value?.toBooleanStrictOrNull() ?: false,
    ) }

    suspend fun setKeepScreenOn(value: Boolean) = withContext(Dispatchers.IO) {
        dao.upsert(FamilySettingEntity(FamilySettingKeys.KEEP_SCREEN_ON, value.toString()))
    }

    suspend fun setLockScreenProgress(value: Boolean) = withContext(Dispatchers.IO) {
        dao.upsert(FamilySettingEntity(FamilySettingKeys.LOCK_SCREEN_PROGRESS, value.toString()))
    }
}
