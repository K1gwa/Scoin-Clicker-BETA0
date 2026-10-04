package com.example.coinclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
    // ---- Fixed settings (words to tap come from the active profile) ----
    private val never = listOf("buy", "pay", "order", "checkout", "place")
    private val needsOrange = listOf("claim")
    private val closeLabels = setOf("x", "×", "✕", "✖", "close", "dismiss", "close ad", "close popup")
    private val intervals = intArrayOf(0, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90, 120) // seconds, 0 = off

    private val handler = Handler(Looper.getMainLooper())
    private val loop = Runnable { tick() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastTap = HashMap<String, Long>()
    private lateinit var profile: Profile
    private var targets = listOf<String>()
    private var onlyZones = mutableListOf<Rect>()    // if any: clicks happen ONLY inside
    private var avoidZones = mutableListOf<Rect>()   // never click inside
    private var scrollZones = mutableListOf<Rect>()  // if any: scrolling happens ONLY inside
    private var running = false
    private var taps = 0
    private var scrolls = 0
    private var lastScroll = 0L
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var profBtn: Button? = null
    private var intervalTv: TextView? = null
    private lateinit var wm: WindowManager

    private fun overlayParams(w: Int, h: Int, extra: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        extra or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun updateStatus() {
        status?.text = "Taps: $taps  Scrolls: $scrolls\nOnly:${onlyZones.size} Avoid:${avoidZones.size} Scroll:${scrollZones.size}"
    }

    // ---- Profiles & zones ----
    private fun reload() {
        profile = Store.current(this)
        targets = profile.targets.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        onlyZones = Store.zones(profile.only); avoidZones = Store.zones(profile.avoid)
        scrollZones = Store.zones(profile.scroll)
        profBtn?.text = "Profile: ${profile.name}"
        showInterval()
        updateStatus()
    }

    private fun persist() {
        val l = Store.load(this)
        val p = l.find { it.name == profile.name } ?: return
        p.only = Store.str(onlyZones); p.avoid = Store.str(avoidZones); p.scroll = Store.str(scrollZones); p.scrollSec = profile.scrollSec
        Store.save(this, l)
    }

    private fun inAny(l: List<Rect>, x: Int, y: Int) = l.any { it.contains(x, y) }
    private fun allowed(x: Int, y: Int, useOnly: Boolean = true) =
        !inAny(avoidZones, x, y) && (!useOnly || onlyZones.isEmpty() || inAny(onlyZones, x, y))

    private fun panelRect(): Rect {
        val p = panel ?: return Rect()
        val loc = IntArray(2); p.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
    }

    // Full-screen layer for drawing a zone. mode: 0 = click-only, 1 = avoid, 2 = scroll.
    private inner class ZoneView(val mode: Int) : View(this@ClickService) {
        private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
        private var drag = false
        private val p = Paint()
        private val colors = intArrayOf(0xFF4CAF50.toInt(), 0xFFF44336.toInt(), 0xFF2196F3.toInt())

        private fun box(c: Canvas, r: Rect, color: Int) {
            p.style = Paint.Style.FILL; p.color = (color and 0x00FFFFFF) or 0x55000000; c.drawRect(r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = color; c.drawRect(r, p)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0x66000000)
            onlyZones.forEach { box(c, it, colors[0]) }
            avoidZones.forEach { box(c, it, colors[1]) }
            scrollZones.forEach { box(c, it, colors[2]) }
            if (drag) box(c, Rect(minOf(sx, ex).toInt(), minOf(sy, ey).toInt(), maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt()), colors[mode])
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 46f
            c.drawText("Drag to draw a ${arrayOf("CLICK-ONLY", "AVOID", "SCROLL")[mode]} area (tap to cancel)", 40f, 180f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = sx; ey = sy; drag = true }
                MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
                MotionEvent.ACTION_UP -> {
                    drag = false
                    val r = Rect(minOf(sx, ex).toInt(), minOf(sy, ey).toInt(), maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt())
                    if (r.width() > 40 && r.height() > 40) {
                        when (mode) { 0 -> onlyZones; 1 -> avoidZones; else -> scrollZones }.add(r)
                        persist()
                    }
                    try { wm.removeView(this) } catch (_: Exception) {}
                    updateStatus()
                    return true
                }
            }
            invalidate(); return true
        }
    }

    private fun drawZone(mode: Int) {
        running = false; handler.removeCallbacks(loop)   // pause so the dim layer isn't scanned
        status?.text = "Draw the area, then press START"
        wm.addView(ZoneView(mode), overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0))
    }

    // Blue line + arrow showing where a scroll swipe happens.
    private inner class LineView(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : View(this@ClickService) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            p.color = 0xFF2196F3.toInt(); p.strokeWidth = 14f; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(x1, y1, x2, y2, p)
            p.style = Paint.Style.FILL
            c.drawCircle(x1, y1, 24f, p)
            c.drawPath(Path().apply { moveTo(x2, y2 - 20); lineTo(x2 - 36, y2 + 36); lineTo(x2 + 36, y2 + 36); close() }, p)
        }
    }

    private fun showScroll(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val v = LineView(x1, y1, x2, y2)
        wm.addView(v, overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        v.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, ms + 300)
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val handle = TextView(this).apply {
            text = "≡ move"; setTextColor(Color.WHITE); setPadding(16, 16, 24, 16)
        }
        val st = TextView(this).apply { setTextColor(Color.WHITE); setPadding(8, 0, 8, 8) }
        status = st

        fun btn(label: String, act: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(20, 0, 20, 0)
            setOnClickListener { act() }
        }

        profBtn = btn("") {
            val l = Store.load(this)
            val i = l.indexOfFirst { it.name == profile.name }
            Store.setActive(this, l[(i + 1) % l.size].name)
            reload()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(st)
            addView(LinearLayout(context).apply {
                addView(btn("START") {
                    reload(); running = true; lastScroll = SystemClock.uptimeMillis()
                    handler.removeCallbacks(loop); tick()
                })
                addView(profBtn)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(btn("–") { stepInterval(-1) })
                addView(TextView(context).apply { setTextColor(Color.WHITE); setPadding(16, 0, 16, 0); intervalTv = this })
                addView(btn("+") { stepInterval(1) })
            })
            addView(LinearLayout(context).apply {
                addView(btn("ONLY") { drawZone(0) })
                addView(btn("AVOID") { drawZone(1) })
                addView(btn("SCROLL") { drawZone(2) })
                addView(btn("CLEAR") {
                    onlyZones.clear(); avoidZones.clear(); scrollZones.clear(); persist(); updateStatus()
                })
            })
            addView(btn("PROFILES") {
                try { startActivity(Intent(this@ClickService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { st.text = "Open the Coin Clicker app from your launcher" }
            })
        }

        val lp = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0)
            .apply { x = 40; y = 300 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
            background = GradientDrawable().apply { setColor(0xCC222222.toInt()); cornerRadius = 28f }
        }
        val minBtn = btn("–") {}
        minBtn.setOnClickListener {
            val hide = content.visibility == View.VISIBLE
            content.visibility = if (hide) View.GONE else View.VISIBLE
            minBtn.text = if (hide) "+" else "–"
            box.post { wm.updateViewLayout(box, lp) }
        }
        // EXIT: tap twice. Turns the service off and removes the panel.
        var armed = false
        val exitBtn = btn("EXIT") {}
        exitBtn.setOnClickListener {
            if (armed) {
                running = false; handler.removeCallbacks(loop); disableSelf()
            } else {
                armed = true; exitBtn.text = "SURE?"
                exitBtn.postDelayed({ armed = false; exitBtn.text = "EXIT" }, 3000)
            }
        }
        // Header (always visible): drag handle, STOP, EXIT, minimize.
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(btn("STOP") { running = false; handler.removeCallbacks(loop); st.text = "Stopped" })
            addView(exitBtn)
            addView(minBtn)
        })
        box.addView(content)

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
        reload()
    }

    // Ring shown ~0.7s: red = tap, amber = popup closed.
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
                        if (!(closePopup(it) || handle(it, bmp))) maybeScroll()
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
    private fun closePopup(t: Text): Boolean {
        val pr = panelRect()
        val r = findCloseNode() ?: t.textBlocks.flatMap { it.lines }.firstOrNull {
            it.text.trim().lowercase() in closeLabels && it.boundingBox?.let { b -> !Rect.intersects(b, pr) } == true
        }?.boundingBox ?: return false
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
        val pr = panelRect()
        for (block in t.textBlocks) for (line in block.lines) {
            val s = line.text.lowercase()
            val box = line.boundingBox ?: continue
            if (Rect.intersects(box, pr)) continue   // never tap our own panel
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

    private fun showInterval() {
        val sec = profile.scrollSec
        intervalTv?.text = if (sec <= 0) "Scroll: off" else "Scroll every ${sec}s"
    }

    private fun stepInterval(dir: Int) {
        var i = intervals.indexOfFirst { it >= profile.scrollSec }
        if (i < 0) i = intervals.lastIndex
        profile.scrollSec = intervals[(i + dir).coerceIn(0, intervals.lastIndex)]
        persist(); showInterval()
    }

    // Scrolls once every N seconds (checked on each ~1.5s scan, and only when nothing was just tapped).
    private fun maybeScroll() {
        val sec = profile.scrollSec
        val now = SystemClock.uptimeMillis()
        if (sec > 0 && now - lastScroll >= sec * 1000L) { lastScroll = now; scrollDown() }
    }

    // Swipe goes inside a SCROLL area if you drew one, otherwise around the screen center.
    private fun scrollDown() {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val zone = scrollZones.randomOrNull()
        val a = zone ?: Rect(0, 0, w, h)
        val lo = if (zone != null) 0.5f else 0.28f
        val hi = if (zone != null) 0.85f else 0.42f
        val cx = a.exactCenterX() + (Random.nextFloat() - 0.5f) * 0.24f * a.width()
        val cy = a.exactCenterY() + (Random.nextFloat() - 0.5f) * 0.10f * a.height()
        val d = a.height() * (lo + Random.nextFloat() * (hi - lo))
        val x1 = cx + (Random.nextFloat() - 0.5f) * 0.04f * a.width(); val y1 = cy + d / 2
        val x2 = cx + (Random.nextFloat() - 0.5f) * 0.04f * a.width(); val y2 = cy - d / 2
        val dur = Random.nextLong(300, 700)
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, dur)).build(), null, null)
        scrolls++; updateStatus()
        showScroll(x1, y1, x2, y2, dur)
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
