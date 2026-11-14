package kr.changbak.radio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** 읽을 한 덩이. gapMs는 읽기 전에 두는 쉼이다. */
data class Utterance(val text: String, val gapMs: Long) {
    // ponytail: 합성 길이는 알 수 없어 글자 수로 추정한다(저장 시연 실측 약 140ms/자). 덩이가 끝나는 순간 실제 위치로 맞춘다.
    val estimateMs get() = gapMs + text.length * 140L
}

/** 장면들을 차례로, 퀴즈가 있으면 질문 뒤 1.6초를 비우고 정답을 읽는다. */
fun narration(sentences: List<String>, quiz: StoryQuiz?): List<Utterance> =
    sentences.map { Utterance(it, 180) } +
        listOfNotNull(quiz?.let { Utterance(it.question, 360) }, quiz?.let { Utterance(it.answer, 1600) })

/** 한국어 읽어주기 엔진. Pleos 빌드는 Pleos TTS, 그 밖에는 기기 TTS를 쓴다. */
interface Voice {
    /** 읽는 소리 크기(0 이상)가 들어올 때마다 부른다. 엔진이 알려주지 않으면 부르지 않는다. */
    var onLevel: ((Float) -> Unit)?
    /** 다 읽으면 done(true), 읽지 못하면 done(false)를 정확히 한 번 부른다. 메인 스레드가 아닐 수 있다. */
    fun speak(text: String, done: (Boolean) -> Unit)
    fun stop()
    fun release()
}

fun newVoice(context: Context): Voice = runCatching {
    // The SDK adapter is only compiled when withPleos is enabled.
    Class.forName("kr.changbak.radio.PleosVoice").getConstructor(Context::class.java)
        .newInstance(context) as Voice
}.getOrElse { SystemVoice(context) }

internal val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

class SystemVoice(context: Context) : Voice {
    override var onLevel: ((Float) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ready: Boolean? = null // null: 준비 중
    private var waiting: (() -> Unit)? = null
    private var done: ((Boolean) -> Unit)? = null
    private var utterance = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS &&
                    tts.setLanguage(Locale.KOREA).let { it >= TextToSpeech.LANG_AVAILABLE }
                if (ready == true) {
                    tts.setAudioAttributes(speechAttributes)
                    tts.setSpeechRate(0.96f)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { finish(utteranceId, false) }
                        override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
                    })
                }
                waiting?.invoke()
                waiting = null
            }
        }
    }

    private fun finish(id: String?, ok: Boolean) {
        if (id != "u$utterance") return
        val callback = done ?: return
        done = null
        callback(ok)
    }

    override fun speak(text: String, done: (Boolean) -> Unit) {
        this.done = done
        val id = "u${++utterance}"
        val go: () -> Unit = {
            if (ready != true || tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                this.done = null
                done(false)
            }
        }
        if (ready == null) waiting = go else go()
    }

    override fun stop() {
        done = null
        waiting = null
        runCatching { tts.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.shutdown() }
    }
}

/**
 * 대본을 Voice로 읽는 Media3 Player. 엔진은 ExoPlayer와 같은 방식(재생·일시정지·끝남·오류)으로 다룬다.
 * 일시정지는 읽던 덩이를 멈추고, 이어 듣기는 그 덩이를 처음부터 다시 읽는다.
 */
class SpokenPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private class Line(val utterance: Utterance, val startMs: Long, val endMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private val voice = newVoice(context)
    private var lines = emptyList<Line>()
    private var id = ""
    private var index = 0
    private var lineAt = 0L // 현재 덩이를 시작한(쉼 포함) elapsedRealtime. 0이면 멈춤.
    private var token = 0
    private var wantPlay = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private val levels = FloatArray(BARS) { IDLE_LEVEL }
    private var peak = 0.05f

    init {
        voice.onLevel = { level -> main.post { record(level) } }
    }

    private val totalMs get() = lines.lastOrNull()?.endMs ?: 0L

    private fun position(): Long {
        val line = lines.getOrNull(index) ?: return totalMs
        if (lineAt == 0L) return line.startMs
        return minOf(line.startMs + SystemClock.elapsedRealtime() - lineAt, line.endMs - 1)
    }

    /** 실제로 들어온 크기만 막대에 기록한다. 아직 읽지 않은 구간은 기본 높이로 둔다. */
    private fun record(level: Float) {
        if (lineAt == 0L || totalMs == 0L) return
        peak = maxOf(peak, level)
        val bar = (position() * BARS / totalMs).toInt().coerceIn(0, BARS - 1)
        levels[bar] = maxOf(levels[bar], (level / peak).coerceIn(IDLE_LEVEL, 1f))
    }

    fun levels(): List<Float> = levels.toList()

    fun load(storyId: String, parts: List<Utterance>) {
        halt()
        var start = 0L
        lines = parts.map { Line(it, start, start + it.estimateMs).also { line -> start = line.endMs } }
        id = storyId
        index = 0
        error = null
        peak = 0.05f
        levels.fill(IDLE_LEVEL)
        playbackState = if (lines.isEmpty()) Player.STATE_ENDED else Player.STATE_READY
        invalidateState()
    }

    private fun halt() {
        token++
        lineAt = 0L
        voice.stop()
    }

    private fun playLine() {
        val line = lines.getOrNull(index) ?: return finish()
        val mine = ++token
        lineAt = SystemClock.elapsedRealtime()
        main.postDelayed({
            if (mine != token) return@postDelayed
            voice.speak(line.utterance.text) { ok ->
                main.post {
                    if (mine != token) return@post
                    if (!ok) return@post fail()
                    index++
                    lineAt = 0L
                    if (index >= lines.size) finish() else playLine()
                }
            }
        }, line.utterance.gapMs)
    }

    private fun finish() {
        lineAt = 0L
        playbackState = Player.STATE_ENDED
        invalidateState()
    }

    private fun fail() {
        Log.w(TAG, "voice_failed index=$index")
        halt()
        error = PlaybackException("voice", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        playbackState = Player.STATE_IDLE
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_CHANGE_MEDIA_ITEMS).build())
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(error)
        if (lines.isNotEmpty() && playbackState != Player.STATE_IDLE) {
            builder.setPlaylist(listOf(MediaItemData.Builder(id).setDurationUs(totalMs * 1_000).build()))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(PositionSupplier { position() })
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        if (playbackState == Player.STATE_READY) {
            if (playWhenReady) playLine() else halt()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        halt()
        lines = emptyList()
        error = null
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        halt()
        voice.release()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val TAG = "SpokenPlayer"
        const val BARS = 72
        const val IDLE_LEVEL = 0.1f
    }
}
