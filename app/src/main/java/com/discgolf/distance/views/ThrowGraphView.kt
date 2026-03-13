package com.discgolf.distance.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.discgolf.distance.data.DiscThrow
import kotlin.math.*

/**
 * Canvas view that renders throws relative to their start points.
 *
 * Full mode (arcMode=false):
 *   - Origin at center, full 360° view.
 *   - Throws colored by session (or disc if colorByDisc=true).
 *
 * Arc / directional mode (arcMode=true):
 *   - Origin at the bottom of the screen; arcs extend upward like a radar.
 *   - Each throw is normalized to "straight ahead = up" using the bearing
 *     recorded for that individual throw (DiscThrow.targetBearing).
 *   - Throws without a stored bearing fall back to the activity-level
 *     targetBearing, or 0° (North) if neither is set.
 *   - Only throws within ±30° of their intended target are shown.
 *   - Zoom starts at 2.5×; double-tap resets to that level.
 */
class ThrowGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var throws: List<DiscThrow> = emptyList()

    /**
     * Activity-level compass bearing toward the basket.
     * Used as fallback for throws that don't have their own targetBearing stored.
     * Null = no bearing set.
     */
    var targetBearing: Float? = null
        set(value) { field = value; resetView() }

    /** When true, show the ±30° directional arc view. */
    var arcMode: Boolean = false
        set(value) { field = value; resetView() }

    /** When true, color throws by disc color instead of session color. */
    var colorByDisc: Boolean = false
        set(value) { field = value; invalidate() }

    /** discId → (display name, ARGB color). Populated from the disc list. */
    var discInfoMap: Map<Long, Pair<String, Int>> = emptyMap()
        set(value) { field = value; invalidate() }

    // transform
    private var scaleFactor = 1f
    private var translateX  = 0f
    private var translateY  = 0f
    private var lastTouchX  = 0f
    private var lastTouchY  = 0f

    // ── Paint objects ─────────────────────────────────────────────────────────

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#40FFFFFF")
        strokeWidth = 1f
    }
    private val ringPaint50 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#20FFFFFF")
        strokeWidth = 0.75f
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        textSize = 28f
        textAlign = Paint.Align.LEFT
    }
    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD700")
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#6000E676")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A2A1A")
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#60FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#FFFFD700")
    }
    private val aimFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#30FFD700")
    }
    private val aimLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sessionColors = listOf(
        Color.parseColor("#00E676"),
        Color.parseColor("#40C4FF"),
        Color.parseColor("#FF6D00"),
        Color.parseColor("#EA80FC"),
        Color.parseColor("#FFD740"),
        Color.parseColor("#FF4081")
    )

    // ── Gesture detectors ─────────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.1f, 20f)
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
        })

    // ── Public API ────────────────────────────────────────────────────────────

    fun setThrows(list: List<DiscThrow>) {
        throws = list
        resetView()
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (throws.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        // ── Coordinate origin ──────────────────────────────────────────────
        // Arc mode: origin at the bottom of the screen so throws fan upward.
        // Full mode: origin at center.
        val cx = width / 2f + translateX
        val cy = if (arcMode) height * 0.85f + translateY
                 else         height / 2f   + translateY

        // ── Ring scale ─────────────────────────────────────────────────────
        // Arc mode: largest ring must fit both the height above the origin
        //   and the ±30° horizontal spread (spread = R * sin30° = R/2 per side,
        //   so R ≤ width - padding to stay on-screen).
        // Full mode: rings fit inside the square view.
        val padding = 80f
        val viewRadius = if (arcMode) {
            val arcOriginY = height * 0.85f          // raw origin (no translate)
            val r_vertical   = arcOriginY - padding  // arc top clears the top bar
            val r_horizontal = width - padding       // ±30° fits horizontally
            minOf(r_vertical, r_horizontal)
        } else {
            (minOf(width, height) / 2f) - padding
        }

        // ── Effective global aim (for arc visual elements + fallback) ──────
        // In arc mode we always have *some* reference; default to North(0°).
        val globalAim: Float? = if (arcMode) (targetBearing ?: 0f) else targetBearing

        // ── Filter throws in arc mode ──────────────────────────────────────
        // Each throw is checked against its own stored targetBearing (or global aim as fallback).
        val visibleThrows = if (arcMode) {
            throws.filter { t ->
                val ref = t.targetBearing ?: globalAim?.toDouble() ?: 0.0
                val raw = DiscThrow.calculateBearing(t.startLat, t.startLng, t.endLat, t.endLng)
                abs(relativeBearing(raw, ref)) <= 30.0
            }
        } else {
            throws
        }

        val maxFt = visibleThrows.maxOfOrNull { it.distanceFeet }
            ?: throws.maxOf { it.distanceFeet }
        val ringStep = 100.0
        val numRings = ceil(maxFt / ringStep).toInt().coerceAtLeast(1)
        val maxRingFt = numRings * ringStep
        val pixelsPerFoot = viewRadius / maxRingFt.toFloat()

        canvas.save()
        canvas.scale(scaleFactor, scaleFactor, cx, cy)

        // ── Arc wedge fill ─────────────────────────────────────────────────
        if (arcMode) {
            val outerR = (maxRingFt * pixelsPerFoot).toFloat()
            val wedgePath = Path().apply {
                moveTo(cx, cy)
                // ±30° around "up" (-90° canvas) → -120° to -60°, sweep 60°
                arcTo(RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
                    -120f, 60f, false)
                close()
            }
            canvas.drawPath(wedgePath, aimFillPaint)
        }

        // ── Distance rings ─────────────────────────────────────────────────
        for (i in 1..numRings) {
            // 50 ft intermediate ring
            val midFt = (i - 1) * ringStep + 50.0
            val r50 = (midFt * pixelsPerFoot).toFloat()
            if (arcMode) {
                canvas.drawArc(RectF(cx - r50, cy - r50, cx + r50, cy + r50),
                    -120f, 60f, false, ringPaint50)
            } else {
                canvas.drawCircle(cx, cy, r50, ringPaint50)
            }
            // 100 ft major ring + label
            val ringFt = i * ringStep
            val r = (ringFt * pixelsPerFoot).toFloat()
            if (arcMode) {
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r),
                    -120f, 60f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            canvas.drawText("${ringFt.toInt()} ft", cx + 6f, cy - r + ringLabelPaint.textSize, ringLabelPaint)
        }

        // ── Axes / bounding radii ──────────────────────────────────────────
        val axisLen = (maxRingFt * pixelsPerFoot).toFloat()
        if (arcMode) {
            val leftRad  = Math.toRadians(-120.0)
            val rightRad = Math.toRadians(-60.0)
            canvas.drawLine(cx, cy,
                cx + (cos(leftRad)  * axisLen).toFloat(),
                cy + (sin(leftRad)  * axisLen).toFloat(), axisPaint)
            canvas.drawLine(cx, cy,
                cx + (cos(rightRad) * axisLen).toFloat(),
                cy + (sin(rightRad) * axisLen).toFloat(), axisPaint)
        } else {
            canvas.drawLine(cx - axisLen, cy, cx + axisLen, cy, axisPaint)
            canvas.drawLine(cx, cy - axisLen, cx, cy + axisLen, axisPaint)
        }

        // ── Aim arrow ─────────────────────────────────────────────────────
        // In arc mode the arrow always points straight up ("intended direction").
        if (globalAim != null) {
            val arrowLen = axisLen.coerceAtMost(viewRadius + padding * 0.6f)
            canvas.drawLine(cx, cy, cx, cy - arrowLen, aimPaint)
            val tip = 20f
            canvas.drawLine(cx, cy - arrowLen, cx - tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawLine(cx, cy - arrowLen, cx + tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawText("Basket", cx, cy - arrowLen - 12f, aimLabelPaint)
        }

        // ── Color lookup maps ──────────────────────────────────────────────
        val sessions = throws.map { it.sessionId }.distinct()
        val sessionColorMap = sessions.mapIndexed { idx, id ->
            id to sessionColors[idx % sessionColors.size]
        }.toMap()

        // ── Throw dots ─────────────────────────────────────────────────────
        for (throw_ in visibleThrows) {
            val rawBearing = DiscThrow.calculateBearing(
                throw_.startLat, throw_.startLng,
                throw_.endLat,   throw_.endLng
            )

            // Rotate to screen: use this throw's own target bearing as reference.
            // In full mode, use the global aim bearing (if set) or absolute bearing.
            val refBearing: Double = when {
                arcMode  -> throw_.targetBearing ?: globalAim?.toDouble() ?: 0.0
                globalAim != null -> globalAim.toDouble()
                else     -> 0.0  // no rotation in full mode without aim
            }

            val displayBearing = if (arcMode || globalAim != null)
                relativeBearing(rawBearing, refBearing)
            else
                rawBearing

            val distFt = throw_.distanceFeet
            val bearingRad = Math.toRadians(displayBearing)
            val dx = sin(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot
            val dy = -cos(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot

            val tx = cx + dx
            val ty = cy + dy

            val color = if (colorByDisc && throw_.discId != null) {
                discInfoMap[throw_.discId]?.second ?: sessionColorMap[throw_.sessionId] ?: discPaint.color
            } else {
                sessionColorMap[throw_.sessionId] ?: discPaint.color
            }

            linePaint.color = (color and 0x00FFFFFF) or 0x60000000
            canvas.drawLine(cx, cy, tx, ty, linePaint)
            discPaint.color = color
            canvas.drawCircle(tx, ty, 6f, discPaint)
            labelPaint.color = color
            canvas.drawText("#${throw_.throwNumber}", tx, ty - 10f, labelPaint)
        }

        // Origin dot
        canvas.drawCircle(cx, cy, 14f, originPaint)
        canvas.restore()

        // ── Fixed overlays ─────────────────────────────────────────────────
        if (colorByDisc) {
            drawDiscLegend(canvas, visibleThrows)
        } else {
            drawSessionLegend(canvas, sessions, sessionColorMap)
        }

        if (globalAim != null && !arcMode) {
            drawAimOverlay(canvas, globalAim)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns bearing of [raw] relative to [ref], in [-180, 180]. */
    private fun relativeBearing(raw: Double, ref: Double): Double {
        var rel = raw - ref
        while (rel > 180)  rel -= 360
        while (rel < -180) rel += 360
        return rel
    }

    private fun drawSessionLegend(
        canvas: Canvas,
        sessions: List<String>,
        colorMap: Map<String, Int>
    ) {
        if (sessions.size <= 1) return
        val x = 16f; var y = 60f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
        for (session in sessions) {
            dot.color = colorMap[session] ?: Color.WHITE
            canvas.drawCircle(x + 10, y, 10f, dot)
            canvas.drawText(session, x + 28, y + 9, txt)
            y += 40f
        }
    }

    private fun drawDiscLegend(canvas: Canvas, visibleThrows: List<DiscThrow>) {
        val discIds = visibleThrows.mapNotNull { it.discId }.distinct()
        if (discIds.isEmpty()) return
        val x = 16f; var y = 60f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
        for (id in discIds) {
            val info = discInfoMap[id] ?: continue
            dot.color = info.second
            canvas.drawCircle(x + 10, y, 10f, dot)
            canvas.drawText(info.first, x + 28, y + 9, txt)
            y += 40f
        }
    }

    private fun drawAimOverlay(canvas: Canvas, bearing: Float) {
        val label = "Aim: %.0f° %s".format(bearing, bearingToCardinal(bearing))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFD700")
            textSize = 26f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(label, 16f, height - 16f, p)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFFFFF")
            textSize = 42f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No throws recorded yet", width / 2f, height / 2f, p)
    }

    private fun bearingToCardinal(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((bearing + 22.5f) / 45f).toInt() % 8]
    }

    private fun resetView() {
        // Scale 1:1 so all throws (up to max distance) are visible by default.
        scaleFactor = 1f
        translateX  = 0f
        translateY  = 0f
        invalidate()
    }
}
