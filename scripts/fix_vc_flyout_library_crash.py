#!/usr/bin/env python3
"""
fix_vc_flyout_library_crash.py

Hotfix crash Virtual Controller flyout saat avnc dipakai sebagai library di
tiny_computer-test (atau app lain).

Root cause:
  VirtualControllerFlyout.kt menggunakan resolveAttribute() + ContextCompat.getColor()
  untuk mendapatkan warna dari theme. Saat avnc dikompile sebagai AAR dengan
  Material 1.7.0, resource IDs yang di-resolve berbeda dari yang ada di host app
  yang memakai Material 1.14.0. Ini menyebabkan Resources.NotFoundException atau
  crash serupa saat flyout coba dibuka.

Fix:
  1. VirtualControllerFlyout.kt ditulis ulang tanpa resolveAttribute() sama sekali.
     Semua warna hardcode — aman di semua versi Material dan semua theme.
  2. avnc app/build.gradle Material versi dinaikkan 1.7.0 → 1.14.0 agar
     sesuai dengan tiny_computer-test sehingga tidak ada version mismatch.

Cara pakai:
    python3 fix_vc_flyout_library_crash.py
    python3 fix_vc_flyout_library_crash.py --repo /path/ke/avnc-test
"""

import argparse, sys
from pathlib import Path

FLYOUT_PATH = "app/src/main/java/com/gaurav/avnc/ui/vnc/VirtualControllerFlyout.kt"
GRADLE_PATH = "app/build.gradle"

FLYOUT_CONTENT = '/*\n * Copyright (c) 2026  DevElderLost.\n *\n * SPDX-License-Identifier:  GPL-3.0-or-later\n *\n * See COPYING.txt for more details.\n */\n\npackage com.gaurav.avnc.ui.vnc\n\nimport android.graphics.Color\nimport android.graphics.Typeface\nimport android.util.TypedValue\nimport android.view.Gravity\nimport android.view.View\nimport android.widget.CheckBox\nimport android.widget.CompoundButton\nimport android.widget.GridLayout\nimport android.widget.LinearLayout\nimport android.widget.Switch\nimport android.widget.TextView\nimport com.gaurav.avnc.R\n\n/**\n * Fills the flyout panel (toolbar_drawer.xml → virtual_controller_group) with a live\n * checkbox-grid so the user can select floating keys and toggle Edit-Position mode —\n * all without leaving the toolbar drawer (mirrors the gesture-style / view-mode flyout UX).\n *\n * All colors are hardcoded rather than resolved from the activity\'s theme so this class\n * works correctly regardless of which Material library version the host app uses or which\n * theme is active.  This avoids resource-ID mismatches that occur when avnc is used as an\n * AAR library inside an app compiled with a different Material version.\n *\n * @param activity     Host [VncActivity].\n * @param controller   The [VirtualController] instance managed by the same activity.\n * @param contentRoot  The [LinearLayout] inside the flyout ScrollView.\n * @param onClose      Called when the user taps "Terapkan" or "Atur Ulang Posisi" —\n *                     the caller (Toolbar) un-checks the toggle button to hide the flyout.\n */\nclass VirtualControllerFlyout(\n        private val activity: VncActivity,\n        private val controller: VirtualController,\n        private val contentRoot: LinearLayout,\n        private val onClose: () -> Unit,\n) {\n    private val checkBoxes = mutableMapOf<Int, CheckBox>()\n    private lateinit var editSwitch: Switch\n\n    /**\n     * Build (or rebuild) the flyout content. Called each time the panel opens so that\n     * checkbox states always reflect the current key list.\n     */\n    fun inflate() {\n        contentRoot.removeAllViews()\n        checkBoxes.clear()\n\n        // --- Edit-mode switch ---\n        editSwitch = Switch(activity).apply {\n            text = activity.getString(R.string.virtual_controller_edit_mode)\n            isChecked = controller.editMode\n            textSize = 13f\n            setPadding(0, dp(4), 0, dp(4))\n            setOnCheckedChangeListener { _, checked -> controller.setEditMode(checked) }\n        }\n        contentRoot.addView(editSwitch)\n        contentRoot.addView(makeDivider())\n\n        // --- Key sections ---\n        VCCatalog.sections.forEach { section ->\n            val header = TextView(activity).apply {\n                text = section.title\n                textSize = 12f\n                setTypeface(null, Typeface.BOLD)\n                alpha = 0.7f\n                setPadding(0, dp(6), 0, dp(2))\n            }\n            contentRoot.addView(header)\n\n            val grid = GridLayout(activity).apply {\n                columnCount = section.columns.coerceAtMost(3) // max 3 cols to fit 220 dp\n            }\n            section.entries.forEach { entry ->\n                val cb = CheckBox(activity).apply {\n                    text = entry.label\n                    textSize = 12f\n                    layoutParams = GridLayout.LayoutParams().apply {\n                        width = GridLayout.LayoutParams.WRAP_CONTENT\n                        height = GridLayout.LayoutParams.WRAP_CONTENT\n                        val m = dp(1)\n                        setMargins(m, m, m, m)\n                    }\n                    // Set isChecked BEFORE the listener so it doesn\'t fire on initialisation.\n                    isChecked = controller.hasKey(entry.keyCode)\n                    setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->\n                        if (checked) controller.addKey(entry) else controller.removeKey(entry.keyCode)\n                    }\n                }\n                checkBoxes[entry.keyCode] = cb\n                grid.addView(cb)\n            }\n            contentRoot.addView(grid)\n        }\n\n        contentRoot.addView(makeDivider())\n\n        // --- Action row ---\n        val btnRow = LinearLayout(activity).apply {\n            orientation = LinearLayout.HORIZONTAL\n            gravity = Gravity.END\n            setPadding(0, dp(4), 0, 0)\n        }\n        btnRow.addView(makeTextButton(activity.getString(R.string.virtual_controller_reset_positions)) {\n            controller.resetPositions()\n            onClose()\n        })\n        btnRow.addView(makeTextButton(activity.getString(R.string.virtual_controller_apply)) {\n            onClose()\n        })\n        contentRoot.addView(btnRow)\n    }\n\n    /** Sync edit-switch when flyout closes (e.g. user tapped the Done chip on overlay). */\n    fun syncEditSwitch() {\n        if (::editSwitch.isInitialized) editSwitch.isChecked = controller.editMode\n    }\n\n    // -------------------------------------------------------------------------\n    // Helpers\n    // -------------------------------------------------------------------------\n\n    private fun makeDivider(): View = View(activity).apply {\n        layoutParams = LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)\n        ).apply { setMargins(0, dp(4), 0, dp(4)) }\n        // Hardcoded semi-transparent neutral — works on both light and dark backgrounds.\n        setBackgroundColor(Color.argb(60, 128, 128, 128))\n    }\n\n    private fun makeTextButton(label: String, onClick: () -> Unit): TextView {\n        return TextView(activity).apply {\n            text = label\n            textSize = 12f\n            // Hardcoded accent color that contrasts with both light/dark toolbar backgrounds.\n            setTextColor(Color.parseColor("#4FC3F7")) // light-blue-300\n            setPadding(dp(8), dp(8), dp(4), dp(8))\n            isClickable = true\n            isFocusable = true\n            setOnClickListener { onClick() }\n        }\n    }\n\n    private fun dp(value: Int): Int = TypedValue.applyDimension(\n            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics\n    ).toInt()\n}\n'

OLD_MATERIAL = 'implementation "com.google.android.material:material:1.7.0"'
NEW_MATERIAL = 'implementation "com.google.android.material:material:1.14.0"'


def write_file(path: Path, content: str, label: str) -> None:
    if path.exists() and path.read_text(encoding="utf-8") == content:
        print(f"  [skip]  {path.name} (sudah identik)")
        return
    path.write_text(content, encoding="utf-8")
    print(f"  [ok]    {path.name} {label}")


def patch_file(path: Path, old: str, new: str, desc: str) -> None:
    if not path.exists():
        print(f"  [warn]  {path.name} tidak ditemukan, skip.")
        return
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"  [skip]  {path.name}: {desc} (sudah)")
        return
    if old not in text:
        print(f"  [warn]  {path.name}: anchor '{desc}' tidak ditemukan, skip.")
        return
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"  [ok]    {path.name}: {desc}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=".", help="Root repo avnc-test")
    args = ap.parse_args()
    repo = Path(args.repo).resolve()

    if not (repo / FLYOUT_PATH).exists():
        sys.exit("[ERROR] VirtualControllerFlyout.kt tidak ditemukan. Pastikan patch v2 sudah dijalankan.")

    print(f"Repo: {repo}")
    print()

    print("1) Rewrite VirtualControllerFlyout.kt (hapus semua theme resolution)...")
    write_file(repo / FLYOUT_PATH, FLYOUT_CONTENT, "(rewrite - no resolveAttribute)")

    print()
    print("2) Update Material library version di app/build.gradle...")
    patch_file(repo / GRADLE_PATH, OLD_MATERIAL, NEW_MATERIAL,
               "Material 1.7.0 → 1.14.0")

    print()
    print("Selesai!")
    print("  • VirtualControllerFlyout kini menggunakan hardcode colors (tidak ada resolveAttribute).")
    print("  • Material library version di avnc sekarang sama dengan tiny_computer-test (1.14.0).")
    print("  • Build ulang avnc → update commit hash di tiny_computer libs.versions.toml.")


if __name__ == "__main__":
    main()
