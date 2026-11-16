package com.aide.mobilestudio.buildtoolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.LinearLayout
import java.io.File

/**
 * Sync Issues Panel - displays Gradle sync issues.
 */
class SyncIssuesPanel(
    private val context: Context,
    private val projectDir: File
) {
    private val issues = mutableListOf<String>()

    /**
     * Scans the project for Gradle sync issues.
     */
    fun scanIssues(): List<String> {
        val buildFile = File(projectDir, "build.gradle.kts")
        if (!buildFile.exists()) {
            return listOf()
        }

        val content = buildFile.readText()
        val issues = mutableListOf<String>()

        content.lines().forEach { line ->
            if (line.contains("error")) {
                issues.add(line.trim())
            }
        }

        this.issues.clear()
        this.issues.addAll(issues)
        return issues
    }

    /**
     * Returns current issues.
     */
    fun getIssues(): List<String> = issues

    /**
     * Creates a simple ListView for displaying issues.
     */
    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, issues)
        listView.adapter = adapter
        return listView
    }
}
