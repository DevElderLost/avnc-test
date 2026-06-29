/*
 * Copyright (c) 2026  DevElderLost.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.isVisible
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.util.AppPreferences
import com.gaurav.avnc.util.addOnGlobalLayoutListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * VirtualController renders a set of floating, freely-positionable key buttons on top of
 * [VncActivity]'s frame view - conceptually similar to Winlator's floating input-controls
 * overlay.
 *
 * Workflow:
 *  - User taps the "Virtual Control" button in [Toolbar] -> [showKeyPicker] opens a checkbox
 *    list (letters, digits, punctuation, modifiers, navigation keys, ...).
 *  - Checked keys show up as small square floating buttons on the remote screen.
 *  - While "Mode Edit Posisi" is active ([editMode]), touching a button drags it instead of
 *    sending a key event; positions are persisted as fractions of the overlay size.
 *
 * Visual style is intentionally fixed (white outline, transparent fill, white label) instead
 * of following the app theme, so buttons stay legible on top of arbitrary remote content.
 */
class VirtualController(private val activity: VncActivity, private val inputHandler: InputHandler) {

    private val viewModel = activity.viewModel
    private val pref: AppPreferences get() = viewModel.pref
    private val container: FrameLayout get() = activity.binding.virtualControllerContainer

    private val json = Json { ignoreUnknownKeys = true }

    private val keys = mutableListOf<VCKey>()
    private val keyViews = mutableMapOf<Int, TextView>()
    private val activeToggles = mutableSetOf<Int>()
    private val lockedToggles = mutableSetOf<Int>()

    private var connected = false
    var editMode = false
        private set

    private var doneChip: TextView? = null

    private val touchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop.toFloat() }

    /**************************************************************************
     * Lifecycle
     **************************************************************************/

    fun initialize() {
        loadKeys()
        rebuildViews()
        inputHandler.onAfterKeyEventListeners += ::onAfterKeyEvent

        // Re-place all buttons whenever the overlay is resized (rotation, multi-window, etc.)
        addOnGlobalLayoutListener(activity, container) {
            keys.forEach { k -> keyViews[k.keyCode]?.let { placeAt(it, k.x, k.y) } }
        }
    }

    fun onConnectionStateChanged(isConnected: Boolean) {
        connected = isConnected
        if (!isConnected && editMode) exitEditMode()
        updateContainerVisibility()
    }

    /**************************************************************************
     * Persistence
     **************************************************************************/

    private fun loadKeys() {
        keys.clear()
        runCatching {
            pref.input.vcKeysLayout?.let { saved ->
                keys += json.decodeFromString(vcKeySerializer, saved)
            }
        }
    }

    private fun saveKeys() {
        pref.input.vcKeysLayout = if (keys.isEmpty()) null else json.encodeToString(vcKeySerializer, keys)
    }

    /**************************************************************************
     * View management
     **************************************************************************/

    private fun rebuildViews() {
        container.removeAllViews()
        keyViews.clear()
        activeToggles.clear()
        lockedToggles.clear()
        doneChip = null

        keys.forEach { addKeyView(it) }
        if (editMode) showDoneChip()
        updateContainerVisibility()
    }

    private fun updateContainerVisibility() {
        container.isVisible = connected && (keys.isNotEmpty() || editMode)
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, activity.resources.displayMetrics).roundToInt()
    }

    /** Square, theme-independent look: white stroke, transparent fill, optional highlight. */
    private fun createSquareDrawable(filled: Boolean, accent: Boolean): GradientDrawable {
        val strokeColor = if (accent) Color.parseColor("#FFD600") else Color.WHITE
        val strokeWidthPx = dpToPx(if (accent) 2f else 1.5f)
        val fillColor = if (filled) Color.argb(140, 255, 255, 255) else Color.TRANSPARENT
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setStroke(strokeWidthPx, strokeColor)
            setColor(fillColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKeyView(key: VCKey): TextView {
        val sizePx = dpToPx(KEY_SIZE_DP)
        val view = TextView(activity).apply {
            text = key.label
            setTextColor(Color.WHITE)
            textSize = if (key.label.length > 2) 10f else 15f
            gravity = Gravity.CENTER
            maxLines = 1
            isFocusable = false
            isFocusableInTouchMode = false
            background = createSquareDrawable(filled = false, accent = editMode)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        keyViews[key.keyCode] = view
        container.addView(view)
        placeAt(view, key.x, key.y)
        attachTouchHandling(view, key)
        return view
    }

    private fun placeAt(view: View, xFraction: Float, yFraction: Float) {
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) return
        val sizePx = if (view.width > 0) view.width else dpToPx(KEY_SIZE_DP)
        val maxX = max(0, cw - sizePx)
        val maxY = max(0, ch - sizePx)
        view.x = (xFraction * maxX).coerceIn(0f, maxX.toFloat())
        view.y = (yFraction * maxY).coerceIn(0f, maxY.toFloat())
    }

    private fun commitPosition(v: View, key: VCKey) {
        val maxX = max(1, container.width - v.width)
        val maxY = max(1, container.height - v.height)
        key.x = (v.x / maxX).coerceIn(0f, 1f)
        key.y = (v.y / maxY).coerceIn(0f, 1f)
        saveKeys()
    }

    /**
     * Single touch handler per floating key, covering three mutually exclusive behaviors:
     *  - Edit mode: drag to reposition (tap with no movement does nothing).
     *  - Normal key, not edit mode: tap sends a key-down/key-up pair; holding repeats it
     *    (mirrors [VirtualKeys]' own repeat-while-held behavior).
     *  - Toggle/modifier key, not edit mode: tap flips it on/off; long-press locks it on
     *    until tapped again (mirrors [VirtualKeys]' locked meta-key behavior).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandling(view: TextView, key: VCKey) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0f
            private var startY = 0f
            private var dragging = false
            private var doRepeat = false
            private var longPressFired = false

            private val longPressRunnable = Runnable {
                if (!editMode && key.isToggle) {
                    if (!activeToggles.contains(key.keyCode)) toggleModifier(view, key)
                    lockedToggles.add(key.keyCode)
                    refreshAppearance(view, key)
                    longPressFired = true
                }
            }

            private fun repeat() {
                if (!doRepeat) return
                sendKey(key.keyCode, true)
                sendKey(key.keyCode, false)
                view.postDelayed({ repeat() }, ViewConfiguration.getKeyRepeatDelay().toLong())
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        startX = v.x; startY = v.y
                        dragging = false
                        longPressFired = false

                        when {
                            editMode -> Unit // wait for ACTION_MOVE to decide if this is a drag
                            key.isToggle -> v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                            else -> {
                                doRepeat = true
                                sendKey(key.keyCode, true)
                                sendKey(key.keyCode, false)
                                v.postDelayed({ repeat() }, ViewConfiguration.getKeyRepeatTimeout().toLong())
                            }
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (editMode) {
                            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop))
                                dragging = true
                            if (dragging) {
                                val maxX = max(0, container.width - v.width)
                                val maxY = max(0, container.height - v.height)
                                v.x = (startX + dx).coerceIn(0f, maxX.toFloat())
                                v.y = (startY + dy).coerceIn(0f, maxY.toFloat())
                            }
                        } else if (key.isToggle && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            v.removeCallbacks(longPressRunnable)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        doRepeat = false
                        if (editMode) {
                            if (dragging) commitPosition(v, key)
                        } else if (key.isToggle && !longPressFired) {
                            toggleModifier(view, key)
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        doRepeat = false
                    }
                }
                return true
            }
        })
    }

    private fun toggleModifier(view: TextView, key: VCKey) {
        val turningOn = !activeToggles.contains(key.keyCode)
        if (turningOn) {
            activeToggles.add(key.keyCode)
        } else {
            activeToggles.remove(key.keyCode)
            lockedToggles.remove(key.keyCode)
        }
        sendKey(key.keyCode, turningOn)
        refreshAppearance(view, key)
    }

    private fun refreshAppearance(view: TextView, key: VCKey) {
        view.background = createSquareDrawable(filled = activeToggles.contains(key.keyCode), accent = editMode)
    }

    /** Mirrors [VirtualKeys]: any non-modifier key-up (from any source) releases unlocked modifiers. */
    private fun releaseUnlockedToggles() {
        val toRelease = activeToggles - lockedToggles
        if (toRelease.isEmpty()) return
        toRelease.forEach { code ->
            activeToggles.remove(code)
            sendKey(code, false)
            keyViews[code]?.let { view -> keys.firstOrNull { it.keyCode == code }?.let { refreshAppearance(view, it) } }
        }
    }

    private fun onAfterKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode))
            releaseUnlockedToggles()
    }

    private fun sendKey(keyCode: Int, isDown: Boolean) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        inputHandler.onVkKeyEvent(KeyEvent(action, keyCode))
    }

    /**************************************************************************
     * Edit mode
     **************************************************************************/

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled

        if (activeToggles.isNotEmpty()) {
            activeToggles.toList().forEach { sendKey(it, false) }
            activeToggles.clear()
            lockedToggles.clear()
        }
        keyViews.forEach { (code, view) ->
            keys.firstOrNull { it.keyCode == code }?.let { refreshAppearance(view, it) }
        }

        if (enabled) showDoneChip() else hideDoneChip()
        updateContainerVisibility()
    }

    private fun exitEditMode() {
        setEditMode(false)
        saveKeys()
    }

    private fun showDoneChip() {
        if (doneChip != null) return
        doneChip = TextView(activity).apply {
            text = activity.getString(R.string.virtual_controller_done)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.argb(230, 255, 214, 0))
            setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
            textSize = 13f
            isClickable = true
            setOnClickListener { exitEditMode() }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(8f)
            }
        }
        container.addView(doneChip)
    }

    private fun hideDoneChip() {
        doneChip?.let { container.removeView(it) }
        doneChip = null
    }

    /**************************************************************************
     * Key picker dialog
     **************************************************************************/

    fun showKeyPicker() {
        val context = activity
        val checkBoxes = mutableMapOf<Int, CheckBox>()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16f)
            setPadding(pad, pad, pad, pad)
        }

        val editSwitch = Switch(context).apply {
            text = context.getString(R.string.virtual_controller_edit_mode)
            isChecked = editMode
        }
        root.addView(editSwitch)

        val hint = TextView(context).apply {
            text = context.getString(R.string.virtual_controller_edit_mode_hint)
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dpToPx(2f), 0, dpToPx(12f))
        }
        root.addView(hint)

        VCCatalog.sections.forEach { section ->
            val header = TextView(context).apply {
                text = section.title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dpToPx(8f), 0, dpToPx(4f))
            }
            root.addView(header)

            val grid = GridLayout(context).apply { columnCount = section.columns }
            section.entries.forEach { entry ->
                val cb = CheckBox(context).apply {
                    text = entry.label
                    isChecked = keys.any { it.keyCode == entry.keyCode }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = GridLayout.LayoutParams.WRAP_CONTENT
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        val m = dpToPx(2f)
                        setMargins(m, m, m, m)
                    }
                }
                checkBoxes[entry.keyCode] = cb
                grid.addView(cb)
            }
            root.addView(grid)
        }

        val scroll = ScrollView(context).apply { addView(root) }

        MaterialAlertDialogBuilder(context)
                .setTitle(R.string.virtual_controller_title)
                .setView(scroll)
                .setPositiveButton(R.string.virtual_controller_apply) { _, _ ->
                    applyPickerSelection(checkBoxes)
                    setEditMode(editSwitch.isChecked)
                }
                .setNeutralButton(R.string.virtual_controller_reset_positions) { _, _ ->
                    applyPickerSelection(checkBoxes, resetPositions = true)
                    setEditMode(editSwitch.isChecked)
                }
                .setNegativeButton(R.string.virtual_controller_cancel, null)
                .show()
    }

    private fun applyPickerSelection(checkBoxes: Map<Int, CheckBox>, resetPositions: Boolean = false) {
        val existingByCode = keys.associateBy { it.keyCode }
        val newKeys = mutableListOf<VCKey>()
        var autoIndex = 0

        checkBoxes.forEach { (keyCode, cb) ->
            if (cb.isChecked) {
                val entry = VCCatalog.findEntry(keyCode) ?: return@forEach
                val existing = existingByCode[keyCode]
                val (x, y) = if (existing != null && !resetPositions) existing.x to existing.y else defaultPosition(autoIndex++)
                newKeys += VCKey(keyCode, entry.label, entry.isToggle, x, y)
            }
        }

        keys.clear()
        keys += newKeys
        saveKeys()
        rebuildViews()
    }

    /** Scatters newly-added keys in a simple grid so they don't all land on top of each other. */
    private fun defaultPosition(index: Int): Pair<Float, Float> {
        val col = index % 4
        val row = (index / 4) % 5
        val x = (0.08f + col * 0.22f).coerceIn(0f, 0.9f)
        val y = (0.18f + row * 0.14f).coerceIn(0f, 0.9f)
        return x to y
    }

    companion object {
        private const val KEY_SIZE_DP = 42f
    }
}

/**
 * A single floating key the user picked, with its on-screen position.
 *
 * Position is stored as a fraction (0f..1f) of the overlay's available space, so it scales
 * reasonably across rotation/screen-size changes. [label] is duplicated from [VCCatalog] at
 * selection time so previously-saved keys keep rendering correctly even if the catalog entry
 * is later renamed.
 */
@Serializable
data class VCKey(
        val keyCode: Int,
        val label: String,
        val isToggle: Boolean = false,
        var x: Float = 0.1f,
        var y: Float = 0.3f,
)

private val vcKeySerializer = ListSerializer(VCKey.serializer())

/** Static catalog of all keys selectable in the Virtual Controller picker dialog. */
object VCCatalog {

    data class Entry(val keyCode: Int, val label: String, val isToggle: Boolean = false)
    data class Section(val title: String, val entries: List<Entry>, val columns: Int)

    private val modifiers = listOf(
            Entry(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", isToggle = true),
            Entry(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", isToggle = true),
            Entry(KeyEvent.KEYCODE_ALT_LEFT, "Alt", isToggle = true),
            Entry(KeyEvent.KEYCODE_META_LEFT, "Super", isToggle = true),
            Entry(KeyEvent.KEYCODE_ESCAPE, "Esc"),
            Entry(KeyEvent.KEYCODE_TAB, "Tab"),
            Entry(KeyEvent.KEYCODE_ENTER, "Enter"),
            Entry(KeyEvent.KEYCODE_SPACE, "Space"),
            Entry(KeyEvent.KEYCODE_DEL, "Bksp"),
            Entry(KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
            Entry(KeyEvent.KEYCODE_INSERT, "Ins"),
            Entry(KeyEvent.KEYCODE_MOVE_HOME, "Home"),
            Entry(KeyEvent.KEYCODE_MOVE_END, "End"),
            Entry(KeyEvent.KEYCODE_PAGE_UP, "PgUp"),
            Entry(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"),
            Entry(KeyEvent.KEYCODE_DPAD_LEFT, "\u2190"),
            Entry(KeyEvent.KEYCODE_DPAD_RIGHT, "\u2192"),
            Entry(KeyEvent.KEYCODE_DPAD_UP, "\u2191"),
            Entry(KeyEvent.KEYCODE_DPAD_DOWN, "\u2193"),
    )

    private val letters = ('A'..'Z').map { c -> Entry(KeyEvent.KEYCODE_A + (c - 'A'), c.toString()) }

    private val numbers = ('0'..'9').map { c -> Entry(KeyEvent.KEYCODE_0 + (c - '0'), c.toString()) }

    private val symbols = listOf(
            Entry(KeyEvent.KEYCODE_GRAVE, "`"),
            Entry(KeyEvent.KEYCODE_MINUS, "-"),
            Entry(KeyEvent.KEYCODE_EQUALS, "="),
            Entry(KeyEvent.KEYCODE_LEFT_BRACKET, "["),
            Entry(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"),
            Entry(KeyEvent.KEYCODE_BACKSLASH, "\\"),
            Entry(KeyEvent.KEYCODE_SEMICOLON, ";"),
            Entry(KeyEvent.KEYCODE_APOSTROPHE, "'"),
            Entry(KeyEvent.KEYCODE_COMMA, ","),
            Entry(KeyEvent.KEYCODE_PERIOD, "."),
            Entry(KeyEvent.KEYCODE_SLASH, "/"),
            Entry(KeyEvent.KEYCODE_AT, "@"),
            Entry(KeyEvent.KEYCODE_POUND, "#"),
            Entry(KeyEvent.KEYCODE_STAR, "*"),
            Entry(KeyEvent.KEYCODE_PLUS, "+"),
    )

    val sections = listOf(
            Section("Ctrl, Shift & lainnya", modifiers, columns = 3),
            Section("Huruf (A-Z)", letters, columns = 6),
            Section("Angka (0-9)", numbers, columns = 5),
            Section("Tanda baca", symbols, columns = 5),
    )

    private val allEntries by lazy { sections.flatMap { it.entries } }

    fun findEntry(keyCode: Int) = allEntries.firstOrNull { it.keyCode == keyCode }
}
