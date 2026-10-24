package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[29,31],qualifiers="ru-w412dp-h915dp-mdpi")
class StaticFontCompatibilityTest {
    @Test fun cyrillicItalicAndStaticTimerRenderNativelyOnAndroid10And12() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=ReadingPreferences(context,"font-compat-${Build.VERSION.SDK_INT}")
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        val output=File(module,"build/outputs/roborazzi/font-compat-api${Build.VERSION.SDK_INT}.png")
        output.parentFile.mkdirs()
        captureRoboImage(filePath=output.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
            RazvorotTheme(prefs,Atmosphere.DAWN){FontCompatibilityPage()}
        }
        val image=android.graphics.BitmapFactory.decodeFile(output.absolutePath)
        assertThat(image.width).isEqualTo(412);assertThat(image.height).isEqualTo(915)
        assertThat(output.length()).isGreaterThan(1000L);image.recycle()
    }
}
