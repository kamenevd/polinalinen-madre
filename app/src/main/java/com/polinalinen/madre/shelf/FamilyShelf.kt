package com.polinalinen.madre.shelf

import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import com.polinalinen.madre.data.remote.UserRecord
import com.polinalinen.madre.model.RuDate
import java.time.Instant
import java.time.ZoneId

/**
 * Полка — место семьи; книга — один человек.
 *
 * Корешок считается по PocketBase user id, не по device_id: один аккаунт на
 * двух телефонах — это одна книга, а не две. Записи без user id (старый
 * формат) на полке корешка не заводят и в чужой формуляр не попадают —
 * выдумывать автора книге нечем.
 */
object FamilyShelf {

    const val OWN_ID = "me"

    const val CAPTION =
        "ваша — с красным ляссе ниже доски. пустой корешок справа не нажимается: задел на будущее."

    const val UNCUT_SPINE_TAG = "shelf-uncut-spine"
    const val LIST_TAG = "shelf-list"

    fun membersFromUsers(users: List<UserRecord>, myUserId: String): List<ShelfMember> {
        val byId = LinkedHashMap<String, ShelfMember>()
        for (user in users) {
            if (user.id.isBlank()) continue
            byId.putIfAbsent(
                user.id,
                ShelfMember(
                    userId = user.id,
                    displayName = user.name.trim().ifBlank { "без имени" },
                    isMe = user.id == myUserId,
                ),
            )
        }
        if (myUserId.isNotBlank() && myUserId != OWN_ID) {
            byId.putIfAbsent(
                myUserId,
                ShelfMember(userId = myUserId, displayName = "вы", isMe = true),
            )
        }
        return orderMembers(byId.values.toList())
    }

    fun localMember(displayName: String): ShelfMember =
        ShelfMember(
            userId = OWN_ID,
            displayName = displayName.trim().ifBlank { "вы" },
            isMe = true,
        )

    /**
     * Корешки: своя книга слева, остальные по имени. Пустой корешок UI
     * дорисовывает сам — в списке членов его нет, чтобы на него нельзя было
     * нажать «потому что он в данных».
     */
    fun orderMembers(members: List<ShelfMember>): List<ShelfMember> {
        val unique = LinkedHashMap<String, ShelfMember>()
        for (member in members) {
            if (member.userId.isBlank()) continue
            unique.putIfAbsent(member.userId, member)
        }
        val mine = unique.values.filter { it.isMe }
        val others = unique.values.filterNot { it.isMe }.sortedBy { it.displayName.lowercase() }
        return mine + others
    }

    fun ledgerFromStats(
        records: List<BakeStatRecord>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ShelfLedgerRow> =
        records
            .mapNotNull { record ->
                val millis = PocketBaseDates.parseOrNull(record.bakedAt) ?: return@mapNotNull null
                val userId = record.userId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ShelfLedgerRow(
                    bakedAtMillis = millis,
                    who = record.displayName?.trim().orEmpty().ifBlank { "без имени" },
                    chapter = record.recipeName,
                    userId = userId,
                    whenLabel = RuDate.dayAndMonth(
                        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(),
                    ),
                )
            }
            .sortedByDescending { it.bakedAtMillis }

    fun ledgerFromLocal(
        records: List<BakeRecordEntity>,
        who: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ShelfLedgerRow> =
        records
            .map { record ->
                ShelfLedgerRow(
                    bakedAtMillis = record.completedAtMillis,
                    who = who.trim().ifBlank { "вы" },
                    chapter = record.recipeName,
                    userId = OWN_ID,
                    whenLabel = RuDate.dayAndMonth(
                        Instant.ofEpochMilli(record.completedAtMillis).atZone(zone).toLocalDate(),
                    ),
                )
            }
            .sortedByDescending { it.bakedAtMillis }

    /**
     * Чужие выпечки для формуляра. Путь к фото не подставляем: снимка на
     * этом телефоне нет, а выдумывать, что он уже на сервере, книга не будет.
     */
    fun recordsForUser(records: List<BakeStatRecord>, userId: String): List<BakeRecordEntity> {
        if (userId.isBlank() || userId == OWN_ID) return emptyList()
        return records
            .filter { it.userId == userId }
            .mapIndexed { index, record ->
                BakeRecordEntity(
                    id = index + 1L,
                    recipeId = record.recipeId,
                    recipeName = record.recipeName,
                    portions = record.portions,
                    completedAtMillis = PocketBaseDates.parseOrNull(record.bakedAt) ?: 0L,
                    photoPath = null,
                )
            }
            .filter { it.completedAtMillis > 0L }
            .sortedByDescending { it.completedAtMillis }
    }

    fun isOwnBook(ownerId: String, myUserId: String?): Boolean =
        ownerId == OWN_ID || (myUserId != null && ownerId == myUserId)
}

data class ShelfMember(
    val userId: String,
    val displayName: String,
    val isMe: Boolean,
)

data class ShelfLedgerRow(
    val bakedAtMillis: Long,
    val who: String,
    val chapter: String,
    val userId: String,
    val whenLabel: String,
)
