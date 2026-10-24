package com.polinalinen.madre.account.yandex

import org.junit.Assert.*
import org.junit.Test

class YandexProfileAndTimerTest {
    private fun profile(avatar:String?="12345/test-avatar",empty:Boolean=false)=VerifiedYandexProfile(
        "synthetic_uid_hash","fixture-login","Синтетическое имя",avatar,empty,1000L)

    @Test fun profileOnlySuggestsAnEmptyBookNameAndNeverChangesAUserName() {
        assertEquals("Синтетическое имя",profile().bookNameSuggestion(""))
        assertNull(profile().bookNameSuggestion("Пользовательский экслибрис"))
        assertFalse(profile().toString().contains("fixture-login"))
    }

    @Test fun avatarCannotBecomeAnArbitraryHostOrPathAndMissingAvatarIsAMonogram() {
        assertEquals("https://avatars.yandex.net/get-yapic/12345/test-avatar/islands-retina-middle",profile().avatarUrl)
        assertNull(profile(empty=true).avatarUrl)
        assertNull(profile(avatar=null,empty=true).avatarUrl)
        assertNull(profile(avatar="",empty=true).avatarUrl)
        listOf("../../private","https://another.example/avatar","avatar?token=synthetic").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java){profile(avatar=bad)}
        }
    }

    @Test fun stationPhraseUsesLiveRemainderRoundedUpNotOriginalStepDuration() {
        assertEquals("Алиса, поставь таймер на 40 минут",StationTimerPrompt.phrase(2400))
        assertEquals("Алиса, поставь таймер на 41 минуту",StationTimerPrompt.phrase(2401))
        assertEquals("Алиса, поставь таймер на 1 минуту",StationTimerPrompt.phrase(1))
        assertEquals("Алиса, поставь таймер на 2 минуты",StationTimerPrompt.phrase(61))
        assertNull(StationTimerPrompt.phrase(0))
        assertNull(StationTimerPrompt.phrase(-1))
    }
}
