import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const base = process.env.FIXTURE_ORIGIN || "http://127.0.0.1:8788";
const auth = { Authorization: "Bearer fixture-local-access-2026" };
const query = "/nearby?lat=35.838&lon=129.212";
const guides = JSON.parse(readFileSync(new URL("../data/journeys.json", import.meta.url), "utf8"));

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
assert.equal((await call(query, 200)).places.length, 3);
assert.deepEqual((await call("/nearby?lat=35.8&lon=130.5", 200)).places, []);

const story = (poiId, status) => call("/stories", status, { poiId, lat: 35.838, lon: 129.212 });
await story("bad", 400);
await story("424242", 404);
assert.equal((await story("126129", 404)).error, "no_source");
assert.equal((await story("126130", 422)).error, "unverified_script");
const first = await story("126128", 200);
const cached = await story("126128", 200);
assert.ok(!("audioBase64" in first), "음성은 앱의 Pleos TTS가 만든다");
assert.equal(first.sentences.length, 2);
assert.ok(first.sentences[0].text.includes(first.title));
assert.ok(cached.cached && cached.generatedAt === first.generatedAt);
assert.ok(first.sentences.every((sentence) => sentence.factIds.length > 0));
console.info("Fixture story verified: script only, no audio");

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
  assert.ok(!("audioBase64" in journey));
  assert.ok(journey.quiz.question.endsWith("?"));
  assert.ok(journey.sentences.at(-1).factIds.includes(journey.quiz.factId));
  assert.deepEqual(journey.chapters.map(({ poiId }) => poiId), guides[region].stops.map(({ poiId }) => poiId));
}
