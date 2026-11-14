package kr.changbak.radio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/** Pleos TTS SDK로 한국어를 읽는다. 초기화 실패나 한국어 미지원이면 기기 TTS로 넘어간다. */
class PleosVoice(context: Context) : Voice {
    override var onLevel: ((Float) -> Unit)? = null
        set(value) {
            field = value
            system?.onLevel = value
        }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: TextToSpeech? = null
    private var ready = false
    private var system: SystemVoice? = null
    private var waiting: (() -> Unit)? = null
    private var done: ((Boolean) -> Unit)? = null
    private var text = ""

    init {
        try {
            val engine = TextToSpeech(app, Mode.HYBRID)
            sdk = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post { onReady(engine) } }
                override fun onFailure(error: ErrorType) { main.post { fallBack("init=$error") } }
            })
        } catch (t: Throwable) {
            fallBack("start=${t.message}")
        }
    }

    private fun onReady(engine: TextToSpeech) {
        if (sdk !== engine) return
        try {
            engine.setLocale(Locale.KOREA)
            engine.setAudioAttributes(speechAttributes)
            engine.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() { main.post { finish(true) } }
                override fun onError(error: ErrorType) {
                    main.post {
                        Log.w(TAG, "speak_error=$error")
                        retryWithSystem() // 엔진·언어 오류는 기기 TTS로 이어 읽는다
                    }
                }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) { onLevel?.invoke(rms.toFloat()) }
            })
            ready = true
            waiting?.invoke()
            waiting = null
        } catch (t: Throwable) {
            fallBack("setup=${t.message}")
        }
    }

    private fun fallBack(reason: String) {
        Log.w(TAG, "pleos_tts_unavailable $reason; using device TTS")
        runCatching { sdk?.release() }
        sdk = null
        ready = false
        system = SystemVoice(app).also { it.onLevel = onLevel }
        waiting?.invoke()
        waiting = null
    }

    private fun retryWithSystem() {
        val callback = done ?: return
        val again = text
        fallBack("speak error")
        done = null
        system?.speak(again, callback)
    }

    private fun finish(ok: Boolean) {
        val callback = done ?: return
        done = null
        callback(ok)
    }

    override fun speak(text: String, done: (Boolean) -> Unit) {
        system?.let { return it.speak(text, done) }
        this.text = text
        this.done = done
        val go: () -> Unit = {
            val fallback = system
            if (fallback != null) {
                this.done = null
                fallback.speak(text, done)
            } else {
                runCatching { sdk!!.speak(text, true) }.onFailure { error ->
                    Log.w(TAG, "speak_failed ${error.message}")
                    finish(false)
                }
            }
        }
        if (ready || sdk == null) go() else waiting = go
    }

    override fun stop() {
        done = null
        waiting = null
        system?.stop()
        runCatching { sdk?.stop() }
    }

    override fun release() {
        stop()
        runCatching { sdk?.release() }
        system?.release()
        sdk = null
        system = null
    }

    private companion object { const val TAG = "PleosVoice" }
}
