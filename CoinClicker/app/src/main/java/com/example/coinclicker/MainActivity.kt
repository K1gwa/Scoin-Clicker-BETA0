package com.example.coinclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply {
            text = "1. Tap the button below.\n2. Open Installed apps > Coin Clicker and turn it ON.\n3. Open Shopee on the page you want. START / STOP buttons float on the right side of the screen.\n\nIt only taps text containing: check in, claim, collect, spin."
            textSize = 16f
        })
        col.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(col)
    }
}
