package com.polinalinen.madre.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

/**
 * Cycle 11: контракт с PocketBase снова держится на именах полей. Регистрация
 * и вход — системные роуты auth-коллекции (camelCase, как в PocketBase), свои
 * роуты семьи — snake_case, как остальные коллекции Мадре.
 */
class FamilyBookRecordsTest {

    private val gson = Gson()

    @Test
    fun `register request matches the pocketbase users collection`() {
        val json = gson.toJson(
            RegisterRequest(
                email = "anya@example.com",
                password = "длинный-пароль",
                passwordConfirm = "длинный-пароль",
                name = "Аня",
            )
        )
        assertThat(json).contains("\"email\":\"anya@example.com\"")
        assertThat(json).contains("\"passwordConfirm\":\"длинный-пароль\"")
        assertThat(json).contains("\"name\":\"Аня\"")
    }

    @Test
    fun `password auth request uses the identity field`() {
        val json = gson.toJson(PasswordAuthRequest(identity = "anya@example.com", password = "пароль"))
        assertThat(json).contains("\"identity\":\"anya@example.com\"")
        assertThat(json).contains("\"password\":\"пароль\"")
    }

    @Test
    fun `auth response parses token and user record`() {
        val json = """
            {"token":"pb_token_1","record":{"id":"u1","email":"anya@example.com","name":"Аня","family":"f1"}}
        """.trimIndent()
        val response = gson.fromJson(json, AuthResponse::class.java)
        assertThat(response.token).isEqualTo("pb_token_1")
        assertThat(response.record.id).isEqualTo("u1")
        assertThat(response.record.family).isEqualTo("f1")
    }

    @Test
    fun `user without a family parses with a blank relation`() {
        val json = """{"token":"t","record":{"id":"u1","email":"a@b.c","name":"Аня","family":""}}"""
        val response = gson.fromJson(json, AuthResponse::class.java)
        assertThat(response.record.family).isEmpty()
    }

    @Test
    fun `user without a family also accepts pocketbase null relation`() {
        val json = """{"token":"t","record":{"id":"u1","email":"a@b.c","name":"Аня","family":null}}"""
        val response = gson.fromJson(json, AuthResponse::class.java)
        assertThat(response.record.family).isNull()
    }

    @Test
    fun `family routes speak snake_case`() {
        assertThat(gson.toJson(CreateFamilyRequest(name = "Ивановы"))).contains("\"name\":\"Ивановы\"")
        assertThat(gson.toJson(JoinFamilyRequest(code = "2W4X6Y8ZABCDEFGH")))
            .contains("\"code\":\"2W4X6Y8ZABCDEFGH\"")

        val response = gson.fromJson(
            """{"family_id":"f1","family_name":"Ивановы","invite_code":"2W4X6Y8ZABCDEFGH"}""",
            FamilyResponse::class.java,
        )
        assertThat(response.familyId).isEqualTo("f1")
        assertThat(response.familyName).isEqualTo("Ивановы")
        assertThat(response.inviteCode).isEqualTo("2W4X6Y8ZABCDEFGH")
    }

    @Test
    fun `join response carries no invite code`() {
        val response = gson.fromJson(
            """{"family_id":"f1","family_name":"Ивановы"}""",
            FamilyResponse::class.java,
        )
        assertThat(response.inviteCode).isNull()
    }

    @Test
    fun `family record parses the collection row`() {
        val record = gson.fromJson("""{"id":"f1","name":"Ивановы","owner":"u1"}""", FamilyRecord::class.java)
        assertThat(record.id).isEqualTo("f1")
        assertThat(record.name).isEqualTo("Ивановы")
        assertThat(record.owner).isEqualTo("u1")
    }
}
