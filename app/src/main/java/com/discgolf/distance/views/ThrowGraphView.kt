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
 * - Distance rings every 100 ft (full circles or 90° arc when arcMode=true).
 * - Each throw is a dot at its relative (x,y) offset from origin.
 * - When targetBearing is set, that direction rotates to the top ("up").
 * - arcMode filters throws to ±45° of the target and draws only the arc wedge.
 * - Supports pinch-zoom and pan.
 */
class ThrowGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var throws: List<DiscThrow> = emptyList()

    /** Compass bearing (degrees) that points toward the basket. Null = North-up. */
    var targetBearing: Float? = null
        set(value) { field = value; resetView() }

    /** When true, show only a 90° arc (±45° around target) instead of full 360°. */
    var arcMode: Boolean = false
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
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        textSize = 28f
        textAlign = Paint.Align.LEFT
    }
    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD700")  // Gold
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")  // Green
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#6000E676")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
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
        color = Color.parseColor("#FFFFD700")  // Gold, matches origin
    }
    private val aimFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#40FFD700")
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

        val aim = targetBearing

        // When arcMode is on, filter to ±45° of target
        val visibleThrows = if (arcMode && aim != null) {
            throws.filter { t ->
                val raw = DiscThrow.calculateBearing(t.startLat, t.startLng, t.endLat, t.endLng)
                val rel = relativeBearing(raw, aim.toDouble())
                abs(rel) <= 45.0
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

        // Draw arc wedge fill (only in arcMode)
        if (arcMode && aim != null) {
            val outerR = (maxRingFt * pixelsPerFoot).toFloat()
            val wedgePath = Path().apply {
                moveTo(cx, cy)
                // Arc from -135° to -45° in canvas coords (±45° around "up")
                arcTo(RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
                    -135f, 90f, false)
                close()
            }
            canvas.drawPath(wedgePath, aimFillPaint)
        }

        // Draw rings (full circles or arcs)
        for (i in 1..numRings) {
            val ringFt = i * ringStep
            val r = (ringFt * pixelsPerFoot).toFloat()
            if (arcMode && aim != null) {
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -135f, 90f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            // Label at top of ring
            canvas.drawText(
                "${ringFt.toInt()} ft",
                cx + 6f,
                cy - r + ringLabelPaint.textSize,
                ringLabelPaint
            )
        }

        // Draw compass axes
        val axisLen = (maxRingFt * pixelsPerFoot).toFloat()
        if (arcMode && aim != null) {
            // Only draw the two bounding radii of the 90° wedge
            val leftRad = Math.toRadians(-135.0)   // canvas angle for NW (-45° bearing)
            val rightRad = Math.toRadians(-45.0)   // canvas angle for NE (+45° bearing)
            canvas.drawLine(cx, cy,
                cx + (cos(leftRad) * axisLen).toFloat(),
                cy + (sin(leftRad) * axisLen).toFloat(), axisPaint)
            canvas.drawLine(cx, cy,
                cx + (cos(rightRad) * axisLen).toFloat(),
                cy + (sin(rightRad) * axisLen).toFloat(), axisPaint)
        } else {
            canvas.drawLine(cx - axisLen, cy, cx + axisLen, cy, axisPaint)
            canvas.drawLine(cx, cy - axisLen, cx, cy + axisLen, axisPaint)
        }

        // Draw aim direction arrow (target bearing = top of graph)
        if (aim != null) {
            val arrowLen = axisLen.coerceAtMost(viewRadius + padding * 0.6f)
            // Arrow points straight up (canvas y decreases)
            canvas.drawLine(cx, cy, cx, cy - arrowLen, aimPaint)
            // Arrowhead
            val tip = 20f
            canvas.drawLine(cx, cy - arrowLen, cx - tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawLine(cx, cy - arrowLen, cx + tip, cy - arrowLen + tip * 1.5f, aimPaint)
            canvas.drawText("Basket", cx, cy - arrowLen - 12f, aimLabelPaint)
        }

        // Draw throws grouped by session
        val sessions = throws.map { it.sessionId }.distinct()
        val sessionColorMap = sessions.mapIndexed { idx, id ->
            id to sessionColors[idx % sessionColors.size]
        }.toMap()

        for (throw_ in visibleThrows) {
            val rawBearing = DiscThrow.calculateBearing(
                throw_.startLat, throw_.startLng,
                throw_.endLat, throw_.endLng
            )
            // Adjust bearing relative to aim direction (0° = aim direction = up)
            val displayBearing = if (aim != null) relativeBearing(rawBearing, aim.toDouble())
                                 else rawBearing
            val distFt = throw_.distanceFeet
            val bearingRad = Math.toRadians(displayBearing)

            val dx = sin(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot
            val dy = -cos(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot

            val tx = cx + dx
            val ty = cy + dy

            val color = sessionColorMap[throw_.sessionId] ?: discPaint.color

            linePaint.color = (color and 0x00FFFFFF) or 0x60000000
            canvas.drawLine(cx, cy, tx, ty, linePaint)

            discPaint.color = color
            canvas.drawCircle(tx, ty, 10f, discPaint)

            labelPaint.color = color
            canvas.drawText("#${throw_.throwNumber}", tx, ty - 14f, labelPaint)
        }

        // Origin dot (on top of everything)
        canvas.drawCircle(cx, cy, 14f, originPaint)

        canvas.restore()

        // Legend (fixed, outside transform)
        drawLegend(canvas, sessions, sessionColorMap)

        // Aim label overlay (fixed position, bottom-left)
        if (aim != null) {
            drawAimOverlay(canvas, aim)
        }
    }

    /**
     * Returns the bearing of [rawBearing] relative to [referenceBearing],
     * in the range [-180, 180]. Positive = clockwise from reference.
     */
    private fun relativeBearing(rawBearing: Double, referenceBearing: Double): Double {
        var rel = rawBearing - referenceBearing
        while (rel > 180) rel -= 360
        while (rel < -180) rel += 360
        return rel
    }

    private fun drawLegend(
        canvas: Canvas,
        sessions: List<String>,
        colorMap: Map<String, Int>
    ) {
        if (sessions.size <= 1) return
        val x = 16f
        var y = 60f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 28f
        }
        for (session in sessions) {
            dot.color = colorMap[session] ?: Color.WHITE
            canvas.drawCircle(x + 10, y, 10f, dot)
            canvas.drawText(session, x + 28, y + 9, txt)
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
        scaleFactor = 1f
        translateX  = 0f
        translateY  = 0f
        invalidate()
    }
}
