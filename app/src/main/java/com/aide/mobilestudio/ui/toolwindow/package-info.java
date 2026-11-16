/**
 * Tool Window System for MobileStudio IDE.
 *
 * Provides a complete tool window management system inspired by Android Studio / IntelliJ Platform:
 * - ToolWindowManager: Central registry and layout management
 * - ToolWindow/ToolWindowTab: Base classes for tool window content and tabs
 * - ToolWindowAnchor/ToolWindowMode: Position and display mode enums
 * - ToolWindowLayout: Grid/split layout system with tab groups
 * - ToolWindowDragController: Drag-and-drop for moving, splitting, tab merging
 * - ToolWindowState: Persistent state via DataStore
 * - StandardToolWindows: Built-in tool windows (Project, Terminal, Logcat, etc.)
 *
 * Features:
 * - Docked, sliding (auto-hide), and floating modes
 * - Four anchor positions (left, right, bottom, top)
 * - Tab reordering, grouping, and splitting
 * - Keyboard shortcuts (Alt+1..9, Shift+Escape, Ctrl+Tab)
 * - State persistence and restoration
 * - Android Studio New UI styling (Compact/Normal/Comfortable density)
 */
package com.aide.mobilestudio.ui.toolwindow;