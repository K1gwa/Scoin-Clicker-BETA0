package com.example.coinclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.random.Random

class ClickService : AccessibilityService() {
    // ---- Settings you can edit (all lowercase) ----
    private val targets = listOf("check in", "check-in", "claim", "collect", "spin", "get coins")
    private val never = listOf("buy", "pay", "order", "checkout", "place")
    private val needsOrange = listOf("claim")
    private val closeLabels = setOf("x", "×", "✕", "✖", "close", "dismiss", "close ad", "close popup")
    private val frozenScans = 3
    private val timerRe = Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b")

    private val handler = Handler(Looper.getMainLooper())
    private val loop = Runnable { tick() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastTap = HashMap<String, Long>()
    private val onlyZones = mutableListOf<Rect>()   // if any exist, clicks happen ONLY inside these
    private val avoidZones = mutableListOf<Rect>()  // never click inside these
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

    private fun updateStatus() {
        status?.text = "Taps: $taps  Scrolls: $scrolls\nOnly: ${onlyZones.size}  Avoid: ${avoidZones.size}"
    }

    // ---- Zones ----
    private fun saveZones() {
        fun s(l: List<Rect>) = l.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
        getSharedPreferences("zones", MODE_PRIVATE).edit()
            .putString("only", s(onlyZones)).putString("avoid", s(avoidZones)).apply()
    }

    private fun loadZones() {
        val sp = getSharedPreferences("zones", MODE_PRIVATE)
        fun p(key: String, out: MutableList<Rect>) {
            out.clear()
            sp.getString(key, "")!!.split(";").filter { it.isNotBlank() }.forEach {
                val v = it.split(",").map { n -> n.toInt() }
                out.add(Rect(v[0], v[1], v[2], v[3]))
            }
        }
        p("only", onlyZones); p("avoid", avoidZones)
    }

    private fun inAny(l: List<Rect>, x: Int, y: Int) = l.any { it.contains(x, y) }
    private fun allowed(x: Int, y: Int, useOnly: Boolean = true) =
        !inAny(avoidZones, x, y) && (!useOnly || onlyZones.isEmpty() || inAny(onlyZones, x, y))

    // Full-screen layer for drawing a zone with your finger.
    private inner class ZoneView(val isOnly: Boolean) : View(this@ClickService) {
        private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
        private var drag = false
        private val p = Paint()
        private val green = 0xFF4CAF50.toInt(); private val red = 0xFFF44336.toInt()

        private fun box(c: Canvas, r: Rect, color: Int) {
            p.style = Paint.Style.FILL; p.color = (color and 0x00FFFFFF) or 0x55000000; c.drawRect(r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = color; c.drawRect(r, p)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0x66000000)
            onlyZones.forEach { box(c, it, green) }
            avoidZones.forEach { box(c, it, red) }
            if (drag) box(c, Rect(minOf(sx, ex).toInt(), minOf(sy, ey).toInt(), maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt()),
                if (isOnly) green else red)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 46f
            c.drawText(if (isOnly) "Drag to draw a CLICK-ONLY area (tap to cancel)" else "Drag to draw an AVOID area (tap to cancel)", 40f, 180f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = sx; ey = sy; drag = true }
                MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
                MotionEvent.ACTION_UP -> {
                    drag = false
                    val r = Rect(minOf(sx, ex).toInt(), minOf(sy, ey).toInt(), maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt())
                    if (r.width() > 40 && r.height() > 40) { (if (isOnly) onlyZones else avoidZones).add(r); saveZones() }
                    try { wm.removeView(this) } catch (_: Exception) {}
                    updateStatus()
                    return true
                }
            }
            invalidate(); return true
        }
    }

    private fun drawZone(isOnly: Boolean) {
        running = false; handler.removeCallbacks(loop)   // pause so the dim layer isn't scanned
        status?.text = "Draw the area, then press START"
        wm.addView(ZoneView(isOnly), overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0))
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadZones()

        val handle = TextView(this).apply {
            text = "≡ drag to move"; setTextColor(Color.WHITE); setPadding(8, 8, 8, 12)
        }
        val st = TextView(this).apply { setTextColor(Color.WHITE); setPadding(8, 0, 8, 8) }
        status = st; updateStatus()

        fun btn(label: String, act: () -> Unit) = Button(this).apply { text = label; setOnClickListener { act() } }
        val row1 = LinearLayout(this).apply {
            addView(btn("START") { running = true; same = 0; lastSig = ""; handler.removeCallbacks(loop); updateStatus(); tick() })
            addView(btn("STOP") { running = false; handler.removeCallbacks(loop); st.text = "Stopped" })
        }
        val row2 = LinearLayout(this).apply {
            addView(btn("ONLY") { drawZone(true) })
            addView(btn("AVOID") { drawZone(false) })
            addView(btn("CLEAR") { onlyZones.clear(); avoidZones.clear(); saveZones(); updateStatus() })
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply { setColor(0xCC222222.toInt()); cornerRadius = 28f }
            addView(handle); addView(st); addView(row1); addView(row2)
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

    // Ring shown ~0.7s: red = tap, amber = popup closed, blue = scroll.
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
                        if (closePopup(it) || handle(it, bmp)) { same = 0 } else watchTimer(it)
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

    private fun cooldown(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - (lastTap[key] ?: 0L) < 8000) return false   // don't re-tap same spot for 8s
        lastTap[key] = now
        return true
    }

    // Closes a popup ONLY by pressing a button labelled exactly "x" / "×" / "close" etc.
    // Tries the app's accessibility tree first, then reads the screenshot text.
    private fun closePopup(t: Text): Boolean {
        val r = findCloseNode() ?: t.textBlocks.flatMap { it.lines }
            .firstOrNull { it.text.trim().lowercase() in closeLabels }?.boundingBox ?: return false
        val x = r.centerX(); val y = r.centerY()
        if (!allowed(x, y, useOnly = false)) return false   // avoid-zones still apply
        if (!cooldown("x${x / 80},${y / 80}")) return false
        taps++; updateStatus()
        flash(x, y, 0xFFFFC107.toInt())
        tap(x.toFloat(), y.toFloat())
        return true
    }

    private fun findCloseNode(): Rect? {
        val root = rootInActiveWindow ?: return null
        fun walk(n: AccessibilityNodeInfo): Rect? {
            val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase()
            if (label != null && label in closeLabels && n.isVisibleToUser) {
                val b = Rect(); n.getBoundsInScreen(b)
                if (!b.isEmpty) return b
            }
            for (i in 0 until n.childCount) {
                val r = n.getChild(i)?.let { walk(it) }
                if (r != null) return r
            }
            return null
        }
        return walk(root)
    }

    // Returns true if it tapped something.
    private fun handle(t: Text, bmp: Bitmap): Boolean {
        for (block in t.textBlocks) for (line in block.lines) {
            val s = line.text.lowercase()
            val box = line.boundingBox ?: continue
            if (never.any { s.contains(it) } || targets.none { s.contains(it) }) continue
            if (needsOrange.any { s.contains(it) } && !isOrange(bmp, box)) continue
            if (!allowed(box.centerX(), box.centerY())) continue
            if (!cooldown("${box.centerX() / 80},${box.centerY() / 80}")) continue
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

    // Swipe is placed randomly around the screen center, with random length and speed.
    private fun scrollDown() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val cx = w / 2 + (Random.nextFloat() - 0.5f) * 0.24f * w
        val cy = h / 2 + (Random.nextFloat() - 0.5f) * 0.10f * h
        val d = h * (0.28f + Random.nextFloat() * 0.14f)
        val p = Path().apply {
            moveTo(cx + (Random.nextFloat() - 0.5f) * 0.04f * w, cy + d / 2)
            lineTo(cx + (Random.nextFloat() - 0.5f) * 0.04f * w, cy - d / 2)
        }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, Random.nextLong(300, 700))).build(), null, null)
        scrolls++; updateStatus()
        flash(cx.toInt(), (cy + d / 2).toInt(), 0xFF2196F3.toInt())
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
