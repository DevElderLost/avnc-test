/*
 * Copyright (c) 2026  DevElderLost.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.graphics.Color
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
import com.gaurav.avnc.R

/**
 * Fills the flyout panel (toolbar_drawer.xml → virtual_controller_group) with a live
 * checkbox-grid so the user can select floating keys and toggle Edit-Position mode —
 * all without leaving the toolbar drawer (mirrors the gesture-style / view-mode flyout UX).
 *
 * All colors are hardcoded rather than resolved from the activity's theme so this class
 * works correctly regardless of which Material library version the host app uses or which
 * theme is active.  This avoids resource-ID mismatches that occur when avnc is used as an
 * AAR library inside an app compiled with a different Material version.
 *
 * @param activity     Host [VncActivity].
 * @param controller   The [VirtualController] instance managed by the same activity.
 * @param contentRoot  The [LinearLayout] inside the flyout ScrollView.
 * @param onClose      Called when the user taps "Terapkan" or "Atur Ulang Posisi" —
 *                     the caller (Toolbar) un-checks the toggle button to hide the flyout.
 */
class VirtualControllerFlyout(
        private val activity: VncActivity,
        private val controller: VirtualController,
        private val contentRoot: LinearLayout,
        private val onClose: () -> Unit,
) {
    private val checkBoxes = mutableMapOf<Int, CheckBox>()
    private lateinit var editSwitch: Switch

    /**
     * Build (or rebuild) the flyout content. Called each time the panel opens so that
     * checkbox states always reflect the current key list.
     */
    fun inflate() {
        contentRoot.removeAllViews()
        checkBoxes.clear()

        // --- Edit-mode switch ---
        editSwitch = Switch(activity).apply {
            text = activity.getString(R.string.virtual_controller_edit_mode)
            isChecked = controller.editMode
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> controller.setEditMode(checked) }
        }
        contentRoot.addView(editSwitch)
        contentRoot.addView(makeDivider())

        // --- Key sections ---
        VCCatalog.sections.forEach { section ->
            val header = TextView(activity).apply {
                text = section.title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                alpha = 0.7f
                setPadding(0, dp(6), 0, dp(2))
            }
            contentRoot.addView(header)

            val grid = GridLayout(activity).apply {
                columnCount = section.columns.coerceAtMost(3) // max 3 cols to fit 220 dp
            }
            section.entries.forEach { entry ->
                val cb = CheckBox(activity).apply {
                    text = entry.label
                    textSize = 12f
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = GridLayout.LayoutParams.WRAP_CONTENT
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        val m = dp(1)
                        setMargins(m, m, m, m)
                    }
                    // Set isChecked BEFORE the listener so it doesn't fire on initialisation.
                    isChecked = controller.hasKey(entry.keyCode)
                    setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                        if (checked) controller.addKey(entry) else controller.removeKey(entry.keyCode)
                    }
                }
                checkBoxes[entry.keyCode] = cb
                grid.addView(cb)
            }
            contentRoot.addView(grid)
        }

        contentRoot.addView(makeDivider())

        // --- Action row ---
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        btnRow.addView(makeTextButton(activity.getString(R.string.virtual_controller_reset_positions)) {
            controller.resetPositions()
            onClose()
        })
        btnRow.addView(makeTextButton(activity.getString(R.string.virtual_controller_apply)) {
            onClose()
        })
        contentRoot.addView(btnRow)
    }

    /** Sync edit-switch when flyout closes (e.g. user tapped the Done chip on overlay). */
    fun syncEditSwitch() {
        if (::editSwitch.isInitialized) editSwitch.isChecked = controller.editMode
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeDivider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
        // Hardcoded semi-transparent neutral — works on both light and dark backgrounds.
        setBackgroundColor(Color.argb(60, 128, 128, 128))
    }

    private fun makeTextButton(label: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 12f
            // Hardcoded accent color that contrasts with both light/dark toolbar backgrounds.
            setTextColor(Color.parseColor("#4FC3F7")) // light-blue-300
            setPadding(dp(8), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics
    ).toInt()
}
