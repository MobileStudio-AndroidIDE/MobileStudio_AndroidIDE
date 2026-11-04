package com.aide.mobilestudio.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base bottom sheet dialog fragment for small screen optimization.
 * Replaces full-screen AlertDialogs with bottom sheets on narrow screens.
 */
abstract class BaseBottomSheetDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog
        if (dialog != null) {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.peekHeight = resources.displayMetrics.heightPixels
            }
        }
    }

    /** Called when the bottom sheet is fully expanded. Override to populate UI. */
    abstract fun onSheetExpanded(view: View)

    /** Optional: custom layout resource. Override if needed. */
    open fun getLayoutRes(): Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutRes = getLayoutRes()
        if (layoutRes != 0) {
            val view = inflater.inflate(layoutRes, container, false)
            onSheetExpanded(view)
            return view
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (getLayoutRes() == 0) {
            onSheetExpanded(view)
        }
    }

    /** Shows the bottom sheet dialog. */
    fun show(activity: FragmentActivity) {
        show(activity.supportFragmentManager, tag)
    }

    companion object {
        private const val DEFAULT_TAG = "BottomSheetDialog"
    }
}

/**
 * Builder for creating bottom sheet dialogs with simple content.
 */
class SimpleBottomSheetDialogFragment : BaseBottomSheetDialogFragment() {

    private var title: String? = null
    private var message: String? = null
    private var positiveText: String? = null
    private var positiveAction: (() -> Unit)? = null
    private var negativeText: String? = null
    private var negativeAction: (() -> Unit)? = null
    private var neutralText: String? = null
    private var neutralAction: (() -> Unit)? = null

    fun setTitle(title: String): SimpleBottomSheetDialogFragment {
        this.title = title
        return this
    }

    fun setMessage(message: String): SimpleBottomSheetDialogFragment {
        this.message = message
        return this
    }

    fun setPositiveButton(text: String, action: () -> Unit): SimpleBottomSheetDialogFragment {
        this.positiveText = text
        this.positiveAction = action
        return this
    }

    fun setNegativeButton(text: String, action: () -> Unit): SimpleBottomSheetDialogFragment {
        this.negativeText = text
        this.negativeAction = action
        return this
    }

    fun setNeutralButton(text: String, action: () -> Unit): SimpleBottomSheetDialogFragment {
        this.neutralText = text
        this.neutralAction = action
        return this
    }

    override fun getLayoutRes(): Int = 0

    override fun onSheetExpanded(view: View) {
        // We'll build the UI programmatically
        val activity = requireActivity()
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)

        if (positiveText != null) {
            builder.setPositiveButton(positiveText) { _, _ -> positiveAction?.invoke() }
        }
        if (negativeText != null) {
            builder.setNegativeButton(negativeText) { _, _ -> negativeAction?.invoke() }
        }
        if (neutralText != null) {
            builder.setNeutralButton(neutralText) { _, _ -> neutralAction?.invoke() }
        }

        val dialog = builder.create()
        dialog.show()
        
        // Adjust dialog to fit bottom sheet
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

/**
 * Extension for showing bottom sheet dialogs easily.
 */
fun FragmentActivity.showBottomSheet(
    title: String? = null,
    message: String? = null,
    positiveText: String? = null,
    positiveAction: (() -> Unit)? = null,
    negativeText: String? = null,
    negativeAction: (() -> Unit)? = null
) {
    val fragment = SimpleBottomSheetDialogFragment()
    title?.let { fragment.setTitle(it) }
    message?.let { fragment.setMessage(it) }
    positiveText?.let { text -> positiveAction?.let { fragment.setPositiveButton(text, it) } }
    negativeText?.let { text -> negativeAction?.let { fragment.setNegativeButton(text, it) } }
    fragment.show(this)
}