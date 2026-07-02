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
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.util.AppPreferences
import com.gaurav.avnc.util.addOnGlobalLayoutListener
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
 *  - User opens the "Virtual Control" toggle in [Toolbar] → [toolbar_virtual_controller.xml]
 *    flyout appears (rendered by [VirtualControllerFlyout]).
 *  - Flyout has live checkboxes; checking/unchecking a key immediately adds/removes the
 *    floating button via [addKey]/[removeKey].
 *  - Edit-Position switch ([setEditMode]) lets the user drag buttons to reposition them.
 *    Positions are persisted as fractions of the overlay size.
 *
 * Visual style: white stroke outline, transparent fill, white label — legible over any
 * remote desktop content without relying on the app theme.
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
     * Public API used by VirtualControllerFlyout
     **************************************************************************/

    /** Whether a key with the given keyCode is currently active (floating on screen). */
    fun hasKey(keyCode: Int): Boolean = keys.any { it.keyCode == keyCode }

    /** Add a key from the catalog immediately (no dialog needed). */
    fun addKey(entry: VCCatalog.Entry) {
        if (hasKey(entry.keyCode)) return
        val idx = keys.size
        val (x, y) = defaultPosition(idx)
        val key = VCKey(entry.keyCode, entry.label, entry.isToggle, x, y)
        keys += key
        addKeyView(key)
        saveKeys()
        updateContainerVisibility()
    }

    /** Remove a floating key immediately. */
    fun removeKey(keyCode: Int) {
        val key = keys.firstOrNull { it.keyCode == keyCode } ?: return
        activeToggles.remove(keyCode)
        lockedToggles.remove(keyCode)
        keyViews.remove(keyCode)?.let { container.removeView(it) }
        keys.remove(key)
        saveKeys()
        updateContainerVisibility()
    }

    /** Reset all positions to the auto-scatter default (called from flyout "Atur Ulang Posisi"). */
    fun resetPositions() {
        keys.forEachIndexed { idx, key ->
            val (x, y) = defaultPosition(idx)
            key.x = x
            key.y = y
            keyViews[key.keyCode]?.let { placeAt(it, x, y) }
        }
        saveKeys()
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
     * Touch handler per floating key:
     *  - Edit mode: drag to reposition.
     *  - Toggle/modifier: tap = on/off; long-press = lock on.
     *  - Normal key: tap sends key-down+key-up; hold repeats.
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
                            editMode -> Unit
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

    internal fun exitEditMode() {
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
            layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
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
     * Helpers
     **************************************************************************/

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
 * A single floating key: keyCode, display label, toggle flag, and on-screen position
 * (stored as fraction 0..1 of overlay size so it scales across orientations/screen sizes).
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

/** Static catalog of all keys available in the Virtual Controller flyout. */
object VCCatalog {

    data class Entry(val keyCode: Int, val label: String, val isToggle: Boolean = false)
    data class Section(val title: String, val entries: List<Entry>, val columns: Int)

    private val modifiers = listOf(
            Entry(android.view.KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", isToggle = true),
            Entry(android.view.KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", isToggle = true),
            Entry(android.view.KeyEvent.KEYCODE_ALT_LEFT, "Alt", isToggle = true),
            Entry(android.view.KeyEvent.KEYCODE_META_LEFT, "Super", isToggle = true),
            Entry(android.view.KeyEvent.KEYCODE_ESCAPE, "Esc"),
            Entry(android.view.KeyEvent.KEYCODE_TAB, "Tab"),
            Entry(android.view.KeyEvent.KEYCODE_ENTER, "Enter"),
            Entry(android.view.KeyEvent.KEYCODE_SPACE, "Space"),
            Entry(android.view.KeyEvent.KEYCODE_DEL, "Bksp"),
            Entry(android.view.KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
            Entry(android.view.KeyEvent.KEYCODE_INSERT, "Ins"),
            Entry(android.view.KeyEvent.KEYCODE_MOVE_HOME, "Home"),
            Entry(android.view.KeyEvent.KEYCODE_MOVE_END, "End"),
            Entry(android.view.KeyEvent.KEYCODE_PAGE_UP, "PgUp"),
            Entry(android.view.KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"),
            Entry(android.view.KeyEvent.KEYCODE_DPAD_LEFT, "\u2190"),
            Entry(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, "\u2192"),
            Entry(android.view.KeyEvent.KEYCODE_DPAD_UP, "\u2191"),
            Entry(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "\u2193"),
    )

    private val letters = ('A'..'Z').map { c ->
        Entry(android.view.KeyEvent.KEYCODE_A + (c - 'A'), c.toString())
    }

    private val numbers = ('0'..'9').map { c ->
        Entry(android.view.KeyEvent.KEYCODE_0 + (c - '0'), c.toString())
    }

    private val symbols = listOf(
            Entry(android.view.KeyEvent.KEYCODE_GRAVE, "`"),
            Entry(android.view.KeyEvent.KEYCODE_MINUS, "-"),
            Entry(android.view.KeyEvent.KEYCODE_EQUALS, "="),
            Entry(android.view.KeyEvent.KEYCODE_LEFT_BRACKET, "["),
            Entry(android.view.KeyEvent.KEYCODE_RIGHT_BRACKET, "]"),
            Entry(android.view.KeyEvent.KEYCODE_BACKSLASH, "\\"),
            Entry(android.view.KeyEvent.KEYCODE_SEMICOLON, ";"),
            Entry(android.view.KeyEvent.KEYCODE_APOSTROPHE, "'"),
            Entry(android.view.KeyEvent.KEYCODE_COMMA, ","),
            Entry(android.view.KeyEvent.KEYCODE_PERIOD, "."),
            Entry(android.view.KeyEvent.KEYCODE_SLASH, "/"),
            Entry(android.view.KeyEvent.KEYCODE_AT, "@"),
            Entry(android.view.KeyEvent.KEYCODE_POUND, "#"),
            Entry(android.view.KeyEvent.KEYCODE_STAR, "*"),
            Entry(android.view.KeyEvent.KEYCODE_PLUS, "+"),
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
