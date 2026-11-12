import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const originalFetch = globalThis.fetch
const valid = { contentid: '126128', title: '경주 대릉원', mapx: '129.21228', mapy: '35.83766' }
const noSource = { ...valid, contentid: '126129', title: '자료 없는 곳' }
const invalid = { ...valid, contentid: '126130', title: '검증 대상' }
const guides = JSON.parse(readFileSync(new URL("../data/journeys.json", import.meta.url), "utf8"))
const timings = JSON.parse(readFileSync(new URL("../../app/src/main/assets/journey_audio.json", import.meta.url), "utf8"))
const fixtureClips = new Map()
for (const [region, guide] of Object.entries(guides)) {
  const bytes = readFileSync(new URL(`../../app/src/main/res/raw/journey_story_${region}.wav`, import.meta.url)).subarray(44)
  const offset = ms => Math.round(ms * 44.1) * 2
  const { chapters, quizStartMs } = timings[region]
  chapters.forEach(([start, end], index) => {
    fixtureClips.set(guide.script[index + 1], bytes.subarray(offset(start), offset(end)))
  })
  let silentStart = -1
  for (let position = offset(quizStartMs); position < bytes.length; position += 2) {
    if (bytes.readInt16LE(position) === 0) {
      if (silentStart < 0) silentStart = position
    } else {
      if (silentStart >= 0 && position - silentStart >= offset(1_590)) {
        fixtureClips.set(guide.quiz.question, bytes.subarray(offset(quizStartMs), silentStart))
        fixtureClips.set(guide.quiz.answer, bytes.subarray(position))
        break
      }
      silentStart = -1
    }
  }
  assert.ok(fixtureClips.has(guide.quiz.answer))
}
const audio = readFileSync(new URL('../../app/src/main/res/raw/daereungwon.wav', import.meta.url))
globalThis.fetch = async (input, init) => {
  const url = new URL(input)
  if (url.host === 'apis.data.go.kr') {
    const q = url.searchParams
    assert.equal(q.get('serviceKey'), 'fixture-tour')
    let rows = []
    if (url.pathname.endsWith('locationBasedList2')) {
      assert.equal(q.get('radius'), '1200')
      assert.ok(Number(q.get('mapX')) > 120)
      assert.ok(Number(q.get('mapY')) < 40)
      if (q.get('contentTypeId') === '12' && Number(q.get('mapX')) < 130) rows = [valid, noSource, invalid]
    } else if (url.pathname.endsWith('detailCommon2')) {
      const place = [valid, noSource, invalid].find(x => x.contentid === q.get('contentId'))
      rows = [{ ...place, overview: place === noSource ? '' : '대릉원에는 신라 시대 고분이 모여 있습니다. 천마총에서 말다래에 그려진 그림이 발견되었습니다.' }]
    } else throw new Error('Unexpected TourAPI operation')
    return Response.json({ response: { header: { resultCode: '0000' }, body: { totalCount: rows.length,
      items: rows.length ? { item: rows.length === 1 ? rows[0] : rows } : '' } } })
  }
  if (url.host === 'api.openai.com') {
    assert.equal(init.headers.Authorization, 'Bearer fixture-openai')
    const body = JSON.parse(init.body)
    if (url.pathname.endsWith('/responses')) {
      assert.equal(body.text.format.type, 'json_schema')
      const { title, facts, focusFactId, stops, city } = JSON.parse(body.input)
      let result
      if (stops) {
        assert.ok(body.text.format.schema.required.includes("quiz"))
        const guide = Object.values(guides).find(item => item.city === city)
        result = { title, quiz: guide.quiz, sentences: stops.map(({ facts: sources }, index) => ({
          text: guide.script[index + 1],
          factIds: sources.map(({ factId }) => factId),
        })) }
      } else {
        assert.ok(facts.some(({ factId }) => factId === focusFactId))
        result = { title, sentences: [
          { text: title + "에는 오래 남은 흔적이 하나 있어요.", factIds: [title === "검증 대상" ? "fake" : focusFactId] },
          { text: facts.find(({ factId }) => factId === focusFactId).text, factIds: [focusFactId] },
        ] }
      }
      return Response.json({ status: 'completed', output: [{ content: [{ type: 'output_text',
        text: JSON.stringify(result) }] }] })
    }
    assert.ok(url.pathname.endsWith('/audio/speech'))
    assert.equal(body.response_format, 'pcm')
    // Fixture PCM uses downsampled demo samples, independent of the OpenAI service.
    assert.match(body.instructions, /표준 한국어/)
    const from = fixtureClips.get(body.input) ?? audio.subarray(44)
    const sampleCount = Math.floor(from.length / 2 * 24000 / 44100)
    const pcm = Buffer.alloc(sampleCount * 2)
    for (let i = 0; i < sampleCount; i++) pcm.writeInt16LE(from.readInt16LE(Math.floor(i * 44100 / 24000) * 2), i * 2)
    return new Response(pcm, { headers: { 'Content-Type': 'application/octet-stream' } })
  }
  return originalFetch(input, init)
}

