package com.filispeen.Wellcam.camera

import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SizeGroup(val ratio: String, val sizes: List<Size>)

private class KnownRatio(val label: String, val value: Float)

private val KnownRatios = listOf(
    KnownRatio("16:9", 16f / 9f),
    KnownRatio("4:3", 4f / 3f),
    KnownRatio("3:2", 3f / 2f),
    KnownRatio("1:1", 1f),
    KnownRatio("16:10", 16f / 10f),
    KnownRatio("5:4", 5f / 4f),
    KnownRatio("2:1", 2f),
    KnownRatio("19.5:9", 19.5f / 9f),
    KnownRatio("21:9", 21f / 9f)
)

internal fun aspectLabel(size: Size): String {
    val ratio = max(size.width, size.height).toFloat() / min(size.width, size.height)
    val known = KnownRatios.firstOrNull { abs(it.value - ratio) < 0.02f }
    return known?.label ?: String.format(Locale.US, "%.2f:1", ratio)
}

fun groupByAspect(sizes: List<Size>): List<SizeGroup> {
    val order = KnownRatios.map { it.label }
    return sizes
        .distinct()
        .groupBy { aspectLabel(it) }
        .map { (label, list) ->
            SizeGroup(label, list.sortedByDescending { it.width.toLong() * it.height })
        }
        .sortedWith(
            compareBy<SizeGroup>(
                { group -> order.indexOf(group.ratio).let { if (it < 0) Int.MAX_VALUE else it } },
                { group -> group.ratio }
            )
        )
}

fun defaultSize(sizes: List<Size>): Size? {
    val groups = groupByAspect(sizes)
    val group = groups.firstOrNull { it.ratio == "16:9" } ?: groups.firstOrNull()
    return group?.sizes?.firstOrNull()
}

fun defaultFps(values: List<Int>): Int? =
    values.filter { it <= 60 }.maxOrNull() ?: values.maxOrNull()
