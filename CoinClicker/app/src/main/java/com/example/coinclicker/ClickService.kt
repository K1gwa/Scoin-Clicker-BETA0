package com.example.coinclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ClickService : AccessibilityService() {
    // Edit these lists (lowercase) to change what gets tapped / never tapped.
    private val targets = listOf("check in", "check-in", "claim", "collect", "spin", "get coins")
    private val never = listOf("buy", "pay", "order", "checkout", "place")

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastTap = HashMap<String, Long>()
    private var running = false
    private var panel: LinearLayout? = null

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this)
        fun btn(label: String, run: Boolean) = Button(this).apply {
            text = label
            setOnClickListener {
                running = run
                handler.removeCallbacksAndMessages(null)
                if (run) tick()
            }
        }
        row.addView(btn("START", true))
        row.addView(btn("STOP", false))
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 250 }
        wm.addView(row, lp)
        panel = row
    }

    private fun tick() {
        if (!running) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (bmp == null) return next()
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { handle(it); next() }
                    .addOnFailureListener { next() }
            }
            override fun onFailure(errorCode: Int) = next()
        })
    }

    private fun next() {
        if (running) handler.postDelayed({ tick() }, 1500)
    }

    private fun handle(t: Text) {
        val now = SystemClock.uptimeMillis()
        for (block in t.textBlocks) for (line in block.lines) {
            val s = line.text.lowercase()
            val box = line.boundingBox ?: continue
            if (never.any { s.contains(it) } || targets.none { s.contains(it) }) continue
            val key = "${box.centerX() / 80},${box.centerY() / 80}"
            if (now - (lastTap[key] ?: 0L) < 8000) continue // don't re-tap same spot for 8s
            lastTap[key] = now
            tap(box.centerX().toFloat(), box.centerY().toFloat())
            return
        }
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        dispatchGesture(g, null, null)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { running = false }
    override fun onDestroy() {
        running = false
        panel?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        super.onDestroy()
    }
}
