package com.arcoregeo.campoar.ui.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.data.openRing
import com.arcoregeo.campoar.geo.DevicePose
import com.arcoregeo.campoar.geo.GeoMath
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class MiniMapDot(val east: Double, val north: Double)

internal data class MiniMapSketch(
    val rings: List<List<MiniMapDot>>,
    val lines: List<List<MiniMapDot>>,
    val points: List<MiniMapDot>,
    val headingDeg: Float,
) {
    val all: List<MiniMapDot>
        get() = rings.flatten() + lines.flatten() + points
}

/**
 * Ground plan of the KMZ/KML relative to the GPS fix. The canvas then turns it
 * heading-up so the wedge is always "where the phone is looking".
 */
internal fun miniMapSketch(document: KmzDocument, viewer: LatLngAlt, headingDeg: Float): MiniMapSketch {
    fun at(coord: LatLngAlt): MiniMapDot {
        val enu = GeoMath.toEnu(viewer, coord)
        return MiniMapDot(enu.east, enu.north)
    }
    return MiniMapSketch(
        rings = document.polygons.map { openRing(it.ring).map(::at) }.filter { it.size >= 2 },
        lines = document.lines.map { it.coordinates.map(::at) }.filter { it.size >= 2 },
        points = document.points.map { at(it.coordinate) },
        headingDeg = headingDeg,
    )
}

/** Rotate ENU so [headingDeg] (compass) points "up" on the map. */
internal fun headingUp(east: Double, north: Double, headingDeg: Float): Pair<Double, Double> {
    val h = Math.toRadians(headingDeg.toDouble())
    val c = cos(h)
    val s = sin(h)
    return east * c - north * s to east * s + north * c
}

internal fun miniMapPixelsPerMetre(dots: List<MiniMapDot>, sizePx: Float, padPx: Float): Float {
    val reach = dots.maxOfOrNull { hypot(it.east, it.north) }?.coerceAtLeast(8.0) ?: 8.0
    val usable = (sizePx / 2f - padPx).coerceAtLeast(8f)
    return (usable / reach).toFloat()
}

internal fun miniMapCanvas(
    east: Double,
    north: Double,
    headingDeg: Float,
    centerX: Float,
    centerY: Float,
    pixelsPerMetre: Float,
): Offset {
    val (x, y) = headingUp(east, north, headingDeg)
    return Offset(
        centerX + (x * pixelsPerMetre).toFloat(),
        centerY - (y * pixelsPerMetre).toFloat(),
    )
}

@Composable
internal fun ArMiniMap(
    document: KmzDocument,
    pose: DevicePose,
    modifier: Modifier = Modifier,
) {
    val sketch = miniMapSketch(document, pose.coordinate, pose.headingDegrees)
    Box(
        modifier = modifier
            .size(148.dp)
            .background(Color(0xCC0F172A), RoundedCornerShape(12.dp))
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.size(136.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = miniMapPixelsPerMetre(sketch.all, size.minDimension, padPx = 16f)
            drawCircle(Color(0x661E293B), radius = size.minDimension / 2f)
            drawCircle(Color(0xFF64748B), radius = size.minDimension / 2f, style = Stroke(2f))

            fun pt(dot: MiniMapDot) = miniMapCanvas(
                dot.east, dot.north, sketch.headingDeg, cx, cy, scale,
            )

            sketch.rings.forEach { ring ->
                if (ring.size < 2) return@forEach
                val path = Path()
                val first = pt(ring.first())
                path.moveTo(first.x, first.y)
                ring.drop(1).forEach { path.lineTo(pt(it).x, pt(it).y) }
                path.close()
                drawPath(path, Color(0x6638BDF8))
                drawPath(path, Color(0xFF7DD3FC), style = Stroke(3f))
            }
            sketch.lines.forEach { line ->
                for (i in 0 until line.lastIndex) {
                    drawLine(Color(0xFFF472B6), pt(line[i]), pt(line[i + 1]), strokeWidth = 4f)
                }
            }
            sketch.points.forEach { drawCircle(Color(0xFF22D3EE), radius = 5f, center = pt(it)) }

            val you = Path().apply {
                moveTo(cx, cy - 14f)
                lineTo(cx + 9f, cy + 10f)
                lineTo(cx, cy + 5f)
                lineTo(cx - 9f, cy + 10f)
                close()
            }
            drawPath(you, Color(0xFF34D399))

            val (nx, ny) = headingUp(0.0, 1.0, sketch.headingDeg)
            val rim = size.minDimension / 2f - 10f
            drawCircle(
                Color(0xFFFDE68A),
                radius = 5f,
                center = Offset(cx + (nx * rim).toFloat(), cy - (ny * rim).toFloat()),
            )
        }
        Text(
            "TÚ",
            color = Color(0xFF86EFAC),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
