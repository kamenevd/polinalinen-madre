package com.polinalinen.madre.ui.kit

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.polinalinen.madre.R

/** Bitmap texture fallback required on older/Huawei platforms by SPEC §10. */
object PaperFonts {
    fun roman(context: Context): Typeface = ResourcesCompat.getFont(context, R.font.literata_400) ?: Typeface.SERIF
    fun light(context: Context): Typeface = ResourcesCompat.getFont(context, R.font.literata_200) ?: Typeface.SERIF
    fun medium(context: Context): Typeface = ResourcesCompat.getFont(context, R.font.literata_500) ?: Typeface.SERIF
    fun italic(context: Context): Typeface = ResourcesCompat.getFont(context, R.font.literata_italic_500) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    fun ui(context: Context): Typeface = ResourcesCompat.getFont(context, R.font.manrope_700) ?: Typeface.SANS_SERIF
}
