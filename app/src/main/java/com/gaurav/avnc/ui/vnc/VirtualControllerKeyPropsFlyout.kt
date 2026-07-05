/*
 * Copyright (c) 2026  DevElderLost.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */

package com.gaurav.avnc.ui.vnc

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.gaurav.avnc.R

/**
 * A small floating flyout panel that appears near a virtual-key button when the user
 * long-presses it in Edit-Position mode. Lets the user adjust:
 *  - Width  (28 dp … 120 dp)
 *  - Height (28 dp … 120 dp)
 *  - Alpha  (10 % … 100 %)
 *
 * The flyout is a lightweight FrameLayout added directly to [container] (same overlay
 * as the key buttons) so it floats over the remote screen. It is removed as soon as the
 * user taps outside or taps the "✓ OK" button.
 *
 * Changes are applied live via [VirtualController.applyKeyProps] so the user sees the
 * result instantly.
 */
class VirtualControllerKeyPropsFlyout(
        private val activity: VncActivity,
        private val controller: VirtualController,
        private val container: FrameLayout,
) {

    private var currentPanel: View? = null

    // -------------------------------------------------------------------------
    // Public
    // -------------------------------------------------------------------------

    /** Show the flyout near [anchorView] for the given [key]. Dismisses any previous instance. */
    fun show(key: VCKey, anchorView: View) {
        dismiss()
        val panel = buildPanel(key, anchorView)
        container.addView(panel)
        currentPanel = panel

        // Touch outside → dismiss
        container.setOnClickListener { dismiss() }
    }

    fun dismiss() {
        currentPanel?.let {
            container.removeView(it)
            currentPanel = null
        }
        container.setOnClickListener(null)
    }

    // -------------------------------------------------------------------------
    // Panel builder
    // -------------------------------------------------------------------------

    private fun buildPanel(key: VCKey, anchorView: View): View {
        val panelW = dp(200)
        val padding = dp(10)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 30, 30, 30))
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT)
            elevation = dp(8).toFloat()
        }

        // Title row: key label + dismiss button
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = activity.getString(R.string.vc_props_title, key.label)
            setTextColor(Color.WHITE); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(makeTextButton("✕") { dismiss() }.apply {
            textSize = 14f; setPadding(dp(4), 0, 0, 0)
        })
        root.addView(titleRow)
        root.addView(makeDivider())

        // Width slider
        var currentW = key.widthDp
        val wLabel = makePropLabel(activity.getString(R.string.vc_props_width), currentW.toInt())
        root.addView(wLabel)
        root.addView(makeSeekBar(currentW, VCKey.MIN_DP, VCKey.MAX_DP) { v ->
            currentW = v
            wLabel.text = activity.getString(R.string.vc_props_width_val, v.toInt())
            controller.applyKeyProps(key.keyCode, currentW, key.heightDp, key.alpha)
        })

        // Height slider
        var currentH = key.heightDp
        val hLabel = makePropLabel(activity.getString(R.string.vc_props_height), currentH.toInt())
        root.addView(hLabel)
        root.addView(makeSeekBar(currentH, VCKey.MIN_DP, VCKey.MAX_DP) { v ->
            currentH = v
            hLabel.text = activity.getString(R.string.vc_props_height_val, v.toInt())
            controller.applyKeyProps(key.keyCode, currentW, currentH, key.alpha)
        })

        // Alpha slider (0.1 … 1.0 mapped to 1 … 100)
        var currentA = key.alpha
        val aLabel = makePropLabel(activity.getString(R.string.vc_props_alpha), (currentA * 100).toInt())
        root.addView(aLabel)
        root.addView(makeSeekBar(currentA * 100f, 10f, 100f) { v ->
            currentA = v / 100f
            aLabel.text = activity.getString(R.string.vc_props_alpha_val, (currentA * 100).toInt())
            controller.applyKeyProps(key.keyCode, currentW, currentH, currentA)
        })

        root.addView(makeDivider())

        // OK button
        val okRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        okRow.addView(makeTextButton(activity.getString(R.string.vc_props_ok)) { dismiss() })
        root.addView(okRow)

        // Position the panel near the anchor, keeping it inside the container
        positionPanel(root, anchorView, panelW)
        return root
    }

    private fun positionPanel(panel: LinearLayout, anchor: View, panelW: Int) {
        // Measure to get actual height estimate
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val panelH = panel.measuredHeight.takeIf { it > 0 } ?: dp(180)

        val cw = container.width; val ch = container.height
        val ax = anchor.x; val ay = anchor.y
        val aw = anchor.width; val ah = anchor.height

        // Prefer below-right; fallback if outside bounds
        var px = ax + aw + dp(6)
        var py = ay

        if (px + panelW > cw) px = (ax - panelW - dp(6)).coerceAtLeast(0f)
        if (py + panelH > ch) py = (ch - panelH - dp(4)).coerceAtLeast(0f)

        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = px.toInt(); topMargin = py.toInt()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePropLabel(name: String, value: Int): TextView =
            TextView(activity).apply {
                text = "$name: $value"
                setTextColor(Color.LTGRAY); textSize = 12f
                setPadding(0, dp(6), 0, dp(2))
            }

    private fun makeSeekBar(
            current: Float, min: Float, max: Float,
            onChange: (Float) -> Unit,
    ): SeekBar {
        val steps = 100
        val progress = ((current - min) / (max - min) * steps).toInt().coerceIn(0, steps)
        return SeekBar(activity).apply {
            this.max = steps
            this.progress = progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(min + (max - min) * p / steps)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
    }

    private fun makeDivider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        setBackgroundColor(Color.argb(80, 200, 200, 200))
    }

    private fun makeTextButton(label: String, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label; setTextColor(Color.parseColor("#4FC3F7"))
                textSize = 12f; setPadding(dp(6), dp(6), dp(2), dp(6))
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }

    private fun dp(v: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                    activity.resources.displayMetrics).toInt()
}
