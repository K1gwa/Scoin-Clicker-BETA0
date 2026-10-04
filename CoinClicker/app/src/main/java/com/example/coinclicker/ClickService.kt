package com.example.coinclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ClickService : AccessibilityService() {
    // Edit these lists (lowercase) to change what gets tapped / never tapped.
    private val targets = listOf("check in", "check-in", "claim", "collect", "spin", "get coins")
    private val never = listOf("buy", "pay", "order", "checkout", "place")

    private val handler = Handler(Looper.getMainLooper())
    private val loop = Runnable { tick() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastTap = HashMap<String, Long>()
    private var running = false
    private var taps = 0
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private lateinit var wm: WindowManager

    private fun overlayParams(w: Int, h: Int, extra: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        extra or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val handle = TextView(this).apply {
            text = "≡ drag to move"; setTextColor(Color.WHITE); setPadding(8, 8, 8, 12)
        }
        val st = TextView(this).apply { text = "Idle"; setTextColor(Color.WHITE); setPadding(8, 0, 8, 8) }
        status = st

        fun btn(label: String, run: Boolean) = Button(this).apply {
            text = label
            setOnClickListener {
                running = run
                handler.removeCallbacks(loop)
                st.text = if (run) "Scanning…" else "Stopped"
                if (run) tick()
            }
        }
        val row = LinearLayout(this).apply { addView(btn("START", true)); addView(btn("STOP", false)) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply { setColor(0xCC222222.toInt()); cornerRadius = 28f }
            addView(handle); addView(st); addView(row)
        }

        val lp = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0)
            .apply { x = 40; y = 300 }

        // Drag the panel by its "≡ drag to move" handle.
        handle.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (ix + (e.rawX - tx).toInt()).coerceAtLeast(0)
                        lp.y = (iy + (e.rawY - ty).toInt()).coerceAtLeast(0)
                        wm.updateViewLayout(box, lp)
                    }
                }
                return true
            }
        })

        wm.addView(box, lp)
        panel = box
    }

    // Red ring shown for ~0.7s where the tap happened.
    private fun flash(cx: Int, cy: Int) {
        val size = 140
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66FF0000); setStroke(8, Color.RED)
            }
        }
        val lp = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            .apply { x = cx - size / 2; y = cy - size / 2 }
        wm.addView(ring, lp)
        ring.postDelayed({ try { wm.removeView(ring) } catch (_: Exception) {} }, 700)
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
        if (running) handler.postDelayed(loop, 1500)
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
            taps++
            status?.text = "Taps: $taps"
            flash(box.centerX(), box.centerY())
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
        handler.removeCallbacks(loop)
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
