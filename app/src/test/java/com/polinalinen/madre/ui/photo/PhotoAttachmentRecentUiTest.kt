package com.polinalinen.madre.ui.photo

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.utils.PhotoStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic private images only. No real picker, permission prompt, phone or historical user photo. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class PhotoAttachmentRecentUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private fun image(directory:File):File {
        val file=File.createTempFile("recent-fixture-",".jpg",directory)
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}
        bitmap.recycle()
        return file
    }
    private fun staging()=File(app.cacheDir,"camera").listFiles()?.map{it.absolutePath}?.toSet()?:emptySet()

    @Test fun recentPhotoCopiesIntoTheSharedEditorAndCancelPreservesTheOriginal() {
        val original=image(app.filesDir)
        val before=original.readBytes()
        val stagedBefore=staging()
        var cancelled=0
        var attached=0
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN) {
            val actions=rememberPhotoAttachmentActions(PhotoStore.PhotoKind.BAKE,12L,{cancelled++}){attached++}
            PaperButton("Недавний кадр",{actions.recent(original.absolutePath)})
        }}
        rule.onAllNodesWithText("Рамка").assertCountEquals(0)
        assertEquals(stagedBefore,staging())
        rule.onNodeWithText("Недавний кадр").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithText("Рамка").fetchSemanticsNodes().isNotEmpty()}
        val staged=staging()-stagedBefore
        assertEquals(1,staged.size)
        assertNotEquals(original.absolutePath,staged.single())
        assertArrayEquals(before,File(staged.single()).readBytes())
        rule.onNodeWithText("← Отмена").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithText("Рамка").fetchSemanticsNodes().isEmpty() && cancelled==1}
        assertEquals(0,attached)
        assertEquals(stagedBefore,staging())
        assertTrue(original.isFile)
        assertArrayEquals(before,original.readBytes())
    }

    @Test fun recentEntryRejectsAnUnownedPathWithoutOpeningEditorOrTouchingItsBytes() {
        val outside=image(app.cacheDir)
        val before=outside.readBytes()
        val stagedBefore=staging()
        var cancelled=0
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT) {
            val actions=rememberPhotoAttachmentActions(PhotoStore.PhotoKind.BAKE,12L,{cancelled++}){fail("Unowned image must not attach")}
            PaperButton("Чужой путь",{actions.recent(outside.absolutePath)})
        }}
        rule.onNodeWithText("Чужой путь").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithText("Чужой путь").fetchSemanticsNodes();cancelled==1}
        rule.onAllNodesWithText("Рамка").assertCountEquals(0)
        assertEquals(stagedBefore,staging())
        assertArrayEquals(before,outside.readBytes())
    }
}
