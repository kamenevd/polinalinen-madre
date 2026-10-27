package com.polinalinen.madre.ui.razvorot

/** Presentation only, after RecipeScaler. Unrecognised/non-quantified prose remains byte-for-byte intact. */
internal fun ingredientDisplayParts(text:String):Pair<String,String> {
    val match=Regex("^(\\d+(?:[.,]\\d+)?\\s+\\S+)\\s+(.+)$").matchEntire(text)
    return if(match==null)text to "" else match.groupValues[2] to match.groupValues[1]
}
