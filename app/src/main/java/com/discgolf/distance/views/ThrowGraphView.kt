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
 * - Distance rings every 100 ft up to max throw distance.
 * - Each throw is a dot at its relative (x,y) offset from origin.
 * - Supports pinch-zoom and pan.
 * - All throws in the same session share the same origin (first throw's start).
 */
class ThrowGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var throws: List<DiscThrow> = emptyList()

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
        color = Color.parseColor("#1A2A1A")  // Dark green background
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#60FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
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

        // Compute max distance for rings (in feet, rounded up to nearest 100)
        val maxFt = throws.maxOf { it.distanceFeet }
        val ringStep = 100.0  // feet per ring
        val numRings = ceil(maxFt / ringStep).toInt().coerceAtLeast(1)
        val maxRingFt = numRings * ringStep

        // Fit the outermost ring into the view with padding
        val padding = 80f
        val viewRadius = (minOf(width, height) / 2f) - padding
        val pixelsPerFoot = viewRadius / maxRingFt.toFloat()

        // Origin = centre of view + pan/zoom
        val cx = width / 2f + translateX
        val cy = height / 2f + translateY

        canvas.save()
        canvas.scale(scaleFactor, scaleFactor, cx, cy)

        // Draw rings
        for (i in 1..numRings) {
            val ringFt = i * ringStep
            val r = (ringFt * pixelsPerFoot).toFloat()
            canvas.drawCircle(cx, cy, r, ringPaint)
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
        canvas.drawLine(cx - axisLen, cy, cx + axisLen, cy, axisPaint)
        canvas.drawLine(cx, cy - axisLen, cx, cy + axisLen, axisPaint)

        // Draw throws grouped by session for colour coding
        val sessions = throws.map { it.sessionId }.distinct()
        val sessionColorMap = sessions.mapIndexed { idx, id ->
            id to sessionColors[idx % sessionColors.size]
        }.toMap()

        for (throw_ in throws) {
            val bearing = DiscThrow.calculateBearing(
                throw_.startLat, throw_.startLng,
                throw_.endLat, throw_.endLng
            )
            val distFt = throw_.distanceFeet
            val bearingRad = Math.toRadians(bearing)

            // Convert polar (bearing, distance) to canvas (x,y)
            // bearing 0=North=up, so x = sin(b)*r, y = -cos(b)*r
            val dx = sin(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot
            val dy = -cos(bearingRad).toFloat() * distFt.toFloat() * pixelsPerFoot

            val tx = cx + dx
            val ty = cy + dy

            val color = sessionColorMap[throw_.sessionId] ?: discPaint.color

            // Line from origin to disc
            linePaint.color = (color and 0x00FFFFFF) or 0x60000000
            canvas.drawLine(cx, cy, tx, ty, linePaint)

            // Disc dot
            discPaint.color = color
            canvas.drawCircle(tx, ty, 10f, discPaint)

            // Throw label
            labelPaint.color = color
            canvas.drawText(
                "#${throw_.throwNumber}",
                tx,
                ty - 14f,
                labelPaint
            )
        }

        // Origin dot (on top)
        canvas.drawCircle(cx, cy, 14f, originPaint)

        canvas.restore()

        // Legend (fixed, outside transform)
        drawLegend(canvas, sessions, sessionColorMap)
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

    private fun drawEmptyState(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFFFFF")
            textSize = 42f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No throws recorded yet", width / 2f, height / 2f, p)
    }

    private fun resetView() {
        scaleFactor = 1f
        translateX  = 0f
        translateY  = 0f
        invalidate()
    }
}
