package com.polinalinen.madre.familyui

import android.app.Application
import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.polinalinen.madre.integration.v2.FamilyInvitation
import com.polinalinen.madre.ui.screens.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FamilyInvitePresentationTest {
    @Test fun namesUseOnlyVerifiedSurnameOrSafeFallbackNeverDisplayName() {
        assertEquals("Наша семья", suggestedFamilyName())
        listOf("Дима", "Иваненко", "", "<script>", "Каменев Иван").forEach { assertEquals("Наша семья", suggestedFamilyName(it)) }
        assertEquals("Семья Каменевых", suggestedFamilyName("Каменев"))
        assertEquals("Семья Каменевых", suggestedFamilyName("Каменева"))
        assertEquals("Семья Ивановых", suggestedFamilyName("Иванов"))
        assertEquals("Семья Синицыных", suggestedFamilyName("Синицына"))
        assertEquals("Галя", familyEditedName(" галя "))
    }
    @Test fun generatedInviteQrDecodesCanonicalHttpsWithMCorrectionAndWhiteQuietZone() {
        val url = "https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP"
        val image = familyInviteQr(checkNotNull(FamilyInvitation.parse(url)))
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
        assertEquals(url, result.text)
        assertEquals("M", result.resultMetadata[com.google.zxing.ResultMetadataType.ERROR_CORRECTION_LEVEL])
        for (i in 0 until image.width) { assertEquals(Color.WHITE, image.getPixel(i, 0)); assertEquals(Color.WHITE, image.getPixel(i, image.height - 1)) }
    }
}
