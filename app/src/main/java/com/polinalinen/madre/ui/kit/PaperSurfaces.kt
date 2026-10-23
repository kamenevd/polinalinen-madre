package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.random.Random

/** One cached, deterministic atmosphere/grain layer for all service screens. */
@Composable fun Modifier.paperBackground(): Modifier {
    val p = LocalPaper.current
    val atmosphere = LocalAtmosphere.current
    return drawWithCache {
        val random = Random(71)
        val grains = List((size.width * size.height / 90f).toInt().coerceAtMost(12000)) {
            Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        }
        val glow = when (atmosphere) {
            Atmosphere.NIGHT -> p.acc.copy(alpha = .035f)
            Atmosphere.DUSK -> p.acc.copy(alpha = .08f)
            else -> p.acc2.copy(alpha = .32f)
        }
        val brush = Brush.radialGradient(listOf(glow, Color.Transparent),
            center = Offset(size.width, 0f), radius = size.height.coerceAtLeast(1f) * .75f)
        onDrawBehind {
            drawRect(p.bg)
            drawRect(brush)
            grains.forEach { drawCircle(p.ink.copy(alpha = .03f), .45.dp.toPx(), it) }
        }
    }
}

/** Compatibility surface for existing layouts; containers remain owned by kit. */
@Composable fun PaperSurface(modifier: Modifier = Modifier, color: Color = LocalPaper.current.bg,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape, content: @Composable () -> Unit) {
    val p = LocalPaper.current
    Box(modifier.then(if (color == p.bg) Modifier.paperBackground().safeDrawingPadding() else Modifier.background(color, shape))) {
        content()
    }
}

@Composable fun PaperScaffold(modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}, bottom: @Composable () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxSize().paperBackground().safeDrawingPadding().imePadding()) {
        header()
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding, content = content)
        bottom()
    }
}

@Composable fun PaperHeader(title: String, onBack: (() -> Unit)? = null, overline: String? = null,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) PaperIconButton(onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", Modifier.size(24.dp))
            }
            if (overline != null) SmallLabel(overline, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            actions()
        }
        Text(title, style = PaperType.h1, color = LocalPaper.current.ink)
    }
}

@Composable fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaper.current
    Column(modifier.fillMaxWidth().drawBehind {
        drawRoundRect(p.line, topLeft = Offset(0f, 1.dp.toPx()), cornerRadius = CornerRadius(20.dp.toPx()))
    }.clip(RoundedCornerShape(20.dp)).background(p.card).padding(18.dp), content = content)
}

@Composable fun PaperSection(overline: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SmallLabel(overline, Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable fun PaperListRow(title: String, modifier: Modifier = Modifier, note: String? = null,
    value: String? = null, valueColor: Color = LocalPaper.current.acc,
    onClick: (() -> Unit)? = null, repeatable: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPaper.current
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .then(if (onClick != null) com.polinalinen.madre.ui.components.bookAction("$title: ${value.orEmpty()}", repeatable = repeatable, onClick = onClick) else Modifier)
        .drawBehind { drawLine(p.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, style = PaperType.title)
            if (note != null) Text(note, color = p.mut, style = PaperType.note, modifier = Modifier.padding(top = 4.dp))
        }
        if (value != null) Text(value, color = valueColor, style = PaperType.ui, modifier = Modifier.padding(start = 12.dp))
        if (trailing != null) trailing()
    }
}

/** Form/content slots keep the caller's state and callbacks outside the kit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PaperDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null) {
    val p = LocalPaper.current
    PaperWindow(onDismissRequest) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.card).padding(24.dp)) {
            ProvideTextStyle(PaperType.h2) { title() }
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalContentColor provides p.ink2) { ProvideTextStyle(PaperType.body) { text() } }
            Spacer(Modifier.height(24.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable fun PaperConfirmDialog(title: String, message: String, confirmLabel: String, dismissLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    PaperDialog(onDismiss, title = { Text(title, color = LocalPaper.current.ink, style = PaperType.h2) },
        text = { Text(message, color = LocalPaper.current.ink2, style = PaperType.body) },
        confirmButton = {
            if (destructive) PaperTextAction(confirmLabel, onConfirm, destructive = true)
            else PaperButton(label = confirmLabel, onClick = onConfirm, variant = PaperButtonVariant.PRIMARY)
        }, dismissButton = { PaperButton(label = dismissLabel, onClick = onDismiss, variant = PaperButtonVariant.SECONDARY) })
}

/** Owns platform dialog dimming, including warm/night scrim, in exactly one place. */
@Composable fun PaperWindow(onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(decorFitsSystemWindows = false), content: @Composable () -> Unit) {
    val p = LocalPaper.current
    Dialog(onDismissRequest, properties = properties) {
        PaperModalWindow()
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view, p.scrimAlpha) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(p.scrimAlpha)
            }
            onDispose {}
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PaperSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaper.current
    ModalBottomSheet(onDismissRequest, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.card, contentColor = p.ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = p.scrim,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 16.dp).size(36.dp, 4.dp).background(p.line, RoundedCornerShape(2.dp))) },
        contentWindowInsets = { WindowInsets.navigationBars.union(WindowInsets.ime) },
        content = { PaperModalWindow(); content() })
}

@Composable fun PaperPhotoViewer(onDismissRequest: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    PaperWindow(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Immersive()
        CompositionLocalProvider(LocalPaper provides NightPaper, LocalContentColor provides NightPaper.ink) {
            Box(Modifier.fillMaxSize().background(Illustration.PhotoBackdrop), content = content)
        }
    }
}

@Composable fun PaperEmpty(overline: String, note: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    val p = LocalPaper.current
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Image, null, Modifier.size(64.dp), tint = p.mut)
        SmallLabel(overline, Modifier.padding(top = 16.dp))
        Text(note, color = p.mut, style = PaperType.note.copy(fontSize = 17.sp),
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
        action?.invoke()
    }
}

@Composable fun PaperProgress(progress: Float? = null, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    if (progress == null) CircularProgressIndicator(modifier.size(24.dp), color = p.acc, strokeWidth = 2.dp, trackColor = p.line)
    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = p.acc, trackColor = p.line)
}

@Composable fun PaperSnackbar(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = p.ink,
            contentColor = p.bg, actionColor = p.acc, dismissActionContentColor = p.bg)
    }
}
