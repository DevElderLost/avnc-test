/*
 * Copyright (c) 2026  DevElderLost.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Fills the [toolbar_virtual_controller.xml] flyout panel with a live checkbox-grid
 * so the user can select which floating keys to show and toggle Edit-Position mode,
 * all without leaving the toolbar drawer (mirrors the gesture-style / view-mode flyout UX).
 *
 * Lifecycle: [inflate] is called once when the ToggleButton becomes checked (drawer open,
 * flyout open). Changes are applied immediately on checkbox toggle and on the "Apply" button
 * for position-reset. The flyout stays open until the user closes the drawer or re-taps the
 * toggle button.
 *
 * @param activity     Host [VncActivity].
 * @param controller   The [VirtualController] instance managed by the same activity.
 * @param contentRoot  The [LinearLayout] inside the flyout ScrollView
 *                     (`R.id.virtual_controller_flyout_content`).
 * @param onClose      Called when the user taps "Terapkan" (apply with optional pos-reset) —
 *                     the caller (Toolbar) should un-check the toggle button to hide the flyout.
 */
class VirtualControllerFlyout(
        private val activity: VncActivity,
        private val controller: VirtualController,
        private val contentRoot: LinearLayout,
        private val onClose: () -> Unit,
) {
    private val checkBoxes = mutableMapOf<Int, CheckBox>()
    private lateinit var editSwitch: Switch

    /** Build the flyout content. Call once per flyout open. */
    fun inflate() {
        contentRoot.removeAllViews()
        checkBoxes.clear()

        // --- Edit-mode switch ---
        editSwitch = Switch(activity).apply {
            text = activity.getString(R.string.virtual_controller_edit_mode)
            isChecked = controller.editMode
            textSize = 13f
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            setOnCheckedChangeListener { _, checked -> controller.setEditMode(checked) }
        }
        contentRoot.addView(editSwitch)
        contentRoot.addView(makeDivider())

        // --- Key sections ---
        VCCatalog.sections.forEach { section ->
            val header = TextView(activity).apply {
                text = section.title
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                alpha = 0.7f
                setPadding(0, dpToPx(6), 0, dpToPx(2))
            }
            contentRoot.addView(header)

            val grid = GridLayout(activity).apply {
                columnCount = section.columns.coerceAtMost(3) // max 3 columns to fit 220dp
            }
            section.entries.forEach { entry ->
                val cb = CheckBox(activity).apply {
                    text = entry.label
                    isChecked = controller.hasKey(entry.keyCode)
                    textSize = 12f
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = GridLayout.LayoutParams.WRAP_CONTENT
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        val m = dpToPx(1)
                        setMargins(m, m, m, m)
                    }
                    setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                        if (checked) controller.addKey(entry)
                        else controller.removeKey(entry.keyCode)
                    }
                }
                checkBoxes[entry.keyCode] = cb
                grid.addView(cb)
            }
            contentRoot.addView(grid)
        }

        contentRoot.addView(makeDivider())

        // --- Action buttons at the bottom ---
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpToPx(4), 0, 0)
        }

        val btnResetPos = makeTextButton(activity.getString(R.string.virtual_controller_reset_positions)) {
            controller.resetPositions()
            onClose()
        }
        val btnApply = makeTextButton(activity.getString(R.string.virtual_controller_apply)) {
            onClose()
        }

        btnRow.addView(btnResetPos)
        btnRow.addView(btnApply)
        contentRoot.addView(btnRow)
    }

    /** Sync switch state when flyout is re-opened (e.g. after edit mode was exited via Done chip). */
    fun syncEditSwitch() {
        if (::editSwitch.isInitialized) editSwitch.isChecked = controller.editMode
    }

    private fun makeDivider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
            setMargins(0, dpToPx(4), 0, dpToPx(4))
        }
        setBackgroundColor(
                TypedValue().let { tv ->
                    activity.theme.resolveAttribute(android.R.attr.listDivider, tv, true)
                    ContextCompat.getColor(activity, tv.resourceId)
                }
        )
    }

    private fun makeTextButton(label: String, onClick: () -> Unit): TextView {
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(TypedValue().let { tv2 ->
                activity.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorPrimary, tv2, true)
                ContextCompat.getColor(activity, tv2.resourceId)
            })
            setPadding(dpToPx(8), dpToPx(8), dpToPx(4), dpToPx(8))
            setBackgroundResource(tv.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), activity.resources.displayMetrics
        ).toInt()
    }
}
