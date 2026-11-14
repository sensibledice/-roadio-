package kr.changbak.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun RadioDashboard(engine: RadioEngine, state: RadioState, onStart: (Boolean) -> Unit,
    openHistory: () -> Unit, openSettings: () -> Unit, openNotebook: () -> Unit,
    wide: Boolean, compact: Boolean, height: Dp) {
    val region = requireNotNull(state.region)
    val route = engine.route(region)
    val demo = state.positionMode == PositionMode.DEMO
    val currentChapter = if (state.demoMoving) state.audio?.chapterIndex ?: -1 else -1
    val gap = if (compact) 10.dp else 18.dp
    if (wide) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
            RouteMapPanel(engine, state, route, currentChapter, compact,
                Modifier.weight(.59f).fillMaxHeight())
            Column(Modifier.weight(.41f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                PlayerPanel(engine, state, route, currentChapter, onStart, compact, Modifier.weight(1f))
                if (!state.active && !compact) NotebookTile(state.completedJourney == region, openNotebook)
            }
        }
    } else {
        PlayerPanel(engine, state, route, currentChapter, onStart, compact, Modifier.fillMaxWidth())
        RouteMapPanel(engine, state, route, currentChapter, compact, Modifier.fillMaxWidth().height(300.dp))
    }

    if (state.demoMoving) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            AccentAction(if (state.paused) "이어 듣기" else "일시정지",
                { if (state.paused) engine.resume() else engine.pause() }, Modifier.weight(1f),
                if (state.paused) RadioIcon.PLAY else RadioIcon.PAUSE)
            PlainAction("시연 종료", { engine.end() }, Modifier.weight(1f), RadioIcon.STOP)
        }
    } else if (state.active) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PlainAction("종료", { engine.end() }, Modifier.weight(1f), RadioIcon.STOP)
            FooterTile("들은 이야기", "해설과 출처", RadioIcon.HISTORY, openHistory, Modifier.weight(1f), compact)
        }
    } else {
        if (!wide || compact) NotebookTile(state.completedJourney == region, openNotebook, compact)
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                FooterTile("저장 음성 시연", "${region.city} · ${audioTime(route.durationMs)}", RadioIcon.PLAY,
                    { onStart(true) }, Modifier.weight(1f), compact, state.connectedPlayer)
                FooterTile("들은 이야기", "해설과 출처", RadioIcon.HISTORY, openHistory, Modifier.weight(1f), compact)
                FooterTile("연결 설정", "지역 · 위치 · 서버", RadioIcon.SETTINGS, openSettings, Modifier.weight(1f), compact)
            }
        } else {
            FooterTile("저장 음성 시연", "${region.city} · ${audioTime(route.durationMs)}", RadioIcon.PLAY,
                { onStart(true) }, Modifier.fillMaxWidth(), compact, state.connectedPlayer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                FooterTile("들은 이야기", "해설과 출처", RadioIcon.HISTORY, openHistory, Modifier.weight(1f), true)
                FooterTile("연결 설정", "지역 · 위치 · 서버", RadioIcon.SETTINGS, openSettings, Modifier.weight(1f), true)
            }
        }
    }
}

@Composable
private fun RouteMapPanel(engine: RadioEngine, state: RadioState, route: DemoRoute, activeChapter: Int,
    compact: Boolean, modifier: Modifier, notebook: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    val demo = state.positionMode == PositionMode.DEMO
    val inset = if (compact) 9.dp else 14.dp
    Surface(modifier, shape = CardShape, color = palette.surfaceVariant) {
        Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(inset)) {
            Row(Modifier.fillMaxWidth().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(route.region.city, modifier = Modifier.weight(1f), fontSize = if (compact) 19.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
                Tag(if (demo) "가상 경로 · " + String.format(Locale.KOREA, "%.1f km", route.meters / 1_000)
                    else if (state.fix == null) "위치 대기" else state.positionMode.label,
                    accent = demo || state.fix != null)
            }
            TravelMap(engine, state, Modifier.fillMaxWidth().weight(1f),
                focusedChapter = activeChapter, showHeading = false, showVehicle = !notebook)
            if (demo && !compact && !notebook) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    route.chapters.forEachIndexed { index, chapter ->
                        val selected = activeChapter == index
                        Surface(Modifier.weight(1f), shape = InnerShape,
                            color = if (selected) PaleGreen else palette.surface,
                            contentColor = if (selected) Ink else palette.onSurface) {
                            Row(Modifier.heightIn(min = 45.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkpoint(index, selected, 24.dp)
                                Text(chapter.place.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Checkpoint(index: Int, selected: Boolean, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (selected) Leaf else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Text((index + 1).toString(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
            color = if (selected) Ink else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerPanel(engine: RadioEngine, state: RadioState, route: DemoRoute, chapter: Int,
    onStart: (Boolean) -> Unit, compact: Boolean, modifier: Modifier) {
    val audio = state.audio
    val demo = state.positionMode == PositionMode.DEMO
    val preparing = state.active && demo && !state.demoMoving
    val title = when {
        !state.active && audio == null -> if (demo) route.story.title else "주변 여행 라디오"
        else -> state.title ?: route.story.title
    }
    val levels = audio?.waveform?.takeIf { it.isNotEmpty() } ?: if (demo && audio == null) route.waveform else emptyList()
    val currentPlace = route.chapters.getOrNull(chapter)?.place?.title
    val marker = when {
        state.demoMoving && audio?.quizActive == true -> "동승자 퀴즈"
        state.demoMoving -> currentPlace ?: "가상 주행 중"
        state.playing -> "재생 중"
        else -> state.storyMode.label
    }
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = Night, contentColor = Color.White) {
        Column(Modifier.padding(if (compact) 15.dp else 26.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(marker, accent = true, onNight = true)
                    if (audio != null && !compact) Text("AI 합성 음성", color = OnNight, fontSize = 12.sp)
                }
                Text(title, fontSize = if (compact) 24.sp else 37.sp,
                    lineHeight = if (compact) 28.sp else 42.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!compact || state.active) Text(
                    if (audio?.quizActive == true && !state.paused) "동승자끼리 소리로 맞혀 보세요" else state.status,
                    color = OnNight,
                    fontSize = if (compact) 13.sp else 16.sp, lineHeight = 21.sp,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!compact) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Glyph(RadioIcon.RADIO, Ink, Modifier.size(23.dp))
                    }
                }
                AudioWave(levels, audio?.fraction ?: 0f, Modifier.weight(1f).height(if (compact) 26.dp else 54.dp))
                if (audio != null && audio.durationMs > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(audioTime(audio.positionMs), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(audioTime(audio.durationMs), color = OnNight, fontSize = 12.sp)
                    }
                } else if (demo && !compact && !preparing) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("저장 시연", color = OnNight, fontSize = 12.sp)
                        Text(audioTime(route.durationMs), color = OnNight, fontSize = 14.sp)
                    }
                }
            }
            if (state.demoMoving) {
                if (!compact) state.sources.firstOrNull()?.takeIf { state.storyMode == StoryMode.LIVE }?.let {
                    Text(it.title, color = OnNight, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val actionLabel = when {
                    preparing -> "해설 준비 중"
                    state.paused -> "이어 듣기"
                    state.active -> "일시정지"
                    else -> "AI 여행 라디오 시작"
                }
                AccentAction(actionLabel, {
                    when {
                        state.paused -> engine.resume()
                        state.active -> engine.pause()
                        else -> onStart(false)
                    }
                }, Modifier.fillMaxWidth(), icon = when {
                    state.paused -> RadioIcon.PLAY
                    state.active -> RadioIcon.PAUSE
                    else -> RadioIcon.RADIO
                }, enabled = state.connectedPlayer && !preparing, busy = preparing)
            }
            val stop = route.chapters.getOrNull(chapter)?.place
            if (!compact && stop != null && state.navigation.connected && state.audio != null) {
                AccentAction("들러보기 · ${stop.title}", { engine.stopBy(stop) },
                    Modifier.fillMaxWidth().padding(top = 8.dp), icon = RadioIcon.ROUTE)
            }
        }
    }
}

@Composable
private fun NotebookTile(completed: Boolean, action: () -> Unit, compact: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    Surface(onClick = action, shape = CardShape, color = palette.surfaceVariant) {
        Surface(Modifier.fillMaxWidth().padding(if (compact) 7.dp else 11.dp), shape = InnerShape, color = palette.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 84.dp).padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(if (compact) 36.dp else 47.dp).clip(InnerShape).background(palette.surfaceVariant),
                    contentAlignment = Alignment.Center) { Glyph(RadioIcon.ROUTE, palette.onSurface) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("여정 노트", fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 16.sp else 20.sp)
                    if (!compact) Text(if (completed) "방금 들은 장소 다시 듣기" else "경유지별 저장 해설과 공식 출처",
                        fontSize = 14.sp, color = palette.onSurfaceVariant)
                }
                Glyph(RadioIcon.ARROW, palette.onSurface, Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun FooterTile(title: String, detail: String, icon: RadioIcon, action: () -> Unit,
    modifier: Modifier, compact: Boolean, enabled: Boolean = true) {
    val palette = MaterialTheme.colorScheme
    val color = if (enabled) Ink else palette.onSurfaceVariant
    val fill = if (enabled) RadioGradient else androidx.compose.ui.graphics.Brush.horizontalGradient(
        listOf(palette.surfaceVariant, palette.surfaceVariant))
    Row(modifier.heightIn(min = if (compact) 68.dp else 88.dp).clip(InnerShape).background(fill)
        .clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(horizontal = if (compact) 10.dp else 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp)) {
        Box(Modifier.size(if (compact) 33.dp else 44.dp).clip(InnerShape).background(Color.White.copy(alpha = .47f)),
            contentAlignment = Alignment.Center) { Glyph(icon, color, Modifier.size(if (compact) 17.dp else 22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 14.sp else 19.sp)
            if (!compact) Text(detail, color = color.copy(alpha = .76f), fontSize = 13.sp)
        }
        if (!compact) Glyph(RadioIcon.ARROW, color, Modifier.size(19.dp))
    }
}

@Composable
internal fun JourneyNotebook(engine: RadioEngine, state: RadioState, wide: Boolean, compact: Boolean,
    openSource: (Source) -> Unit) {
    val region = requireNotNull(state.region)
    val route = engine.route(region)
    val episode = engine.notebookEpisode(region, state.history)
    val chapters = route.chaptersFor(episode)
    var selected by rememberSaveable(region, episode.generatedAt) { mutableIntStateOf(0) }
    val previewState = state.copy(positionMode = PositionMode.DEMO, fix = null, demoProgress = 0f)
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            RouteMapPanel(engine, previewState, route, selected, compact,
                Modifier.weight(.53f).height(if (compact) 300.dp else 510.dp), notebook = true)
            NotebookContent(engine, state, episode, chapters, selected, { selected = it }, openSource, Modifier.weight(.47f))
        }
    } else {
        RouteMapPanel(engine, previewState, route, selected, compact,
            Modifier.fillMaxWidth().height(260.dp), notebook = true)
        NotebookContent(engine, state, episode, chapters, selected, { selected = it }, openSource, Modifier.fillMaxWidth())
    }
}

@Composable
private fun NotebookContent(engine: RadioEngine, state: RadioState, episode: Story, chapters: List<RouteChapter>,
    selected: Int, select: (Int) -> Unit, openSource: (Source) -> Unit, modifier: Modifier) {
    val palette = MaterialTheme.colorScheme
    val chapter = chapters[selected]
    Surface(modifier, shape = CardShape, color = palette.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(if (episode.generatedAt == null) "저장 해설" else "AI로 만든 이야기", accent = true)
            chapters.forEachIndexed { index, item ->
                val isCurrent = state.audio?.id == "${episode.id}/${item.place.id}"
                val isSelected = index == selected
                Surface(onClick = {
                    select(index)
                    if (isCurrent) engine.stopPreview() else engine.replayChapter(episode, item)
                }, enabled = state.connectedPlayer, shape = InnerShape,
                    color = if (isSelected) PaleGreen else palette.surface,
                    contentColor = if (isSelected) Ink else palette.onSurface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Checkpoint(index, isSelected, 30.dp)
                        Text(item.place.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (isCurrent) "정지" else audioTime(item.endMs - item.startMs), fontSize = 13.sp)
                        Glyph(if (isCurrent) RadioIcon.STOP else RadioIcon.PLAY,
                            if (isSelected) Ink else palette.onSurface, Modifier.size(19.dp))
                    }
                }
            }
            state.audio?.takeIf { it.id == "${episode.id}/${chapter.place.id}" }?.let { audio ->
                Surface(Modifier.fillMaxWidth(), shape = InnerShape, color = Night, contentColor = Color.White) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AudioWave(audio.waveform, audio.fraction, Modifier.weight(1f).height(30.dp))
                        Text(audioTime(audio.positionMs), fontSize = 13.sp)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = InnerShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chapter.text, fontSize = 17.sp, lineHeight = 24.sp)
                    Text("이야기의 근거", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    chapter.facts.forEach {
                        Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = palette.onSurfaceVariant)
                    }
                    PlainAction(chapter.source.title, { openSource(chapter.source) }, icon = RadioIcon.LINK)
                }
            }
            episode.quiz?.let { QuizCard(it) }
        }
    }
}
