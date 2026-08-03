package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlin.math.ln
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 11, фича «Семейная книга»: код приглашения диктуют вслух
 * и переписывают от руки, поэтому его нормализация обязана прощать регистр,
 * дефисы и путаницу O/0, I/1 — но при этом не терять стойкость.
 */
class InviteCodeTest {

    @Test
    fun `alphabet is 32 distinct crockford base32 characters`() {
        assertThat(InviteCode.ALPHABET.toSet()).hasSize(32)
        assertThat(InviteCode.ALPHABET).doesNotContain("I")
        assertThat(InviteCode.ALPHABET).doesNotContain("L")
        assertThat(InviteCode.ALPHABET).doesNotContain("O")
        assertThat(InviteCode.ALPHABET).doesNotContain("U")
    }

    @Test
    fun `code carries at least 80 bits of entropy`() {
        val bits = InviteCode.LENGTH * (ln(InviteCode.ALPHABET.length.toDouble()) / ln(2.0))
        assertThat(bits).isAtLeast(80.0)
        assertThat(InviteCode.ENTROPY_BITS).isAtLeast(80)
    }

    @Test
    fun `normalize accepts the formatted shape as written on paper`() {
        assertThat(InviteCode.normalize("2W4X-6Y8Z-ABCD-EFGH")).isEqualTo("2W4X6Y8ZABCDEFGH")
    }

    @Test
    fun `normalize is case insensitive and ignores stray separators`() {
        assertThat(InviteCode.normalize("  2w4x 6y8z_abcd.efgh ")).isEqualTo("2W4X6Y8ZABCDEFGH")
    }

    @Test
    fun `normalize folds the characters people confuse`() {
        // O→0, I/L→1 — канон Crockford base32.
        assertThat(InviteCode.normalize("OIL2345678ABCDEF")).isEqualTo("0112345678ABCDEF")
    }

    @Test
    fun `normalize rejects wrong length`() {
        assertThat(InviteCode.normalize("2W4X6Y8Z")).isNull()
        assertThat(InviteCode.normalize("2W4X6Y8ZABCDEFGHJ")).isNull()
        assertThat(InviteCode.normalize("")).isNull()
        assertThat(InviteCode.normalize("   ")).isNull()
    }

    @Test
    fun `normalize rejects characters outside the alphabet`() {
        assertThat(InviteCode.normalize("2W4X6Y8ZABCDEFGU")).isNull()
        assertThat(InviteCode.normalize("2W4X6Y8ZABCDEFGЖ")).isNull()
    }

    @Test
    fun `format groups the code into readable quads`() {
        assertThat(InviteCode.format("2W4X6Y8ZABCDEFGH")).isEqualTo("2W4X-6Y8Z-ABCD-EFGH")
    }

    @Test
    fun `format leaves anything it cannot group untouched`() {
        assertThat(InviteCode.format("короткий")).isEqualTo("короткий")
    }

    @Test
    fun `isValid mirrors normalize`() {
        assertThat(InviteCode.isValid("2w4x-6y8z-abcd-efgh")).isTrue()
        assertThat(InviteCode.isValid("не код")).isFalse()
    }
}
