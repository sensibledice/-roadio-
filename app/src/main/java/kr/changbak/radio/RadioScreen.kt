package kr.changbak.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Page { HOME, HISTORY, SETTINGS, REGION, NOTEBOOK }

@Composable
fun RadioScreen(engine: RadioEngine, onStart: (Boolean) -> Unit, openSource: (Source) -> Unit) {
    val state by engine.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var selectedStory by remember { mutableStateOf<Story?>(null) }

    fun back() {
        when {
            selectedStory != null -> selectedStory = null
            page == Page.REGION -> page = Page.SETTINGS
            else -> {
                if (page == Page.NOTEBOOK) engine.stopPreview()
                page = Page.HOME
            }
        }
    }

    LaunchedEffect(state.detailsAllowed) {
        if (!state.detailsAllowed) {
            page = Page.HOME
            selectedStory = null
        }
    }
    BackHandler(state.detailsAllowed && (page != Page.HOME || selectedStory != null)) { back() }

    RadioTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxHeight < 530.dp
                val wide = maxWidth >= 680.dp
                val horizontal = when {
                    maxWidth >= 1300.dp -> 44.dp
                    maxWidth >= 800.dp -> 28.dp
                    else -> 16.dp
                }
                val vertical = if (compact) 10.dp else 22.dp
                val cardHeight = if (compact) (maxHeight - 164.dp).coerceIn(218.dp, 340.dp)
                    else (maxHeight * .64f).coerceIn(410.dp, 730.dp)
                when {
                    state.restriction != Restriction.PARKED -> RestrictedPage(state)
                    state.region == null || page == Page.REGION ->
                        RegionPicker(state.region != null, ::back, wide, compact) { region ->
                            if (region != state.region) engine.chooseRegion(region)
                            page = Page.HOME
                        }
                    else -> {
                        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 1660.dp).fillMaxSize()
                            .verticalScroll(rememberScrollState()).padding(horizontal = horizontal, vertical = vertical),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp)) {
                            Masthead(state, compact, wide)
                            val story = selectedStory
                            if (story != null) {
                                PageTitle(story.title, "목록", ::back)
                                SourcePage(story, openSource, wide, onVote = { isUp -> engine.voteWiki(story, isUp) })
                            } else when (page) {
                                Page.HOME, Page.REGION -> RadioDashboard(engine, state, onStart,
                                    { page = Page.HISTORY }, { page = Page.SETTINGS }, { page = Page.NOTEBOOK },
                                    wide, compact, cardHeight)
                                Page.HISTORY -> {
                                    PageTitle("들은 이야기", "홈", ::back)
                                    HistoryPage(state, engine::replay, { selectedStory = it }, engine::delete, wide)
                                }
                                Page.SETTINGS -> {
                                    PageTitle("연결 설정", "홈", ::back)
                                    SettingsPage(engine, state, { page = Page.HOME }, { page = Page.REGION }, wide)
                                }
                                Page.NOTEBOOK -> {
                                    PageTitle("여정 노트", "홈", ::back)
                                    JourneyNotebook(engine, state, wide, compact, openSource)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Masthead(state: RadioState, compact: Boolean, wide: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 13.dp)) {
        Box(Modifier.size(if (compact) 39.dp else 52.dp).clip(InnerShape).background(Night),
            contentAlignment = Alignment.Center) {
            Glyph(RadioIcon.RADIO, Lime, Modifier.size(if (compact) 21.dp else 27.dp))
        }
        Text(stringResource(R.string.screen_title), Modifier.weight(1f), fontSize = if (compact) 22.sp else 34.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-.8).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (wide && !compact) state.region?.let { Tag(it.label) }
        Tag(state.positionMode.label, accent = true)
    }
}

@Composable
private fun PageTitle(title: String, backLabel: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        PlainAction(backLabel, back, icon = RadioIcon.BACK)
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold,
            fontSize = 31.sp, lineHeight = 37.sp, letterSpacing = (-.6).sp)
    }
}

@Composable
private fun RestrictedPage(state: RadioState) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(56.dp).clip(InnerShape).background(Night), contentAlignment = Alignment.Center) {
            Glyph(RadioIcon.RADIO, Lime, Modifier.size(25.dp))
        }
        if (state.playing && state.title != null) {
            Text(state.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text(when {
            state.restriction == Restriction.UNKNOWN -> "차량 상태를 확인하는 중"
            state.playing -> "여행 해설 재생 중"
            else -> "화면은 정차 후 이용할 수 있어요"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
    }
}
