package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Запись о кормлении закваски. Экран 5 «Кормление» (v4-screen-inventory.md):
 * фото, мука/вода в граммах, место хранения, заметка.
 *
 * ВАЖНО (pitfall из CLAUDE.md/v3): photoPath хранит путь к файлу, НЕ uri.path.
 * С Cycle 15 путь ОТНОСИТЕЛЬНЫЙ — от filesDir («feeding_photos/feed_1_2.jpg»):
 * абсолютный ломался при переезде filesDir между профилями. Собирать файл
 * обратно только через PhotoStore.resolve, он же понимает старые абсолютные
 * записи (MIGRATION_6_7 переписала не все — у кого-то была своя раскладка).
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
    /**
     * Legacy (Cycle 26, v9): гидратация, подтверждённая руками. Новые записи её
     * не заполняют — читается как прежнее значение, см. [finalHydrationPercent].
     */
    val hydrationPercent: Int? = null,
    /** Legacy (v9): штамп-наблюдение о закваске. Новые записи его не пишут. */
    val starterObservation: String? = null,
    /** When the observation was made; null for legacy/no-observation rows. */
    val observedAtMillis: Long? = null,
    /** Сколько закваски оставили в банке перед кормлением (v10). */
    val retainedStarterGrams: Int? = null,
    /**
     * Гидратация после кормления — посчитана [HydrationMath] из трёх масс, а не
     * набрана руками. Она же питает расчёт следующего кормления.
     */
    val finalHydrationPercent: Int? = null,
    /**
     * «Комментарий закваски»: снимок фактов на момент записи (FeedingComment).
     * Неизменяемый — пересчитывать его позже нечем и незачем. Заметка человека
     * живёт отдельно, в [notes].
     */
    val generatedComment: String? = null,
)

enum class StorageLocation { KITCHEN, FRIDGE }
