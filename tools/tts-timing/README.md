# TTS timing harness

Measures how the narrator's pauses and pace come out, on the host, with the same Kokoro model and sherpa-onnx
version as the app. Used to tune `Narrator.kt`.

- `timing_test.txt`: dialogue-heavy prose with commas, ?, !, dashes, ellipses, lists, short sentences.
- `typography_test.txt`: book conventions: quote styles, `. . .`, `--`, en dashes, dialogue dashes, `?!`, footnote
  marks, scene breaks, headings, initials.
- `pipeline_before.py` / `pipeline_now.py`: Python mirrors of the narrator's text splitting, pauses and audio
  post-processing before and after the timing work. Keep `pipeline_now.py` in step with `Narrator.kt`.
- `analyze.py` + `wt.py`: score every punctuation mark against a target pause range. Cuts made by the pipeline are
  measured exactly from the audio; pauses inside one model call are measured with Whisper word timestamps.
- `pace.py`: Kokoro's speaking rate vs input length, per voice (shows why very short inputs get a speed boost).
- `loudness.py`: mirror of `Loudness.kt` (K-weighted gated level, running level per voice, gain + soft limiter).
  `render_loudness.py` renders the same paragraph with several voices before/after it, into `out/loudness/`, and
  prints the measured levels. `pipeline_now.py` applies it unless `loud=0`.

```
python3 -m venv venv && ./venv/bin/pip install sherpa-onnx==1.13.8 numpy soundfile faster-whisper
./venv/bin/python run3.py timing_test pipeline_before        # baseline
./venv/bin/python run3.py timing_test pipeline_now           # current
V=1 ./venv/bin/python run3.py typography_test pipeline_now sid=3 speed=1.0   # other voice, list off-target marks
./venv/bin/python render_loudness.py 3 27 108 28                            # loudness before/after, per voice
```

Defaults are Lewis (UK) with its recommended preset: `sid=108` (light blend), speed 0.9, 380 ms between sentences,
150 ms at commas. Audio goes to `out/` (not meant to be committed).
