package com.example.coinclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
    // ---- Settings you can edit (all lowercase) ----
    private val targets = listOf("check in", "check-in", "claim", "collect", "spin", "get coins")
    private val never = listOf("buy", "pay", "order", "checkout", "place")
    private val needsOrange = listOf("claim")   // these only get tapped if the button is orange
    private val frozenScans = 3                  // timer unchanged for this many scans (~1.5s each) = stuck
    private val timerRe = Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b")

    private val handler = Handler(Looper.getMainLooper())
    private val loop = Runnable { tick() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastTap = HashMap<String, Long>()
    private var running = false
    private var taps = 0
    private var scrolls = 0
    private var lastSig = ""
    private var same = 0
    private var lastScroll = 0L
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

    private fun updateStatus() { status?.text = "Taps: $taps  Scrolls: $scrolls" }

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
                if (run) { same = 0; lastSig = ""; updateStatus(); tick() } else st.text = "Stopped"
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

    // Ring shown ~0.7s: red = tap, blue = scroll.
    private fun flash(cx: Int, cy: Int, color: Int = Color.RED) {
        val size = 140
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((color and 0x00FFFFFF) or 0x66000000); setStroke(8, color)
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
                    .addOnSuccessListener {
                        if (handle(it, bmp)) { same = 0 } else watchTimer(it)
                        next()
                    }
                    .addOnFailureListener { next() }
            }
            override fun onFailure(errorCode: Int) = next()
        })
    }

    private fun next() {
        if (running) handler.postDelayed(loop, 1500)
    }

    // Returns true if it tapped something.
    private fun handle(t: Text, bmp: Bitmap): Boolean {
        val now = SystemClock.uptimeMillis()
        for (block in t.textBlocks) for (line in block.lines) {
            val s = line.text.lowercase()
            val box = line.boundingBox ?: continue
            if (never.any { s.contains(it) } || targets.none { s.contains(it) }) continue
            if (needsOrange.any { s.contains(it) } && !isOrange(bmp, box)) continue
            val key = "${box.centerX() / 80},${box.centerY() / 80}"
            if (now - (lastTap[key] ?: 0L) < 8000) continue // don't re-tap same spot for 8s
            lastTap[key] = now
            taps++; updateStatus()
            flash(box.centerX(), box.centerY())
            tap(box.centerX().toFloat(), box.centerY().toFloat())
            return true
        }
        return false
    }

    // Samples pixels just around the text; orange = hue 5-40, strong color, bright.
    private fun isOrange(bmp: Bitmap, box: Rect): Boolean {
        val cx = box.centerX(); val cy = box.centerY()
        val dx = (box.height() * 0.8f).toInt(); val dy = (box.height() * 0.6f).toInt()
        val h4 = box.height() / 4; val w4 = box.width() / 4
        val pts = listOf(
            box.left - dx to cy - h4, box.left - dx to cy, box.left - dx to cy + h4,
            box.right + dx to cy - h4, box.right + dx to cy, box.right + dx to cy + h4,
            cx - w4 to box.top - dy, cx to box.top - dy, cx + w4 to box.top - dy,
            cx - w4 to box.bottom + dy, cx to box.bottom + dy, cx + w4 to box.bottom + dy
        )
        val hsv = FloatArray(3)
        var hits = 0
        for ((x, y) in pts) {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            Color.colorToHSV(bmp.getPixel(x, y), hsv)
            if (hsv[0] in 5f..40f && hsv[1] > 0.5f && hsv[2] > 0.7f) hits++
        }
        return hits >= 5
    }

    // If a timer (like 00:15) is on screen but hasn't changed for a few scans, scroll down.
    private fun watchTimer(t: Text) {
        val minY = resources.displayMetrics.heightPixels * 0.07 // ignore the status-bar clock
        val sig = t.textBlocks.flatMap { it.lines }
            .filter { (it.boundingBox?.centerY() ?: 0) > minY }
            .flatMap { l -> timerRe.findAll(l.text).map { it.value }.toList() }
            .joinToString("|")
        if (sig.isEmpty() || sig != lastSig) { same = 0; lastSig = sig; return }
        same++
        val now = SystemClock.uptimeMillis()
        if (same >= frozenScans && now - lastScroll > 6000) {
            lastScroll = now; same = 0
            scrollDown()
        }
    }

    private fun scrollDown() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val p = Path().apply { moveTo(w / 2, h * 0.75f); lineTo(w / 2, h * 0.30f) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400)).build(), null, null)
        scrolls++; updateStatus()
        flash((w / 2).toInt(), (h * 0.75f).toInt(), 0xFF2196F3.toInt())
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
