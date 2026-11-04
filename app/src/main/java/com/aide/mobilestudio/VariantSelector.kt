package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * VariantSelector - Android Studio-style Build Variant selector
 * Shows available build variants and lets the user pick one.
 */
class VariantSelector(private val context: Context) {

    data class VariantChoice(
        val name: String,
        val type: String,
        val flavor: String,
        val isDebug: Boolean
    )

    interface OnVariantSelectedListener {
        fun onVariantSelected(variant: VariantChoice)
    }

    private var currentVariant: String = "debug"
    private var availableVariants: List<VariantChoice> = emptyList()
    private var listener: OnVariantSelectedListener? = null
    private var variantLabel: TextView? = null

    fun setVariantLabel(label: TextView) {
        variantLabel = label
        updateLabel()
    }

    fun setListener(listener: OnVariantSelectedListener) {
        this.listener = listener
    }

    fun setVariants(variants: List<VariantChoice>, activeVariant: String = "debug") {
        availableVariants = variants
        currentVariant = activeVariant
        updateLabel()
    }

    fun setVariantsFromModel(model: AndroidProjectModel) {
        val variants = model.availableVariants.map { v ->
            VariantChoice(
                name = v.name,
                type = v.type,
                flavor = v.flavor,
                isDebug = v.isDebug
            )
        }
        if (variants.isNotEmpty()) {
            setVariants(variants, model.activeVariant)
        } else {
            // Provide defaults for projects without explicit variant config
            setVariants(
                listOf(
                    VariantChoice("debug", "Debug", "", true),
                    VariantChoice("release", "Release", "", false)
                ),
                model.activeVariant
            )
        }
    }

    fun getCurrentVariant(): String = currentVariant

    fun showSelector() {
        if (availableVariants.isEmpty()) {
            showNoVariantsDialog()
            return
        }

        val variantGroups = availableVariants.groupBy { it.flavor.ifEmpty { "(default)" } }
        val sortedGroups = variantGroups.keys.sorted()

        val builder = AlertDialog.Builder(context)
        builder.setTitle("Build Variant")

        if (variantGroups.size <= 1) {
            // Simple list - no flavors
            val variants = availableVariants
            val checkedIndex = variants.indexOfFirst { it.name == currentVariant }.coerceAtLeast(0)
            builder.setSingleChoiceItems(
                variants.map { "${it.type} (${it.name})" }.toTypedArray(),
                checkedIndex
            ) { dialog, which ->
                selectVariant(variants[which])
                dialog.dismiss()
            }
        } else {
            // Grouped by flavor
            val items = mutableListOf<String>()
            val flatVariants = mutableListOf<VariantChoice>()
            for (group in sortedGroups) {
                items.add("── $group ──")
                flatVariants.add(VariantChoice("", "", "", false)) // placeholder
                for (v in variantGroups[group]!!) {
                    items.add("  ${v.type}")
                    flatVariants.add(v)
                }
            }
            val checkedIndex = flatVariants.indexOfFirst { it.name == currentVariant }.coerceAtLeast(0)
            builder.setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                val selected = flatVariants[which]
                if (selected.name.isNotEmpty()) {
                    selectVariant(selected)
                    dialog.dismiss()
                }
            }
        }

        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun selectVariant(variant: VariantChoice) {
        currentVariant = variant.name
        updateLabel()
        listener?.onVariantSelected(variant)
    }

    private fun updateLabel() {
        variantLabel?.text = currentVariant.replaceFirstChar { it.uppercase() }
    }

    private fun showNoVariantsDialog() {
        AlertDialog.Builder(context)
            .setTitle("Build Variants")
            .setMessage("No build variants found. Run Gradle Sync to detect variants.\n\nDefault: debug")
            .setPositiveButton("OK", null)
            .show()
    }

    fun getAssembleTask(): String = "assemble${currentVariant.replaceFirstChar { it.uppercase() }}"
    fun getInstallTask(): String = if (currentVariant.contains("debug")) "installDebug" else "installRelease"
    fun getCleanTask(): String = "clean"
    fun getBundleTask(): String = "bundle${currentVariant.replaceFirstChar { it.uppercase() }}"
}
