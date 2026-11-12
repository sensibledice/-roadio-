"""Generate the bundled Korean guide recordings with Supertonic 3."""
import argparse
import json
from pathlib import Path
import wave

import numpy as np
from supertonic import TTS

root = Path(__file__).resolve().parent / "app/src/main"
stories = json.loads((root / "assets/demo_stories.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser()
parser.add_argument("--journeys-only", action="store_true")
args = parser.parse_args()
journeys = json.loads((root.parents[2] / "server/data/journeys.json").read_text(encoding="utf-8"))
recordings = [] if args.journeys_only else [(story["audio"], story["sentences"]) for story in stories]
recordings += [(f"journey_story_{region}", guide["script"] + [guide["quiz"]["question"], guide["quiz"]["answer"]])
               for region, guide in journeys.items()]

tts = TTS(model="supertonic-3")
voice = tts.get_voice_style(voice_name="F2")
journey_audio = {}
for name, chapters in recordings:
    clips = []
    is_journey = name.startswith("journey_story_")
    for index, text in enumerate(chapters):
        speed = 0.91 if is_journey else 0.94
        if is_journey and index >= len(chapters) - 2:
            speed = 0.90
        samples, _ = tts.synthesize(text, voice_style=voice, lang="ko", speed=speed, total_steps=14)
        mono = np.asarray(samples).reshape(-1)
        peak = max(float(np.max(np.abs(mono))), 0.01)
        voiced = np.flatnonzero(np.abs(mono) > peak * 0.015)
        if voiced.size:
            mono = mono[max(0, voiced[0] - 25):min(len(mono), voiced[-1] + tts.sample_rate // 10)]
        rms = max(float(np.sqrt(np.mean(mono ** 2))), 0.01)
        clips.append(mono * min(0.74 / peak, 0.115 / rms))
    lead = np.zeros(round(tts.sample_rate * 0.18))
    if is_journey:
        clips = [clips[0], *(np.concatenate([lead, clip]) for clip in clips[1:-1]),
                 np.concatenate([np.zeros(round(tts.sample_rate * 1.6)), clips[-1]])]
    padded = np.concatenate([lead, *clips])
    with wave.open(str(root / "res/raw" / f"{name}.wav"), "wb") as audio:
        audio.setparams((1, 2, tts.sample_rate, 0, "NONE", "not compressed"))
        audio.writeframes((padded * 32767).astype("<i2").tobytes())
    if is_journey:
        edges = np.cumsum([round(tts.sample_rate * 0.18), *map(len, clips)])
        windows = np.array_split(padded, 54)
        energy = np.array([np.sqrt(np.mean(window ** 2)) for window in windows])
        normalized = np.sqrt(energy / max(energy.max(), 0.001))
        journey_audio[name.removeprefix("journey_story_")] = {
            "durationMs": round(len(padded) * 1000 / tts.sample_rate),
            "chapters": [[round(a * 1000 / tts.sample_rate), round(b * 1000 / tts.sample_rate)]
                         for a, b in zip(edges[1:-3], edges[2:-2])],
            "quizStartMs": round(edges[-3] * 1000 / tts.sample_rate),
            "waveform": [round(float(value), 3) for value in normalized],
        }
    print(f"{name}: {len(padded) / tts.sample_rate:.2f} s")

(root / "assets/journey_audio.json").write_text(
    json.dumps(journey_audio, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
)
