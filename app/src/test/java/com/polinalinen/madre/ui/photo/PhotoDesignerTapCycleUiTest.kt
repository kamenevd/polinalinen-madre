package com.polinalinen.madre.ui.photo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.theme.MadreTheme
import com.polinalinen.madre.utils.PhotoStore
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class PhotoDesignerTapCycleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stagedPhoto(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("photo-designer-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFCCAA77.toInt())
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun `designer uses tap rows for frame warm stamp and corner`() {
        val staged = stagedPhoto()
        rule.setContent {
            MadreTheme {
                PhotoDesignerDialog(
                    staged = staged,
                    kind = PhotoStore.PhotoKind.BAKE,
                    key = 12L,
                    onCancel = {},
                    onSaved = {},
                )
            }
        }

        rule.onNodeWithText("Рамка").assertIsDisplayed()
        rule.onNodeWithText("Тёплый свет").assertIsDisplayed()
        rule.onNodeWithText("Оттиск").assertIsDisplayed()

        rule.onNodeWithText("без оттиска").performClick()
        rule.onNodeWithText("колос").assertIsDisplayed()
        rule.onNodeWithText("Угол").assertIsDisplayed()

        rule.onNodeWithText("колос").performClick()
        rule.onNodeWithText("каравай").assertIsDisplayed()
        rule.onNodeWithText("каравай").performClick()
        rule.onNodeWithText("розетка").assertIsDisplayed()
        rule.onNodeWithText("розетка").performClick()
        rule.onNodeWithText("без оттиска").assertIsDisplayed()
        rule.onNodeWithText("Угол").assertDoesNotExist()
    }
}
