package com.discgolf.distance.data

import android.graphics.Color

/**
 * 96-color palette for disc selection: 8 hue columns × 12 brightness rows.
 * Rows go dark → light. Hues: Red, Orange, Yellow, Green, Cyan, Sky, Purple, Pink.
 */
object DiscColors {

    const val COLUMNS = 8

    val palette: List<Int> by lazy {
        val list = mutableListOf<Int>()
        // 8 hue families (degrees)
        val hues = floatArrayOf(0f, 30f, 60f, 120f, 180f, 210f, 270f, 330f)
        // 12 (saturation, value) brightness levels, darkest → lightest
        val levels = arrayOf(
            0.95f to 0.25f,
            0.95f to 0.35f,
            0.95f to 0.46f,
            0.95f to 0.57f,
            0.95f to 0.68f,
            0.90f to 0.78f,
            0.90f to 0.88f,
            0.85f to 0.95f,
            0.70f to 0.97f,
            0.52f to 0.98f,
            0.32f to 1.00f,
            0.16f to 1.00f
        )
        for ((s, v) in levels) {
            for (h in hues) {
                list.add(Color.HSVToColor(floatArrayOf(h, s, v)))
            }
        }
        list
    }

    /** Default disc color (vivid green, row 6, col 3) */
    val defaultColor: Int get() = palette[6 * COLUMNS + 3]
}
