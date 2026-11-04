package com.aide.mobilestudio.ui.ide

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.R

/**
 * IDE window shell modeled on the Android Studio window anatomy:
 *
 *  1. TopToolbar     — menu, title, window-level actions
 *  2. Breadcrumb     — compact navigation of the current location (mono)
 *  3. Content        — the tool itself (editor / list / console)
 *  4. Right tool rail — tool-window buttons (landscape only, when provided)
 *  5. Left tool rail  — drawer toggle + tool buttons (landscape only)
 *  6. Status strip    — pinned at the very bottom, full window width
 *
 * Two orientation-specific arrangements:
 *
 *  - Portrait  : everything stacked — toolbar, secondary toolbar, breadcrumb,
 *    content, status strip. The drawer overlay carries tool windows.
 *  - Landscape : desktop-style — full-width toolbar, then a horizontal body of
 *    left rail | (secondary toolbar / breadcrumb / content) | right rail,
 *    with the status strip pinned across the bottom.
 *
 * [content] is a [ColumnScope] lambda so screens can pin a flexible body with
 * `Modifier.weight(1f)` above their fixed bottom rows — the previous
 * non-scoped lambda let `fillMaxSize` bodies swallow status strips.
 */
@Composable
fun IdeMainLayout(
    title: String,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    breadcrumb: String? = null,
    drawerContent: @Composable () -> Unit = {},
    topActions: @Composable RowScope.() -> Unit = {},
    secondaryActions: (@Composable RowScope.() -> Unit)? = null,
    statusBar: (@Composable () -> Unit)? = null,
    rail: (@Composable ColumnScope.() -> Unit)? = null,
    rightRail: (@Composable ColumnScope.() -> Unit)? = null,
    drawerWidthDp: Dp = 280.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ide = LocalIdeColors.current
    var drawerOpen by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Drawer toggle lives in the toolbar in portrait, in the left rail in
    // landscape — never in two places at once.
    val menuButton: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .requiredSize(IdeDimens.control)
                .clickable { drawerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            IdeIcon(
                iconRes = R.drawable.ic_ide_menu,
                color = ide.textSoft,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    val toolbar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IdeDimens.toolbar)
                .background(Color(ide.p.surface))
                .border(0.5.dp, ide.border),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!landscape) {
                menuButton()
                Spacer(Modifier.width(IdeDimens.space3))
            }
            if (showBack && onBack != null) {
                Box(
                    modifier = Modifier
                        .requiredSize(IdeDimens.toolbar)
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
                    .padding(
                        start = if (showBack) 0.dp else IdeDimens.gutter,
                        end = IdeDimens.gutterTight,
                    )
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
                modifier = Modifier.padding(end = IdeDimens.gutterTight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IdeDimens.space2),
                content = topActions,
            )
        }
    }

    val secondaryBar: (@Composable () -> Unit)? = secondaryActions?.let { actions ->
        {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color(ide.p.card))
                    .border(0.5.dp, ide.divider)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = IdeDimens.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IdeDimens.space3),
                content = actions,
            )
        }
    }

    val breadcrumbBar: (@Composable () -> Unit)? = breadcrumb?.let { path ->
        {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(Color(ide.p.bg))
                    .border(0.5.dp, ide.divider)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = IdeDimens.gutterTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    path,
                    style = IdeMono.path,
                    color = ide.textSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    val drawerOverlay: @Composable BoxScope.() -> Unit = {
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000))
                    .clickable { drawerOpen = false },
                contentAlignment = Alignment.TopStart,
            ) {
                Column(
                    modifier = Modifier
                        .width(drawerWidthDp)
                        .fillMaxHeight()
                        .background(Color(ide.p.surface))
                        .border(0.5.dp, ide.border),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                    ) {
                        Text(
                            "MobileStudio",
                            style = MaterialTheme.typography.titleMedium,
                            color = ide.text,
                        )
                        Text(
                            subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ide.textSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IdeDivider(horizontal = true)
                    drawerContent()
                }
            }
        }
    }

    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            val errMsg = errorMessage
            if (errMsg != null && errMsg.isNotBlank()) {
                ErrorBanner(errMsg)
            }
            content()
        }
    }

    Column(Modifier.fillMaxSize().background(ide.window)) {
        toolbar()
        if (secondaryBar != null && !landscape) secondaryBar()
        if (breadcrumbBar != null && !landscape) breadcrumbBar()

        if (landscape) {
            // ── Landscape: desktop-style window body ──
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // (5) left tool rail — drawer toggle + screen tool buttons
                Column(
                    modifier = Modifier
                        .width(46.dp)
                        .fillMaxHeight()
                        .background(Color(ide.p.surface))
                        .border(0.5.dp, ide.border)
                        .padding(vertical = IdeDimens.space3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(IdeDimens.space3),
                ) {
                    menuButton()
                    if (rail != null) {
                        IdeDivider(horizontal = true)
                        rail()
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    if (secondaryBar != null) secondaryBar()
                    if (breadcrumbBar != null) breadcrumbBar()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        body()
                        drawerOverlay()
                    }
                }

                // (4) right tool strip — only when the screen has real tools
                if (rightRail != null) {
                    Column(
                        modifier = Modifier
                            .width(46.dp)
                            .fillMaxHeight()
                            .background(Color(ide.p.surface))
                            .border(0.5.dp, ide.border)
                            .padding(vertical = IdeDimens.space3),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(IdeDimens.space3),
                        content = rightRail,
                    )
                }
            }
        } else {
            // ── Portrait: stacked window body ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                body()
                drawerOverlay()
            }
        }

        // (6) status strip — always the last row of the window
        if (statusBar != null) {
            statusBar()
        }
    }
}

/** 에러 배너: 클립보드 이미지 읽기 불가 등 치명적 경고 */
@Composable
private fun ErrorBanner(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFB71C1C))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * One row of the drawer menu.
 *
 * [onClick] is the whole contract: a row with no action renders disabled
 * instead of pretending to work, so the menu cannot grow dead entries again.
 * [checked] marks the current value of a radio/checkbox-style setting.
 */
data class IdeMenuItem(
    val label: String,
    val checked: Boolean = false,
    val danger: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** A titled section of the drawer menu; sections are separated by hairlines. */
data class IdeMenuGroup(val title: String, val items: List<IdeMenuItem>)

/**
 * The window menu (Android Studio style): flat grouped rows where every row
 * performs a real action belonging to the screen that owns the drawer. Screens
 * build the groups themselves — there is no shared list of placeholder items.
 */
@Composable
fun IdeDrawerMenu(groups: List<IdeMenuGroup>, modifier: Modifier = Modifier) {
    val ide = LocalIdeColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        groups.forEachIndexed { groupIndex, group ->
            Text(
                text = group.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ide.muted,
            )
            group.items.forEach { item ->
                val enabled = item.onClick != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { item.onClick?.invoke() }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            !enabled -> ide.disabled
                            item.danger -> ide.error
                            else -> ide.text
                        },
                    )
                    if (item.checked) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ide.accent,
                        )
                    }
                }
            }
            if (groupIndex != groups.lastIndex) {
                IdeDivider(horizontal = true)
            }
        }
    }
}
