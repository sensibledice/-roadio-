package kr.changbak.radio

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

enum class PositionMode(val label: String) {
    DEMO("가상 주행"), DEVICE("기기 위치"), PLEOS("Pleos 위치")
}
enum class StoryMode(val label: String) {
    SAVED("저장된 데모 음성"), LIVE("실시간 생성")
}
enum class Restriction { UNKNOWN, PARKED, RESTRICTED }

data class Point(val lat: Double, val lon: Double) {
    fun distance(other: Point): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat, lon, other.lat, other.lon, result)
        return result[0]
    }
    fun heading(other: Point): Float {
        val result = FloatArray(2)
        Location.distanceBetween(lat, lon, other.lat, other.lon, result)
        return result[1]
    }
}
data class Fix(
    val point: Point,
    val observedAt: Long,
    val accuracy: Float,
    val heading: Float? = null
) {
    fun fresh(now: Long = SystemClock.elapsedRealtime()) =
        now - observedAt in 0..10_000 && accuracy in 0f..60f

    fun aheadOf(place: Point): Boolean {
        val bearing = heading ?: return true
        val change = (point.heading(place) - bearing + 540f) % 360f - 180f
        return point.distance(place) < 45f || abs(change) <= 90f
    }
}
data class Place(val id: String, val title: String, val point: Point)
data class Source(val title: String, val url: String)
data class ChapterCue(val poiId: String, val startMs: Long, val endMs: Long)
data class StoryQuiz(val question: String, val answer: String, val startMs: Long)
data class WikiMeta(
    val id: String,
    val author: String,
    val verified: Boolean,
    val upvotes: Int,
    val status: String = "verified"
)

data class Story(
    val id: String,
    val title: String,
    val sentences: List<String>,
    val sources: List<Source>,
    val queriedAt: String,
    val generatedAt: String?,
    val uri: Uri,
    val cached: Boolean = false,
    val evidence: List<String> = emptyList(),
    val chapters: List<ChapterCue> = emptyList(),
    val quiz: StoryQuiz? = null,
    val wiki: WikiMeta? = null
)
data class Heard(val key: String, val story: Story, val time: Long)
data class Candidate(val place: Place, var nearest: Float, var story: Story? = null)

internal fun Context.rawRecording(name: String) =
    Uri.parse("android.resource://$packageName/raw/$name")

fun JSONArray.strings() = (0 until length()).map { getString(it) }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun JSONObject.chapterCues() = optJSONArray("chapters")?.objects().orEmpty().map {
    ChapterCue(it.getString("poiId"), it.getLong("startMs"), it.getLong("endMs"))
}

fun JSONObject.storyQuiz() = optJSONObject("quiz")?.let {
    StoryQuiz(it.getString("question"), it.getString("answer"), it.getLong("startMs"))
}

fun JSONObject.wikiMeta() = optJSONObject("wiki")?.let {
    WikiMeta(
        it.getString("id"),
        it.getString("author"),
        it.optBoolean("verified", true),
        it.optInt("upvotes", 0),
        it.optString("status", "verified")
    )
}

class History(context: Context) {
    private val prefs = context.getSharedPreferences("heard", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "heard").apply { mkdirs() }
    var entries: List<Heard> = runCatching {
        JSONArray(prefs.getString("entries", "[]")).objects().map { item ->
            val poiId = item.getString("poiId")
            val stored = Uri.parse(item.getString("uri"))
            val legacy = when (poiId) {
                "demo-daereungwon" -> "daereungwon_v1"
                "demo-wolji" -> "wolji_v1"
                else -> null
            }
            val uri = if (legacy != null && stored.scheme == "android.resource" && stored.pathSegments.size == 1)
                context.rawRecording(legacy) else stored
            val story = Story(poiId, item.getString("title"),
                item.getJSONArray("sentences").strings(),
                item.getJSONArray("sources").objects().map { Source(it.getString("title"), it.getString("url")) },
                item.getString("queriedAt"), item.optString("generatedAt").ifEmpty { null },
                uri, item.optBoolean("cached"), item.optJSONArray("evidence")?.strings().orEmpty(),
                item.chapterCues(), item.storyQuiz(), item.wikiMeta())
            Heard(item.getString("key"), story, item.getLong("time"))
        }.filter { it.story.uri.scheme != "file" || File(it.story.uri.path!!).isFile }
    }.getOrDefault(emptyList())
        private set

    fun remember(story: Story) {
        val key = UUID.randomUUID().toString()
        val saved = if (story.uri.scheme == "file") {
            val copy = File(directory, "$key.wav")
            File(requireNotNull(story.uri.path)).copyTo(copy)
            story.copy(uri = Uri.fromFile(copy))
        } else story
        val next = listOf(Heard(key, saved, System.currentTimeMillis())) + entries
        entries = next.take(24)
        persist()
        next.drop(24).forEach(::deleteAudio)
    }

    fun delete(entry: Heard) {
        entries = entries.filterNot { it.key == entry.key }
        persist()
        deleteAudio(entry)
    }

    private fun deleteAudio(entry: Heard) {
        entry.story.uri.path?.let { path ->
            val audio = File(path)
            if (audio.parentFile == directory) audio.delete()
        }
    }

    private fun persist() {
        val array = JSONArray()
        entries.forEach { (key, story, time) ->
            val sources = JSONArray(story.sources.map { JSONObject().put("title", it.title).put("url", it.url) })
            val chapters = JSONArray(story.chapters.map {
                JSONObject().put("poiId", it.poiId).put("startMs", it.startMs).put("endMs", it.endMs)
            })
            val quiz = story.quiz?.let {
                JSONObject().put("question", it.question).put("answer", it.answer).put("startMs", it.startMs)
            }
            val wiki = story.wiki?.let {
                JSONObject().put("id", it.id).put("author", it.author)
                    .put("verified", it.verified).put("upvotes", it.upvotes)
                    .put("status", it.status)
            }
            array.put(JSONObject().put("key", key).put("time", time).put("poiId", story.id)
                .put("title", story.title).put("sentences", JSONArray(story.sentences))
                .put("sources", sources).put("queriedAt", story.queriedAt)
                .put("generatedAt", story.generatedAt ?: "").put("uri", story.uri.toString())
                .put("cached", story.cached).put("evidence", JSONArray(story.evidence))
                .put("chapters", chapters).put("quiz", quiz).put("wiki", wiki))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }
}


