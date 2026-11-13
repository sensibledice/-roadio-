import assert from "node:assert/strict";

const base = process.env.FIXTURE_ORIGIN || "http://127.0.0.1:8788";
const auth = { Authorization: "Bearer fixture-local-access-2026" };
const query = "/nearby?lat=35.838&lon=129.212";

async function call(path, status, input, headers = auth) {
  const options = input ? {
    method: "POST", headers: { ...headers, "Content-Type": "application/json" },
    body: JSON.stringify(input),
  } : { headers };
  const response = await fetch(base + path, { ...options, signal: AbortSignal.timeout(12_000) });
  const result = await response.json();
  assert.equal(response.status, status, result.error || 'unexpected_response');
  console.info(status, result.error || "ok");
  return result;
}

await call("/health", 200);
await call(query, 401, undefined, {});
await call("/nearby?lat=900&lon=0", 400);
const nearbyResults = await call(query, 200);
assert.ok(nearbyResults.places.length >= 3);
assert.ok(nearbyResults.places.some(p => p.poiId.startsWith("wiki-")));
assert.deepEqual((await call("/nearby?lat=35.8&lon=130.5", 200)).places, []);

const story = (poiId, status) => call("/stories", status, { poiId, lat: 35.838, lon: 129.212 });
await story("bad", 400);
await story("424242", 404);
assert.equal((await story("126129", 404)).error, "no_source");
assert.equal((await story("126130", 422)).error, "unverified_script");
const first = await story("126128", 200);
const cached = await story("126128", 200);
const wav = Buffer.from(first.audioBase64, "base64");
assert.equal(wav.toString("ascii", 0, 4), "RIFF");
assert.deepEqual(wav.subarray(44, 44 + 8640), Buffer.alloc(8640));
assert.ok(first.durationMs > 8_000 && first.durationMs <= 22_180);
assert.equal(first.sentences.length, 2);
assert.ok(first.sentences[0].text.includes(first.title));
assert.ok(cached.cached && cached.generatedAt === first.generatedAt);
assert.ok(first.sentences.every((sentence) => sentence.factIds.length > 0));
console.info("Fixture WAV verified", first.durationMs, "ms, 180 ms lead-in");

const ca = { lat: 37.80436, lon: -122.45951 };
const local = await call(`/nearby?lat=${ca.lat}&lon=${ca.lon}&region=ca`, 200);
assert.ok(local.places.some(({ poiId }) => poiId === "ca-crissy"));
assert.deepEqual((await call(query + "&region=ca", 200)).places, []);
assert.equal((await call(query + "&region=eu", 400)).error, "invalid_region");
const calStory = await call("/stories", 200, { poiId: "ca-crissy", ...ca });
assert.match(calStory.sources[0].url, /^https:\/\/www\.nps\.gov\//);
await call("/stories", 404, { poiId: "ca-crissy", lat: 35.838, lon: 129.212 });
await call("/journey", 400, { region: "eu" });
for (const region of ["kr", "ca"]) {
  const journey = await call("/journey", 200, { region });
  assert.equal(journey.poiId, "journey-" + region);
  assert.equal(journey.sentences.length, 3);
  assert.equal(journey.sources.length, 3);
  assert.ok(journey.durationMs >= 18_000);
  assert.ok(journey.quiz.question.endsWith("?"));
  assert.ok(journey.sentences.at(-1).factIds.includes(journey.quiz.factId));
  assert.equal(journey.chapters.length, 3);
  assert.equal(journey.quiz.startMs, journey.chapters.at(-1).endMs);
  let previousEnd = 0;
  for (const chapter of journey.chapters) {
    assert.ok(chapter.startMs >= previousEnd && chapter.endMs > chapter.startMs);
    previousEnd = chapter.endMs;
  }
  const recording = Buffer.from(journey.audioBase64, "base64");
  let silenceSamples = 0;
  let maxSilenceSamples = 0;
  for (let offset = 44 + journey.quiz.startMs * 48; offset < recording.length; offset += 2) {
    silenceSamples = recording.readInt16LE(offset) === 0 ? silenceSamples + 1 : 0;
    maxSilenceSamples = Math.max(maxSilenceSamples, silenceSamples);
  }
  assert.ok(maxSilenceSamples >= 38_400, "Quiz should leave 1.6 seconds to answer");
}

// Wiki tests
console.info("Testing Wiki Guide contributions and verification...");
const wikiList = await call("/wiki/contributions", 200);
assert.ok(Array.isArray(wikiList.contributions));
assert.ok(wikiList.contributions.length >= 2);

const singleWiki = await call(`/wiki/contributions?poiId=${wikiList.contributions[0].id}`, 200);
assert.equal(singleWiki.contribution.id, wikiList.contributions[0].id);

// Add contribution with safety check
await call("/wiki/contribute", 422, {
  title: "위험한 장소",
  lat: 35.838,
  lon: 129.212,
  region: "kr",
  author: "스패머",
  facts: ["지금 창밖을 보세요 눈앞에 절경이 펼쳐집니다."],
  source: { title: "출처", url: "https://example.com" },
});

const newContrib = await call("/wiki/contribute", 201, {
  title: "첨성대 별빛 정원",
  lat: 35.8347,
  lon: 129.219,
  region: "kr",
  author: "밤하늘관측자",
  facts: ["첨성대 주변 잔디밭에는 밤이 되면 야생화 군락 위로 조명이 은은하게 켜집니다."],
  source: { title: "국가유산청 첨성대 공식 안내", url: "https://www.heritage.go.kr" },
});
assert.equal(newContrib.contribution.title, "첨성대 별빛 정원");
assert.equal(newContrib.contribution.status, "reviewed");

// Test vote
const voted = await call("/wiki/vote", 200, { id: newContrib.contribution.id, isUpvote: true });
assert.equal(voted.upvotes, 2);

// Test story generation with wiki POI
const wikiStory = await call("/stories", 200, {
  poiId: "wiki-kr-daereungwon-pine",
  lat: 35.8375,
  lon: 129.2132,
});
assert.ok(wikiStory.wiki);
assert.equal(wikiStory.wiki.author, "경주로컬_민우");
assert.equal(wikiStory.wiki.verified, true);
console.info("Wiki Guide verification tests passed successfully!");


