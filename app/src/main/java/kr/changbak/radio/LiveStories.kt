package kr.changbak.radio

import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

data class Connection(val origin: String = "", val token: String = "") {
    fun valid(debug: Boolean): Boolean {
        val url = runCatching { URI(origin) }.getOrNull() ?: return false
        if (url.host == null || url.userInfo != null || url.rawQuery != null ||
            url.rawFragment != null || url.rawPath !in listOf("", "/")) return false
        return url.scheme == "https" || debug && url.scheme == "http" &&
            url.host in listOf("10.0.2.2", "127.0.0.1", "localhost")
    }
}

class LiveStories {
    private data class Cached(val place: Place, val story: Story, val until: Long)
    private val recent = linkedMapOf<String, Cached>()

    suspend fun nearby(connection: Connection, region: TripRegion, point: Point): List<Place> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = request(connection, "/nearby?lat=${point.lat}&lon=${point.lon}&region=${region.id}")
                response.getJSONArray("places").objects().map {
                    Place(it.getString("poiId"), it.getString("title"),
                        Point(it.getDouble("lat"), it.getDouble("lon")))
                }
            } catch (error: java.io.IOException) {
                if (error is ServerResponse && error.status < 500) throw error
                val fallback = synchronized(recent) {
                    recent.filter { (key, cached) ->
                        key.startsWith(prefix(connection)) && cached.usable() &&
                            cached.place.point.distance(point) <= 1_200
                    }.values.map { it.place }
                }
                if (fallback.isEmpty()) throw error
                fallback
            }
        }

    suspend fun story(connection: Connection, place: Place, point: Point): Story =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject().put("poiId", place.id).put("lat", point.lat).put("lon", point.lon)
            val key = prefix(connection) + place.id
            try {
                val response = request(connection, "/stories", body)
                val story = decode(response, place.id)
                synchronized(recent) {
                    recent[key] = Cached(place, story, SystemClock.elapsedRealtime() + 600_000)
                    if (recent.size > 16) recent.remove(recent.keys.first())
                }
                story
            } catch (error: java.io.IOException) {
                if (error is ServerResponse && error.status < 500) throw error
                val cached = synchronized(recent) { recent[key] }
                if (cached != null && cached.usable()) {
                    cached.story.copy(cached = true)
                } else throw error
            }
        }

    suspend fun journey(connection: Connection, region: TripRegion): Story =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = request(connection, "/journey", JSONObject().put("region", region.id))
            decode(response, region.journeyId)
        }

    private fun decode(response: JSONObject, id: String): Story {
        check(response.getString("poiId") == id)
        val sources = response.getJSONArray("sources").objects().map {
            val url = URI(it.getString("url"))
            check(url.scheme == "https" && url.host != null && url.userInfo == null)
            Source(it.getString("title"), url.toString())
        }
        check(sources.isNotEmpty())
        val sentences = response.getJSONArray("sentences").objects()
        val texts = sentences.map { it.getString("text") }
        check(texts.isNotEmpty() && texts.all { it.isNotBlank() && it.length <= 400 })
        val factIds = sentences.flatMap { it.getJSONArray("factIds").strings() }.toSet()
        val evidence = response.getJSONArray("facts").objects()
            .filter { it.getString("factId") in factIds }
            .map { it.getString("text") }
        check(evidence.isNotEmpty())
        // 서버는 대본만 보낸다. 장면 길이는 읽기 속도로 추정하고, 재생 중에는 덩이가 끝날 때 맞춘다.
        val sent = response.optJSONObject("quiz")
        val parts = narration(texts, sent?.let { StoryQuiz(it.getString("question"), it.getString("answer"), 0) })
        val ids = response.optJSONArray("chapters")?.objects().orEmpty().map { it.getString("poiId") }
        check(ids.isEmpty() || ids.size == texts.size)
        var elapsed = 0L
        val chapters = ids.mapIndexed { i, poiId ->
            ChapterCue(poiId, elapsed, elapsed + parts[i].estimateMs).also { elapsed = it.endMs }
        }
        val quiz = sent?.let { StoryQuiz(it.getString("question"), it.getString("answer"), elapsed) }
        return Story(id, response.getString("title"), texts, sources, response.getString("queriedAt"),
            response.getString("generatedAt"), Uri.EMPTY, response.getBoolean("cached"), evidence, chapters, quiz)
    }

    private fun prefix(connection: Connection) = connection.origin.trimEnd('/') + "/"

    private fun Cached.usable() = SystemClock.elapsedRealtime() < until

    private fun request(connection: Connection, path: String, body: JSONObject? = null): JSONObject {
        val url = URI(connection.origin.trimEnd('/') + path).toURL()
        val http = url.openConnection() as HttpURLConnection
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = 2500
            http.readTimeout = when {
                path == "/journey" -> 47_000
                body == null -> 3_500
                else -> 11_000
            }
            if (connection.token.isNotBlank()) http.setRequestProperty("Authorization", "Bearer ${connection.token}")
            if (body != null) {
                http.requestMethod = "POST"
                http.setRequestProperty("Content-Type", "application/json")
                http.doOutput = true
                http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (http.responseCode != 200) throw ServerResponse(http.responseCode)
            return http.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size == -1) break
                    check(bytes.size() + size <= 200_000)
                    bytes.write(buffer, 0, size)
                }
                JSONObject(bytes.toString("UTF-8"))
            }
        } finally {
            http.disconnect()
        }
    }
}

class ServerResponse(val status: Int) : java.io.IOException("HTTP $status")

