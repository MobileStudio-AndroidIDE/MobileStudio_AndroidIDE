/**
 * Project View System for MobileStudio IDE.
 *
 * Provides a complete project view implementation with multiple display modes:
 * - ProjectView: Main composable with toolbar, tree, and details
 * - ProjectViewMode: Display modes (Project, Android, Structure, Packages, Scopes, etc.)
 * - ProjectTreeModel: Tree nodes for files, directories, modules, libraries, structure
 * - ProjectTreeRenderer: Virtualized LazyColumn renderer for large trees
 * - ProjectFilter: File type, name pattern, scope, and custom filtering
 * - ProjectScope: User-defined file scopes with pattern matching
 * - ProjectViewActions: Context menu actions (New, Refactor, Find Usages, Delete, etc.)
 * - ProjectViewDragDrop: Drag-and-drop for moving/copying files and reordering
 *
 * Features:
 * - Multiple view modes matching Android Studio
 * - Virtualized rendering for 10k+ nodes
 * - Expand/collapse with animation
 * - Multi-selection and keyboard navigation
 * - Drag-and-drop with visual feedback
 * - Custom scopes and filters
 * - Structure view for code elements
 * - Problem/error integration
 * - Git status indicators
 */
package com.aide.mobilestudio.ui.projectview;