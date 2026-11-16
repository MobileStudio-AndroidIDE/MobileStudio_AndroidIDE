package com.aide.mobilestudio.buildtoolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.TextView
import com.aide.mobilestudio.R
import java.io.File

/**
 * Build Variants Panel - selects build variant (debug/release × flavor combinations).
 * Shows as dropdown in mini toolbar, similar to Android Studio.
 */
class BuildVariantsPanel(
    private val context: Context,
    private val projectDir: File
) {
    private val TAG = "BuildVariantsPanel"

    var onVariantChanged: ((BuildVariant) -> Unit)? = null

    private val variants = listOf(
        BuildVariant("debug", "arm64-v8a", "Debug"),
        BuildVariant("release", "arm64-v8a", "Release"),
        BuildVariant("debug", "armeabi-v7a", "Debug"),
        BuildVariant("release", "armeabi-v7a", "Release")
    )

    data class BuildVariant(
        val buildType: String,
        val abi: String,
        val displayName: String
    )

    private var currentVariant: BuildVariant? = null

    /**
     * Creates a simple spinner view for variant selection.
     */
    fun createView(): android.widget.Spinner {
        val spinner = android.widget.Spinner(context)
        val adapter = ArrayAdapter(context, R.layout.spinner_dark_item, variants.map { it.displayName })
        adapter.setDropDownViewResource(R.layout.spinner_dark_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val variant = variants[position]
                currentVariant = variant
                onVariantChanged?.invoke(variant)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
            }
        }

        return spinner
    }

    /**
     * Returns the currently selected variant.
     */
    fun getCurrentVariant(): BuildVariant? = currentVariant
}
