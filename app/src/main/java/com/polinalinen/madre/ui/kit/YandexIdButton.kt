package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.components.TapGate

enum class YandexIdButtonSize { L, M }

/**
 * Бренд Яндекса: единственное узкое исключение. Black/white and the official
 * unmodified logo/text vector paths MUST NOT be tinted to the paper palette.
 * Source/hash provenance is bundled in raw/yandex_id_button_provenance.json.
 */
@Composable
fun YandexIdButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: YandexIdButtonSize = YandexIdButtonSize.M,
    dark: Boolean = LocalAtmosphere.current == Atmosphere.NIGHT,
    enabled: Boolean = true,
) {
    val artworkHeight = if (size == YandexIdButtonSize.L) 56.dp else 44.dp
    val targetHeight = if (size == YandexIdButtonSize.L) 56.dp else 48.dp
    val background = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val interactions = remember { MutableInteractionSource() }
    val gate = remember { TapGate() }
    Box(modifier.fillMaxWidth().height(targetHeight)
        .semantics { contentDescription = "Войти с Яндекс ID" }
        .clickable(interactionSource = interactions, indication = null, enabled = enabled,
            role = Role.Button, onClickLabel = "Войти с Яндекс ID") {
            if (gate.accept(System.currentTimeMillis())) onClick()
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(artworkHeight)
            .clip(RoundedCornerShape(artworkHeight / 2)).background(background),
            contentAlignment = Alignment.Center) {
            Image(painterResource(if (dark) R.drawable.yandex_id_button_white else R.drawable.yandex_id_button_black),
                contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(artworkHeight))
        }
    }
}
