package com.aide.mobilestudio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.aide.mobilestudio.AppLanguage
import java.io.File

/** Bilingual label for IDE chrome (the app's own translation table is keyed on Korean). */
private fun Context.ideLabel(en: String, ko: String): String =
    if (AppLanguage.current(this).code == "ko") ko else en

/** True when the open file can be rendered by the layout preview engine. */
internal fun File?.isPreviewableLayout(): Boolean =
    this != null && isFile && extension.lowercase() == "xml"

private fun Context.buildToolsPanel(): View {
    val tabNames = listOf("Build", "APK", "HTML")
    val tabIds = listOf(R.id.tools_tab_build, R.id.tools_tab_apk, R.id.tools_tab_community)
    val panelIds = listOf(R.id.tools_panel_build, R.id.tools_panel_apk, R.id.tools_panel_community)
    return LinearLayout(this).apply {
        id = R.id.tools_panel_root
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c(StudioUi.LayerBottomPanel))
        // Header
        addView(LinearLayout(this@buildToolsPanel).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(c(StudioUi.LayerPanelTabs))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            addView(TextView(this@buildToolsPanel).apply {
                text = "Tools"
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(c("#DCE3EE"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(this@buildToolsPanel).apply {
                id = R.id.btn_close_tools
                text = "\u2630"
                textSize = 16f
                setTextColor(c("#8E99AC"))
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        })
        // Tab strip
        addView(LinearLayout(this@buildToolsPanel).apply {
            id = R.id.tools_tab_strip
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(c(StudioUi.LayerTabs))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            tabNames.forEachIndexed { i, name ->
                addView(TextView(this@buildToolsPanel).apply {
                    id = tabIds[i]
                    text = name
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(c(if (i == 0) "#4F8EF7" else "#8E99AC"))
                    if (i == 0) background = StudioUi.rounded(this@buildToolsPanel, fill = "#232D3B", stroke = "#4F8EF7", radiusDp = 12)
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                })
            }
        })
        // Content area (FrameLayout for tab switching)
        addView(FrameLayout(this@buildToolsPanel).apply {
            id = R.id.tools_panel_content
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            // Build tab
            addView(LinearLayout(this@buildToolsPanel).apply {
                id = R.id.tools_panel_build
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(this@buildToolsPanel).apply {
                    text = "Build"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c(StudioUi.Text))
                })
                addView(LinearLayout(this@buildToolsPanel).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                    addView(TextView(this@buildToolsPanel).apply {
                        id = R.id.tools_build_option_label
                        text = "assembleDebug"
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTextColor(c(StudioUi.TextSoft))
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        background = StudioUi.rounded(this@buildToolsPanel, fill = StudioUi.Surface2, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusMd)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    addView(Button(this@buildToolsPanel).apply {
                        id = R.id.tools_btn_run
                        text = "🛠️ Build"
                        textSize = 11f
                        studioButton("#238636", "#7EE787")
                        layoutParams = LinearLayout.LayoutParams(dp(80), dp(34))
                    })
                })
                addView(LinearLayout(this@buildToolsPanel).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(Button(this@buildToolsPanel).apply {
                        id = R.id.tools_btn_stop
                        text = "⏹️ Stop"
                        textSize = 11f
                        studioButton("#DA3633", "#FF7B72")
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
                    })
                    addView(Button(this@buildToolsPanel).apply {
                        id = R.id.tools_btn_clean
                        text = "\uD83E\uDDF9 Clean"
                        textSize = 11f
                        studioButton("#21262D", "#ABB2BF")
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(4) }
                    })
                    addView(Button(this@buildToolsPanel).apply {
                        id = R.id.tools_btn_apk
                        text = "\uD83D\uDCE6 APK"
                        textSize = 11f
                        studioButton("#1F6FEB", "#58A6FF")
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(4) }
                    })
                })
                addView(ProgressBar(this@buildToolsPanel).apply {
                    id = R.id.tools_build_progress
                    visibility = View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(6) }
                })
                addView(ScrollView(this@buildToolsPanel).apply {
                    addView(LinearLayout(this@buildToolsPanel).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@buildToolsPanel).apply {
                            id = R.id.tools_build_output
                            text = ""
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(c(StudioUi.TextSoft))
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        })
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // APK tab
            addView(LinearLayout(this@buildToolsPanel).apply {
                id = R.id.tools_panel_apk
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(this@buildToolsPanel).apply {
                    text = "APK Download"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(c("#C3CCD8"))
                })
                addView(ScrollView(this@buildToolsPanel).apply {
                    addView(LinearLayout(this@buildToolsPanel).apply {
                        id = R.id.tools_apk_list
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(8), 0, 0)
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // HTML Viewer tab
            addView(LinearLayout(this@buildToolsPanel).apply {
                id = R.id.tools_panel_community
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(this@buildToolsPanel).apply {
                    text = "HTML Viewer"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(c("#C3CCD8"))
                })
                addView(TextView(this@buildToolsPanel).apply {
                    text = "View and edit HTML files with full CSS/JS support."
                    textSize = 11f
                    setTextColor(c("#8E99AC"))
                    setPadding(0, dp(8), 0, dp(8))
                })
                addView(Button(this@buildToolsPanel).apply {
                    id = R.id.tools_community_chat
                    text = "\uD83C\uDF10 Open HTML Viewer"
                    textSize = 11f
                    studioButton("#B57CE7", "#FFFFFF")
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) }
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }
}

internal fun MainActivity.buildMainLayout(): View {
    // In the tablet-landscape window the explorer is docked in the left pane,
    // so there is no START drawer — MainActivity still calls open/close on it
    // from several places, and those become no-ops instead of throwing.
    val threePane = useThreePaneLayout()
    val root = object : DrawerLayout(this) {
        var explorerDockedInPane = false

        private fun startIsDocked(gravity: Int) =
            explorerDockedInPane && gravity == GravityCompat.START

        override fun openDrawer(gravity: Int) {
            if (startIsDocked(gravity)) return
            super.openDrawer(gravity)
        }

        override fun openDrawer(gravity: Int, animate: Boolean) {
            if (startIsDocked(gravity)) return
            super.openDrawer(gravity, animate)
        }

        override fun closeDrawer(gravity: Int) {
            if (startIsDocked(gravity)) return
            super.closeDrawer(gravity)
        }

        override fun closeDrawer(gravity: Int, animate: Boolean) {
            if (startIsDocked(gravity)) return
            super.closeDrawer(gravity, animate)
        }
    }.apply {
        id = R.id.drawer_layout
        setBackgroundColor(c(StudioUi.Ink))
        explorerDockedInPane = threePane
    }
    val explorerSurface = buildDrawerSurface()
    root.addView(buildMainSurface(explorerSurface))
    if (!threePane) {
        val drawerWidth = minOf((resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(340)).coerceAtLeast(dp(240))
        root.addView(explorerSurface, DrawerLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = GravityCompat.START
        })
    }
        // Phase 4.2: Tools panel. DrawerLayout only supports LEFT/RIGHT/NO_GRAVITY
        // children — Gravity.BOTTOM throws "does not have a valid layout_gravity"
        // (IllegalStateException in DrawerLayout.onMeasure). Use an END drawer in
        // BOTH orientations; in landscape it's wider (60% of screen height was the
        // old broken bottom-sheet attempt).
        val toolsPanelWidth = minOf((resources.displayMetrics.widthPixels * 0.75f).toInt(), dp(360)).coerceAtLeast(dp(260))
        root.addView(buildToolsPanel(), DrawerLayout.LayoutParams(toolsPanelWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = GravityCompat.END
        })
    // Phase 4.3: Reduce drawer swipe edge from 24dp to 16dp to avoid back gesture conflict
    root.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    root.setScrimColor(Color.TRANSPARENT)
    // Note: DrawerLayout edge size is not directly configurable in AndroidX,
    // but we can minimize the scrim and ensure gestures don't conflict
    return root
}

private fun Context.buildMenuBar(): View {
    return FrameLayout(this).apply {
        id = R.id.menu_bar_area
        setBackgroundColor(StudioUi.color(StudioUi.LayerTabs))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))

        // Header row: horizontally scrollable menu strip (Android Studio text menu).
        val header = LinearLayout(this@buildMenuBar).apply {
            id = R.id.menu_bar_header
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
            addView(HorizontalScrollView(this@buildMenuBar).apply {
                id = R.id.menu_bar_scroll
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, dp(28))
                addView(LinearLayout(this@buildMenuBar).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
                    val items = listOf(
                        R.id.menu_file to "File",
                        R.id.menu_edit to "Edit",
                        R.id.menu_view to "View",
                        R.id.menu_navigate to "Navigate",
                        R.id.menu_code to "Code",
                        R.id.menu_refactor to "Refactor",
                        R.id.menu_build to "Build",
                        R.id.menu_run to "Run",
                        R.id.menu_tools to "Tools",
                        R.id.menu_git to "Git",
                        R.id.menu_window to "Window",
                        R.id.menu_help to "Help",
                    )
                    items.forEach { (viewId, label) ->
                        addView(TextView(this@buildMenuBar).apply {
                            this.id = viewId
                            text = label
                            textSize = StudioUi.TypeSmall
                            setTextColor(StudioUi.color(StudioUi.TextSoft))
                            gravity = Gravity.CENTER
                            setPadding(dp(10), 0, dp(10), 0)
                            minWidth = dp(44)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                })
            })
        }

        // Submenu container: expands below the header when a menu is open.
        val submenu = LinearLayout(this@buildMenuBar).apply {
            id = R.id.menu_bar_submenu
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }

        // Outside-touch overlay (transparent) that closes the open submenu.
        val overlay = View(this@buildMenuBar).apply {
            id = R.id.menu_bar_overlay
            setBackgroundColor(Color.TRANSPARENT)
            setClickable(true)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            setOnClickListener { /* handled via activity state */ }
        }

        addView(header)
        addView(submenu)
        addView(overlay)
    }
}

/** The tablet-landscape window: docked explorer + editor + tool window. */
private fun Context.useThreePaneLayout(): Boolean =
    isLandscapeScreen() && resources.displayMetrics.widthPixels >= 600 * resources.displayMetrics.density

private fun Context.buildMainSurface(explorerSurface: View): View {
    // Phase 6.1: Horizontal mode 3-pane layout (file tree | editor | bottom panel)
    if (useThreePaneLayout()) {
        return buildLandscapeMainSurface(explorerSurface)
    }
    
    return FrameLayout(this).apply {
        val content = LinearLayout(this@buildMainSurface).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(StudioUi.Ink))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(buildMenuBar())
            addView(buildChrome())
            // The rail runs beside everything below the bar and stops short of
            // the status line, so a status that spans the screen still spans
            // the screen - the rail is navigation, not chrome worth two rows.
            addView(LinearLayout(this@buildMainSurface).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
                addView(buildSideRail())
                addView(LinearLayout(this@buildMainSurface).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    )
                    addView(buildProjectTabStrip())
                    addView(buildTabStrip())
                    addView(buildWorkspaceArea(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                })
            })
            addView(buildStatusBar())
        }
        addView(content)
    }
}

private fun Context.isLandscapeScreen(): Boolean =
    resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

/**
 * The one top bar: menu, project switcher, workspace tabs, run.
 *
 * The old chrome was two rows - a File/Edit/View/Build text menu stacked over
 * a toolbar - and it spent a fifth of a phone's height on navigation that was
 * touched maybe once a session. The menus did not disappear with it: every one
 * of them was already listed by showMainMenu(), which nothing had bound to
 * anything, so the hamburger now opens that and all twelve still exist.
 *
 * Back/forward moved onto the file tab strip where file history belongs, and
 * every id MainActivity drives (run, sync, spinner, badges, preview) is still
 * present here or there, so moving a row did not quietly kill a handler.
 */
private fun Context.buildChrome(): View {
    return LinearLayout(this).apply {
        // Legacy id: applyTabletMode()/applyPhoneMode() find the toolbar by it.
        id = R.id.build_icon_toolbar
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        setBackgroundColor(c(StudioUi.LayerToolbar))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))

        addView(iconButton(R.id.btn_menu, android.R.drawable.ic_menu_sort_by_size, StudioUi.TextSoft, "Menu", 36))

        // Project switcher. It opens the menu drawer rather than a picker that
        // does not exist; the chevron is there so it reads as a control and
        // not as a title, which is what it behaves like.
        addView(TextView(this@buildChrome).apply {
            id = R.id.tv_project_name
            text = "MobileStudio"
            textSize = StudioUi.TypeSmall
            setTextColor(c(StudioUi.Text))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            compoundDrawablePadding = dp(3)
            setOnClickListener { (context as? MainActivity)?.openMainMenuFromToolbar() }
            StudioUi.touchLift(this)
            StudioUi.enforceMinTouchTarget(this)
        }, LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.MATCH_PARENT))

        addView(topTab(R.id.tab_top_project, "프로젝트", "Project", selected = true))
        addView(topTab(R.id.tab_top_run, "실행", "Run"))
        addView(topTab(R.id.tab_top_debug, "디버그", "Debug"))
        addView(topTab(R.id.tab_top_deploy, "배포", "Deploy"))
        addView(topTab(R.id.tab_top_ai, "AI", "AI"))

        addView(View(this@buildChrome), LinearLayout.LayoutParams(0, 1, 1f))

        // Run is the one thing that keeps its colour - it is the action the
        // bar exists to offer. Sync is not here: on a 384dp phone this row
        // already spends every pixel it has on the five tabs the design
        // shows, and a sixth control pushed the run button clean off the
        // right edge. It lives on the file tab row now, where there is room.
        addView(iconButton(R.id.btn_run, android.R.drawable.ic_media_play, StudioUi.ActiveGreen, "Build", 38))

        addView(ProgressBar(this@buildChrome).apply {
            id = R.id.build_progress
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })

        // Kept: MainActivity flips its visibility and owns its click handler.
        addView(hiddenIcon(R.id.btn_preview))
    }
}

/**
 * A workspace tab on the top bar.
 *
 * The design draws these as plain words in a row, which makes them look like
 * labels rather than controls - and a 13sp word with a few dp around it is a
 * much smaller target than anyone is aiming at. A selected state that reads
 * at a glance plus the shared touch floor fixes both without changing how the
 * row looks.
 */
private fun Context.topTab(viewId: Int, label: String, labelEn: String, selected: Boolean = false): TextView {
    return TextView(this).apply {
        id = viewId
        // The destination's name, so the highlight is driven from the view
        // itself and a new tab cannot be wired to the wrong id.
        tag = labelEn.lowercase()
        text = AppLanguage.literal(this@topTab, label)
        textSize = StudioUi.TypeSmall
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setSingleLine(true)
        setTextColor(c(if (selected) StudioUi.Text else StudioUi.Muted))
        background = StudioUi.ripple(
            this@topTab,
            StudioUi.rounded(
                this@topTab,
                fill = if (selected) StudioUi.Surface2 else "#00000000",
                stroke = "#00000000",
                radiusDp = StudioUi.RadiusMd,
            ),
        )
        setPadding(dp(4), 0, dp(4), 0)
        // A 46dp floor per tab is a fifth of a 240dp-wide phone's bar across
        // five tabs alone. 40dp is still a bigger target than the label it
        // carries and it is what lets the whole row fit with the run button.
        minimumWidth = dp(40)
        StudioUi.touchLift(this)
        StudioUi.enforceMinTouchTarget(this)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}

/**
 * The tablet-landscape window, modeled on Android Studio's desktop anatomy:
 *
 *   menu bar ──────────────── spans the whole window ────────────────
 *   toolbar  ──────────────── spans the whole window ────────────────
 *   rail │ explorer (320dp) │ editor + file tabs (flex) │ tool window │
 *   status bar ───────────── spans the whole window ─────────────────
 *
 * The explorer here is the surface that is the START drawer in portrait, so
 * the tree keeps one implementation (ids, adapter, path bar) and simply stops
 * sliding. The tool window sits in a wrap_content pane next to its grabber, so
 * the pane grows and collapses with the panel instead of leaving a dead strip.
 */
private fun Context.buildLandscapeMainSurface(explorerSurface: View): View {
    val ctx = this
    return FrameLayout(ctx).apply {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(StudioUi.Ink))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            addView(ctx.buildMenuBar())
            addView(ctx.buildChrome())

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

                // The same rail as portrait, so rotating does not move the app's
                // sections somewhere else entirely.
                addView(ctx.buildSideRail())

                // Left pane: explorer, docked (the portrait START drawer).
                addView(FrameLayout(ctx).apply {
                    id = R.id.landscape_file_tree_container
                    layoutParams = LinearLayout.LayoutParams(ctx.explorerPaneWidth(), ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(c(StudioUi.Surface))
                    addView(explorerSurface, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                })
                addView(ctx.paneDivider())

                // Center: project/file tabs over the editor.
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    setBackgroundColor(c(StudioUi.Ink))
                    addView(ctx.buildProjectTabStrip())
                    addView(ctx.buildTabStrip())
                    addView(
                        ctx.buildWorkspaceArea(withPanel = false),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                })
                addView(ctx.paneDivider())

                // Right pane: the bottom tool window. wrap_content (not a fixed
                // width) so showing and hiding the panel widens or collapses
                // this pane; the grabber stays as the handle while collapsed.
                addView(LinearLayout(ctx).apply {
                    id = R.id.landscape_bottom_panel_container
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(c(StudioUi.LayerBottomPanel))
                    addView(ctx.buildPanelGrabber(landscape = true))
                    addView(
                        ctx.buildBottomPanel(),
                        LinearLayout.LayoutParams(ctx.toolPaneWidth(), ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                })
            })

            addView(ctx.buildStatusBar())
        }
        addView(root)
    }
}

/** Hairline between docked panes, in the shared divider token. */
private fun Context.paneDivider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(c(StudioUi.Divider))
}

/**
 * Explorer pane width: 30% of the window, capped at 320dp. A fixed 320dp on a
 * 600dp-wide tablet, plus a fixed tool window, squeezed the editor to nothing;
 * the docked panes take a share of the width that is actually there.
 */
private fun Context.explorerPaneWidth(): Int =
    (resources.displayMetrics.widthPixels * 0.30f).toInt().coerceAtMost(dp(320))

/** Tool-window width: the same 34% share the phone-landscape panel takes, capped at 360dp. */
private fun Context.toolPaneWidth(): Int =
    (resources.displayMetrics.widthPixels * 0.34f).toInt().coerceAtMost(dp(360))

private fun Context.buildProjectTabStrip(): View {
    return HorizontalScrollView(this).apply {
        id = R.id.project_tab_scroll
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        background = StudioUi.gradient(this@buildProjectTabStrip, "#0E1524", "#0A0F15")
        addView(LinearLayout(this@buildProjectTabStrip).apply {
            id = R.id.project_tab_container
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        // Tag to track tab count for conditional visibility
        tag = 0
    }
}

/** Updates project tab strip visibility based on tab count */
fun MainActivity.updateProjectTabStripVisibility(tabCount: Int) {
    val tabScroll = findViewById<HorizontalScrollView>(R.id.project_tab_scroll)
    val container = findViewById<LinearLayout>(R.id.project_tab_container)
    if (tabCount <= 1) {
        tabScroll.visibility = View.GONE
    } else {
        tabScroll.visibility = View.VISIBLE
        container.tag = tabCount
    }
}

private fun Context.buildTabStrip(): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c(StudioUi.LayerTabs))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        addView(HorizontalScrollView(this@buildTabStrip).apply {
            id = R.id.tab_scroll
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
            addView(LinearLayout(this@buildTabStrip).apply {
                id = R.id.tab_container
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // File history lives with the file tabs. The top bar spends its width
        // on the workspace tabs the design shows, and back/forward are about
        // this file list rather than about the app, so they moved here - the
        // ids and their handlers are untouched, only their parent changed.
        addView(LinearLayout(this@buildTabStrip).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
            // Sync sits with file history rather than in the top bar - it has
            // to be somewhere, and the top bar has no pixel left to spare.
            addView(iconButton(R.id.btn_sync_now, android.R.drawable.ic_menu_rotate, StudioUi.TextSoft, "Sync", 30))
            addView(iconButton(R.id.btn_back, android.R.drawable.ic_menu_revert, StudioUi.TextSoft, "Back", 30))
            addView(iconButton(R.id.btn_forward, android.R.drawable.ic_media_ff, StudioUi.TextSoft, "Forward", 30))
        })

        addView(View(this@buildTabStrip).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(c(StudioUi.Divider))
        })
    }
}
/**
 * The left rail: every section of the app in one column of icon-over-label.
 *
 * The sections used to be scattered - some were tabs in a row across the
 * bottom, some were entries in the tools menu, some were behind the status
 * bar - so nothing ever said what the app contains or which part of it you
 * were in. Each item is a full column rather than a bare glyph, so the label
 * under the icon stays readable, and the selected one is marked by a filled
 * outline as well as a colour, which is what makes it findable at a glance.
 */
private fun Context.buildSideRail(): View {
    val items = listOf(
        Triple(R.id.side_item_project, "🗂", "프로젝트" to "Project"),
        Triple(R.id.side_item_search, "🔍", "검색" to "Search"),
        Triple(R.id.side_item_problems, "✖", "문제" to "Problems"),
        Triple(R.id.side_item_git, "⑂", "Git" to "Git"),
        Triple(R.id.side_item_shell, ">_", "터미널" to "Terminal"),
        Triple(R.id.side_item_chat, "💬", "Chat" to "Chat"),
        Triple(R.id.side_item_ai, "✦", "AI" to "AI"),
        Triple(R.id.side_item_extensions, "🧩", "확장" to "Extensions"),
        Triple(R.id.side_item_settings, "⚙", "설정" to "Settings"),
    )
    return LinearLayout(this).apply {
        id = R.id.side_bar
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(c(StudioUi.LayerTabs))
        setPadding(dp(2), dp(6), dp(2), dp(6))
        layoutParams = LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.MATCH_PARENT)
        items.forEach { (viewId, glyph, labels) ->
            addView(sideRailItem(viewId, glyph, labels))
        }
        addView(View(this@buildSideRail), LinearLayout.LayoutParams(0, 0, 1f))
    }
}

/**
 * One rail destination. The tag carries the destination's name so the
 * highlight can be driven from anywhere that changes it - a tap here, a
 * bottom-panel tab, a shortcut - instead of only from taps on the rail,
 * which would leave it disagreeing with the screen the moment anything else
 * moved the app.
 */
private fun Context.sideRailItem(viewId: Int, glyph: String, labels: Pair<String, String>): LinearLayout {
    return LinearLayout(this).apply {
        id = viewId
        tag = labels.second.lowercase()
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(8), dp(2), dp(8))
        isClickable = true
        StudioUi.touchLift(this)
        addView(TextView(this@sideRailItem).apply {
            text = glyph
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(c(StudioUi.InactiveIcon))
            maxLines = 1
        })
        addView(TextView(this@sideRailItem).apply {
            text = AppLanguage.literal(this@sideRailItem, labels.first)
            textSize = StudioUi.TypeCaption
            gravity = Gravity.CENTER
            setTextColor(c(StudioUi.InactiveIcon))
            maxLines = 1
            setPadding(0, dp(1), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}

private fun Context.buildWorkspaceArea(withPanel: Boolean = true): View {
    // The banner, welcome screen and editor form one stack in both
    // orientations; only what happens to the build-output panel differs.
    val editorStack = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c(StudioUi.Ink))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f,
        )
        addView(LinearLayout(this@buildWorkspaceArea).apply {
            id = R.id.banner_container
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        })
        addView(buildWelcomeScreen())
        addView(buildEditorContainer())
    }

    // Tablet-landscape 3-pane: the panel lives in its own right pane, so the
    // editor stack needs no divider, grabber or docked panel of its own.
    if (!withPanel) return editorStack

    if (!isLandscapeScreen()) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(StudioUi.Ink))
            addView(editorStack)
            addView(View(this@buildWorkspaceArea).apply {
                id = R.id.panel_divider
                setBackgroundColor(c(StudioUi.Divider))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(buildPanelGrabber(landscape = false))
            addView(buildBottomPanel())
        }
    }

    // Landscape: the build-output panel moves beside the editor. A phone in
    // landscape has only ~360dp of height, and a panel across the bottom eats
    // a third of it; there is plenty of width instead. The divider stays the
    // same view, so MainActivity's drag-to-resize keeps working - it adjusts
    // width here and height in portrait.
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(c(StudioUi.Ink))
        addView(editorStack)
        addView(View(this@buildWorkspaceArea).apply {
            id = R.id.panel_divider
            setBackgroundColor(c(StudioUi.Divider))
        }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(buildPanelGrabber(landscape = true))
        addView(buildBottomPanel(), LinearLayout.LayoutParams(
            landscapePanelWidth(),
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }
}

/**
 * The handle that pulls the bottom panel up and lets it back down.
 *
 * It sits directly against the panel's leading edge as a sibling rather than a
 * child of it, so it is still on screen while the panel is collapsed - the only
 * way a collapsed panel stays reachable - and it rides that edge as the edge
 * animates in. No separate animation is needed: the panel's own height
 * animation carries the handle with it.
 *
 * The glyph points the way the panel travels, and flips once the panel is out,
 * so the direction of the next press is always what is shown.
 */
private fun Context.buildPanelGrabber(landscape: Boolean): View {
    val icon = TextView(this).apply {
        id = R.id.shell_grabber_icon
        text = "^"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(c(StudioUi.TextSoft))
        rotation = if (landscape) -90f else 0f
    }
    return FrameLayout(this).apply {
        id = R.id.shell_grabber
        background = StudioUi.rounded(
            this@buildPanelGrabber,
            fill = StudioUi.Surface2,
            stroke = StudioUi.Border,
            radiusDp = 8,
        )
        addView(
            icon,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        isClickable = true
        isFocusable = true
        contentDescription =
            if (landscape) "Show or hide the side panel" else "Show or hide the bottom panel"
        layoutParams = if (landscape) {
            LinearLayout.LayoutParams(dp(PANEL_GRABBER_SIZE_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_GRABBER_SIZE_DP))
        }
    }
}

/** Thickness of the panel grabber; also its touch target. */
private const val PANEL_GRABBER_SIZE_DP = 28

/** Share of the screen width the build-output panel takes in landscape. */
private fun Context.landscapePanelWidth(): Int {
    val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    return (widthDp * 0.34f).toInt().coerceIn(dp(280), dp(440))
}

private fun Context.buildWelcomeScreen(): View {
    return ScrollView(this).apply {
        id = R.id.welcome_screen
        setBackgroundColor(c(StudioUi.Ink))
        // 오른쪽 가장자리에 스크롤바 두께만큼 흰 줄이 보이던 것 제거
        isVerticalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        addView(LinearLayout(this@buildWelcomeScreen).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            addView(LinearLayout(this@buildWelcomeScreen).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background = StudioUi.glow(this@buildWelcomeScreen, "#0C1322", StudioUi.Blue, 24)
                addView(TextView(this@buildWelcomeScreen).apply {
                    text = "M"
                    textSize = 36f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c("#08111E"))
                    background = StudioUi.gradient(this@buildWelcomeScreen, "#4F8EF7", "#B57CE7", 24)
                    layoutParams = LinearLayout.LayoutParams(dp(86), dp(86)).apply { bottomMargin = dp(14) }
                })
                addView(TextView(this@buildWelcomeScreen).apply {
                    text = "MOBILESTUDIO"
                    textSize = 10f
                    gravity = Gravity.CENTER
                    typeface = Typeface.MONOSPACE
                    letterSpacing = 0.32f
                    setTextColor(c("#4F8EF7"))
                    setPadding(0, 0, 0, dp(4))
                })
                addView(TextView(this@buildWelcomeScreen).apply {
                    text = "MobileStudio"
                    textSize = 24f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c(StudioUi.Text))
                })
                addView(TextView(this@buildWelcomeScreen).apply {
                    text = "on-device Android IDE bootstrap"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    letterSpacing = 0.08f
                    setTextColor(c(StudioUi.Green))
                    setPadding(0, dp(4), 0, dp(16))
                })
                addView(TextView(this@buildWelcomeScreen).apply {
                    text = "Build, inspect, preview, chat, and ship projects from a phone-sized workspace."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(c(StudioUi.TextSoft))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            })
            addView(TextView(this@buildWelcomeScreen).apply {
                text = "MS"
                textSize = 36f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(c("#4F8EF7"))
                setPadding(0, 0, 0, dp(6))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@buildWelcomeScreen).apply {
                text = "MobileStudio"
                textSize = 22f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                setTextColor(c("#E6EDF3"))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@buildWelcomeScreen).apply {
                text = "Android IDE on Device"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(c("#8E99AC"))
                setPadding(0, dp(3), 0, dp(22))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(LinearLayout(this@buildWelcomeScreen).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(22))
                // 하드코딩된 hex는 테마 리맵 대상이 아니어서 밝은 테마에서
                // "어두운 글자 + 어두운 배경"이 된다. 반드시 시맨틱 팔레트 상수를 쓴다.
                addView(actionButton(R.id.btn_new_project, "New Project", StudioUi.Blue, "#FFFFFF"))
                addView(actionButton(R.id.btn_open_project, "Open Project", StudioUi.Card2, StudioUi.Text))
                addView(actionButton(R.id.btn_ai_welcome, "AI", StudioUi.Purple, "#FFFFFF").apply {
                    setOnClickListener { (context as? MainActivity)?.launchAiAssistant() }
                })
            })
            addView(TextView(this@buildWelcomeScreen).apply {
                text = AppLanguage.literal(this@buildWelcomeScreen, "Recent Projects")
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(c("#8E99AC"))
                id = R.id.recent_label
                setPadding(0, 0, 0, dp(8))
            })
            addView(RecyclerView(this@buildWelcomeScreen).apply {
                id = R.id.rv_recent_projects
                visibility = View.GONE
                isNestedScrollingEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@buildWelcomeScreen).apply {
                text = AppLanguage.literal(this@buildWelcomeScreen, "Recent Files")
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(c("#8E99AC"))
                id = R.id.recent_files_label
                setPadding(0, dp(14), 0, dp(8))
                visibility = View.GONE
            })
            addView(RecyclerView(this@buildWelcomeScreen).apply {
                id = R.id.rv_recent_files
                visibility = View.GONE
                isNestedScrollingEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun Context.buildEditorContainer(): View {
    return FrameLayout(this).apply {
        id = R.id.editor_container
        visibility = View.GONE
        addView(LinearLayout(this@buildEditorContainer).apply {
            orientation = LinearLayout.VERTICAL
            // Extension notice banner (small, non-blocking; e.g. Shell Script extension download)
            addView(LinearLayout(this@buildEditorContainer).apply {
                id = R.id.extension_banner
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            })
            // Editor top bar
            addView(LinearLayout(this@buildEditorContainer).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = StudioUi.rounded(this@buildEditorContainer, fill = StudioUi.LayerToolbar, stroke = "#00000000", radiusDp = 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
                // 편집기 툴바도 상단과 같은 규칙: 아이콘은 단일 톤, 의미가 겹치지 않게 아이콘 분리.
                // (기존: Command Palette 와 AI Chat 이 둘 다 wrench 를 썼다)
                addView(ImageButton(this@buildEditorContainer).apply {
                    id = R.id.btn_shell_toggle
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    background = StudioUi.ripple(this@buildEditorContainer, StudioUi.rounded(this@buildEditorContainer, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setColorFilter(c(StudioUi.TextSoft))
                    setOnClickListener { (context as? MainActivity)?.toggleShellFromEditor() }
                    contentDescription = AppLanguage.literal(this@buildEditorContainer, "Shell")
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(ImageButton(this@buildEditorContainer).apply {
                    id = R.id.btn_command_palette
                    setImageResource(R.drawable.ic_menu)
                    background = StudioUi.ripple(this@buildEditorContainer, StudioUi.rounded(this@buildEditorContainer, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setColorFilter(c(StudioUi.TextSoft))
                    setOnClickListener { (context as? MainActivity)?.showCommandPalette() }
                    contentDescription = AppLanguage.literal(this@buildEditorContainer, "Command Palette")
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(ImageButton(this@buildEditorContainer).apply {
                    id = R.id.btn_ai_chat
                    setImageResource(R.drawable.ic_ai)
                    background = StudioUi.ripple(this@buildEditorContainer, StudioUi.rounded(this@buildEditorContainer, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setColorFilter(c(StudioUi.TextSoft))
                    setOnClickListener { (context as? MainActivity)?.openAiChatFromEditor() }
                    contentDescription = AppLanguage.literal(this@buildEditorContainer, "Open AI Chat")
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(ImageButton(this@buildEditorContainer).apply {
                    id = R.id.btn_find_inline
                    setImageResource(android.R.drawable.ic_menu_search)
                    background = StudioUi.ripple(this@buildEditorContainer, StudioUi.rounded(this@buildEditorContainer, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setColorFilter(c(StudioUi.TextSoft))
                    setOnClickListener { (context as? MainActivity)?.toggleFindReplaceInline() }
                    contentDescription = AppLanguage.literal(this@buildEditorContainer, "Find/Replace")
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                // Split layout preview (Pixel-class device frames, like the IDE design).
                addView(ImageButton(this@buildEditorContainer).apply {
                    id = R.id.btn_preview_toggle
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    background = StudioUi.ripple(this@buildEditorContainer, StudioUi.rounded(this@buildEditorContainer, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setColorFilter(c(StudioUi.TextSoft))
                    setOnClickListener { (context as? MainActivity)?.togglePreview() }
                    contentDescription = AppLanguage.literal(this@buildEditorContainer, "Layout preview")
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                // Breadcrumb
                addView(HorizontalScrollView(this@buildEditorContainer).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(LinearLayout(this@buildEditorContainer).apply {
                        id = R.id.breadcrumb
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6), 0, dp(6), 0)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
                    })
                }, LinearLayout.LayoutParams(0, dp(28), 1f))
                // Error/Warning navigation
                addView(LinearLayout(this@buildEditorContainer).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), 0, dp(8), 0)
                    addView(TextView(this@buildEditorContainer).apply {
                        id = R.id.editor_warning_count
                        text = ""
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                        setTextColor(c(StudioUi.ActiveAmber))
                        visibility = View.GONE
                        setPadding(dp(2), 0, dp(2), 0)
                    })
                    addView(TextView(this@buildEditorContainer).apply {
                        id = R.id.editor_error_count
                        text = ""
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                        setTextColor(c(StudioUi.ActiveRed))
                        visibility = View.GONE
                        setPadding(dp(2), 0, dp(2), 0)
                    })
                    addView(TextView(this@buildEditorContainer).apply {
                        id = R.id.btn_prev_error
                        text = "\u25B2"
                        textSize = 10f
                        setTextColor(c(StudioUi.InactiveIcon))
                        setPadding(dp(4), 0, dp(4), 0)
                        setOnClickListener { (context as? MainActivity)?.navigateToPrevError() }
                    })
                    addView(TextView(this@buildEditorContainer).apply {
                        id = R.id.btn_next_error
                        text = "\u25BC"
                        textSize = 10f
                        setTextColor(c(StudioUi.InactiveIcon))
                        setPadding(dp(4), 0, dp(4), 0)
                        setOnClickListener { (context as? MainActivity)?.navigateToNextError() }
                    })
                })
            })
            addView(buildFindReplacePanel())
            addView(buildQuickBar())
            // Split view: code on the left, optional layout preview on the right
            // (the 👁 toggle in the editor top bar shows/hides the right pane).
            addView(LinearLayout(this@buildEditorContainer).apply {
                id = R.id.editor_split
                orientation = LinearLayout.HORIZONTAL
                addView(
                    buildEditorOverlay(),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                )
                addView(
                    buildPreviewPane(),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
}

private fun Context.buildFindReplacePanel(): View {
    return LinearLayout(this).apply {
        id = R.id.find_replace_panel
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = StudioUi.rounded(this@buildFindReplacePanel, fill = StudioUi.LayerToolbar, stroke = "#00000000", radiusDp = 0)
        visibility = View.GONE
        // Row 1: Find
        addView(LinearLayout(this@buildFindReplacePanel).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input(R.id.et_find, "Find...", imeOptions = EditorInfo.IME_ACTION_NEXT), LinearLayout.LayoutParams(0, dp(36), 1f))
            // Result count
            addView(TextView(this@buildFindReplacePanel).apply {
                id = R.id.tv_find_count
                text = "0 / 0"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(c(StudioUi.TextSoft))
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Prev
            addView(iconButton(R.id.btn_find_prev, android.R.drawable.arrow_up_float, StudioUi.TextSoft, "Prev", 32))
            // Next
            addView(iconButton(R.id.btn_find_next, android.R.drawable.arrow_down_float, StudioUi.TextSoft, "Next", 32))
            // Case sensitive
            addView(checkBox(R.id.cb_case_sensitive, "Aa"))
            // Regex
            addView(checkBox(R.id.cb_regex, ".*"))
            // Close
            addView(iconButton(R.id.btn_find_close, android.R.drawable.ic_menu_close_clear_cancel, StudioUi.InactiveIcon, "Close", 32))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        // Row 2: Replace
        addView(LinearLayout(this@buildFindReplacePanel).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input(R.id.et_replace, "Replace with..."), LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(actionButton(R.id.btn_replace_one, "Replace", StudioUi.Card2, StudioUi.Text, 34))
            addView(actionButton(R.id.btn_replace_all, "All", StudioUi.Card2, StudioUi.Text, 34))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
    }
}

private fun Context.buildEditorOverlay(): View {
    return FrameLayout(this).apply {
        id = R.id.editor_overlay
        setBackgroundColor(StudioUi.palette.editorBg)
        addView(HorizontalScrollView(this@buildEditorOverlay).apply {
            id = R.id.editor_host
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(StudioUi.palette.editorBg)
            addView(ScrollView(this@buildEditorOverlay).apply {
                isFillViewport = true
                addView(LineNumberEditText(this@buildEditorOverlay).apply {
                    id = R.id.code_editor
                    this.textSize = 12.5f
                    this.setLineSpacing(0f, 1.2f)
                    this.letterSpacing = 0.02f
                    this.typeface = Typeface.create("monospace", Typeface.NORMAL)
                    this.setTextColor(StudioUi.palette.editorText)
                    setHintTextColor(StudioUi.palette.muted)
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                    setPadding(dp(8), dp(8), dp(12), dp(120))
                    setBackgroundColor(StudioUi.palette.editorBg)
                    setHorizontallyScrolling(true)
                    isHorizontalScrollBarEnabled = true
                    minWidth = resources.displayMetrics.widthPixels
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/**
 * Right half of the split editor: renders the current XML layout through
 * [LayoutLibPreviewEngine] into a device-sized image. When the LayoutLib
 * runtime is missing the pane shows the engine's honest message instead of a
 * fake preview.
 */
private fun Context.buildPreviewPane(): View {
    return LinearLayout(this).apply {
        id = R.id.preview_pane
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(c(StudioUi.Surface))
        addView(LinearLayout(this@buildPreviewPane).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(c(StudioUi.LayerToolbar))
            addView(TextView(this@buildPreviewPane).apply {
                id = R.id.preview_device_chip
                text = "Pixel 7"
                textSize = 11f
                setTextColor(c(StudioUi.ActiveBlue))
                background = StudioUi.ripple(
                    this@buildPreviewPane,
                    StudioUi.rounded(this@buildPreviewPane, fill = StudioUi.Surface2, stroke = StudioUi.Divider, radiusDp = StudioUi.RadiusMd),
                )
                setPadding(dp(10), dp(4), dp(10), dp(4))
                StudioUi.touchLift(this)
                setOnClickListener { (context as? MainActivity)?.cyclePreviewDevice() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@buildPreviewPane), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(R.id.preview_refresh, android.R.drawable.ic_popup_sync, StudioUi.TextSoft, "Refresh preview", 30).apply {
                setOnClickListener { (context as? MainActivity)?.refreshPreview() }
            })
            addView(iconButton(R.id.preview_close, android.R.drawable.ic_menu_close_clear_cancel, StudioUi.InactiveIcon, "Close preview", 30).apply {
                setOnClickListener { (context as? MainActivity)?.closePreview() }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(FrameLayout(this@buildPreviewPane).apply {
            setBackgroundColor(StudioUi.palette.editorBg)
            addView(ScrollView(this@buildPreviewPane).apply {
                addView(ImageView(this@buildPreviewPane).apply {
                    id = R.id.preview_image
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(this@buildPreviewPane).apply {
                id = R.id.preview_message
                textSize = 11f
                setTextColor(c(StudioUi.TextSoft))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                visibility = View.GONE
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
}

private fun Context.buildQuickBar(): View {
    return HorizontalScrollView(this).apply {
        id = R.id.quick_bar_scroll
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(c(StudioUi.LayerToolbar))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        addView(LinearLayout(this@buildQuickBar).apply {
            id = R.id.quick_bar_container
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            // Symbol buttons
            val symbols = listOf(
                "Tab" to "\t",
                "{" to "{}",
                "(" to "()",
                "[" to "[]",
                "=" to "=",
                ":" to ":",
                "." to ".",
                "/" to "/",
                "->" to "\u2192",
                "_" to "_",
                ";" to ";",
                "!" to "!"
            )
            symbols.forEach { (label, insert) ->
                addView(TextView(this@buildQuickBar).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(c(StudioUi.Text))
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    minimumWidth = dp(36)
                    background = StudioUi.ripple(this@buildQuickBar, StudioUi.rounded(this@buildQuickBar, fill = StudioUi.Card, stroke = StudioUi.Divider, radiusDp = 6))
                    StudioUi.touchLift(this)
                    setOnClickListener {
                        val editor = (context as? MainActivity)?.codeEditor ?: return@setOnClickListener
                        val start = editor.selectionStart.coerceAtLeast(0)
                        editor.text?.insert(start, insert)
                    }
                })
            }
            // Separator
            addView(View(this@buildQuickBar).apply {
                setBackgroundColor(c(StudioUi.Divider))
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginStart = dp(4); marginEnd = dp(4) }
            })
            // Action buttons (Undo, Redo, Format, Comment, Save, etc.)
            val actions = listOf(
                "\u21A9" to { (context as? MainActivity)?.codeEditor?.undo() },         // Undo
                "\u21AA" to { (context as? MainActivity)?.codeEditor?.redo() },         // Redo
                "\u2261" to { (context as? MainActivity)?.formatCurrentFile() },        // Format
                "\u2044" to { (context as? MainActivity)?.handleEditorToolbarAction("__TOGGLE_COMMENT__") }, // Comment
                "\u2398" to { (context as? MainActivity)?.handleEditorToolbarAction("__DUP_LINE__") },       // Duplicate line
                "\u2191" to { (context as? MainActivity)?.handleEditorToolbarAction("__MOVE_LINE_UP__") },   // Move up
                "\u2193" to { (context as? MainActivity)?.handleEditorToolbarAction("__MOVE_LINE_DOWN__") }, // Move down
                "\u2318" to { (context as? MainActivity)?.toggleFindReplace() },        // Find
                "\u2637" to { (context as? MainActivity)?.showGoToLineDialog() }        // Go to Line
            )
            actions.forEach { (label, action) ->
                addView(TextView(this@buildQuickBar).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(c(StudioUi.TextSoft))
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    minimumWidth = dp(36)
                    background = StudioUi.ripple(this@buildQuickBar, StudioUi.rounded(this@buildQuickBar, fill = StudioUi.Surface2, stroke = StudioUi.Divider, radiusDp = 6))
                    StudioUi.touchLift(this)
                    setOnClickListener { action() }
                })
            }
        })
    }
}

/**
 * A small action chip on the terminal's toolbar row.
 *
 * These used to be bare text with no background and no minimum size, so
 * "Copy" and "Paste" were a few pixels of glyph each and gave no sign of being
 * pressable at all. A chip with a real background and the shared touch-target
 * floor makes them findable and hit-able.
 */
private fun Context.shellChip(viewId: Int, label: String, onClick: (() -> Unit)? = null): TextView {
    return TextView(this).apply {
        id = viewId
        text = label
        textSize = StudioUi.TypeSmall
        setTextColor(c(StudioUi.TextSoft))
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minimumHeight = dp(30)
        background = StudioUi.ripple(
            this@shellChip,
            StudioUi.rounded(
                this@shellChip,
                fill = StudioUi.Surface2,
                stroke = StudioUi.Border,
                radiusDp = StudioUi.RadiusMd,
            ),
        )
        StudioUi.touchLift(this)
        StudioUi.enforceMinTouchTarget(this)
        if (onClick != null) setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginStart = dp(4)
        layoutParams = params
    }
}

private fun Context.buildTerminalKeyBar(): View {
    return HorizontalScrollView(this).apply {
        id = R.id.terminal_key_bar
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(c(StudioUi.Surface))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(LinearLayout(this@buildTerminalKeyBar).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(terminalKeyButton(R.id.btn_terminal_ctrl, "Ctrl"))
            addView(terminalKeyButton(R.id.btn_terminal_esc, "Esc"))
            addView(terminalKeyButton(R.id.btn_terminal_tab, "Tab"))
            addView(terminalKeyButton(R.id.btn_terminal_up, "\u2191"))
            addView(terminalKeyButton(R.id.btn_terminal_down, "\u2193"))
            addView(terminalKeyButton(R.id.btn_terminal_left, "\u2190"))
            addView(terminalKeyButton(R.id.btn_terminal_right, "\u2192"))
            addView(terminalKeyButton(R.id.btn_terminal_enter, "Enter"))
            addView(terminalKeyButton(R.id.btn_terminal_backspace, "\u232B"))
        })
    }
}

/**
 * A key on the terminal's extra row.
 *
 * These stand in for keys a phone keyboard does not have, so they are pressed
 * constantly and often one-handed. They get a real fill and stroke rather than
 * a hairline outline on nothing, a minimum width so the arrows line up in a
 * column, and the shared touch-target floor - a 12sp glyph with 10dp of padding
 * around it is otherwise well under the 48dp a tap should reach.
 */
private fun Context.terminalKeyButton(viewId: Int, label: String): TextView {
    return TextView(this).apply {
        id = viewId
        text = label
        textSize = StudioUi.TypeSmall
        typeface = Typeface.MONOSPACE
        setTextColor(c(StudioUi.TextSoft))
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minWidth = dp(40)
        minimumHeight = dp(34)
        background = StudioUi.ripple(
            this@terminalKeyButton,
            StudioUi.rounded(
                this@terminalKeyButton,
                fill = StudioUi.Surface2,
                stroke = StudioUi.Border,
                radiusDp = StudioUi.RadiusMd,
            ),
        )
        StudioUi.touchLift(this)
        StudioUi.enforceMinTouchTarget(this)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(4)
        layoutParams = params
    }
}

private fun Context.buildBottomPanel(): View {
    val root = LinearLayout(this).apply {
        id = R.id.bottom_panel
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c(StudioUi.LayerBottomPanel))
        visibility = View.GONE
        addView(buildPanelTabs())
        // Resize handle for dynamic panel height
        val resizeHandle = View(this@buildBottomPanel).apply {
            id = R.id.panel_resize_handle
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9))
            setBackgroundColor(c(StudioUi.DividerLight))
        }
        addView(resizeHandle)
        // Drag handling lives in MainActivity, which owns the panel's size
        // state. The listener that used to sit here replaced itself on
        // ACTION_DOWN and removed itself on ACTION_UP, so the handle worked
        // exactly once and was dead from the second gesture onward.
    }
    val panelContent = FrameLayout(this)
    panelContent.apply {
            addView(LinearLayout(this@buildBottomPanel).apply {
                id = R.id.build_output_container
                orientation = LinearLayout.VERTICAL

                // Top: Build Monitor (task chips)
                addView(BuildMonitorView(this@buildBottomPanel).apply {
                    id = R.id.build_monitor
                    visibility = View.VISIBLE
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                // Middle: Tree View
                addView(com.aide.mobilestudio.BuildOutputTreeView(this@buildBottomPanel).apply {
                    id = R.id.build_tree_view
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

                // Divider
                addView(View(this@buildBottomPanel).apply {
                    setBackgroundColor(c("#2F3B4D"))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

                // Bottom: Raw Log Output. The placeholder overlays the log
                // rather than sharing its height, so the first build does not
                // have to share the panel with a note about itself.
                addView(FrameLayout(this@buildBottomPanel).apply {
                    addView(BuildOutputView(this@buildBottomPanel).apply {
                        id = R.id.build_output
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                    addView(StudioUi.emptyState(
                        this@buildBottomPanel,
                        glyph = "\u25B6",
                        title = AppLanguage.literal(this@buildBottomPanel, "아직 빌드 기록이 없습니다"),
                        hint = AppLanguage.literal(
                            this@buildBottomPanel,
                            "재생산 아이콘을 눌러 첫 빌드를 시작하세요",
                        ),
                    ).apply { id = R.id.build_output_empty }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f))

            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@buildBottomPanel).apply {
                id = R.id.logcat_container
                visibility = View.GONE
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c(StudioUi.LayerBottomPanel))
                addView(AutoScrollLogView(this@buildBottomPanel).apply {
                    id = R.id.logcat_text
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(c("#8E99AC"))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setTextIsSelectable(false)
                    movementMethod = android.text.method.ScrollingMovementMethod()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@buildBottomPanel).apply {
                id = R.id.problems_container
                visibility = View.GONE
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c(StudioUi.LayerBottomPanel))
                
                // Problems header with tabs
                addView(LinearLayout(this@buildBottomPanel).apply {
                    id = R.id.problems_tabs
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = Gravity.CENTER_VERTICAL
                    
                    addView(TextView(this@buildBottomPanel).apply {
                        id = R.id.problems_tab_all
                        text = "Problems"
                        textSize = 12f
                        setTextColor(c(StudioUi.Text))
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        background = StudioUi.rounded(this@buildBottomPanel, fill = StudioUi.Surface2, radiusDp = StudioUi.RadiusMd)
                    })
                    
                    addView(TextView(this@buildBottomPanel).apply {
                        id = R.id.problems_tab_file
                        text = "File"
                        textSize = 12f
                        setTextColor(c(StudioUi.TextSoft))
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        }
                    })
                    
                    addView(TextView(this@buildBottomPanel).apply {
                        id = R.id.problems_tab_project
                        text = "Project Errors"
                        textSize = 12f
                        setTextColor(c(StudioUi.TextSoft))
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        }
                    })
                })
                
                addView(RecyclerView(this@buildBottomPanel).apply {
                    id = R.id.problems_recycler
                    setBackgroundColor(c(StudioUi.LayerBottomPanel))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@buildBottomPanel).apply {
                id = R.id.search_panel
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setBackgroundColor(c(StudioUi.LayerBottomPanel))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
addView(LinearLayout(this@buildBottomPanel).apply {
                id = R.id.shell_panel
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setBackgroundColor(c(StudioUi.LayerBottomPanel))
                
                // Shell toolbar
                addView(LinearLayout(this@buildBottomPanel).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setBackgroundColor(c(StudioUi.LayerTabs))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
                    addView(android.widget.TextView(this@buildBottomPanel).apply {
                        text = "Terminal"
                        setTextColor(StudioUi.color(StudioUi.TextSoft))
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(shellChip(
                        R.id.btn_terminal_copy,
                        AppLanguage.literal(this@buildBottomPanel, "Copy"),
                    ))
                    addView(shellChip(
                        R.id.btn_terminal_paste,
                        AppLanguage.literal(this@buildBottomPanel, "Paste"),
                    ))
                    addView(shellChip(R.id.btn_shell_clear, "Clear") {
                        root.findViewById<TextView>(R.id.shell_output_text)?.text = ""
                    })
                })

                // Tabbed PTY terminal (created lazily on first switch to the Shell panel)
                addView(LinearLayout(this@buildBottomPanel).apply {
                    id = R.id.terminal_tab_bar
                    orientation = LinearLayout.HORIZONTAL
                    visibility = View.GONE
                    setBackgroundColor(c(StudioUi.LayerTabs))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(android.widget.FrameLayout(this@buildBottomPanel).apply {
                    id = R.id.terminal_container
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                })

                // Termux-style extra key row (Ctrl modifier + common keys)
                addView(buildTerminalKeyBar())

                // Output area (like Build log) - legacy fallback shell UI
                addView(android.widget.ScrollView(this@buildBottomPanel).apply {
                    id = R.id.shell_output_scroll
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    setBackgroundColor(c(StudioUi.Ink))
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    isVerticalFadingEdgeEnabled = true
                    addView(android.widget.TextView(this@buildBottomPanel).apply {
                        id = R.id.shell_output_text
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = StudioUi.TypeBody
                        setTextColor(c(StudioUi.Text))
                        setTextIsSelectable(true)
                        setLineSpacing(0f, 1.1f)
                        layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                
                // Input area (Termux-style: prompt inline, Enter sends, no send button)
                addView(LinearLayout(this@buildBottomPanel).apply {
                    id = R.id.shell_input_row
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setBackgroundColor(c(StudioUi.Surface))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(android.widget.TextView(this@buildBottomPanel).apply {
                        text = "$"
                        setTextColor(c(StudioUi.Green))
                        textSize = StudioUi.TypeBody
                        typeface = android.graphics.Typeface.MONOSPACE
                        setPadding(0, 0, dp(6), 0)
                    })
                    addView(android.widget.EditText(this@buildBottomPanel).apply {
                        id = R.id.shell_input
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 14f
                        setTextColor(StudioUi.color(StudioUi.Text))
                        setHintTextColor(StudioUi.color(StudioUi.TextSoft))
                        hint = "명령어 입력..."
                        isSingleLine = true
                        setPadding(dp(2), dp(6), dp(8), dp(6))
                        background = null
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        setOnEditorActionListener { v, actionId, _ ->
                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                                performShellInput(v.rootView.findViewById(R.id.shell_panel))
                                true
                            } else false
                        }
                        setOnKeyListener { v, keyCode, event ->
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                                performShellInput(v.rootView.findViewById(R.id.shell_panel))
                                true
                            } else false
                        }
                    })
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    root.addView(panelContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // Dynamic height: 50% of screen height, clamped between 300dp and 800dp
    val screenHeightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
    val panelHeightDp = (screenHeightDp * 0.5f).toInt().coerceIn(dp(300), dp(800))
    root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(panelHeightDp))
    return root
}

private fun Context.performShellInput(root: View) {
    val input = root.findViewById<EditText>(R.id.shell_input) ?: return
    val outputText = root.findViewById<TextView>(R.id.shell_output_text) ?: return
    val scrollView = root.findViewById<ScrollView>(R.id.shell_output_scroll) ?: return
    val cmd = input.text.toString().trim()
    if (cmd.isEmpty()) return

    outputText.append("\n$ $cmd\n")
    input.text.clear()

    Thread {
        try {
            val cwd = com.aide.mobilestudio.StorageHelper.projectsRoot(this@performShellInput)

            // Native ELF tool auto-detection: clang/ninja/make 등 모든 네이티브 실행파일
            val tokens = cmd.split("\\s+".toRegex())
            val firstToken = tokens.firstOrNull()?.removeSuffix(".exe") ?: ""
            val nativeBinary = if (firstToken in NATIVE_TOOL_NAMES) resolveNativeToolBinary(firstToken) else null

            if (nativeBinary != null) {
                // Check ELF architecture before running
                val arch = readElfArch(File(nativeBinary))
                if (arch == "arm64-v8a") {
                    // Run Android ELF directly via /system/bin/linker64 (bypasses noexec)
                    val args = tokens.drop(1)
                    val pb = ProcessBuilder(listOf("/system/bin/linker64", nativeBinary) + args)
                    pb.directory(cwd)
                    val toolDir = File(nativeBinary).parentFile
                    val libDirs = buildList {
                        toolDir?.let {
                            add(it.absolutePath)
                            File(it.parentFile, "lib").takeIf { l -> l.isDirectory }?.let { l -> add(l.absolutePath) }
                            File(it.parentFile, "lib64").takeIf { l -> l.isDirectory }?.let { l -> add(l.absolutePath) }
                            File(it.parentFile, "usr/lib").takeIf { l -> l.isDirectory }?.let { l -> add(l.absolutePath) }
                            File(it.parentFile, "usr/lib64").takeIf { l -> l.isDirectory }?.let { l -> add(l.absolutePath) }
                        }
                        File(filesDir, "toolchain/runtime-tools/lib").takeIf { l -> l.isDirectory }?.let { add(it.absolutePath) }
                        add(applicationInfo.nativeLibraryDir)
                        add("/system/lib64")
                        add("/system/lib")
                    }.distinct().joinToString(":")
                    pb.environment().putAll(mapOf(
                        "LD_LIBRARY_PATH" to libDirs,
                        "HOME" to filesDir.absolutePath,
                        "TERM" to "xterm-256color",
                        "LANG" to "en_US.UTF-8",
                        "TMPDIR" to File(cacheDir, "shell_tmp").apply { mkdirs() }.absolutePath
                    ))
                    File(applicationInfo.nativeLibraryDir, "libexec_redirect.so").takeIf { it.exists() }?.let {
                        pb.environment()["LD_PRELOAD"] = it.absolutePath
                    }
                    pb.redirectErrorStream(true)
                    runShellProcess(pb, outputText, scrollView)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        outputText.append("ERROR: architecture not supported: your cpu architecture is arm64(-v8a) but the file you are trying to run is $arch\n")
                        outputText.append("(${nativeBinary})\n")
                    }
                }
                return@Thread
            }

            // Regular shell command with full integrated environment
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(cwd)
            pb.environment().putAll(buildFullShellEnv())
            pb.redirectErrorStream(true)
            runShellProcess(pb, outputText, scrollView)
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                outputText.append("Error: ${e.message}\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }.start()
}

private fun Context.runShellProcess(pb: ProcessBuilder, outputText: TextView, scrollView: ScrollView) {
    val proc = pb.start()
    val reader = proc.inputStream.bufferedReader()
    val lineBuf = StringBuilder()
    var overwrite = false
    var inEscape = false
    var escapeType = 0
    var ch: Int
    val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun emit(line: String, over: Boolean) {
        handler.post {
            if (over) {
                // Carriage return: replace the last output line in place (spinner updates)
                val t = outputText.text
                val lastNewline = t.lastIndexOf('\n')
                outputText.text = if (lastNewline >= 0)
                    t.subSequence(0, lastNewline + 1).toString() + line + "\n"
                else line + "\n"
            } else {
                outputText.append("$line\n")
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    while (reader.read().also { ch = it } != -1) {
        if (inEscape) {
            // Consume ANSI escape sequence characters (state machine)
            when (escapeType) {
                0 -> when (ch) {
                    '['.code -> escapeType = 1  // CSI: params + final byte
                    ']'.code -> escapeType = 2  // OSC: terminated by BEL or ST
                    else -> inEscape = false    // 2-char escape (ESC=, ESC>)
                }
                1 -> if (ch in 0x40..0x7E) inEscape = false  // CSI final byte
                2 -> when {
                    ch == 0x07 -> inEscape = false              // BEL terminator
                    ch == 0x1B -> escapeType = 3                // ST: ESC \
                    else -> {}
                }
                3 -> if (ch == '\\'.code) inEscape = false
            }
            continue
        }
        when (ch) {
            0x1B -> { inEscape = true; escapeType = 0 }
            '\r'.code -> {
                // CR: next text overwrites current line (TUI spinner behavior)
                if (lineBuf.isNotEmpty()) {
                    emit(lineBuf.toString(), true)
                    lineBuf.clear()
                } else {
                    overwrite = true
                }
            }
            '\n'.code -> {
                val line = lineBuf.toString()
                lineBuf.clear()
                if (line.isNotEmpty()) emit(line, overwrite)
                overwrite = false
            }
            8 -> {
                if (lineBuf.isNotEmpty()) lineBuf.deleteCharAt(lineBuf.length - 1)
            }
            else -> if (ch >= 32 || ch == '\t'.code) lineBuf.append(ch.toChar())
        }
    }
    if (lineBuf.isNotEmpty()) emit(lineBuf.toString(), overwrite)
    val exitCode = proc.waitFor()
    handler.post {
        outputText.append("[exit $exitCode]\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

/** Native tools that are Android ELF executables (auto-detected + run via linker64). */
private val NATIVE_TOOL_NAMES = setOf(
    "clang", "clang++", "gcc", "g++", "cc", "c++",
    "ld", "ld.lld", "ld.gold", "lld", "lld-link", "llvm-ld",
    "ar", "llvm-ar", "nm", "llvm-nm", "objdump", "llvm-objdump",
    "readelf", "llvm-readelf", "strip", "llvm-strip", "ranlib", "llvm-ranlib",
    "make", "cmake", "ninja", "ninja-build", "pkg-config", "pkgconf",
    "addr2line", "llvm-addr2line", "objcopy", "llvm-objcopy", "size", "strings",
    "c++filt", "llvm-cxxfilt", "llvm-size", "llvm-cov", "llvm-profdata",
    "clang-format", "clang-tidy", "clangd", "lldb", "gdb"
)

/** Finds an Android-executable native tool binary by searching NDK/CMake/toolchain dirs. */
private fun Context.resolveNativeToolBinary(toolName: String): String? {
    val roots = mutableListOf<File>()
    // NDK via ToolchainManager
    runCatching {
        com.aide.mobilestudio.ToolchainManager(this@resolveNativeToolBinary)
            .activeDir(com.aide.mobilestudio.ToolchainManager.Kind.NDK)
    }?.getOrNull()?.let { roots.add(it) }
    // Common toolchain dirs
    File(filesDir, "toolchain/ndk").takeIf { it.isDirectory }?.let { roots.add(it) }
    File(filesDir, "toolchain/cmake").takeIf { it.isDirectory }?.let { roots.add(it) }
    File(filesDir, "toolchain/llvm").takeIf { it.isDirectory }?.let { roots.add(it) }
    File(filesDir, "toolchain/runtime-tools").takeIf { it.isDirectory }?.let { roots.add(it) }
    // Native lib dir (bundled tools)
    File(applicationInfo.nativeLibraryDir).takeIf { it.isDirectory }?.let { roots.add(it) }

    val searchSubdirs = listOf(
        "",
        "bin",
        "usr/bin",
        "toolchains/llvm/prebuilt/linux-x86_64/bin",
        "toolchains/llvm/prebuilt/android-aarch64/bin",
        "toolchains/llvm/prebuilt/linux-aarch64/bin"
    )
    for (rootDir in roots) {
        for (sub in searchSubdirs) {
            val f = File(rootDir, if (sub.isEmpty()) toolName else "$sub/$toolName")
            if (f.isFile) return f.absolutePath
        }
    }
    return null
}

/** Reads the ELF header and returns the architecture string (e.g. "arm64-v8a", "x86_64"). */
private fun readElfArch(file: File): String? {
    return try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 20) return null
            val header = ByteArray(20)
            raf.readFully(header)
            // ELF magic: 0x7f 'E' 'L' 'F'
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return null
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            when (machine) {
                0xB7 -> "arm64-v8a"   // EM_AARCH64
                0x3E -> "x86_64"      // EM_X86_64
                0x03 -> "x86"         // EM_386
                0x28 -> "arm32"       // EM_ARM
                0xF3 -> "riscv64"     // EM_RISCV
                0x8E -> "mips64"      // EM_MIPS64... (rare)
                else -> "unknown(machine=0x" + Integer.toHexString(machine) + ")"
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** Full integrated shell environment (PATH with toolchains, LD_LIBRARY_PATH, LD_PRELOAD). */
private fun Context.buildFullShellEnv(): Map<String, String> {
    val tm = runCatching { com.aide.mobilestudio.ToolchainManager(this@buildFullShellEnv) }.getOrNull()
    val jdk = runCatching { tm?.activeJdkHome() }.getOrNull()
    val sdk = runCatching { tm?.activeDir(com.aide.mobilestudio.ToolchainManager.Kind.SDK) }.getOrNull()
    val ndk = runCatching { tm?.activeDir(com.aide.mobilestudio.ToolchainManager.Kind.NDK) }.getOrNull()
    val cmake = runCatching { tm?.activeDir(com.aide.mobilestudio.ToolchainManager.Kind.CMAKE) }.getOrNull()
    val gradle = runCatching { tm?.activeDir(com.aide.mobilestudio.ToolchainManager.Kind.GRADLE) }.getOrNull()
    val python = runCatching { tm?.activeDir(com.aide.mobilestudio.ToolchainManager.Kind.PYTHON) }.getOrNull()
    val nativeLibDir = applicationInfo.nativeLibraryDir
    val runtimeToolsBin = File(filesDir, "toolchain/runtime-tools/bin").takeIf { it.isDirectory }
    val runtimeToolsLib = File(filesDir, "toolchain/runtime-tools/lib").takeIf { it.isDirectory }
    val tmp = File(cacheDir, "shell_tmp").apply { mkdirs() }

    val pathParts = buildList {
        add("/system/bin")
        add("/system/xbin")
        runtimeToolsBin?.let { add(it.absolutePath) }
        jdk?.let { add(File(it, "bin").absolutePath) }
        gradle?.let { add(File(it, "bin").absolutePath) }
        sdk?.let {
            add(File(it, "platform-tools").absolutePath)
            add(File(it, "cmdline-tools/latest/bin").absolutePath)
        }
        cmake?.let { add(File(it, "bin").absolutePath) }
        ndk?.let { add(File(it, "toolchains/llvm/prebuilt/linux-x86_64/bin").absolutePath) }
        python?.let {
            add(File(it, "bin").absolutePath)
            add(File(it, "local/bin").absolutePath)
        }
    }.distinct().joinToString(":")

    val ldParts = buildList {
        jdk?.let {
            add(File(it, "lib/server").absolutePath)
            add(File(it, "lib").absolutePath)
            add(File(it, "lib/jli").absolutePath)
        }
        cmake?.let {
            add(File(it, "bin").absolutePath)
            add(File(it, "lib").absolutePath)
        }
        runtimeToolsLib?.let { add(it.absolutePath) }
        add(nativeLibDir)
        add("/system/lib64")
        add("/system/lib")
    }.distinct().joinToString(":")

    return buildMap {
        put("PATH", pathParts)
        put("LD_LIBRARY_PATH", ldParts)
        put("HOME", filesDir.absolutePath)
        put("TMPDIR", tmp.absolutePath)
        put("TERM", "xterm-256color")
        put("LANG", "en_US.UTF-8")
        put("MOBILESTUDIO_NATIVE_LIB_DIR", nativeLibDir)
        put("MOBILESTUDIO_APK_PATH", applicationInfo.sourceDir)
        put("MOBILESTUDIO_CODE_CACHE", codeCacheDir.absolutePath)
        File(nativeLibDir, "libexec_redirect.so").takeIf { it.exists() }?.let {
            put("LD_PRELOAD", it.absolutePath)
        }
        jdk?.let { put("JAVA_HOME", it.absolutePath) }
        sdk?.let {
            put("ANDROID_HOME", it.absolutePath)
            put("ANDROID_SDK_ROOT", it.absolutePath)
        }
        ndk?.let {
            put("ANDROID_NDK_HOME", it.absolutePath)
            put("ANDROID_NDK_ROOT", it.absolutePath)
        }
        gradle?.let { put("GRADLE_HOME", it.absolutePath) }
    }
}

private fun Context.buildPanelTabs(): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(c(StudioUi.LayerPanelTabs))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        addView(HorizontalScrollView(this@buildPanelTabs).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(LinearLayout(this@buildPanelTabs).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                // The order the design shows: diagnostics first, then the raw
                // output they came from. Search and AI are gone from here -
                // they live on the rail now, where they are reachable whether
                // the panel is open or not.
                addView(panelTab(R.id.tab_problems, ideLabel("Problems", "문제"), iconColor = StudioUi.ActiveRed))
                addView(panelTab(R.id.tab_build, ideLabel("Build", "출력"), selected = true, iconColor = StudioUi.ActiveGreen))
                addView(panelTab(R.id.tab_shell, ideLabel("Terminal", "터미널"), iconColor = StudioUi.ActivePurple))
                addView(panelTab(R.id.tab_logcat, ideLabel("Logcat", "로그캣"), iconColor = StudioUi.ActiveAmber))
            })
        })
        // Build progress indicator
        addView(ProgressBar(this@buildPanelTabs).apply {
            id = R.id.build_progress_mini
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        // Clear
        addView(iconButton(R.id.btn_clear_panel, android.R.drawable.ic_menu_delete, StudioUi.InactiveIcon, "Clear", 30))
        // Hide
        addView(iconButton(R.id.btn_hide_panel, android.R.drawable.arrow_up_float, StudioUi.InactiveIcon, "Hide", 30))

        // Long-press drag to resize panel
        var dragStartY = 0f
        var dragStartHeight = 0
        setOnLongClickListener { true }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartHeight = (v.parent as? LinearLayout)?.let { parent ->
                        // Find the bottom_panel in the hierarchy
                        var p: android.view.ViewParent? = v.parent
                        while (p != null && p !is android.widget.FrameLayout) p = p.parent
                        (p as? android.widget.FrameLayout)?.layoutParams?.height ?: 0
                    } ?: 0
                    false // let long-press propagate
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (dragStartY - event.rawY).toInt()
                    val newHeight = (dragStartHeight + deltaY).coerceIn(dp(120), dp(600))
                    // Find and resize the bottom_panel
                    var p: android.view.ViewParent? = v.parent
                    while (p != null) {
                        if (p is android.widget.FrameLayout && p.id == R.id.bottom_panel) {
                            p.layoutParams = p.layoutParams.apply { height = newHeight }
                            break
                        }
                        p = p.parent
                    }
                    true
                }
                else -> false
            }
        }
    }
}

private fun Context.buildStatusBar(): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(c(StudioUi.LayerStatusBar))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))

        // Error and warning counts first: the design puts them at the leading
        // edge of the status line, where there is room for them to grow and
        // where they sit next to the thing they describe. They were squeezed
        // into the top bar, which has no space left for a count that can reach
        // three digits.
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.badge_warnings
            text = ""
            textSize = StudioUi.TypeCaption
            typeface = Typeface.MONOSPACE
            setTextColor(c(StudioUi.ActiveAmber))
            visibility = View.GONE
        })
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.badge_errors
            text = ""
            textSize = StudioUi.TypeCaption
            typeface = Typeface.MONOSPACE
            setTextColor(c(StudioUi.ActiveRed))
            visibility = View.GONE
            setPadding(dp(2), 0, dp(6), 0)
        })
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.tv_build_status
            text = ""
            textSize = StudioUi.TypeCaption
            typeface = Typeface.MONOSPACE
            setTextColor(c(StudioUi.ActiveGreen))
            setSingleLine(true)
            maxEms = 10
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(2), 0, dp(6), 0)
        })
        addView(View(this@buildStatusBar), LinearLayout.LayoutParams(dp(1), dp(14)).apply {
            setBackgroundColor(c(StudioUi.Divider))
        })

        // Left: status message
        addView(statusText(R.id.tv_status_left, "Ready"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Separator
        addView(dividerVertical())
        // Language
        addView(statusText(R.id.tv_status_lang, "Kotlin"))

        // Separator
        addView(dividerVertical())
        // Cursor position (clickable -> Go to Line)
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.tv_status_cursor
            text = "Ln 1, Col 1"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(c(StudioUi.TextSoft))
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            setOnClickListener { (context as? MainActivity)?.showGoToLineDialog() }
            StudioUi.touchLift(this)
        })

        // Separator
        addView(dividerVertical())
        // Encoding
        addView(statusText(R.id.tv_status_encoding, "UTF-8"))

        // Gradle version of the open project (hidden when there is none)
        addView(statusText(R.id.tv_status_gradle, ""))

        // Memory indicator
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.tv_memory_indicator
            text = ""
            textSize = 9f
            setTextColor(c(StudioUi.InactiveText))
            setPadding(dp(6), 0, dp(4), 0)
            isClickable = true
        })
        // Background task
        addView(TextView(this@buildStatusBar).apply {
            id = R.id.tv_background_task
            text = ""
            textSize = 9f
            setTextColor(c(StudioUi.InactiveText))
            setPadding(dp(4), 0, dp(6), 0)
            isClickable = true
        })

        // Right: error/sync status
        addView(statusText(R.id.tv_status_right, ""))
    }
}

private fun Context.buildDrawerSurface(): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c(StudioUi.Surface))
        addView(LinearLayout(this@buildDrawerSurface).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(c(StudioUi.Surface))
            addView(TextView(this@buildDrawerSurface).apply {
                id = R.id.tv_drawer_title
                text = "EXPLORER"
                textSize = 10f
                letterSpacing = 0.12f
                typeface = Typeface.MONOSPACE
                setTextColor(c(StudioUi.Muted))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
            })
            addView(LinearLayout(this@buildDrawerSurface).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(iconButton(R.id.btn_drawer_new_project, android.R.drawable.ic_menu_add, String.format("#%06X", 0xFFFFFF and StudioUi.palette.accent), "New project", 28))
                addView(iconButton(R.id.btn_new_file, android.R.drawable.ic_menu_edit, String.format("#%06X", 0xFFFFFF and StudioUi.palette.success), "New file", 28))
                addView(iconButton(R.id.btn_new_folder, android.R.drawable.ic_menu_sort_by_size, String.format("#%06X", 0xFFFFFF and StudioUi.palette.warning), "New folder", 28))
                addView(View(this@buildDrawerSurface).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                addView(TextView(this@buildDrawerSurface).apply {
                    id = R.id.btn_tutorial
                    text = if (AppLanguage.isKorean(this@buildDrawerSurface)) "튜토리얼" else "Tutorial"
                    textSize = 11f
                    setTextColor(c(StudioUi.Muted))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    gravity = Gravity.CENTER
                    background = StudioUi.ripple(this@buildDrawerSurface, StudioUi.rounded(this@buildDrawerSurface, fill = StudioUi.Card, radiusDp = StudioUi.RadiusSm))
                })
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(StudioUi.divider(this@buildDrawerSurface, StudioUi.Divider))
        // Windows Explorer style path bar: Up button + current directory breadcrumb
        addView(LinearLayout(this@buildDrawerSurface).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(c(StudioUi.Surface))
            addView(ImageButton(this@buildDrawerSurface).apply {
                id = R.id.btn_drawer_up
                setImageResource(android.R.drawable.ic_menu_upload)
                background = null
                setColorFilter(c(StudioUi.Blue))
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(24))
                setOnClickListener { (context as? MainActivity)?.navigateDrawerUp() }
                contentDescription = "Up"
            })
            addView(TextView(this@buildDrawerSurface).apply {
                id = R.id.tv_drawer_path
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(c(StudioUi.TextSoft))
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(dp(6), 0, 0, 0)
                setOnClickListener { (context as? MainActivity)?.navigateDrawerUp() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(StudioUi.thinDivider(this@buildDrawerSurface))
        addView(LinearLayout(this@buildDrawerSurface).apply {
            id = R.id.file_tree_search_container
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(c(StudioUi.Surface))
            addView(ImageButton(this@buildDrawerSurface).apply {
                setImageResource(android.R.drawable.ic_menu_search)
                background = null
                setColorFilter(c(StudioUi.Muted))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            addView(EditText(this@buildDrawerSurface).apply {
                id = R.id.et_file_tree_search
                hint = if (AppLanguage.isKorean(this@buildDrawerSurface)) "파일 검색..." else "Search files..."
                setHintTextColor(c(StudioUi.Muted))
                setTextColor(c(StudioUi.Text))
                textSize = 12f
                background = null
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        addView(StudioUi.divider(this@buildDrawerSurface, StudioUi.Divider))
        addView(RecyclerView(this@buildDrawerSurface).apply {
            id = R.id.file_tree
            setBackgroundColor(c(StudioUi.Surface))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(2)
        })
        addView(StudioUi.divider(this@buildDrawerSurface, StudioUi.Divider))
        addView(LinearLayout(this@buildDrawerSurface).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(c(StudioUi.Surface))
            addView(TextView(this@buildDrawerSurface).apply {
                id = R.id.btn_settings
                text = if (AppLanguage.isKorean(this@buildDrawerSurface)) "설정" else "Settings"
                textSize = 11f
                setTextColor(c(StudioUi.TextSoft))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                background = StudioUi.ripple(this@buildDrawerSurface, StudioUi.rounded(this@buildDrawerSurface, fill = StudioUi.Card, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusSm))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            })
            addView(TextView(this@buildDrawerSurface).apply {
                id = R.id.btn_theme
                text = if (AppLanguage.isKorean(this@buildDrawerSurface)) "테마" else "Theme"
                textSize = 11f
                setTextColor(c(StudioUi.TextSoft))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                background = StudioUi.ripple(this@buildDrawerSurface, StudioUi.rounded(this@buildDrawerSurface, fill = StudioUi.Card, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusSm))
            })
        })
    }
}

private fun Context.menuText(viewId: Int, label: String): TextView {
    return TextView(this).apply {
        id = viewId
        text = label
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setTextColor(c(StudioUi.Text))
        setPadding(dp(10), 0, dp(10), 0)
        background = StudioUi.ripple(this@menuText, StudioUi.rounded(this@menuText, fill = StudioUi.Card, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusXl))
        StudioUi.touchLift(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun Context.panelTab(viewId: Int, label: String, selected: Boolean = false, iconColor: String = "#6B7280"): TextView {
    // Icon-only mode for narrow screens (< 400dp width)
    val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    val iconOnly = widthDp < 400
    
    val iconMap = mapOf(
        R.id.tab_build to "\u25B6",
        R.id.tab_logcat to "\uD83D\uDC31",
        R.id.tab_problems to "\u2716",
        R.id.tab_search to "\uD83D\uDD0D",
        R.id.tab_shell to ">_"
    )
    
    return TextView(this).apply {
        id = viewId
        text = if (iconOnly) iconMap[viewId] ?: label else AppLanguage.literal(this@panelTab, label)
        textSize = StudioUi.TypeSmall
        gravity = Gravity.CENTER
        setTextColor(c(if (selected) iconColor else StudioUi.InactiveIcon))
        if (selected) {
            background = GradientDrawable().apply {
                setColor(c(StudioUi.LayerPanelTabs))
                cornerRadius = dp(StudioUi.RadiusMd).toFloat()
                setStroke(dp(1), c(iconColor))
            }
        }
        StudioUi.touchLift(this)
        StudioUi.enforceMinTouchTarget(this)
        val pad = if (iconOnly) dp(8) else dp(10)
        setPadding(pad, 0, pad, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun Context.statusText(viewId: Int, label: String): TextView {
    return TextView(this).apply {
        id = viewId
        text = label
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(c(StudioUi.TextSoft))
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
    }
}

private fun Context.iconButton(viewId: Int, icon: Int, tint: String, description: String, size: Int): ImageButton {
    return ImageButton(this).apply {
        id = viewId
        setImageResource(icon)
        setColorFilter(c(tint))
        contentDescription = AppLanguage.literal(this@iconButton, description)
        background = StudioUi.ripple(this@iconButton, StudioUi.rounded(this@iconButton, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusXl))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        StudioUi.touchLift(this)
        StudioUi.enforceMinTouchTarget(this)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
}

private fun Context.hiddenIcon(viewId: Int): ImageButton {
    return ImageButton(this).apply {
        id = viewId
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(0, 0)
    }
}

private fun Context.actionButton(
    viewId: Int,
    label: String,
    bg: String,
    fg: String,
    height: Int = 44
): Button {
    return Button(this).apply {
        id = viewId
        text = AppLanguage.literal(this@actionButton, label)
        textSize = 11f
        studioButton(fill = bg, text = fg, accent = StudioUi.ButtonBorder, radiusDp = StudioUi.RadiusMd)
        StudioUi.enforceMinTouchTarget(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(height)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }
}

private fun Context.input(viewId: Int, hintText: String, imeOptions: Int = EditorInfo.IME_ACTION_NONE): EditText {
    return EditText(this).apply {
        id = viewId
        hint = AppLanguage.literal(this@input, hintText)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(c(StudioUi.Text))
        setHintTextColor(c(StudioUi.Muted))
        setSingleLine(true)
        this.imeOptions = imeOptions
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(10), 0, dp(10), 0)
        background = StudioUi.rounded(this@input, fill = StudioUi.InputBg, stroke = StudioUi.InputBorder, radiusDp = StudioUi.RadiusMd)
    }
}

private fun Context.checkBox(viewId: Int, label: String): CheckBox {
    return CheckBox(this).apply {
        id = viewId
        text = label
        textSize = 10f
        setTextColor(c("#8E99AC"))
        buttonTintList = ColorStateList.valueOf(c("#4F8EF7"))
        minWidth = 0
        minHeight = 0
        StudioUi.enforceMinTouchTarget(this)
    }
}

private fun Context.dividerVertical(): View {
    return View(this).apply {
        setBackgroundColor(c(StudioUi.Divider))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun c(hex: String): Int = Color.parseColor(StudioUi.remapHex(hex))

internal class AutoFollowScrollView(context: Context) : ScrollView(context) {
    var followMode: Boolean = true
        private set

    var onFollowChanged: ((Boolean) -> Unit)? = null

    fun scrollToTopNow() {
        smoothScrollTo(0, 0)
    }

    fun scrollToBottomNow() {
        post { fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun setFollowMode(enabled: Boolean) {
        if (followMode == enabled) return
        followMode = enabled
        onFollowChanged?.invoke(enabled)
        if (enabled) scrollToBottomNow()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t > oldt) return
        val child = getChildAt(0)
        if (child == null) return
        val atBottom = (child.height - scrollY - height) <= 20
        if (followMode && !atBottom) {
            followMode = false
            onFollowChanged?.invoke(false)
        } else if (!followMode && atBottom) {
            followMode = true
            onFollowChanged?.invoke(true)
        }
    }
}