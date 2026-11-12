package kr.changbak.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF171916)
internal val Night = Color(0xFF171A17)
internal val Lime = Color(0xFFA7ED58)
internal val Leaf = Color(0xFF49DB73)
internal val PaleGreen = Color(0xFFDDF1CD)
internal val OnNight = Color(0xFFCDD4CA)
internal val CardShape = RoundedCornerShape(28.dp)
internal val InnerShape = RoundedCornerShape(19.dp)
internal val RadioGradient = Brush.horizontalGradient(listOf(Leaf, Lime))

private val Day = lightColorScheme(
    primary = Lime, onPrimary = Ink, background = Color(0xFFF8F8F5), onBackground = Ink,
    surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFEEEFEA),
    onSurfaceVariant = Color(0xFF525A50), outline = Color(0xFF9BA596), outlineVariant = Color(0xFFDFE3DA)
)
private val Evening = darkColorScheme(
    primary = Lime, onPrimary = Ink, background = Color(0xFF1B201B), onBackground = Color(0xFFF1F4ED),
    surface = Color(0xFF292E28), onSurface = Color(0xFFF1F4ED), surfaceVariant = Color(0xFF323A31),
    onSurfaceVariant = Color(0xFFCBD4C6), outline = Color(0xFF84927F), outlineVariant = Color(0xFF485546)
)

@Composable
internal fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Evening else Day, content = content)
}

internal enum class RadioIcon { RADIO, PLAY, PAUSE, HISTORY, SETTINGS, ROUTE, ARROW, BACK, STOP, LINK }

@Composable
internal fun Glyph(icon: RadioIcon, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        fun at(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        val stroke = (size.minDimension * .085f).coerceAtLeast(1f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, at(x, y), at(x2, y2), stroke, StrokeCap.Round)
        fun circle(x: Float, y: Float, radius: Float) =
            drawCircle(color, radius * size.minDimension, at(x, y), style = Stroke(stroke))
        when (icon) {
            RadioIcon.RADIO -> listOf(.28f, .57f, .91f, .65f, .32f).forEachIndexed { i, height ->
                val x = .14f + i * .18f
                line(x, .5f - height / 2, x, .5f + height / 2)
            }
            RadioIcon.PLAY -> drawPath(Path().apply {
                moveTo(size.width * .31f, size.height * .16f)
                lineTo(size.width * .85f, size.height * .5f)
                lineTo(size.width * .31f, size.height * .84f)
                close()
            }, color)
            RadioIcon.PAUSE -> {
                line(.37f, .22f, .37f, .78f)
                line(.63f, .22f, .63f, .78f)
            }
            RadioIcon.HISTORY -> {
                circle(.5f, .5f, .39f)
                line(.5f, .27f, .5f, .5f)
                line(.5f, .5f, .67f, .61f)
            }
            RadioIcon.SETTINGS -> listOf(.35f, .66f, .47f).forEachIndexed { i, x ->
                val y = .22f + i * .28f
                line(.11f, y, .89f, y)
                drawCircle(color, stroke * 1.34f, at(x, y))
            }
            RadioIcon.ROUTE -> {
                circle(.19f, .76f, .105f)
                circle(.81f, .24f, .105f)
                line(.33f, .76f, .55f, .76f)
                line(.55f, .76f, .55f, .24f)
                line(.55f, .24f, .67f, .24f)
            }
            RadioIcon.ARROW, RadioIcon.BACK -> {
                val direction = if (icon == RadioIcon.ARROW) 1 else -1
                line(.5f - .31f * direction, .5f, .5f + .29f * direction, .5f)
                line(.5f + .29f * direction, .5f, .5f + .02f * direction, .23f)
                line(.5f + .29f * direction, .5f, .5f + .02f * direction, .77f)
            }
            RadioIcon.STOP -> drawRect(color, at(.27f, .27f),
                androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .46f))
            RadioIcon.LINK -> {
                line(.34f, .66f, .73f, .27f)
                line(.49f, .27f, .73f, .27f)
                line(.73f, .27f, .73f, .51f)
                line(.23f, .49f, .23f, .79f)
                line(.23f, .79f, .53f, .79f)
            }
        }
    }
}

@Composable
internal fun Tag(label: String, accent: Boolean = false, onNight: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    val color = when {
        onNight -> Color(0xFF2E382D)
        accent && !isSystemInDarkTheme() -> PaleGreen
        else -> palette.surfaceVariant
    }
    Row(Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (accent) Box(Modifier.size(7.dp).background(if (onNight) Lime else Color(0xFF4B9C3B), CircleShape))
        Text(label, color = if (onNight) OnNight else palette.onSurface,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AccentAction(label: String, action: () -> Unit, modifier: Modifier = Modifier,
    icon: RadioIcon = RadioIcon.RADIO, enabled: Boolean = true, busy: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    val textColor = if (enabled) Ink else palette.onSurfaceVariant
    val fill = if (enabled) RadioGradient else Brush.horizontalGradient(listOf(palette.surfaceVariant, palette.surfaceVariant))
    Row(modifier.heightIn(min = 64.dp).clip(CircleShape).background(fill)
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = textColor, strokeWidth = 2.dp)
        else Glyph(icon, textColor)
        Text(label, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PlainAction(label: String, action: () -> Unit, modifier: Modifier = Modifier,
    icon: RadioIcon? = null, onNight: Boolean = false, enabled: Boolean = true) {
    val palette = MaterialTheme.colorScheme
    val color = if (onNight) Color.White else palette.onSurface
    Surface(onClick = action, modifier = modifier.heightIn(min = 56.dp), enabled = enabled,
        shape = CircleShape, color = if (onNight) Color(0xFF30372F) else palette.surface,
        contentColor = color) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Glyph(it, color, Modifier.size(17.dp)) }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AudioWave(levels: List<Float>, played: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (levels.isEmpty()) {
            drawLine(Color(0xFF4C554B), Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            return@Canvas
        }
        val gap = size.width / levels.size
        val stroke = minOf(3.dp.toPx(), gap * .46f)
        levels.forEachIndexed { i, level ->
            val x = (i + .5f) * gap
            val half = size.height * (.055f + level.coerceIn(0f, 1f) * .39f)
            val color = if (i.toFloat() / levels.size < played) {
                if (i < levels.size / 2) Leaf else Lime
            } else Color(0xFF687568)
            drawLine(color, Offset(x, size.height / 2 - half),
                Offset(x, size.height / 2 + half), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

internal fun audioTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
