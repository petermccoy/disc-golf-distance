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
 * Canvas view that renders all throws relative to their start points.
 *
 * - Distance rings every 100 ft (full circles or 60° arc when arcMode=true).
 * - Each throw is a dot at its relative (x,y) offset from origin.
 * - When targetBearing is set, that direction rotates to the top ("up").
 * - arcMode shows a ±30° wedge; when targetBearing is null, North is used.
 * - colorByDisc: colors throws by their disc color instead of session color.
 * - Supports pinch-zoom and pan.
 */
class ThrowGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var throws: List<DiscThrow> = emptyList()

    /** Compass bearing (degrees) toward the basket. Null = North-up. */
    var targetBearing: Float? = null
        set(value) { field = value; resetView() }

    /** When true, show only a ±30° arc instead of full 360°. */
    var arcMode: Boolean = false
        set(value) { field = value; resetView() }

    /**
     * When true, color each throw dot by its disc color instead of session color.
     * Populate [discInfoMap] with disc data for colors and legend labels.
     */
    var colorByDisc: Boolean = false
        set(value) { field = value; invalidate() }

    /** discId → (display name, ARGB color). Populate from the disc list. */
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

    // Session colours (cycles)
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

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
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

        // When arcMode is on and no bearing was set, fall back to North (0°).
        val aim = if (arcMode) (targetBearing ?: 0f) else targetBearing

        // In arcMode, filter to ±30° of the effective aim direction.
        val visibleThrows = if (arcMode && aim != null) {
            throws.filter { t ->
                val raw = DiscThrow.calculateBearing(t.startLat, t.startLng, t.endLat, t.endLng)
                val rel = relativeBearing(raw, aim.toDouble())
                abs(rel) <= 30.0
            }
        } else {
            throws
        }

        val maxFt = visibleThrows.maxOfOrNull { it.distanceFeet }
            ?: throws.maxOf { it.distanceFeet }
        val ringStep = 100.0
        val numRings = ceil(maxFt / ringStep).toInt().coerceAtLeast(1)
        val maxRingFt = numRings * ringStep

        val padding = 80f
        val viewRadius = (minOf(width, height) / 2f) - padding
        val pixelsPerFoot = viewRadius / maxRingFt.toFloat()

        val cx = width / 2f + translateX
        val cy = height / 2f + translateY

        canvas.save()
        canvas.scale(scaleFactor, scaleFactor, cx, cy)

        // Arc wedge fill (arcMode only) – 60° wide (±30°)
        // Canvas angles: 0°=right, 90°=down, -90°=up.
        // ±30° around "up" (-90°): left edge = -120°, right edge = -60°, sweep = 60°.
        if (arcMode && aim != null) {
            val outerR = (maxRingFt * pixelsPerFoot).toFloat()
            val wedgePath = Path().apply {
                moveTo(cx, cy)
                arcTo(RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
                    -120f, 60f, false)
                close()
            }
            canvas.drawPath(wedgePath, aimFillPaint)
        }

        // Distance rings: 50 ft intermediate (lighter) + 100 ft major (labeled)
        for (i in 1..numRings) {
            val midFt = (i - 1) * ringStep + 50.0
            val r50 = (midFt * pixelsPerFoot).toFloat()
            if (arcMode && aim != null) {
                canvas.drawArc(RectF(cx - r50, cy - r50, cx + r50, cy + r50),
                    -120f, 60f, false, ringPaint50)
            } else {
                canvas.drawCircle(cx, cy, r50, ringPaint50)
            }

            val ringFt = i * ringStep
            val r = (ringFt * pixelsPerFoot).toFloat()
            if (arcMode && aim != null) {
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r),
                    -120f, 60f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            canvas.drawText("${ringFt.toInt()} ft", cx + 6f, cy - r + ringLabelPaint.textSize, ringLabelPaint)
        }

        // Axes / wedge edges
        val axisLen = (maxRingFt * pixelsPerFoot).toFloat()
        if (arcMode && aim != null) {
            // Two bounding radii for the ±30° wedge
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

        // Aim arrow (target bearing = top of graph)
        if (aim != null) {
            val arrowLen = axisLen.coerceAtMost(viewRadius + padding * 0.6f)
            canvas.drawLine(cx, cy, cx, cy - arrowLen, aimPaint)
            val tip = 20f
            canvas.drawLine(cx, cy - arrowLen, cx - tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawLine(cx, cy - arrowLen, cx + tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawText("Basket", cx, cy - arrowLen - 12f, aimLabelPaint)
        }

        // Build color lookup
        val sessions = throws.map { it.sessionId }.distinct()
        val sessionColorMap = sessions.mapIndexed { idx, id ->
            id to sessionColors[idx % sessionColors.size]
        }.toMap()

        // Draw throw dots
        for (throw_ in visibleThrows) {
            val rawBearing = DiscThrow.calculateBearing(
                throw_.startLat, throw_.startLng,
                throw_.endLat, throw_.endLng
            )
            val displayBearing = if (aim != null) relativeBearing(rawBearing, aim.toDouble())
                                 else rawBearing
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

        // Fixed overlays (not affected by transform)
        if (colorByDisc) {
            drawDiscLegend(canvas, visibleThrows)
        } else {
            drawSessionLegend(canvas, sessions, sessionColorMap)
        }

        if (aim != null) {
            drawAimOverlay(canvas, aim)
        }
    }

    private fun relativeBearing(rawBearing: Double, referenceBearing: Double): Double {
        var rel = rawBearing - referenceBearing
        while (rel > 180) rel -= 360
        while (rel < -180) rel += 360
        return rel
    }

    private fun drawSessionLegend(
        canvas: Canvas,
        sessions: List<String>,
        colorMap: Map<String, Int>
    ) {
        if (sessions.size <= 1) return
        val x = 16f
        var y = 60f
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
        val x = 16f
        var y = 60f
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
        // Arc mode starts more zoomed-in so the ±30° wedge fills the screen.
        scaleFactor = if (arcMode) 2.5f else 1f
        translateX  = 0f
        translateY  = 0f
        invalidate()
    }
}
