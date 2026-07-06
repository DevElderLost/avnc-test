/*
 * Copyright (c) 2026  DevElderLost.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */

package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.os.SystemClock
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

    /**
     * Invoked when a button is long-pressed while [editMode] is active.
     * Set by Toolbar so the property flyout (size/alpha) can be shown.
     */
    var onKeyLongPressInEditMode: ((VCKey, TextView) -> Unit)? = null

    private val touchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop.toFloat() }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun initialize() {
        loadKeys()
        rebuildViews()
        inputHandler.onAfterKeyEventListeners += ::onAfterKeyEvent
        addOnGlobalLayoutListener(activity, container) {
            keys.forEach { k -> keyViews[k.keyCode]?.let { placeAt(it, k.x, k.y) } }
        }
    }

    fun onConnectionStateChanged(isConnected: Boolean) {
        connected = isConnected
        if (!isConnected && editMode) exitEditMode()
        updateContainerVisibility()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun hasKey(keyCode: Int): Boolean = keys.any { it.keyCode == keyCode }
    fun getKey(keyCode: Int): VCKey? = keys.firstOrNull { it.keyCode == keyCode }

    fun addKey(entry: VCCatalog.Entry) {
        if (hasKey(entry.keyCode)) return
        val (x, y) = defaultPosition(keys.size)
        val key = VCKey(entry.keyCode, entry.label, entry.isToggle,
            x, y, needsShift = entry.needsShift, needsNumLock = entry.needsNumLock)
        keys += key
        addKeyView(key)
        saveKeys()
        updateContainerVisibility()
    }

    fun removeKey(keyCode: Int) {
        val key = keys.firstOrNull { it.keyCode == keyCode } ?: return
        activeToggles.remove(keyCode); lockedToggles.remove(keyCode)
        keyViews.remove(keyCode)?.let { container.removeView(it) }
        keys.remove(key)
        saveKeys()
        updateContainerVisibility()
    }

    fun resetPositions() {
        keys.forEachIndexed { idx, key ->
            val (x, y) = defaultPosition(idx)
            key.x = x; key.y = y
            keyViews[key.keyCode]?.let { placeAt(it, x, y) }
        }
        saveKeys()
    }

    /** Apply width/height/alpha from the property flyout. */
    fun applyKeyProps(keyCode: Int, widthDp: Float, heightDp: Float, alpha: Float) {
        val key = keys.firstOrNull { it.keyCode == keyCode } ?: return
        key.widthDp  = widthDp.coerceIn(VCKey.MIN_DP, VCKey.MAX_DP)
        key.heightDp = heightDp.coerceIn(VCKey.MIN_DP, VCKey.MAX_DP)
        key.alpha    = alpha.coerceIn(0.1f, 1.0f)
        rebuildKeyView(key)
        saveKeys()
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private fun loadKeys() {
        keys.clear()
        runCatching {
            pref.input.vcKeysLayout?.let { keys += json.decodeFromString(vcKeySerializer, it) }
        }
    }

    private fun saveKeys() {
        pref.input.vcKeysLayout =
            if (keys.isEmpty()) null else json.encodeToString(vcKeySerializer, keys)
    }

    // -------------------------------------------------------------------------
    // View management
    // -------------------------------------------------------------------------

    private fun rebuildViews() {
        container.removeAllViews()
        keyViews.clear(); activeToggles.clear(); lockedToggles.clear(); doneChip = null
        keys.forEach { addKeyView(it) }
        if (editMode) showDoneChip()
        updateContainerVisibility()
    }

    private fun rebuildKeyView(key: VCKey) {
        val oldView = keyViews.remove(key.keyCode)
        val savedX = oldView?.x ?: -1f
        val savedY = oldView?.y ?: -1f
        oldView?.let { container.removeView(it) }
        val newView = addKeyView(key)
        if (savedX >= 0f) { newView.x = savedX; newView.y = savedY }
        else placeAt(newView, key.x, key.y)
    }

    private fun updateContainerVisibility() {
        container.isVisible = connected && (keys.isNotEmpty() || editMode)
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, activity.resources.displayMetrics)
            .roundToInt()

    private fun createDrawable(filled: Boolean, accent: Boolean, alpha: Float): GradientDrawable {
        val base = if (accent) Color.parseColor("#FFD600") else Color.WHITE
        val a = (alpha * 255).toInt().coerceIn(26, 255)
        val stroke = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
        val fill = if (filled) Color.argb((140 * alpha).toInt(), 255, 255, 255) else Color.TRANSPARENT
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 0f
            setStroke(dpToPx(if (accent) 2f else 1.5f), stroke)
            setColor(fill)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKeyView(key: VCKey): TextView {
        val view = TextView(activity).apply {
            text = key.label
            setTextColor(Color.WHITE)
            this.alpha = key.alpha
            textSize = when { key.label.length > 3 -> 9f; key.label.length > 2 -> 10f; else -> 15f }
            gravity = Gravity.CENTER; maxLines = 1
            isFocusable = false; isFocusableInTouchMode = false
            background = createDrawable(filled = false, accent = editMode, alpha = key.alpha)
            layoutParams = FrameLayout.LayoutParams(dpToPx(key.widthDp), dpToPx(key.heightDp))
        }
        keyViews[key.keyCode] = view
        container.addView(view)
        placeAt(view, key.x, key.y)
        attachTouchHandling(view, key)
        return view
    }

    private fun placeAt(view: View, xf: Float, yf: Float) {
        val cw = container.width; val ch = container.height
        if (cw <= 0 || ch <= 0) return
        val w = if (view.width > 0) view.width else dpToPx(VCKey.DEFAULT_DP)
        val h = if (view.height > 0) view.height else dpToPx(VCKey.DEFAULT_DP)
        view.x = (xf * max(0, cw - w)).coerceIn(0f, max(0, cw - w).toFloat())
        view.y = (yf * max(0, ch - h)).coerceIn(0f, max(0, ch - h).toFloat())
    }

    private fun commitPosition(v: View, key: VCKey) {
        val maxX = max(1, container.width - v.width)
        val maxY = max(1, container.height - v.height)
        key.x = (v.x / maxX).coerceIn(0f, 1f)
        key.y = (v.y / maxY).coerceIn(0f, 1f)
        saveKeys()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandling(view: TextView, key: VCKey) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f; private var downRawY = 0f
            private var startX = 0f; private var startY = 0f
            private var dragging = false; private var doRepeat = false; private var lfFired = false

            private val editLpRunnable = Runnable {
                if (editMode && !dragging) {
                    lfFired = true
                    onKeyLongPressInEditMode?.invoke(key, view)
                }
            }
            private val toggleLpRunnable = Runnable {
                if (!editMode && key.isToggle) {
                    if (!activeToggles.contains(key.keyCode)) toggleModifier(view, key)
                    lockedToggles.add(key.keyCode); refreshAppearance(view, key); lfFired = true
                }
            }

            private fun repeat() {
                if (!doRepeat) return
                sendVcKey(key); view.postDelayed({ repeat() }, ViewConfiguration.getKeyRepeatDelay().toLong())
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        startX = v.x; startY = v.y; dragging = false; lfFired = false
                        when {
                            editMode -> v.postDelayed(editLpRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                            key.isToggle -> v.postDelayed(toggleLpRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                            else -> { doRepeat = true; sendVcKey(key)
                                v.postDelayed({ repeat() }, ViewConfiguration.getKeyRepeatTimeout().toLong()) }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                        if (editMode) {
                            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                dragging = true; v.removeCallbacks(editLpRunnable)
                            }
                            if (dragging) {
                                val mx = max(0, container.width - v.width)
                                val my = max(0, container.height - v.height)
                                v.x = (startX + dx).coerceIn(0f, mx.toFloat())
                                v.y = (startY + dy).coerceIn(0f, my.toFloat())
                            }
                        } else if (key.isToggle && (abs(dx) > touchSlop || abs(dy) > touchSlop))
                            v.removeCallbacks(toggleLpRunnable)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(editLpRunnable); v.removeCallbacks(toggleLpRunnable); doRepeat = false
                        if (editMode) { if (dragging) commitPosition(v, key) }
                        else if (key.isToggle && !lfFired) toggleModifier(view, key)
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(editLpRunnable); v.removeCallbacks(toggleLpRunnable); doRepeat = false
                    }
                }
                return true
            }
        })
    }

    private fun sendVcKey(key: VCKey) {
        when {
            key.needsShift -> {
                // Shift+symbol combos: look up real keyCode from shiftKeyMap.
                val realCode = if (key.keyCode < 0) VCCatalog.shiftKeyMap[key.keyCode] ?: return
                               else key.keyCode
                sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, true)
                sendKeyWithMeta(realCode, true, computeActiveMeta(excludeModifierCode = KeyEvent.KEYCODE_SHIFT_LEFT))
                sendKeyWithMeta(realCode, false, computeActiveMeta(excludeModifierCode = KeyEvent.KEYCODE_SHIFT_LEFT))
                sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, false)
            }
            key.needsNumLock -> {
                // Numpad keys: include META_NUM_LOCK_ON so KeyHandler doesn't ignore them.
                sendKeyWithMeta(key.keyCode, true,  computeActiveMeta() or KeyEvent.META_NUM_LOCK_ON)
                sendKeyWithMeta(key.keyCode, false, computeActiveMeta() or KeyEvent.META_NUM_LOCK_ON)
            }
            else -> {
                // Normal key: include active modifier metaState so Ctrl/Alt combos are explicit.
                sendKeyWithMeta(key.keyCode, true,  computeActiveMeta(excludeModifierCode = key.keyCode))
                sendKeyWithMeta(key.keyCode, false, computeActiveMeta(excludeModifierCode = key.keyCode))
            }
        }
    }

    private fun toggleModifier(view: TextView, key: VCKey) {
        val on = !activeToggles.contains(key.keyCode)
        if (on) activeToggles.add(key.keyCode)
        else { activeToggles.remove(key.keyCode); lockedToggles.remove(key.keyCode) }
        sendKey(key.keyCode, on); refreshAppearance(view, key)
    }

    private fun refreshAppearance(view: TextView, key: VCKey) {
        view.background = createDrawable(filled = activeToggles.contains(key.keyCode),
            accent = editMode, alpha = key.alpha)
    }

    private fun releaseUnlockedToggles() {
        (activeToggles - lockedToggles).toList().forEach { code ->
            activeToggles.remove(code); sendKey(code, false)
            keyViews[code]?.let { v -> keys.firstOrNull { it.keyCode == code }
                ?.let { refreshAppearance(v, it) } }
        }
    }

    private fun onAfterKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode))
            releaseUnlockedToggles()
    }

    /**
     * Send a key event. Uses ACTION_DOWN/UP with no extra metaState.
     * Used for modifier keys themselves (Ctrl/Shift/Alt/Meta DOWN/UP).
     */
    private fun sendKey(keyCode: Int, isDown: Boolean) {
        inputHandler.onVkKeyEvent(
            KeyEvent(if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * Send a key event with explicit metaState so KeyHandler knows which modifiers
     * are active. This is critical for Ctrl+Fkey, Ctrl+letter, etc. to work reliably:
     * the KeyEvent carries the modifier info explicitly rather than relying solely on
     * KeyHandler's internal vkMetaState tracking.
     */
    private fun sendKeyWithMeta(keyCode: Int, isDown: Boolean, meta: Int) {
        val now = SystemClock.uptimeMillis()
        inputHandler.onVkKeyEvent(
            KeyEvent(now, now, if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                     keyCode, 0, meta))
    }

    /**
     * Compute a metaState int from currently active toggle modifiers.
     * @param excludeModifierCode keyCode to exclude (the key being sent itself).
     */
    private fun computeActiveMeta(excludeModifierCode: Int = -1): Int {
        var meta = 0
        activeToggles.forEach { code ->
            if (code == excludeModifierCode) return@forEach
            meta = meta or when (code) {
                KeyEvent.KEYCODE_CTRL_LEFT,  KeyEvent.KEYCODE_CTRL_RIGHT  ->
                    KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                    KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_ALT_LEFT,   KeyEvent.KEYCODE_ALT_RIGHT   ->
                    KeyEvent.META_ALT_ON   or KeyEvent.META_ALT_LEFT_ON
                KeyEvent.KEYCODE_META_LEFT,  KeyEvent.KEYCODE_META_RIGHT  ->
                    KeyEvent.META_META_ON  or KeyEvent.META_META_LEFT_ON
                else -> 0
            }
        }
        return meta
    }

    // -------------------------------------------------------------------------
    // Edit mode
    // -------------------------------------------------------------------------

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (activeToggles.isNotEmpty()) {
            activeToggles.toList().forEach { sendKey(it, false) }
            activeToggles.clear(); lockedToggles.clear()
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
        // Guard: ensure the container is not blocking touches after edit mode ends.
        // The props flyout may have left isClickable=true on the container.
        container.isClickable = false
        container.isFocusable = false
    }

    private fun showDoneChip() {
        if (doneChip != null) return
        doneChip = TextView(activity).apply {
            text = activity.getString(R.string.virtual_controller_done)
            setTextColor(Color.BLACK); setBackgroundColor(Color.argb(230, 255, 214, 0))
            setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
            textSize = 13f; isClickable = true; setOnClickListener { exitEditMode() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dpToPx(8f) }
        }
        container.addView(doneChip)
    }

    private fun hideDoneChip() { doneChip?.let { container.removeView(it) }; doneChip = null }

    private fun defaultPosition(index: Int): Pair<Float, Float> {
        val col = index % 4; val row = (index / 4) % 5
        return (0.08f + col * 0.22f).coerceIn(0f, 0.9f) to (0.18f + row * 0.14f).coerceIn(0f, 0.9f)
    }
}

@Serializable
data class VCKey(
    val keyCode: Int,
    val label: String,
    val isToggle: Boolean = false,
    var x: Float = 0.1f,
    var y: Float = 0.3f,
    var widthDp: Float = DEFAULT_DP,
    var heightDp: Float = DEFAULT_DP,
    var alpha: Float = 1.0f,
    val needsShift: Boolean = false,     // true for Shift+key combos (!@#$%^&*...)
    val needsNumLock: Boolean = false,   // true for numpad keys (requires META_NUM_LOCK_ON)
) {
    companion object {
        const val DEFAULT_DP = 42f
        const val MIN_DP    = 28f
        const val MAX_DP    = 120f
    }
}

private val vcKeySerializer = ListSerializer(VCKey.serializer())

object VCCatalog {

    data class Entry(
        val keyCode: Int,
        val label: String,
        val isToggle: Boolean = false,
        val needsShift: Boolean = false,
        val needsNumLock: Boolean = false,
    )

    data class Section(val title: String, val entries: List<Entry>, val columns: Int)

    private val modifiers = listOf(
        Entry(KeyEvent.KEYCODE_CTRL_LEFT,   "Ctrl",  isToggle = true),
        Entry(KeyEvent.KEYCODE_SHIFT_LEFT,  "Shift", isToggle = true),
        Entry(KeyEvent.KEYCODE_ALT_LEFT,    "Alt",   isToggle = true),
        Entry(KeyEvent.KEYCODE_META_LEFT,   "Super", isToggle = true),
        Entry(KeyEvent.KEYCODE_ESCAPE,      "Esc"),
        Entry(KeyEvent.KEYCODE_TAB,         "Tab"),
        Entry(KeyEvent.KEYCODE_ENTER,       "Enter"),
        Entry(KeyEvent.KEYCODE_SPACE,       "Space"),
        Entry(KeyEvent.KEYCODE_DEL,         "Bksp"),
        Entry(KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
        Entry(KeyEvent.KEYCODE_INSERT,      "Ins"),
        Entry(KeyEvent.KEYCODE_MOVE_HOME,   "Home"),
        Entry(KeyEvent.KEYCODE_MOVE_END,    "End"),
        Entry(KeyEvent.KEYCODE_PAGE_UP,     "PgUp"),
        Entry(KeyEvent.KEYCODE_PAGE_DOWN,   "PgDn"),
        Entry(KeyEvent.KEYCODE_DPAD_LEFT,   "\u2190"),
        Entry(KeyEvent.KEYCODE_DPAD_RIGHT,  "\u2192"),
        Entry(KeyEvent.KEYCODE_DPAD_UP,     "\u2191"),
        Entry(KeyEvent.KEYCODE_DPAD_DOWN,   "\u2193"),
    )

    // F1–F12 (KEYCODE_F1 = 131, F2 = 132, ... F12 = 142)
    private val functionKeys = (1..12).map { n ->
        Entry(KeyEvent.KEYCODE_F1 + (n - 1), "F$n")
    }

    private val letters = ('A'..'Z').map { c ->
        Entry(KeyEvent.KEYCODE_A + (c - 'A'), c.toString())
    }

    private val numbers = ('0'..'9').map { c ->
        Entry(KeyEvent.KEYCODE_0 + (c - '0'), c.toString())
    }

    private val symbols = listOf(
        Entry(KeyEvent.KEYCODE_GRAVE,          "`"),
        Entry(KeyEvent.KEYCODE_MINUS,          "-"),
        Entry(KeyEvent.KEYCODE_EQUALS,         "="),
        Entry(KeyEvent.KEYCODE_LEFT_BRACKET,   "["),
        Entry(KeyEvent.KEYCODE_RIGHT_BRACKET,  "]"),
        Entry(KeyEvent.KEYCODE_BACKSLASH,      "\\"),
        Entry(KeyEvent.KEYCODE_SEMICOLON,      ";"),
        Entry(KeyEvent.KEYCODE_APOSTROPHE,     "'"),
        Entry(KeyEvent.KEYCODE_COMMA,          ","),
        Entry(KeyEvent.KEYCODE_PERIOD,         "."),
        Entry(KeyEvent.KEYCODE_SLASH,          "/"),
        Entry(KeyEvent.KEYCODE_AT,             "@"),
        Entry(KeyEvent.KEYCODE_POUND,          "#"),
        Entry(KeyEvent.KEYCODE_STAR,           "*"),
        Entry(KeyEvent.KEYCODE_PLUS,           "+"),
    )

    // Shift+key combos — sent as Shift+baseKey so they work in any VNC server layout
    // Each entry has a UNIQUE negative keyCode so it won't clash with the base key entry.
    private val shiftSymbols = listOf(
        Entry(-1,  "!",  needsShift = true),   // Shift+1
        Entry(-2,  "\$", needsShift = true),   // Shift+4
        Entry(-3,  "%",  needsShift = true),   // Shift+5
        Entry(-4,  "^",  needsShift = true),   // Shift+6
        Entry(-5,  "&",  needsShift = true),   // Shift+7
        Entry(-6,  "(",  needsShift = true),   // Shift+9
        Entry(-7,  ")",  needsShift = true),   // Shift+0
        Entry(-8,  "_",  needsShift = true),   // Shift+minus
        Entry(-9,  "{",  needsShift = true),   // Shift+[
        Entry(-10, "}",  needsShift = true),   // Shift+]
        Entry(-11, "|",  needsShift = true),   // Shift+backslash
        Entry(-12, ":",  needsShift = true),   // Shift+;
        Entry(-13, "\"", needsShift = true),   // Shift+'
        Entry(-14, "<",  needsShift = true),   // Shift+,
        Entry(-15, ">",  needsShift = true),   // Shift+.
        Entry(-16, "?",  needsShift = true),   // Shift+/
        Entry(-17, "~",  needsShift = true),   // Shift+`
    )

    // Numpad keys: distinct from regular number row.
    // needsNumLock=true causes META_NUM_LOCK_ON to be added when sending,
    // which prevents KeyHandler.shouldIgnoreEvent() from dropping them.
    private val numpadKeys = listOf(
        Entry(KeyEvent.KEYCODE_NUMPAD_7, "Num7", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_8, "Num8", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_9, "Num9", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_DIVIDE,   "N/",   needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_4, "Num4", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_5, "Num5", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_6, "Num6", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "N*",   needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_1, "Num1", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_2, "Num2", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_3, "Num3", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "N-",   needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_0, "Num0", needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_DOT,      "N.",   needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_ENTER,    "NEntr",needsNumLock = true),
        Entry(KeyEvent.KEYCODE_NUMPAD_ADD,      "N+",   needsNumLock = true),
    )

    val sections = listOf(
        Section("Ctrl, Shift & lainnya",  modifiers,    columns = 3),
        Section("F1 – F12",               functionKeys, columns = 4),
        Section("Huruf (A-Z)",            letters,      columns = 6),
        Section("Angka (0-9)",            numbers,      columns = 5),
        Section("Numpad",                 numpadKeys,   columns = 4),
        Section("Tanda baca",             symbols,      columns = 5),
        Section("Shift + tanda baca",     shiftSymbols, columns = 5),
    )

    private val allEntries by lazy { sections.flatMap { it.entries } }
    fun findEntry(keyCode: Int) = allEntries.firstOrNull { it.keyCode == keyCode }

    /** Map from synthetic negative keyCode to the actual key+metaState to send. */
    val shiftKeyMap: Map<Int, Int> = mapOf(
        -1  to KeyEvent.KEYCODE_1,
        -2  to KeyEvent.KEYCODE_4,
        -3  to KeyEvent.KEYCODE_5,
        -4  to KeyEvent.KEYCODE_6,
        -5  to KeyEvent.KEYCODE_7,
        -6  to KeyEvent.KEYCODE_9,
        -7  to KeyEvent.KEYCODE_0,
        -8  to KeyEvent.KEYCODE_MINUS,
        -9  to KeyEvent.KEYCODE_LEFT_BRACKET,
        -10 to KeyEvent.KEYCODE_RIGHT_BRACKET,
        -11 to KeyEvent.KEYCODE_BACKSLASH,
        -12 to KeyEvent.KEYCODE_SEMICOLON,
        -13 to KeyEvent.KEYCODE_APOSTROPHE,
        -14 to KeyEvent.KEYCODE_COMMA,
        -15 to KeyEvent.KEYCODE_PERIOD,
        -16 to KeyEvent.KEYCODE_SLASH,
        -17 to KeyEvent.KEYCODE_GRAVE,
    )
}
