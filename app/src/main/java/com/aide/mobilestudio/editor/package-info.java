/**
 * Editor Core System for MobileStudio IDE.
 *
 * Provides a complete code editor implementation with advanced features:
 * - EditorCore: Main Compose-based editor (BasicTextField2)
 * - CodeFolding: Brace, import, comment, and region folding
 * - MultiCursor: Alt+Click, Ctrl+Alt+Up/Down, column selection
 * - CodeCompletion: LSP + local symbols + snippets with fuzzy matching
 * - RefactoringEngine: Rename, Extract Method/Variable/Field/Parameter, Inline, Move, Change Signature
 * - ImportOptimizer: Unused removal, sorting, static import merging, wildcard optimization
 * - CodeFormatter: ktfmt/spotless integration with EditorConfig support
 * - InlayHints: Parameter names, type hints, chaining hints, nullable hints
 * - Breadcrumb: Hierarchical navigation (package → class → method → block)
 * - Minimap: Syntax-highlighted overview with viewport, markers, git changes
 * - EditorTabs: Tab groups, splits, pinning, preview, drag-to-reorder/split
 * - EditorDiagnostics: Real-time errors/warnings with quick fixes
 * - ProjectContext: Project services for editor operations
 *
 * Features:
 * - Tree-sitter ready architecture (SyntaxHighlighter interface)
 * - Language Server Protocol integration points
 * - Multi-cursor editing
 * - Code folding with multiple strategies
 * - Intelligent code completion with auto-import
 * - Comprehensive refactoring engine
 * - Import organization and optimization
 * - Configurable code formatting
 * - Inlay hints for parameter names and types
 * - Breadcrumb navigation
 * - Minimap with diagnostics and git markers
 * - Advanced tab management with splits and groups
 * - Android Studio New UI styling with density settings
 */
package com.aide.mobilestudio.editor;