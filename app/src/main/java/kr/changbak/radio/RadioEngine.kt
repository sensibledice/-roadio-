package kr.changbak.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AudioProgress(
    val id: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val waveform: List<Float> = emptyList(),
    val chapterIndex: Int = -1,
    val quizActive: Boolean = false
) {
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class RadioState(
    val region: TripRegion? = null,
    val positionMode: PositionMode = PositionMode.DEMO,
    val storyMode: StoryMode = StoryMode.LIVE,
    val restriction: Restriction = Restriction.UNKNOWN,
    val title: String? = null,
    val status: String = "가끔, 이곳의 뜻밖의 이야기를 들려드려요",
    val active: Boolean = false,
    val paused: Boolean = false,
    val playing: Boolean = false,
    val demoMoving: Boolean = false,
    val demoProgress: Float = 0f,
    val audio: AudioProgress? = null,
    val completedJourney: TripRegion? = null,
    val fix: Point? = null,
    val cached: Boolean = false,
    val generatedAt: String? = null,
    val sources: List<Source> = emptyList(),
    val history: List<Heard> = emptyList(),
    val navigation: NavigationState = NavigationState(),
    val connectedPlayer: Boolean = false
) {
    val detailsAllowed get() = restriction == Restriction.PARKED && !demoMoving
}

class RadioEngine(private val context: Context) {
    private val routes = mutableMapOf<TripRegion, DemoRoute>()
    fun route(region: TripRegion) = routes.getOrPut(region) { DemoRoute(context, region) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val live = LiveStories(context)
    private val historyStore = History(context)
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val initialRegion = TripRegion.entries.find { it.id == preferences.getString("region", null) }
    var selectedPosition = PositionMode.entries.find { it.name == preferences.getString("position", null) }
        ?: initialRegion?.defaultPosition ?: PositionMode.DEMO
        private set
    private val mutable = MutableStateFlow(RadioState(region = initialRegion, positionMode = selectedPosition,
        history = historyStore.entries))
    val state = mutable.asStateFlow()
    private val ui get() = mutable.value
    private inline fun show(update: RadioState.() -> RadioState) { mutable.value = ui.update() }

    var connection = Connection(preferences.getString("origin", "").orEmpty())
        private set
    private var player: Player? = null
    private var foreground = false
    private var locationServiceReady = false
    private var location: Fix? = null
    private val gps = DeviceLocation(context) { if (ui.positionMode == PositionMode.DEVICE) acceptFix(it) }
    private val restrictions = VehicleRestrictions(context) { next ->
        handler.post {
            if (ui.restriction != next) {
                Log.i(TAG, "platform_restriction=$next")
                show { copy(restriction = next) }
                if (next != Restriction.PARKED && !playbackAllowed()) {
                    if (ui.active && ui.positionMode == PositionMode.DEMO) {
                        end("차량 주행 제한으로 시연을 종료했어요")
                    } else {
                        resetCandidate()
                        stopPlayback()
                        show { copy(status = "차량 주행 제한으로 대기 중") }
                    }
                }
            }
        }
    }
    private val navi = newNavigation(context,
        { next -> handler.post { show { copy(navigation = next) } } },
        {
            handler.post {
                if (ui.positionMode == PositionMode.PLEOS) {
                    resetCandidate()
                    stopPlayback()
                    lastQueryAt = Long.MIN_VALUE / 2
                }
            }
        })
    val hasNavi get() = navi != null && restrictions.automotive
    private var loop: Job? = null
    private var audioClock: Job? = null
    private var waveformJob: Job? = null
    private var request: Job? = null
    private var generation = 0
    private var candidate: Candidate? = null
    private var lastQueryAt = Long.MIN_VALUE / 2
    private var lastQueryPoint: Point? = null
    private var nextStoryAt = 0L
    private val requested = mutableSetOf<String>()
    private val played = mutableSetOf<String>()
    private var current: Story? = null
    private var currentPlace: Place? = null
    private var currentNearest = Float.POSITIVE_INFINITY
    private var pendingHistory = false
    private var transientLoss = false
    private var focusHeld = false
    private var focusBlockedUntil = 0L
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onFocus, handler).build()

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
        restrictions.connect()
        if (hasNavi) navi?.start()
        if (!hasNavi && selectedPosition == PositionMode.PLEOS) selectedPosition = PositionMode.DEMO
        show { copy(connectedPlayer = true, positionMode = selectedPosition) }
    }

    fun detach() {
        end()
        player?.removeListener(listener)
        player = null
        navi?.stop()
        restrictions.disconnect()
        show { copy(connectedPlayer = false, restriction = Restriction.UNKNOWN, navigation = NavigationState()) }
    }

    fun visible(value: Boolean) {
        foreground = value
        if (value) restrictions.read()
    }

    fun chooseRegion(region: TripRegion) {
        if (ui.active || !ui.detailsAllowed) return
        stopPlayback()
        selectedPosition = region.defaultPosition
        preferences.edit().putString("region", region.id).putString("position", selectedPosition.name).apply()
        show { copy(region = region, positionMode = selectedPosition, storyMode = StoryMode.LIVE,
            fix = null, title = null, cached = false, generatedAt = null, sources = emptyList(),
            demoProgress = 0f, status = "가끔, 이곳의 뜻밖의 이야기를 들려드려요") }
    }

    fun locationServiceStarted() {
        if (!ui.active || ui.positionMode != PositionMode.DEVICE) return
        locationServiceReady = true
        if (!ui.paused && !gps.start()) end("기기 GPS와 위치 권한을 확인해 주세요")
    }

    fun locationServiceFailed() {
        if (ui.active && ui.positionMode == PositionMode.DEVICE) end("주행 중 위치 서비스를 시작할 수 없어요")
    }

    fun configure(mode: PositionMode, config: Connection) {
        if (ui.active || !ui.detailsAllowed || mode == PositionMode.PLEOS && !hasNavi) return
        connection = config
        selectedPosition = mode
        preferences.edit().putString("origin", config.origin).putString("position", mode.name).apply()
        show { copy(positionMode = mode, storyMode = StoryMode.LIVE, fix = null, title = null, demoProgress = 0f,
            cached = false, generatedAt = null, sources = emptyList(), status = if (mode == PositionMode.DEMO)
                "가끔, 이곳의 뜻밖의 이야기를 들려드려요" else "위치 신호를 기다리고 있어요") }
    }

    fun begin(bundledDemo: Boolean = false) {
        val region = ui.region ?: return
        if (!ui.detailsAllowed || !ui.connectedPlayer || ui.active) return
        val mode = if (bundledDemo) PositionMode.DEMO else selectedPosition
        show { copy(positionMode = mode, storyMode = if (bundledDemo) StoryMode.SAVED else StoryMode.LIVE,
            fix = null, title = null, cached = false, generatedAt = null, sources = emptyList(),
            demoProgress = 0f) }
        if (!bundledDemo && !connection.valid(BuildConfig.DEBUG)) {
            show { copy(status = "연결 설정에서 생성 서버 주소를 입력해 주세요") }
            return
        }
        if (mode == PositionMode.DEVICE && (!foreground || !gps.allowed())) {
            permissionDenied()
            return
        }
        stopPlayback()
        resetCandidate()
        requested.clear()
        played.clear()
        location = null
        lastQueryAt = Long.MIN_VALUE / 2
        lastQueryPoint = null
        nextStoryAt = 0L
        focusBlockedUntil = 0
        show { copy(active = true, paused = false, status = when {
            mode != PositionMode.DEMO -> "주변에서 들려드릴 이야기를 찾는 중"
            bundledDemo -> "여행 해설을 여는 중"
            else -> "경로 전체의 AI 해설을 준비하는 중"
        }) }
        Log.i(TAG, "session_started position=$mode stories=${ui.storyMode} region=${region.id}")
        if (mode == PositionMode.DEMO) {
            startJourney(region, bundledDemo)
            return
        }
        if (mode == PositionMode.DEVICE && !DrivingLocationService.start(context)) {
            locationServiceFailed()
            return
        }
        loop = scope.launch {
            var lastPoll = 0L
            while (ui.active) {
                val now = SystemClock.elapsedRealtime()
                if (!ui.paused) {
                    if (mode == PositionMode.PLEOS && now - lastPoll >= 3_000) {
                        navi?.poll()
                        lastPoll = now
                    }
                    tick(now)
                }
                delay(350)
            }
        }
    }

    private fun startJourney(region: TripRegion, bundledDemo: Boolean) {
        val journey = route(region)
        show { copy(title = journey.story.title) }
        if (bundledDemo) {
            prepare(journey.story, null, true)
        } else {
            val epoch = generation
            val config = connection
            request = scope.launch {
                try {
                    val story = withTimeout(50_000) { live.journey(config, region) }
                    if (epoch == generation && ui.active) prepare(story, null, true)
                } catch (_: TimeoutCancellationException) {
                    if (epoch == generation) end("AI 해설 준비 시간이 지났어요. 다시 시작해 주세요")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (epoch == generation) end(when ((error as? ServerResponse)?.status) {
                        401 -> "연결 설정에서 시연 접근 코드를 확인해 주세요"
                        503 -> "생성 서버에 OpenAI API 설정이 필요해요"
                        422 -> "여행 해설을 완성하지 못했어요. 다시 시작해 주세요"
                        504 -> "AI 해설 준비 시간이 지났어요. 다시 시작해 주세요"
                        else -> "AI 해설 서버에 연결하지 못했어요"
                    })
                }
            }
        }
        loop = scope.launch {
            while (ui.active) {
                player?.let { player ->
                    if (!ui.paused && !transientLoss && !player.playWhenReady &&
                        SystemClock.elapsedRealtime() >= focusBlockedUntil) {
                        startAudio()
                    }
                }
                delay(100)
            }
        }
    }

    fun permissionDenied() { show { copy(status = "정확한 위치 권한을 허용하면 기기 위치를 쓸 수 있어요") } }

    fun pause() {
        if (!ui.active) {
            stopPlayback()
            return
        }
        player?.pause()
        abandonFocus()
        gps.stop()
        if (ui.positionMode != PositionMode.DEMO) resetCandidate()
        show { copy(paused = true, playing = false, status = "일시정지") }
        Log.i(TAG, "session_paused")
    }

    fun resume() {
        if (!ui.active || !ui.paused || !playbackAllowed()) return
        if (ui.positionMode == PositionMode.DEVICE && locationServiceReady && !gps.start()) {
            end("기기 GPS와 위치 권한을 확인해 주세요")
            return
        }
        show { copy(paused = false, status = if (positionMode == PositionMode.DEMO)
            "여행 해설을 이어가는 중" else "주변에서 들려드릴 이야기를 찾는 중") }
        if (current != null && validForPlayback()) startAudio()
        Log.i(TAG, "session_resumed")
    }

    fun end(message: String = "여행 라디오를 종료했어요") {
        val wasActive = ui.active
        val usedDevice = ui.positionMode == PositionMode.DEVICE
        show { copy(active = false, paused = false, demoMoving = false, status = message,
            positionMode = selectedPosition, fix = null) }
        loop?.cancel()
        loop = null
        resetCandidate()
        gps.stop()
        if (usedDevice) DrivingLocationService.stop(context)
        locationServiceReady = false
        stopPlayback()
        location = null
        if (wasActive) Log.i(TAG, "session_ended")
    }

    fun replay(entry: Heard) {
        if (!ui.detailsAllowed || player == null) return
        if (ui.active) end()
        prepare(entry.story, null, false)
    }

    fun notebookEpisode(region: TripRegion, history: List<Heard>): Story {
        val route = route(region)
        val episode = history.firstOrNull { it.story.id == region.journeyId }?.story
        return episode?.takeIf { it.generatedAt != null &&
            it.chapters.map { chapter -> chapter.poiId } == route.places.map { place -> place.id } &&
            it.sentences.size == route.chapters.size } ?: route.story
    }

    fun replayChapter(episode: Story, chapter: RouteChapter) {
        if (!ui.detailsAllowed || player == null) return
        if (ui.active) end()
        val story = episode.copy(
            id = "${episode.id}/${chapter.place.id}", title = chapter.place.title,
            sentences = listOf(chapter.text), sources = listOf(chapter.source), evidence = chapter.facts,
            chapters = emptyList(), quiz = null
        )
        prepare(story, null, false, chapter)
    }

    fun stopPreview() {
        if (ui.detailsAllowed && !ui.active && current != null) {
            stopPlayback()
            show { copy(status = "해설을 멈췄어요") }
        }
    }

    fun delete(entry: Heard) {
        if (!ui.detailsAllowed) return
        if (current?.uri == entry.story.uri) stopPlayback()
        historyStore.delete(entry)
        show { copy(history = historyStore.entries) }
    }

    fun openNavigation() {
        if (ui.detailsAllowed && ui.navigation.canOpen) navi?.open()
    }

    private fun acceptFix(fix: Fix) {
        if (!ui.active || ui.paused) return
        if (location == null && ui.positionMode == PositionMode.DEVICE && fix.fresh()) {
            Log.i(TAG, "device_gps_fresh")
        }
        location = fix
        currentPlace?.let { currentNearest = minOf(currentNearest, fix.point.distance(it.point)) }
        if (fix.fresh() && ui.fix != fix.point) show { copy(fix = fix.point) }
    }

    private fun tick(now: Long) {
        val fix = location
        if (fix == null || !fix.fresh(now)) {
            if (ui.active && !ui.paused && currentPlace != null) stopPlayback()
            val message = if (ui.positionMode == PositionMode.PLEOS && ui.navigation.locationObserved)
                "내비 좌표의 정확도와 갱신 시각을 확인할 수 없어요"
            else "신선한 위치 신호를 기다리고 있어요"
            show { copy(fix = null, status = message) }
            return
        }
        if (!playbackAllowed() || transientLoss) return
        if (current != null) {
            if (player?.playWhenReady == false && now >= focusBlockedUntil) {
                if (validForPlayback()) startAudio() else stopPlayback()
            }
            return
        }
        if (now < nextStoryAt) return
        val next = candidate
        if (next != null) {
            val distance = fix.point.distance(next.place.point)
            next.nearest = minOf(next.nearest, distance)
            if (distance > 1_200 || distance > next.nearest + 55 ||
                !fix.aheadOf(next.place.point)) {
                Log.i(TAG, "candidate_discarded reason=passed")
                resetCandidate()
            } else if (distance <= 450 && next.story != null) {
                val story = requireNotNull(next.story)
                candidate = null
                prepare(story, next.place, true)
            } else if (next.story != null) show { copy(status = "장소에 가까워지면 재생해요") }
            return
        }
        if (request?.isActive == true) return
        val age = now - lastQueryAt
        if (age < 3_000 || age < 20_000 && lastQueryPoint?.distance(fix.point)?.let { it < 90f } == true) return
        lastQueryAt = now
        lastQueryPoint = fix.point
        val epoch = generation
        val config = connection
        show { copy(status = "주변 관광지를 찾는 중") }
        request = scope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                val places = withTimeout(4_000) { live.nearby(config, requireNotNull(ui.region), fix.point) }
                Log.i(TAG, "nearby count=${places.size} elapsed_ms=${SystemClock.elapsedRealtime() - start}")
                val latest = location?.takeIf { it.fresh() } ?: return@launch
                if (epoch != generation || !ui.active || ui.paused) return@launch
                val place = choose(places, latest)
                if (place == null) {
                    show { copy(status = if (region == TripRegion.CALIFORNIA)
                        "이 주변에 등록된 여행 이야기가 없어요" else "안내 가능한 이야기를 찾는 중") }
                    return@launch
                }
                requested.add(place.id)
                val jobCandidate = Candidate(place, latest.point.distance(place.point))
                candidate = jobCandidate
                show { copy(title = place.title, cached = false, generatedAt = null, sources = emptyList(),
                    status = "공식 자료로 이야기를 만드는 중") }
                val started = SystemClock.elapsedRealtime()
                val story = withTimeout(12_000) { live.story(config, place, latest.point) }
                if (epoch == generation && ui.active && !ui.paused && candidate === jobCandidate) {
                    jobCandidate.story = story
                    show { copy(generatedAt = story.generatedAt, sources = story.sources, cached = story.cached) }
                    Log.i(TAG, "story_ready elapsed_ms=${SystemClock.elapsedRealtime() - started} cached=${story.cached}")
                }
            } catch (_: TimeoutCancellationException) {
                if (epoch == generation) {
                    candidate = null
                    show { copy(status = "이야기 준비 시간이 지나 건너뛰었어요") }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (epoch != generation) return@launch
                candidate = null
                show { copy(status = when ((error as? ServerResponse)?.status) {
                    404, 422 -> "확인할 자료가 부족해 이야기를 건너뛰었어요"
                    401 -> "시연 접근 코드가 만료되었거나 올바르지 않아요"
                    429 -> "잠시 뒤에 다시 이야기를 찾아요"
                    503 -> "생성 서버에 API 설정이 필요해요"
                    else -> "연결이 끊겼어요"
                }) }
            }
        }
    }

    private fun choose(places: List<Place>, fix: Fix): Place? {
        val recent = historyStore.entries.asSequence()
            .filter { System.currentTimeMillis() - it.time in 0..86_400_000 }
            .map { it.story.id }.toSet()
        return places.asSequence().filter { it.id !in played && it.id !in requested && it.id !in recent &&
            fix.point.distance(it.point) <= 1_200 && fix.aheadOf(it.point) }
            .sortedBy { fix.point.distance(it.point) }.take(4).toList().randomOrNull()
    }

    private fun resetCandidate() {
        generation++
        request?.cancel()
        request = null
        candidate = null
    }

    private fun playbackAllowed() = when (ui.positionMode) {
        PositionMode.DEVICE -> ui.restriction != Restriction.UNKNOWN && locationServiceReady
        PositionMode.DEMO -> ui.restriction == Restriction.PARKED
        PositionMode.PLEOS -> ui.restriction == Restriction.PARKED &&
            (ui.navigation.maneuverMeters ?: Int.MAX_VALUE) > 200
    }

    private fun validForPlayback(): Boolean {
        val place = currentPlace
        if (place == null) {
            return if (ui.active && ui.positionMode == PositionMode.DEMO) playbackAllowed() && !ui.paused
                else ui.detailsAllowed && !ui.paused
        }
        val fix = location ?: return false
        return ui.active && !ui.paused && playbackAllowed() && fix.fresh() &&
            (!pendingHistory || fix.point.distance(place.point) <= minOf(450f, currentNearest + 55) &&
                fix.aheadOf(place.point))
    }

    private fun prepare(story: Story, place: Place?, remember: Boolean, chapter: RouteChapter? = null) {
        val player = player ?: return
        stopPlayback()
        current = story
        currentPlace = place
        currentNearest = place?.let { location?.point?.distance(it.point) } ?: Float.POSITIVE_INFINITY
        pendingHistory = remember
        show { copy(title = story.title, status = "음성 재생 준비 중", cached = story.cached,
            generatedAt = story.generatedAt, sources = story.sources, audio = AudioProgress(story.id)) }
        val item = MediaItem.Builder().setMediaId(story.id).setUri(story.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(story.title)
                .setArtist(context.getString(R.string.app_name)).setIsPlayable(true).build())
        if (chapter != null) item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(chapter.startMs).setEndPositionMs(chapter.endMs).build())
        player.setMediaItem(item.build())
        player.prepare()
        waveformJob = scope.launch {
            val levels = withContext(Dispatchers.IO) {
                readWaveform(context, story.uri, chapter?.startMs ?: 0, chapter?.endMs)
            }
            show { copy(audio = audio?.copy(waveform = levels)) }
        }
        audioClock = scope.launch {
            while (current != null) {
                if (player.duration > 0) {
                    show {
                        val position = player.currentPosition
                        val quizStart = story.quiz?.startMs
                        val progress = audio?.copy(positionMs = position, durationMs = player.duration,
                            chapterIndex = story.chapters.indexOfLast { position in it.startMs until it.endMs },
                            quizActive = quizStart != null && position >= quizStart)
                        val routeDuration = quizStart?.takeIf { it > 0 } ?: player.duration
                        copy(audio = progress, demoProgress = if (active && positionMode == PositionMode.DEMO)
                            (position.toFloat() / routeDuration).coerceIn(0f, 1f) else demoProgress)
                    }
                }
                delay(100)
            }
        }
    }

    private fun startAudio() {
        val player = player ?: return
        if (current == null || player.playbackState != Player.STATE_READY ||
            transientLoss || !validForPlayback()) return
        if (!focusHeld) {
            focusHeld = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) {
                focusBlockedUntil = SystemClock.elapsedRealtime() + 2_000
                Log.i(TAG, "platform_focus_denied")
                show { copy(status = "차량 오디오 우선권을 기다리는 중") }
                return
            }
        }
        player.play()
    }

    private fun abandonFocus() {
        if (focusHeld || transientLoss) manager.abandonAudioFocusRequest(focus)
        focusHeld = false
        transientLoss = false
    }

    private fun stopPlayback() {
        audioClock?.cancel()
        audioClock = null
        waveformJob?.cancel()
        waveformJob = null
        current = null
        currentPlace = null
        pendingHistory = false
        player?.pause()
        player?.clearMediaItems()
        abandonFocus()
        show { copy(playing = false, audio = null) }
    }

    private fun onFocus(change: Int) {
        Log.i(TAG, "platform_focus_change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (ui.active && ui.positionMode == PositionMode.DEMO) {
                    end("차량 오디오 우선권이 바뀌어 시연을 종료했어요")
                    return
                }
                stopPlayback()
                resetCandidate()
                if (ui.active) pause()
                show { copy(status = "다른 오디오가 우선권을 가져갔어요") }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                transientLoss = true
                player?.pause()
                show { copy(status = "다른 오디오가 재생되어 잠시 대기 중") }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                transientLoss = false
                if (validForPlayback()) startAudio() else stopPlayback()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> startAudio()
                Player.STATE_ENDED -> {
                    val ended = current ?: return
                    Log.i(TAG, "audio_ended id=${ended.id}")
                    if (ui.active && ui.positionMode == PositionMode.DEMO) {
                        show { copy(demoProgress = 1f, completedJourney = region) }
                        end("여행 해설을 모두 들었어요")
                        return
                    }
                    if (currentPlace != null) {
                        nextStoryAt = SystemClock.elapsedRealtime() + Random.nextLong(120_000, 240_001)
                    }
                    stopPlayback()
                    show { copy(status = if (active) "잠시 쉬어가요" else "이야기를 모두 들었어요") }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val story = current ?: return
            show { copy(playing = isPlaying, demoMoving = demoMoving ||
                (isPlaying && active && positionMode == PositionMode.DEMO),
                status = if (isPlaying) "재생 중" else status) }
            if (!isPlaying) return
            Log.i(TAG, "audio_started id=${story.id} duration_ms=${player?.duration} position_ms=${player?.currentPosition}")
            if (pendingHistory) {
                pendingHistory = false
                played.add(story.id)
                nextStoryAt = SystemClock.elapsedRealtime() + Random.nextLong(120_000, 240_001)
                runCatching { historyStore.remember(story) }
                show { copy(history = historyStore.entries) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "audio_error=${error.errorCodeName}")
            currentPlace?.let { requested.add(it.id) }
            if (ui.active && ui.positionMode == PositionMode.DEMO) {
                end("여행 해설을 재생할 수 없어요")
                return
            }
            stopPlayback()
            show { copy(status = "음성을 재생할 수 없어 건너뛰었어요") }
        }
    }

    companion object { private const val TAG = "RadioEngine" }
}

