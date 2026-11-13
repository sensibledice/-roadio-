import { createServer } from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
import { createHash, timingSafeEqual } from "node:crypto";
import { configured, distance, Failure, getNearby, inRegion, isPoint, isRegion, makeJourney, makeStory, radius, validId } from "./providers.ts";
import type { Place, Story } from "./providers.ts";
import { wiki } from "./wiki.ts";

const token = process.env.DEMO_TOKEN?.trim() ?? "";
const expiresAt = Date.parse(process.env.DEMO_TOKEN_EXPIRES_AT ?? "");
if (token && (token.length < 16 || !Number.isFinite(expiresAt))) {
  throw new Error("DEMO_TOKEN requires at least 16 characters and a valid DEMO_TOKEN_EXPIRES_AT");
}
const hash = (value: string) => createHash("sha256").update(value).digest();
const tokenHash = hash("Bearer " + token);
const port = Number(process.env.PORT || "8787");
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");

type Timed<T> = { value: T; until: number };
const places = new Map<string, Timed<Place>>();
const stories = new Map<string, Timed<Story>>();
const inFlight = new Map<string, Promise<Story>>();
const rate = new Map<string, { count: number; until: number }>();

function store<T>(map: Map<string, Timed<T>>, key: string, value: T, ttl: number, cap: number) {
  map.delete(key);
  map.set(key, { value, until: Date.now() + ttl });
  if (map.size > cap) map.delete(map.keys().next().value!);
}

function lookup<T>(map: Map<string, Timed<T>>, key: string): T | undefined {
  const item = map.get(key);
  if (!item) return undefined;
  if (item.until > Date.now()) return item.value;
  map.delete(key);
}

function rateLimit(kind: string, limit: number) {
  const now = Date.now();
  const entry = rate.get(kind);
  if (!entry || entry.until <= now) rate.set(kind, { count: 1, until: now + 60_000 });
  else if (++entry.count > limit) throw new Failure(429, "rate_limited");
}

function send(response: ServerResponse, status: number, body: object) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff" });
  response.end(JSON.stringify(body));
}

async function body(request: IncomingMessage): Promise<unknown> {
  if (!request.headers["content-type"]?.startsWith("application/json")) {
    throw new Failure(415, "json_required");
  }
  if (Number(request.headers["content-length"]) > 2048) throw new Failure(413, "body_too_large");
  const parts: Buffer[] = [];
  let size = 0;
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    const bytes = Buffer.from(chunk);
    size += bytes.length;
    if (size > 2048) {
      request.resume();
      throw new Failure(413, "body_too_large");
    }
    parts.push(bytes);
  }
  try {
    return JSON.parse(Buffer.concat(parts).toString("utf8"));
  } catch {
    throw new Failure(400, "invalid_json");
  }
}

function coordinates(url: URL) {
  const latitude = url.searchParams.get("lat");
  const longitude = url.searchParams.get("lon");
  if (!latitude?.trim() || !longitude?.trim()) throw new Failure(400, "invalid_coordinates");
  const point = { lat: Number(latitude), lon: Number(longitude) };
  if (!isPoint(point)) throw new Failure(400, "invalid_coordinates");
  return point;
}

async function handle(request: IncomingMessage, response: ServerResponse) {
  const started = performance.now();
  let operation = "other";
  let count: number | undefined;
  let status = 500;
  let code = "internal";
  try {
    const url = new URL(request.url || "/", "http://127.0.0.1");
    if (request.method === "GET" && url.pathname === "/health") {
      operation = "health";
      status = 200;
      return send(response, status, { ok: true, configured: configured() });
    }
    if (token && (Date.now() >= expiresAt ||
      !timingSafeEqual(hash(request.headers.authorization ?? ""), tokenHash))) {
      throw new Failure(401, "access_denied");
    }
    if (request.method === "GET" && url.pathname === "/wiki/contributions") {
      operation = "wiki_get";
      const poiId = url.searchParams.get("poiId");
      if (poiId) {
        const item = wiki.get(poiId);
        if (!item) throw new Failure(404, "wiki_not_found");
        status = 200;
        return send(response, status, { contribution: item });
      }
      const lat = url.searchParams.get("lat");
      const lon = url.searchParams.get("lon");
      if (lat && lon) {
        const point = coordinates(url);
        const region = url.searchParams.get("region") ?? "kr";
        if (!isRegion(region)) throw new Failure(400, "invalid_region");
        const list = wiki.getNearby(point, radius, region, true);
        status = 200;
        return send(response, status, { contributions: list });
      }
      status = 200;
      return send(response, status, { contributions: wiki.all() });
    }
    if (request.method === "POST" && url.pathname === "/wiki/contribute") {
      operation = "wiki_contribute";
      const input = await body(request) as Parameters<typeof wiki.addContribution>[0];
      rateLimit(operation, 15);
      const res = wiki.addContribution(input);
      if (!res.ok) throw new Failure(422, res.reason || "invalid_contribution");
      status = 201;
      return send(response, status, { ok: true, contribution: res.contribution });
    }
    if (request.method === "POST" && url.pathname === "/wiki/vote") {
      operation = "wiki_vote";
      const input = await body(request) as { id?: unknown; isUpvote?: unknown };
      if (typeof input?.id !== "string" || typeof input?.isUpvote !== "boolean") {
        throw new Failure(400, "invalid_vote_input");
      }
      rateLimit(operation, 60);
      const res = wiki.vote(input.id, input.isUpvote);
      if (!res.ok) throw new Failure(404, "wiki_not_found");
      status = 200;
      return send(response, status, { ...res });
    }
    if (request.method === "GET" && url.pathname === "/nearby") {
      operation = "nearby";
      const point = coordinates(url);
      rateLimit(operation, 60);
      const region = url.searchParams.get("region") ?? "kr";
      if (!isRegion(region)) throw new Failure(400, "invalid_region");
      const nearby = await getNearby(point, AbortSignal.timeout(3800), region);
      count = nearby.length;
      for (const place of nearby) store(places, place.poiId, place, 180_000, 512);
      status = 200;
      return send(response, status, { places: nearby });
    }
    if (request.method === "POST" && url.pathname === "/journey") {
      operation = "journey";
      const input = await body(request) as { region?: unknown } | null;
      const region = input?.region;
      if (!isRegion(region)) throw new Failure(400, "invalid_region");
      rateLimit(operation, 8);
      const id = "journey-" + region;
      let job = inFlight.get(id);
      if (!job) {
        if (inFlight.size >= 3) throw new Failure(429, "generation_busy");
        job = makeJourney(region, AbortSignal.timeout(45_000));
        inFlight.set(id, job);
        void job.finally(() => inFlight.delete(id)).catch(() => {});
      }
      const story = await job;
      status = 200;
      return send(response, status, { ...story, cached: false });
    }
    if (request.method === "POST" && url.pathname === "/stories") {
      operation = "stories";
      const input = await body(request);
      if (!isPoint(input)) throw new Failure(400, "invalid_coordinates");
      const id = (input as { poiId?: unknown }).poiId;
      if (!validId(id)) throw new Failure(400, "invalid_poi");
      const place = lookup(places, id);
      if (!place || !inRegion(input, id.startsWith("ca-") ? "ca" : "kr") || distance(place, input) > radius) throw new Failure(404, "poi_outside_recent_search");
      rateLimit(operation, 20);
      const hit = lookup(stories, id);
      if (hit) {
        status = 200;
        return send(response, status, { ...hit, cached: true });
      }
      let job = inFlight.get(id);
      if (!job) {
        if (inFlight.size >= 3) throw new Failure(429, "generation_busy");
        job = makeStory(place, AbortSignal.timeout(10_900));
        inFlight.set(id, job);
        void job.then((story) => store(stories, id, story, 600_000, 20), () => {})
          .finally(() => inFlight.delete(id));
      }
      const story = await job;
      status = 200;
      return send(response, status, { ...story, cached: false });
    }
    throw new Failure(404, "not_found");
  } catch (error) {
    const failure = error instanceof Failure ? error : error instanceof Error &&
      ["AbortError", "TimeoutError"].includes(error.name) ? new Failure(504, "deadline") :
      new Failure(502, "provider_unavailable");
    status = failure.status;
    code = failure.code;
    if (!response.destroyed) send(response, status, { error: code });
  } finally {
    console.info(JSON.stringify({ operation, status, code: status >= 400 ? code : undefined,
      elapsedMs: Math.round(performance.now() - started), count }));
  }
}

const server = createServer({ requestTimeout: 15_000, headersTimeout: 5_000 }, handle);
server.listen(port, "127.0.0.1", () => console.info(JSON.stringify({ event: "listening", port })));

