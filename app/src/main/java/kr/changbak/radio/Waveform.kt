package kr.changbak.radio

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// All guide recordings use mono PCM16 WAV, with a canonical 44-byte header.
internal fun readWaveform(context: Context, uri: Uri, startMs: Long, endMs: Long?): List<Float> = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
    if (bytes.size < 46 || String(bytes, 0, 4) != "RIFF" || String(bytes, 36, 4) != "data") {
        return emptyList()
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.getShort(22).toInt() != 1 || buffer.getShort(34).toInt() != 16) return emptyList()
    val rate = buffer.getInt(24)
    val count = (bytes.size - 44) / 2
    val start = (startMs * rate / 1_000).toInt().coerceIn(0, count)
    val end = endMs?.let { (it * rate / 1_000).toInt().coerceIn(start, count) } ?: count
    val windows = 54
    val energy = List(windows) { i ->
        val from = start + (end - start) * i / windows
        val to = start + (end - start) * (i + 1) / windows
        var sum = 0.0
        var samples = 0
        for (index in from until to step 4) {
            val value = buffer.getShort(44 + 2 * index).toDouble()
            sum += value * value
            samples++
        }
        sqrt(sum / samples.coerceAtLeast(1))
    }
    val peak = energy.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    energy.map { sqrt(it / peak).toFloat() }
}.getOrDefault(emptyList())
