package kr.changbak.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class TripRegion(val id: String, val label: String, val city: String, val asset: String) {
    KOREA("kr", "국내 · 한국", "경주", "gyeongju"),
    CALIFORNIA("ca", "국외 · 캘리포니아", "샌프란시스코", "california");

    val journeyId get() = "journey-$id"
    val defaultPosition get() = if (this == KOREA) PositionMode.DEMO else PositionMode.DEVICE
}

data class RouteChapter(
    val place: Place, val text: String, val source: Source, val facts: List<String>,
    val startMs: Long, val endMs: Long
)

class DemoRoute(context: Context, val region: TripRegion) {
    private val guide = JSONObject(context.assets.open("journeys.json").bufferedReader().use { it.readText() })
        .getJSONObject(region.id)
    private val stops = guide.getJSONArray("stops").objects()
    private val script = guide.getJSONArray("script").strings()
    private val audio = JSONObject(context.assets.open("journey_audio.json").bufferedReader().use { it.readText() })
        .getJSONObject(region.id)
    val durationMs = audio.getLong("durationMs")
    val waveform = audio.getJSONArray("waveform").let { values ->
        (0 until values.length()).map { values.getDouble(it).toFloat() }
    }
    val chapters = stops.mapIndexed { i, stop ->
        val source = stop.getJSONObject("source")
        val times = audio.getJSONArray("chapters").getJSONArray(i)
        RouteChapter(
            place = Place(stop.getString("poiId"), stop.getString("title"),
                Point(stop.getDouble("lat"), stop.getDouble("lon"))),
            text = script[i + 1],
            source = Source(source.getString("title"), source.getString("url")),
            facts = stop.getJSONArray("facts").strings(),
            startMs = times.getLong(0),
            endMs = times.getLong(1)
        )
    }
    val places = chapters.map { it.place }
    val story = Story(region.journeyId, guide.getString("title"), script, chapters.map { it.source },
        "2026-11-12", null, context.rawRecording("journey_story_${region.id}"),
        evidence = chapters.flatMap { it.facts },
        chapters = chapters.map { ChapterCue(it.place.id, it.startMs, it.endMs) },
        quiz = guide.getJSONObject("quiz").let {
            StoryQuiz(it.getString("question"), it.getString("answer"), audio.getLong("quizStartMs"))
        })

    fun chaptersFor(episode: Story): List<RouteChapter> {
        if (episode.generatedAt == null || episode.chapters.map { it.poiId } != places.map { it.id } ||
            episode.sentences.size != chapters.size) return chapters
        return chapters.mapIndexed { index, chapter ->
            val cue = episode.chapters[index]
            chapter.copy(text = episode.sentences[index], facts = chapter.facts.filter { it in episode.evidence },
                startMs = cue.startMs, endMs = cue.endMs)
        }
    }

    val points = JSONArray(context.assets.open("${region.asset}_route.json").bufferedReader().use { it.readText() })
        .let { coordinates ->
            (0 until coordinates.length()).map { index ->
                val coordinate = coordinates.getJSONArray(index)
                Point(coordinate.getDouble(1), coordinate.getDouble(0))
            }
        }
    private val distance = points.zipWithNext { a, b -> a.distance(b) }.runningFold(0f, Float::plus)
    val meters = distance.last()
    val stopFractions = places.map { place ->
        distance[points.indices.minBy { points[it].distance(place.point) }] / meters
    }

    fun position(fraction: Float): Point {
        val traveled = fraction.coerceIn(0f, 1f) * meters
        val end = distance.binarySearch(traveled)
        if (end >= 0) return points[end]
        val next = -end - 1
        val start = next - 1
        val mix = (traveled - distance[start]) / (distance[next] - distance[start])
        return Point(points[start].lat + (points[next].lat - points[start].lat) * mix,
            points[start].lon + (points[next].lon - points[start].lon) * mix)
    }
}
