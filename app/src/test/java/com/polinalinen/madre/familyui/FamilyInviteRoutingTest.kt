package com.polinalinen.madre.familyui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.integration.v2.FamilyInviteRouting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FamilyInviteRoutingTest {
    private val url = "https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP"
    private fun intent(value: String = url) = Intent(Intent.ACTION_VIEW, Uri.parse(value))
    @Test fun productionQueueAcceptsColdAndWarmLinksAndRestoresOnlyCanonicalInvite() {
        val cold = FamilyInviteRouting.Queue { true }; cold.accept(intent()); assertEquals(url, cold.pending.value)
        val saved = cold.pending.value
        val rotated = FamilyInviteRouting.Queue { true }; rotated.restore(saved); assertEquals(url, rotated.pending.value)
        rotated.pending.value = null
        val warm = "https://kdnfx.space/madre/f/QRSTUVWXYZabcdef"
        rotated.accept(intent(warm)); assertEquals(warm, rotated.pending.value)
        rotated.restore("SYNTHETIC_OAUTH"); assertNull(rotated.pending.value)
    }
    @Test fun exactCaseSchemeHostPathLengthNoPortUserInfoQueryFragmentEncodingOrClipboardGuessing() {
        val invalid = listOf(url.replace("https", "http"), url.replace("https", "HTTPS"), url.replace("space/", "space:443/"),
            url.replace("kdnfx", "KDNFX"), url.replace("kdnfx.space", "evil.kdnfx.space"), url.replace("kdnfx.space", "user@kdnfx.space"),
            "$url/", "$url?x=1", "$url#fragment", " $url", "$url ", url.dropLast(1), url + "A", url.replace("ABC", "%41BC"),
            url.replace("/madre/f/", "/madre/F/"), url.replace("/madre/f/", "/madre/f/../"))
        invalid.forEach { assertNull(it, FamilyInviteRouting.read(intent(it), true)) }
        assertNull(FamilyInviteRouting.read(Intent(Intent.ACTION_SEND).setData(Uri.parse(url)), true))
        assertNull(FamilyInviteRouting.read(intent(), false))
        val disabled = FamilyInviteRouting.Queue { false }; disabled.accept(intent()); disabled.restore(url); assertNull(disabled.pending.value)
    }
    @Test fun mainManifestDeclaresOnlyTheApprovedVerifiedHttpsSurfaceAndKeepsSingleTop() {
        val module = if (File("app/src/main/AndroidManifest.xml").isFile) File("app") else File(".")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File(module, "src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activity = (0 until document.getElementsByTagName("activity").length).map { document.getElementsByTagName("activity").item(it) as org.w3c.dom.Element }.single { it.getAttributeNS(android, "name") == ".MainActivity" }
        assertEquals("singleTop", activity.getAttributeNS(android, "launchMode"))
        val filters = activity.getElementsByTagName("intent-filter")
        val verified = (0 until filters.length).map { filters.item(it) as org.w3c.dom.Element }.single { it.getAttributeNS(android, "autoVerify") == "true" }
        val data = verified.getElementsByTagName("data").item(0) as org.w3c.dom.Element
        assertEquals("https", data.getAttributeNS(android, "scheme")); assertEquals("kdnfx.space", data.getAttributeNS(android, "host")); assertEquals("/madre/f/", data.getAttributeNS(android, "pathPrefix"))
        val action = verified.getElementsByTagName("action").item(0) as org.w3c.dom.Element
        assertEquals(Intent.ACTION_VIEW, action.getAttributeNS(android, "name"))
        val categories = verified.getElementsByTagName("category")
        assertEquals(setOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE), (0 until categories.length).map { (categories.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }.toSet())
    }
}
