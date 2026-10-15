"""Bakes the voice blends into Kokoro's voice file and updates the model's speaker metadata.

For every English voice (speakers 0-27) it appends a light (25%) and half (50%) mix with the steadiest
voice of the same accent and gender (fewest hesitations in tools/voice_stats.txt), at speakers
54 + 2*sid and 54 + 2*sid + 1. Must match Voices.BLEND_PARTNER in the app.

usage: python bake_blends.py <original model dir> <assets/kokoro dir>
"""
import os
import sys

import numpy as np
import onnx

src, dst = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
names = ("af_alloy,af_aoede,af_bella,af_heart,af_jessica,af_kore,af_nicole,af_nova,af_river,af_sarah,af_sky,am_adam,am_echo,am_eric,"
         "am_fenrir,am_liam,am_michael,am_onyx,am_puck,am_santa,bf_alice,bf_emma,bf_isabella,bf_lily,bm_daniel,bm_fable,bm_george,bm_lewis").split(",")

# per voice: words/min at 1x, long (>=200 ms) and medium (120-200 ms) gaps on a reference passage
stats = {}
for line in open(os.path.join(here, "voice_stats.txt")):
    sid, lang, wpm, long_, mid = line.split()
    stats[int(sid)] = (int(long_), int(mid))

v = np.fromfile(os.path.join(src, "voices.bin"), dtype=np.float32).reshape(54, 510, 256)
blends, new_names = [], []
for i, n in enumerate(names):
    group = [j for j, m in enumerate(names) if m[:2] == n[:2] and j != i]
    p = min(group, key=lambda j: stats[j][0] * 10 + stats[j][1])
    for w, tag in [(0.25, "light"), (0.5, "half")]:
        blends.append((1 - w) * v[i] + w * v[p])
        new_names.append(f"{n}+{names[p]}_{tag}")
    print(f"{n:12s} + {names[p]}")

allv = np.concatenate([v, np.stack(blends)]).astype(np.float32)
allv.tofile(os.path.join(dst, "voices.bin"))

m = onnx.load(os.path.join(src, "model.onnx"))
meta = {p.key: p for p in m.metadata_props}
spk = meta["speaker_names"].value.split(",") + new_names
meta["n_speakers"].value = str(len(spk))
meta["speaker_names"].value = ",".join(spk)
if "speaker2id" in meta:
    meta["speaker2id"].value = ",".join(f"{s}->{i}" for i, s in enumerate(spk))
if "id2speaker" in meta:
    meta["id2speaker"].value = ",".join(f"{i}->{s}" for i, s in enumerate(spk))
onnx.save(m, os.path.join(dst, "model.onnx"))
print(f"done: {len(spk)} speakers")
