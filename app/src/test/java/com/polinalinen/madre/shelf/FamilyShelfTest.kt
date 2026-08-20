package com.polinalinen.madre.shelf

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import com.polinalinen.madre.data.remote.UserRecord
import org.junit.Test
import java.time.ZoneOffset

/**
 * Cycle 27: полка считает книги по user id, не по устройству.
 */
class FamilyShelfTest {

    private val now = 1_784_887_200_000L

    private fun user(id: String, name: String) = UserRecord(id, "$id@example.com", name, "f1")

    private fun bake(
        userId: String?,
        recipe: String,
        daysAgo: Long,
        displayName: String? = null,
        deviceId: String = "dev-$userId",
        photo: String? = null,
    ) = BakeStatRecord(
        id = "$userId-$recipe-$daysAgo",
        deviceId = deviceId,
        clientEventId = "$deviceId-$recipe-$daysAgo",
        recipeId = recipe.lowercase(),
        recipeName = recipe,
        portions = 1,
        bakedAt = PocketBaseDates.toIso(now - daysAgo * 86_400_000L),
        userId = userId,
        displayName = displayName,
        photo = photo,
    )

    @Test
    fun `one account on two devices is still one spine`() {
        val members = FamilyShelf.membersFromUsers(
            listOf(
                user("u-anya", "Аня"),
                user("u-anya", "Аня с кухни"),
            ),
            myUserId = "u-anya",
        )
        assertThat(members.map { it.userId }).containsExactly("u-anya")
        assertThat(members.single().isMe).isTrue()
    }

    @Test
    fun `family list is keyed by user id, not device id`() {
        val members = FamilyShelf.membersFromUsers(
            listOf(user("u-anya", "Аня"), user("u-dima", "Дима")),
            myUserId = "u-anya",
        )
        assertThat(members.map { it.userId }).containsExactly("u-anya", "u-dima").inOrder()
        assertThat(members.first().isMe).isTrue()
        assertThat(members.last().isMe).isFalse()
    }

    @Test
    fun `legacy bakes without a user id do not grow a spine or a ledger row`() {
        val records = listOf(
            bake(userId = null, recipe = "Багет", daysAgo = 1, deviceId = "old-phone"),
            bake(userId = "u-anya", recipe = "Ржаной", daysAgo = 2, displayName = "Аня"),
        )
        assertThat(FamilyShelf.ledgerFromStats(records, ZoneOffset.UTC).map { it.userId })
            .containsExactly("u-anya")
        assertThat(FamilyShelf.recordsForUser(records, "old-phone")).isEmpty()
    }

    @Test
    fun `ledger keeps the printed display name, not a later rename`() {
        val rows = FamilyShelf.ledgerFromStats(
            listOf(bake("u-anya", "Чиабатта", 0, displayName = "Каменевы")),
            ZoneOffset.UTC,
        )
        assertThat(rows.single().who).isEqualTo("Каменевы")
    }

    @Test
    fun `ledger has no empty days and lists only baked ones, newest first`() {
        val rows = FamilyShelf.ledgerFromStats(
            listOf(
                bake("u-dima", "Ржаной", 5, displayName = "Дима"),
                bake("u-anya", "Багет", 1, displayName = "Аня"),
            ),
            ZoneOffset.UTC,
        )
        assertThat(rows.map { it.chapter }).containsExactly("Багет", "Ржаной").inOrder()
        assertThat(rows).hasSize(2)
    }

    @Test
    fun `another person's book gets server photo URL when id and photo present`() {
        val records = listOf(
            bake("u-dima", "Багет", 1, displayName = "Дима", photo = "crust.jpg"),
        )
        val mapped = FamilyShelf.recordsForUser(records, "u-dima")
        assertThat(mapped).hasSize(1)
        val path = mapped.single().photoPath
        assertThat(path).isNotNull()
        assertThat(path).contains("api/files/bake_stats/")
        assertThat(path).contains("u-dima-Багет-1")
        assertThat(path).contains("crust.jpg")
        assertThat(mapped.single().recipeName).isEqualTo("Багет")
    }

    @Test
    fun `recordsForUser sets no photoPath if id or photo blank`() {
        val records = listOf(
            bake("u-dima", "Багет", 1, displayName = "Дима", photo = null),
        )
        val mapped = FamilyShelf.recordsForUser(records, "u-dima")
        assertThat(mapped.single().photoPath).isNull()
    }

    @Test
    fun `own book id is me or the signed-in user`() {
        assertThat(FamilyShelf.isOwnBook("me", "u-anya")).isTrue()
        assertThat(FamilyShelf.isOwnBook("u-anya", "u-anya")).isTrue()
        assertThat(FamilyShelf.isOwnBook("u-dima", "u-anya")).isFalse()
    }

    @Test
    fun `local ledger uses the name on this phone and never a blank who`() {
        val rows = FamilyShelf.ledgerFromLocal(
            listOf(
                BakeRecordEntity(
                    id = 1,
                    recipeId = "rye",
                    recipeName = "Ржаной",
                    portions = 1,
                    completedAtMillis = now,
                ),
            ),
            who = "  ",
            zone = ZoneOffset.UTC,
        )
        assertThat(rows.single().who).isEqualTo("вы")
        assertThat(rows.single().chapter).isEqualTo("Ржаной")
    }
}
