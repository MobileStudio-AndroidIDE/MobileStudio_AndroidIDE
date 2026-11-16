package com.aide.mobilestudio.buildtoolwindow

import java.io.File

/**
 * Gradle Task Tree - displays Gradle task hierarchy.
 */
class GradleTaskTree(
    private val context: android.content.Context,
    private val projectDir: File
) {
    private val tasks = mutableListOf<String>()

    /**
     * Loads Gradle tasks from the project.
     */
    fun loadTasks(): List<String> {
        val gradleFile = File(projectDir, "build.gradle.kts")
        if (!gradleFile.exists()) {
            return listOf()
        }

        val content = gradleFile.readText()
        val tasks = mutableListOf<String>()
        content.lines().forEach { line ->
            if (line.contains("task ")) {
                tasks.add(line.trim())
            }
        }
        this.tasks.clear()
        this.tasks.addAll(tasks)
        return tasks
    }

    /**
     * Returns the current task list.
     */
    fun getTasks(): List<String> = tasks
}
