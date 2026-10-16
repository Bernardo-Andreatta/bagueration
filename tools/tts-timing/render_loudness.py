"""Before/after the loudness step: the same paragraph with several voices, into out/loudness/, with measured levels.
./venv/bin/python render_loudness.py [sid ...]   (default: Heart 3, Lewis 27, Lewis light blend 108, Dora 28)"""
import os, sys, soundfile as sf, numpy as np
import pipeline_now, loudness

HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = {3: "heart", 27: "lewis", 108: "lewis_light_blend", 28: "dora"}
sids = [int(a) for a in sys.argv[1:]] or [3, 27, 108, 28]
# the first paragraph block of timing_test.txt (up to and including "Oh! Of course. The train.")
lines = open(os.path.join(HERE, "timing_test.txt")).read().split("\n")
text = "\n".join(lines[:8])
out = os.path.join(HERE, "out", "loudness"); os.makedirs(out, exist_ok=True)
print(f"{'voice':20s} {'stage':7s} {'K-wtd dB':>9s} {'RMS dB':>7s} {'peak':>5s} {'gain':>5s}")
for sid in sids:
    name = NAMES.get(sid, f"sid{sid}")
    for stage, loud in (("before", 0), ("after", 1)):
        p = pipeline_now.P(text, sid=sid, speed=1.0, loud=loud)
        a, r, _ = p.render()
        f = os.path.join(out, f"{name}_{stage}.wav")
        sf.write(f, a, r)
        db, sec = loudness.measure(a, r)
        print(f"{name:20s} {stage:7s} {db:9.1f} {loudness.rms_db(a):7.1f} {np.abs(a).max():5.2f} {p.level.gain:5.2f}")
