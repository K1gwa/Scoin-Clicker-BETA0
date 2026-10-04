package com.example.coinclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun btn(label: String, act: () -> Unit) =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { act() } }

    private fun render() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "1. Turn on Coin Clicker in Accessibility settings.\n2. Open Shopee and use the floating panel: START / STOP, draw ONLY / AVOID / SCROLL areas, and switch profiles.\n\nTap a profile below to edit or delete it."
        })
        col.addView(btn("Open Accessibility Settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val active = Store.active(this)
        Store.load(this).forEach { p -> col.addView(btn((if (p.name == active) "● " else "") + p.name) { edit(p) }) }
        col.addView(btn("+ Add profile") { edit(null) })
    }

    private fun edit(p: Profile?) {
        val etName = EditText(this).apply { hint = "Profile name"; setText(p?.name ?: "") }
        val etWords = EditText(this).apply { hint = "Words to tap, separated by commas"; setText(p?.targets ?: Store.DEFAULT_TARGETS) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(etName); addView(etWords)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (p == null) "Add profile" else "Edit profile").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = etName.text.toString().trim()
                val l = Store.load(this)
                if (n.isEmpty() || l.any { it.name == n && it.name != p?.name }) {
                    Toast.makeText(this, "Name is empty or already used", Toast.LENGTH_SHORT).show()
                } else {
                    if (p == null) l.add(Profile(n, etWords.text.toString()))
                    else l.find { it.name == p.name }?.let {
                        if (Store.active(this) == it.name) Store.setActive(this, n)
                        it.name = n; it.targets = etWords.text.toString()
                    }
                    Store.save(this, l)
                }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (p != null && Store.load(this).size > 1) {
            dlg.setNeutralButton("Delete") { _, _ ->
                val l = Store.load(this); l.removeAll { it.name == p.name }
                Store.save(this, l)
                if (Store.active(this) == p.name) Store.setActive(this, l[0].name)
                render()
            }
        }
        dlg.show()
    }
}
