package kr.changbak.radio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
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

class LiveStories(context: Context) {
    private val directory = File(context.cacheDir, "generated").apply { mkdirs() }
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
                val story = decode(response, place.id, 2_000_000)
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
            decode(response, region.journeyId, 3_200_000)
        }

    private fun decode(response: JSONObject, id: String, audioLimit: Int): Story {
        check(response.getString("poiId") == id)
        val pcm = android.util.Base64.decode(response.getString("audioBase64"), android.util.Base64.DEFAULT)
        check(pcm.size in 44..audioLimit && String(pcm, 0, 4) == "RIFF")
        val sources = response.getJSONArray("sources").objects().map {
            val url = URI(it.getString("url"))
            check(url.scheme == "https" && url.host != null && url.userInfo == null)
            Source(it.getString("title"), url.toString())
        }
        check(sources.isNotEmpty())
        val sentences = response.getJSONArray("sentences").objects()
        val factIds = sentences.flatMap { it.getJSONArray("factIds").strings() }.toSet()
        val evidence = response.getJSONArray("facts").objects()
            .filter { it.getString("factId") in factIds }
            .map { it.getString("text") }
        check(evidence.isNotEmpty())
        val duration = (pcm.size - 44L) / 48
        val chapters = response.chapterCues()
        var previousEnd = 0L
        for (chapter in chapters) {
            check(chapter.startMs >= previousEnd && chapter.endMs > chapter.startMs &&
                chapter.endMs <= duration + 1)
            previousEnd = chapter.endMs
        }
        val quiz = response.storyQuiz()
        if (quiz != null) check(quiz.startMs >= previousEnd && quiz.startMs < duration)
        val file = File.createTempFile("story-", ".wav", directory)
        file.writeBytes(pcm)
        return Story(id, response.getString("title"),
            sentences.map { it.getString("text") },
            sources, response.getString("queriedAt"), response.getString("generatedAt"),
            Uri.fromFile(file), response.getBoolean("cached"), evidence, chapters, quiz)
    }

    private fun prefix(connection: Connection) = connection.origin.trimEnd('/') + "/"

    private fun Cached.usable() = SystemClock.elapsedRealtime() < until &&
        story.uri.path?.let { File(it).exists() } == true

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
                    check(bytes.size() + size <= if (path == "/journey") 4_400_000 else 3_000_000)
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

