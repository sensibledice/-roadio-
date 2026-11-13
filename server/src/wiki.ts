import { readFileSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import type { Point, Source } from "./providers.ts";
import { distance, inRegion, isPoint, isRegion, radius } from "./providers.ts";

export type WikiStatus = "draft" | "reviewed" | "verified";

export type WikiContribution = {
  id: string;
  poiId: string;
  title: string;
  lat: number;
  lon: number;
  region: "kr" | "ca";
  author: string;
  facts: string[];
  source: Source;
  status: WikiStatus;
  upvotes: number;
  downvotes: number;
  createdAt: string;
  updatedAt: string;
};

export type WikiMeta = {
  id: string;
  author: string;
  verified: boolean;
  upvotes: number;
  status: WikiStatus;
};

const dataUrl = new URL("../data/wiki_contributions.json", import.meta.url);
const prohibited = /https?:|www\.|오른쪽|왼쪽|눈앞|고개를|정차하세요|차를 세우|핸들을|지금 보세요|창밖을 보세요|영업시간|입장료|가격|최고|최초|\d+\s*위/iu;

class WikiStore {
  private items = new Map<string, WikiContribution>();

  constructor() {
    this.reload();
  }

  reload() {
    try {
      const raw = readFileSync(dataUrl, "utf8");
      const list = JSON.parse(raw) as WikiContribution[];
      this.items.clear();
      for (const item of list) {
        this.items.set(item.id, item);
      }
    } catch {
      // If file doesn't exist yet or is empty, keep empty map
    }
  }

  save() {
    try {
      const list = [...this.items.values()];
      writeFileSync(dataUrl, JSON.stringify(list, null, 2), "utf8");
    } catch (err) {
      console.warn("Failed to persist wiki contributions:", err);
    }
  }

  all(): WikiContribution[] {
    return [...this.items.values()];
  }

  get(id: string): WikiContribution | undefined {
    return this.items.get(id);
  }

  getNearby(point: Point, targetRadius: number = radius, region: "kr" | "ca" = "kr", includeReviewed = false): WikiContribution[] {
    if (!inRegion(point, region)) return [];
    return [...this.items.values()]
      .filter((item) => {
        if (item.region !== region) return false;
        if (item.status === "verified" || (includeReviewed && item.status === "reviewed")) {
          return distance(item, point) <= targetRadius;
        }
        return false;
      })
      .sort((a, b) => distance(a, point) - distance(b, point));
  }

  addContribution(input: {
    poiId?: string;
    title: string;
    lat: number;
    lon: number;
    region: "kr" | "ca";
    author: string;
    facts: string[];
    source: Source;
  }): { ok: boolean; contribution?: WikiContribution; reason?: string } {
    const { title, lat, lon, region, author, facts, source } = input;
    if (!title || typeof title !== "string" || title.trim().length < 2 || title.trim().length > 60) {
      return { ok: false, reason: "invalid_title" };
    }
    if (!isPoint({ lat, lon }) || !isRegion(region) || !inRegion({ lat, lon }, region)) {
      return { ok: false, reason: "invalid_coordinates" };
    }
    if (!author || typeof author !== "string" || author.trim().length < 2 || author.trim().length > 30) {
      return { ok: false, reason: "invalid_author" };
    }
    if (!Array.isArray(facts) || facts.length < 1 || facts.length > 5) {
      return { ok: false, reason: "invalid_facts_count" };
    }
    for (const fact of facts) {
      if (typeof fact !== "string" || fact.trim().length < 15 || fact.trim().length > 250) {
        return { ok: false, reason: "invalid_fact_length" };
      }
      if (prohibited.test(fact)) {
        return { ok: false, reason: "prohibited_safety_words" };
      }
    }
    if (!source || typeof source.title !== "string" || typeof source.url !== "string" ||
        !source.url.startsWith("https://")) {
      return { ok: false, reason: "invalid_source" };
    }

    const cleanAuthor = author.trim().slice(0, 30);
    const cleanTitle = title.trim().slice(0, 60);
    const id = input.poiId?.trim() || `wiki-${region}-${randomUUID().slice(0, 8)}`;
    const now = new Date().toISOString();

    const contribution: WikiContribution = {
      id,
      poiId: id,
      title: cleanTitle,
      lat,
      lon,
      region,
      author: cleanAuthor,
      facts: facts.map((f) => f.trim()),
      source: { title: source.title.trim().slice(0, 100), url: source.url.trim() },
      status: "reviewed", // Initially reviewed by automatic safety checks
      upvotes: 1, // Author starts with 1 upvote
      downvotes: 0,
      createdAt: now,
      updatedAt: now,
    };

    this.items.set(id, contribution);
    this.save();
    return { ok: true, contribution };
  }

  vote(id: string, isUpvote: boolean): { ok: boolean; upvotes?: number; downvotes?: number; status?: WikiStatus } {
    const item = this.items.get(id);
    if (!item) return { ok: false };

    if (isUpvote) {
      item.upvotes += 1;
      // When upvotes reach 3 and upvotes > 2 * downvotes, promote to verified
      if (item.upvotes >= 3 && item.upvotes >= item.downvotes * 2) {
        item.status = "verified";
      }
    } else {
      item.downvotes += 1;
      // If downvotes exceed upvotes, downgrade to draft
      if (item.downvotes > item.upvotes && item.status !== "draft") {
        item.status = "draft";
      }
    }
    item.updatedAt = new Date().toISOString();
    this.save();
    return { ok: true, upvotes: item.upvotes, downvotes: item.downvotes, status: item.status };
  }
}

export const wiki = new WikiStore();
