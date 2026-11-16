package com.aide.mobilestudio.runconfig

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Run Configuration Manager - manages run/debug configurations.
 * Simplified implementation for compilation.
 */
class RunConfigurationManager(private val context: Context) {
    private val configurations = ConcurrentHashMap<String, RunConfiguration>()
    private val runDotDir = File(context.filesDir, ".run")

    private val prefs: SharedPreferences = context.getSharedPreferences("run_configurations", Context.MODE_PRIVATE)

    init {
        runDotDir.mkdirs()
        loadConfigurations()
    }

    fun createConfiguration(config: RunConfiguration): RunConfiguration {
        val id = config.id
        configurations[id] = config
        saveConfiguration(config)
        writeToXmlFile(config)
        return config
    }

    fun getConfiguration(id: String): RunConfiguration? {
        return configurations[id]
    }

    fun getAllConfigurations(): List<RunConfiguration> {
        return configurations.values.toList()
    }

    fun deleteConfiguration(id: String) {
        configurations.remove(id)
        val file = File(runDotDir, "$id.xml")
        file.delete()
    }

    private fun saveConfiguration(config: RunConfiguration) {
        val editor = prefs.edit()
        editor.putString(config.id, config.toString())
        editor.apply()
    }

    private fun loadConfigurations() {
        val all = prefs.all
        for ((key, value) in all) {
            if (key.startsWith("config_")) {
                val config = RunConfiguration(id = key, name = value as String)
                configurations[config.id] = config
            }
        }
    }

    private fun writeToXmlFile(config: RunConfiguration) {
        val file = File(runDotDir, "${config.id}.run.xml")
        val xml = """<?xml version="1.0" encoding="UTF-8"?><configuration name="${config.name}" type="${config.type}"/>"""
        file.writeText(xml)
    }
}