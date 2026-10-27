package com.polinalinen.madre.ui.screens

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.polinalinen.madre.integration.v2.FamilyInvitation

/** The existing session exposes only a display name, NOT a verified surname. Never guess from it. */
internal fun suggestedFamilyName(verifiedSurname: String? = null): String {
    val surname = verifiedSurname?.trim().orEmpty()
    if (!surname.matches(Regex("[А-ЯЁ][а-яё]{2,}"))) return "Наша семья"
    val stem = when {
        listOf("ова", "ева", "ина", "ына").any { surname.endsWith(it) } -> surname.dropLast(1)
        listOf("ов", "ев", "ин", "ын").any { surname.endsWith(it) } -> surname
        else -> return "Наша семья"
    }
    return "Семья ${stem}ых"
}

internal fun familyEditedName(value: String): String = value.trim().replaceFirstChar { it.titlecase() }

/** Invite only: typed canonical HTTPS input, M correction, four-module quiet zone, no camera. */
internal fun familyInviteQr(invitation: FamilyInvitation): Bitmap {
    val matrix = QRCodeWriter().encode(invitation.savedInviteUrl(), BarcodeFormat.QR_CODE, 512, 512,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
