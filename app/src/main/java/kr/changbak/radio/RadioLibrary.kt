package kr.changbak.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
internal fun HistoryPage(state: RadioState, replay: (Heard) -> Unit, sources: (Story) -> Unit,
    delete: (Heard) -> Unit, wide: Boolean) {
    val palette = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    if (state.history.isEmpty()) {
        SoftPanel(Modifier.fillMaxWidth()) {
            Text("재생한 여행 이야기가 여기에 모여요", fontSize = 18.sp)
        }
    }
    state.history.forEach { heard ->
        key(heard.key) {
            Surface(Modifier.fillMaxWidth(), shape = CardShape, color = palette.surfaceVariant) {
                val playing = state.playing && state.audio?.id == heard.story.id
                val controls: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (pendingDelete == heard.key) {
                            PlainAction("삭제 확인", { delete(heard); pendingDelete = null })
                            PlainAction("취소", { pendingDelete = null })
                        } else {
                            PlainAction("다시 듣기", { replay(heard) }, icon = if (playing) RadioIcon.RADIO else RadioIcon.PLAY)
                            PlainAction("출처 보기", { sources(heard.story) }, icon = RadioIcon.LINK)
                            PlainAction("삭제", { pendingDelete = heard.key })
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(15.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HistoryLabel(heard, Modifier.weight(1f))
                        controls()
                    }
                } else {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryLabel(heard, Modifier.fillMaxWidth())
                        controls()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLabel(heard: Heard, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(45.dp).clip(InnerShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) { Glyph(RadioIcon.HISTORY, MaterialTheme.colorScheme.onSurface) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(heard.story.title, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatDate(Date(heard.time)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SourcePage(story: Story, openSource: (Source) -> Unit, wide: Boolean) {
    val transcript: @Composable (Modifier) -> Unit = { modifier ->
        SoftPanel(modifier) {
            Text("해설", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Surface(Modifier.fillMaxWidth(), shape = InnerShape) {
                Text(story.sentences.joinToString(" "), Modifier.padding(16.dp), fontSize = 18.sp, lineHeight = 28.sp)
            }
            if (story.generatedAt != null) Text("생성 ${localDate(story.generatedAt)}", fontSize = 14.sp)
            else Text("저장 음성 · 자료 확인 ${localDate(story.queriedAt)}", fontSize = 14.sp)
            if (story.cached) Tag("최근 생성 음성", accent = true)
            story.quiz?.let { QuizCard(it) }
        }
    }
    val evidence: @Composable (Modifier) -> Unit = { modifier ->
        SoftPanel(modifier) {
            Text("이야기의 근거", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            story.evidence.forEach { Text(it, fontSize = 16.sp, lineHeight = 23.sp) }
            story.sources.forEach { source ->
                PlainAction(source.title, { openSource(source) }, Modifier.fillMaxWidth(), RadioIcon.LINK)
            }
        }
    }
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        transcript(Modifier.weight(.53f))
        evidence(Modifier.weight(.47f))
    } else {
        transcript(Modifier.fillMaxWidth())
        evidence(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun QuizCard(quiz: StoryQuiz) {
    var showAnswer by rememberSaveable(quiz.question, quiz.answer) { mutableStateOf(false) }
    val palette = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = InnerShape, color = palette.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("동승자 퀴즈", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(quiz.question, fontSize = 16.sp, lineHeight = 23.sp)
            PlainAction(if (showAnswer) "정답 접기" else "정답 보기", { showAnswer = !showAnswer },
                icon = RadioIcon.ARROW)
            if (showAnswer) Text(quiz.answer, color = palette.onSurfaceVariant, fontSize = 16.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
internal fun SettingsPage(engine: RadioEngine, state: RadioState, back: () -> Unit,
    changeRegion: () -> Unit, wide: Boolean) {
    var position by remember { mutableStateOf(engine.selectedPosition) }
    var origin by remember { mutableStateOf(engine.connection.origin) }
    var token by remember { mutableStateOf(engine.connection.token) }
    val regionPanel: @Composable (Modifier) -> Unit = { modifier ->
        SoftPanel(modifier) {
            Text("여행 지역", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            Text(state.region?.label.orEmpty(), fontSize = 17.sp)
            PlainAction("여행 지역 변경", changeRegion, icon = RadioIcon.ROUTE)
            Spacer(Modifier.height(4.dp))
            Text("위치 입력", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            PositionMode.entries.filter { it != PositionMode.PLEOS || engine.hasNavi }.forEach { option ->
                Surface(onClick = { position = option }, shape = InnerShape,
                    color = if (position == option) PaleGreen else MaterialTheme.colorScheme.surface,
                    contentColor = if (position == option) Ink else MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                            .background(if (position == option) Color(0xFF459F3C) else MaterialTheme.colorScheme.outline))
                        Text(option.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    val serverPanel: @Composable (Modifier) -> Unit = { modifier ->
        SoftPanel(modifier) {
            Text("실시간 생성 서버", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth(), label = { Text("생성 서버 주소") },
                singleLine = true, shape = InnerShape, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("시연 접근 코드") },
                singleLine = true, shape = InnerShape, visualTransformation = PasswordVisualTransformation())
            AccentAction("설정 적용", { engine.configure(position, Connection(origin.trim(), token.trim())); back() },
                Modifier.fillMaxWidth(), RadioIcon.SETTINGS)
        }
    }
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        regionPanel(Modifier.weight(.44f))
        serverPanel(Modifier.weight(.56f))
    } else {
        regionPanel(Modifier.fillMaxWidth())
        serverPanel(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun RegionPicker(canGoBack: Boolean, back: () -> Unit, wide: Boolean, compact: Boolean,
    select: (TripRegion) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).widthIn(max = 1100.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 20.dp else 44.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 21.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(InnerShape).background(Night), contentAlignment = Alignment.Center) {
                    Glyph(RadioIcon.RADIO, Lime)
                }
                Text(stringResource(R.string.screen_title), Modifier.weight(1f).padding(start = 10.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (canGoBack) PlainAction("뒤로", back, icon = RadioIcon.BACK)
            }
            Text("어디에서 달리시나요?", fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 31.sp else 49.sp, lineHeight = if (compact) 38.sp else 55.sp)
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    RegionOption(TripRegion.KOREA, select, Modifier.weight(1f), compact)
                    RegionOption(TripRegion.CALIFORNIA, select, Modifier.weight(1f), compact)
                }
            } else {
                TripRegion.entries.forEach { RegionOption(it, select, Modifier.fillMaxWidth(), true) }
            }
        }
    }
}

@Composable
private fun RegionOption(region: TripRegion, select: (TripRegion) -> Unit, modifier: Modifier, compact: Boolean) {
    val korea = region == TripRegion.KOREA
    val palette = MaterialTheme.colorScheme
    Surface(onClick = { select(region) }, modifier = modifier, shape = CardShape, color = palette.surfaceVariant) {
        Column(Modifier.padding(if (compact) 12.dp else 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.fillMaxWidth(), shape = InnerShape, color = palette.surface) {
                Column(Modifier.padding(if (compact) 13.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Tag(if (korea) "국내" else "국외", accent = true)
                    Text(if (korea) "한국" else "미국 캘리포니아", fontSize = if (compact) 23.sp else 30.sp,
                        fontWeight = FontWeight.ExtraBold)
                    Text(if (korea) "경주 가상 주행 · 한국 관광 이야기" else "샌프란시스코 가상 주행 · 기기 위치 안내",
                        fontSize = 14.sp, color = palette.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(CircleShape).background(RadioGradient)
                .padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("선택", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Bold)
                Glyph(RadioIcon.ARROW, Ink, Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun SoftPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = CardShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private fun formatDate(value: Date) =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.KOREA).format(value)

private fun localDate(value: String) = runCatching { formatDate(Date.from(Instant.parse(value))) }.getOrDefault(value)
