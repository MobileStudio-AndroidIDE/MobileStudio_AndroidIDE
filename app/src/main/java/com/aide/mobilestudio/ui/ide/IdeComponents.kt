package com.aide.mobilestudio.ui.ide

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.R

/**
 * Shared, flat, dense components in the Android Studio style: toolbar strips
 * instead of app bars, tool tabs, bordered inputs, plain list rows with
 * dividers, status strips. No gradients, no glow, no per-row rounded cards.
 */

// ────────────────────────── Window glyphs (vector) ──────────────────────────

/**
 * A window-chrome icon drawn as a vector drawable, tinted from the palette so
 * dark and light themes both get the real stroked/filled shape.
 */
@Composable
fun IdeIcon(
    iconRes: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val painter: Painter = painterResource(iconRes)
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.paint(
            painter = painter,
            colorFilter = ColorFilter.tint(color),
        ),
    )
}

// ────────────────────────── Top toolbar strip ──────────────────────────

/** 48dp flat toolbar strip. Replaces Material TopAppBar for tool screens. */
@Composable
fun IdeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ide = LocalIdeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IdeDimens.toolbar)
            .background(Color(ide.p.surface))
            .border(0.5.dp, ide.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(IdeDimens.toolbar)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(
                    iconRes = R.drawable.ic_ide_back,
                    color = ide.textSoft,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 2.dp else IdeDimens.gutter, end = IdeDimens.gutterTight)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = ide.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) IdeMono.path else MaterialTheme.typography.bodySmall,
                    color = ide.textSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IdeDimens.space2),
            modifier = Modifier.padding(end = IdeDimens.gutterTight),
            content = actions,
        )
    }
}

/** Compact toolbar action button (30dp). primary = accent fill, danger = red text on flat surface. */
@Composable
fun IdeAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    iconRes: Int? = null,
    iconColor: Color = Color.Unspecified,
    iconSize: Dp = 16.dp,
) {
    val ide = LocalIdeColors.current
    val shapes = LocalShapes.current
    val fg = when {
        !enabled -> ide.disabled
        primary -> ide.onAccent
        danger -> ide.error
        else -> ide.text
    }
    val bg = when {
        primary && enabled -> ide.accent
        primary -> ide.accent.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val effectiveIconColor = iconColor.takeIf { it != Color.Unspecified } ?: fg
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = IdeDimens.control, minWidth = 28.dp)
            .background(bg, shapes.control)
            .then(
                if (primary) Modifier
                else Modifier.border(0.5.dp, if (enabled) ide.border else ide.divider, shapes.control)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            IdeIcon(
                iconRes = iconRes,
                color = effectiveIconColor,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/**
 * Square glyph button for tool toolbars (back, overflow, refresh, close).
 *
 * Uses a text glyph because the IDE deliberately ships no icon font; the glyph
 * lives in [IdeGlyphs] so call sites stay readable and consistent.
 */
@Composable
fun IdeIconButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentDescription: String? = null,
    iconRes: Int? = null,
) {
    val ide = LocalIdeColors.current
    val shapes = LocalShapes.current
    Box(
        modifier = modifier
            .size(IdeDimens.control)
            .background(if (selected) ide.selectedBg else Color.Transparent, shapes.control)
            .border(
                0.5.dp,
                when {
                    !enabled -> ide.divider
                    selected -> ide.accent
                    else -> ide.border
                },
                shapes.control,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            IdeIcon(
                iconRes = iconRes,
                color = if (selected) ide.accent else ide.textSoft,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                glyph,
                color = when {
                    !enabled -> ide.disabled
                    selected -> ide.accent
                    else -> ide.textSoft
                },
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

/** Small flat selectable chip — quick locations, filters. Never a pill. */
@Composable
fun IdeChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ide = LocalIdeColors.current
    val shapes = LocalShapes.current
    val fg = when {
        !enabled -> ide.disabled
        selected -> ide.text
        else -> ide.textSoft
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 28.dp)
            .background(if (selected) ide.selectedBg else Color.Transparent, shapes.control)
            .border(0.5.dp, if (selected) ide.accent else if (enabled) ide.border else ide.divider, shapes.control)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Two-column key/value row for info sheets (media metadata, build details). */
@Composable
fun IdeKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 120.dp,
) {
    val ide = LocalIdeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = IdeDimens.space1),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = ide.textSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
        )
        Spacer(Modifier.width(IdeDimens.space4))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = ide.text,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Canonical toolbar glyphs, so screens never invent their own. */
object IdeGlyphs {
    const val back = "‹"
    const val close = "✕"
    const val overflow = "⋮"
    const val refresh = "↻"
    const val up = "↑"
    const val search = "⌕"
    const val sort = "⇅"
    const val hiddenShown = "◉"
    const val hiddenOff = "◌"
}

// ────────────────────────── Tab strip ──────────────────────────

/** IntelliJ-style tool tabs in a 30dp strip: the selected tab gets a 2dp accent underline. */
@Composable
fun IdeTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ide = LocalIdeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(Color(ide.p.surface))
            .border(0.5.dp, ide.divider),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable { onSelect(index) }
                    .background(if (active) Color(ide.p.card) else Color.Transparent),
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) ide.text else ide.textSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (active) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(ide.accent)
                            .align(Alignment.BottomCenter)
                    )
                }
            }
            if (index != tabs.lastIndex) {
                Box(Modifier.width(1.dp).height(14.dp).background(ide.divider))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ────────────────────────── Inputs ──────────────────────────

@Composable
private fun IdeSelectionColors(content: @Composable () -> Unit) {
    val ide = LocalIdeColors.current
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = ide.accent,
            backgroundColor = ide.selection,
        ),
        content = content,
    )
}

/** Compact 34dp filter input: editor-colored field, 0.5dp border, borderless when focused style. */
@Composable
fun IdeFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    imeAction: ImeAction = ImeAction.Search,
) {
    val ide = LocalIdeColors.current
    IdeSelectionColors {
        Row(
            modifier = modifier
                .heightIn(min = 34.dp)
                .background(Color(ide.p.editorBg), LocalShapes.current.field)
                .border(0.5.dp, ide.border, LocalShapes.current.field)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = (if (mono) IdeMono.log else MaterialTheme.typography.bodyMedium)
                    .copy(color = ide.text),
                cursorBrush = SolidColor(ide.accent),
                keyboardOptions = KeyboardOptions(
                    imeAction = imeAction,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.None,
                ),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = ide.muted,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

// ────────────────────────── Panels & sections ──────────────────────────

/** Bordered flat panel; replaces rounded cards for info blocks. */
@Composable
fun IdePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ide = LocalIdeColors.current
    Column(
        modifier = modifier
            .background(Color(ide.p.card), LocalShapes.current.panel)
            .border(0.5.dp, ide.border, LocalShapes.current.panel)
            .padding(10.dp),
        content = content,
    )
}

@Composable
fun IdeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = LocalIdeColors.current.textSoft,
    )
}

/** 1dp hairline divider (horizontal by default). */
@Composable
fun IdeDivider(horizontal: Boolean = true) {
    Box(
        Modifier
            .then(if (horizontal) Modifier.fillMaxWidth().height(1.dp) else Modifier.fillMaxHeight().width(1.dp))
            .background(LocalIdeColors.current.divider)
    )
}

// ────────────────────────── List rows ──────────────────────────

/** Flat list row, min 44dp tall, optional select/press highlight. No per-row cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdeListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    titleColor: Color = LocalIdeColors.current.text,
    titleMono: Boolean = false,
    titleEmphasis: Boolean = false,
    subtitleMono: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val ide = LocalIdeColors.current
    val clickableModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            onClick = onClick, onLongClick = onLongClick,
        )
        onLongClick != null -> Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (selected) ide.selectedBg else Color.Transparent)
            .then(clickableModifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = when {
                    titleMono -> IdeMono.path
                    titleEmphasis -> MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    else -> MaterialTheme.typography.bodyLarge
                },
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) IdeMono.log else MaterialTheme.typography.bodySmall,
                    color = ide.textSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
    }
}

// ────────────────────────── Dots & status strip ──────────────────────────

/** 8dp state dot — for statuses, not decoration. */
@Composable
fun IdeDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, LocalShapes.current.pill))
}

/** Fixed bottom status strip (24dp) with left and right content slots. */
@Composable
fun IdeStatusStrip(
    left: @Composable RowScope.() -> Unit = {},
    right: @Composable RowScope.() -> Unit = {},
) {
    val ide = LocalIdeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(ide.p.surface))
            .border(0.5.dp, ide.divider),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = left,
        )
        Row(
            modifier = Modifier.padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = right,
        )
    }
}

// ────────────────────────── Empty state ──────────────────────────

@Composable
fun IdeEmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    val ide = LocalIdeColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("—", style = MaterialTheme.typography.titleLarge, color = ide.disabled)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = ide.textSoft)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = ide.muted)
        }
    }
}
