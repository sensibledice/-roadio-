package kr.changbak.radio

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import org.json.JSONArray

private data class MapArea(val lat: ClosedFloatingPointRange<Double>, val lon: ClosedFloatingPointRange<Double>)
private data class MapWay(val layer: String, val points: List<Point>, val name: String)

private fun readWays(context: Context, region: TripRegion): List<MapWay> {
    val data = JSONArray(context.assets.open("${region.asset}_map.json").bufferedReader().use { it.readText() })
    return (0 until data.length()).map { index ->
        val item = data.getJSONArray(index)
        val coordinates = item.getJSONArray(1)
        MapWay(item.getString(0), (0 until coordinates.length() step 2).map { offset ->
            Point(coordinates.getDouble(offset + 1), coordinates.getDouble(offset))
        }, item.optString(2))
    }
}

@Composable
internal fun TravelMap(engine: RadioEngine, state: RadioState, modifier: Modifier = Modifier,
    focusedChapter: Int = -1, showHeading: Boolean = true, showVehicle: Boolean = true) {
    val region = state.region ?: return
    val demo = state.positionMode == PositionMode.DEMO
    val fix = state.fix
    val area = if (region == TripRegion.KOREA) MapArea(35.8305..35.8450, 129.203..129.231)
        else MapArea(37.793..37.816, -122.485..-122.444)
    val covered = demo || fix == null || fix.lat in area.lat && fix.lon in area.lon
    val dark = isSystemInDarkTheme()
    val land = if (dark) Color(0xFF26302A) else Color(0xFFEAEDE5)
    val labelInk = if (dark) Color(0xFFF4F5EF) else Color(0xFF263427)
    val mapShape = RoundedCornerShape(24.dp)
    Surface(modifier, shape = mapShape, color = land, contentColor = labelInk) {
        Box(Modifier.fillMaxSize()) {
            if (covered) {
                val context = LocalContext.current
                val route = remember(engine, region) { engine.route(region) }
                val ways = remember(context, region) { readWays(context, region).groupBy { it.layer } }
                val roadLabels = remember(ways, region) {
                    val names = if (region == TripRegion.KOREA) setOf("태종로", "원화로", "임해로")
                        else setOf("Mason Street", "Lincoln Boulevard")
                    ways["main"].orEmpty().filter { it.name in names }.groupBy { it.name }
                        .mapNotNull { (name, segments) ->
                            segments.flatMap { it.points.zipWithNext() }
                                .filter { (a, b) -> a.lon in area.lon && b.lon in area.lon && a.lat in area.lat && b.lat in area.lat }
                                .maxByOrNull { (a, b) -> a.distance(b) }
                                ?.let { (a, b) -> name to Point((a.lat + b.lat) / 2, (a.lon + b.lon) / 2) }
                        }
                }
                val progress = animateFloatAsState(state.demoProgress, tween(115, easing = LinearEasing),
                    label = "가상 주행 위치")
                val markerFix = rememberUpdatedState(fix)
                val description = if (demo) "${region.city} 도로 지도와 가상 주행 위치" else "${region.city} 도로 지도와 기기 위치"
                val tint = if (dark) Color(0xFFB3ED74) else Color(0xFF2C6B37)
                val pin = if (dark) Color(0xFF18221D) else Color.White
                val water = if (dark) Color(0xFF315865) else Color(0xFFB0D8E3)
                val features = remember(route, demo, fix) {
                    if (demo || fix == null) route.points + route.places.map { it.point }
                    else listOf(fix)
                }
                Box(Modifier.fillMaxSize().semantics { contentDescription = description }.drawWithCache {
                    val view = features.ifEmpty { route.points }
                    val west = view.minOf { it.lon }
                    val east = view.maxOf { it.lon }
                    val south = view.minOf { it.lat }
                    val north = view.maxOf { it.lat }
                    val mid = Point((south + north) / 2, (west + east) / 2)
                    val lonScale = cos(Math.toRadians(mid.lat)).toFloat()
                    val scale = minOf(size.width / (maxOf(.012, (east - west) * 1.21).toFloat() * lonScale),
                        size.height / maxOf(.008, (north - south) * 1.38).toFloat())
                    fun project(point: Point) = Offset(size.width / 2f + (point.lon - mid.lon).toFloat() * lonScale * scale,
                        size.height / 2f - (point.lat - mid.lat).toFloat() * scale)
                    fun geometry(points: List<Point>, closed: Boolean = false) = Path().apply {
                        points.forEachIndexed { i, point ->
                            val p = project(point)
                            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                        if (closed) close()
                    }
                    val polygons = setOf("sea", "island", "green", "heritage", "water")
                    val layers = ways.mapValues { (layer, group) ->
                        Path().apply { group.forEach { addPath(geometry(it.points, layer in polygons)) } }
                    }
                    val line = geometry(route.points)
                    val painter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        strokeWidth = 3.5.dp.toPx()
                        strokeJoin = Paint.Join.ROUND
                    }
                    val labelPoints = roadLabels.map { (name, point) -> name to project(point) }
                    val stopPoints = route.places.map { it.title to project(it.point) }
                    val stream = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val walking = Stroke(.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val local = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val outline = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val major = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val routeOutline = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    onDrawBehind {
                        fun fill(layer: String, color: Color) {
                            layers[layer]?.let { drawPath(it, color) }
                        }
                        fun lines(layer: String, color: Color, width: Stroke) {
                            layers[layer]?.let { drawPath(it, color, style = width) }
                        }
                        fun label(name: String, x: Float, y: Float, textSize: Float) {
                            painter.textSize = textSize
                            val inset = (painter.measureText(name) / 2 + 6.dp.toPx()).coerceAtMost(size.width / 2)
                            val center = x.coerceIn(inset, size.width - inset)
                            painter.style = Paint.Style.STROKE
                            painter.color = land.toArgb()
                            drawContext.canvas.nativeCanvas.drawText(name, center, y, painter)
                            painter.style = Paint.Style.FILL
                            painter.color = labelInk.toArgb()
                            drawContext.canvas.nativeCanvas.drawText(name, center, y, painter)
                        }
                        fill("sea", water)
                        fill("island", land)
                        fill("green", if (dark) Color(0xFF344936) else Color(0xFFD3E2C5))
                        fill("heritage", if (dark) Color(0xFF49503A) else Color(0xFFE5DFC3))
                        lines("stream", water, stream)
                        fill("water", water)
                        lines("path", if (dark) Color(0xFF566350) else Color(0xFFD0D8CA), walking)
                        lines("street", if (dark) Color(0xFF4F5B53) else Color(0xFFFDFDFB), local)
                        lines("main", if (dark) Color(0xFF373F36) else Color(0xFFC9CBBF), outline)
                        lines("main", if (dark) Color(0xFF757965) else Color(0xFFFFF4D6), major)
                        if (demo) {
                            drawPath(line, pin, style = routeOutline)
                            drawPath(line, tint, style = major)
                        }
                        labelPoints.forEach { (name, p) -> label(name, p.x, p.y - 10.dp.toPx(), 11.sp.toPx()) }
                        stopPoints.forEachIndexed { index, (name, p) ->
                            if (p.x in 0f..size.width && p.y in 0f..size.height) {
                                val focused = demo && focusedChapter == index
                                if (focused) drawCircle(Color(0x6556CC57), 23.dp.toPx(), p)
                                drawCircle(pin, (if (focused) 14 else 10).dp.toPx(), p)
                                drawCircle(if (focused) Color(0xFF2B782E) else tint,
                                    (if (focused) 11f else 7.5f).dp.toPx(), p)
                                if (demo) {
                                    painter.textSize = 9.sp.toPx()
                                    painter.style = Paint.Style.FILL
                                    painter.color = (if (focused) Color.White else pin).toArgb()
                                    drawContext.canvas.nativeCanvas.drawText((index + 1).toString(), p.x, p.y + 3.dp.toPx(), painter)
                                }
                                label(name, p.x, p.y + 26.dp.toPx(), 13.sp.toPx())
                            }
                        }
                        val position = if (demo) route.position(progress.value) else markerFix.value
                        position?.takeIf { showVehicle }?.let {
                            val p = project(it)
                            drawCircle(Color(0x40458FF3), 16.dp.toPx(), p)
                            drawCircle(Color.White, 9.dp.toPx(), p)
                            drawCircle(Color(0xFF226ED5), 6.5.dp.toPx(), p)
                        }
                    }
                })
                if (showHeading) MapTag(when {
                    demo -> "${region.city} 가상 경로"
                    fix == null -> "${region.city} · 위치 신호 대기 중"
                    else -> "${region.city} · 현재 위치"
                }, Modifier.align(Alignment.TopStart).padding(12.dp))
                MapTag("© OpenStreetMap contributors", Modifier.align(Alignment.BottomEnd).padding(8.dp), small = true)
            } else {
                Column(Modifier.align(Alignment.Center).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${region.city} 지도 범위 밖", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("주변 여행 이야기는 계속 찾아요", fontSize = 14.sp)
                    state.navigation.destination?.let { Text("목적지  $it", fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun MapTag(text: String, modifier: Modifier, small: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(text, Modifier.padding(horizontal = if (small) 7.dp else 12.dp, vertical = if (small) 3.dp else 8.dp),
            fontWeight = if (small) FontWeight.Normal else FontWeight.Bold, fontSize = if (small) 10.sp else 15.sp)
    }
}
