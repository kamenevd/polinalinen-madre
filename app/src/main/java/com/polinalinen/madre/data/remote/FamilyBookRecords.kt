package com.polinalinen.madre.data.remote

import com.google.gson.annotations.SerializedName

/**
 * «Семейная книга» (DESIGN-V4.md Cycle 11, фича 28): тела запросов и ответов
 * PocketBase.
 *
 * Имена полей идут двумя разными стилями, и это не небрежность: регистрация и
 * вход — системные маршруты auth-коллекции самого PocketBase, там camelCase
 * (`passwordConfirm`); свои маршруты `/api/madre/family/…` отвечают snake_case,
 * как и остальные коллекции Мадре.
 *
 * Здесь только форма данных — сеть живёт в [FamilyBookApi], состояние и
 * решения о токене в account/FamilyAccountRepository.
 */

data class RegisterRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("passwordConfirm") val passwordConfirm: String,
    @SerializedName("name") val name: String,
)

data class PasswordAuthRequest(
    @SerializedName("identity") val identity: String,
    @SerializedName("password") val password: String,
)

/**
 * Запись пользователя. `family` — relation на коллекцию families; PocketBase
 * отдаёт незаполненную связь пустой строкой, а не null.
 */
data class UserRecord(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("name") val name: String,
    @SerializedName("family") val family: String,
)

data class AuthResponse(
    @SerializedName("token") val token: String,
    @SerializedName("record") val record: UserRecord,
)

data class CreateFamilyRequest(
    @SerializedName("name") val name: String,
)

data class JoinFamilyRequest(
    @SerializedName("code") val code: String,
)

/**
 * Ответ маршрутов семьи. `invite_code` приходит только на создание книги и
 * смену кода — сервер показывает открытый код ровно один раз, поэтому при
 * вступлении по чужому коду поле отсутствует (null).
 */
data class FamilyResponse(
    @SerializedName("family_id") val familyId: String,
    @SerializedName("family_name") val familyName: String,
    @SerializedName("invite_code") val inviteCode: String? = null,
)

/** Строка коллекции families — хэш кода приглашения серверу скрыт (hidden). */
data class FamilyRecord(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("owner") val owner: String,
)
