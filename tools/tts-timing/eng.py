import sherpa_onnx as so, numpy as np, functools
import os
A = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "kokoro")
@functools.lru_cache(None)
def engine(lang="en", mns=1, threads=8):
    cfg = so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(
        kokoro=so.OfflineTtsKokoroModelConfig(model=f"{A}/model.onnx", voices=f"{A}/voices.bin", tokens=f"{A}/tokens.txt",
            lexicon=f"{A}/lexicon-us-en.txt" if lang.startswith("en") else "", data_dir=f"{A}/espeak-ng-data", lang=lang),
        num_threads=threads, provider="cpu"), max_num_sentences=mns)
    return so.OfflineTts(cfg)
def gen(text, sid=27, speed=0.9, lang="en", mns=1):
    a = engine(lang, mns).generate(text, sid=sid, speed=speed)
    return np.array(a.samples, dtype=np.float32), a.sample_rate
def frames(x, rate, ms=10):
    fr = rate*ms//1000; n = len(x)//fr
    return np.sqrt((x[:n*fr].reshape(n, fr)**2).mean(1))
def gaps(x, rate, th=0.01, minms=60):
    """quiet runs (>= minms) strictly inside speech, as (start_ms, len_ms)."""
    r = frames(x, rate); q = r < th
    nz = np.where(~q)[0]
    if len(nz) == 0: return []
    out=[]; s=None
    for k in range(nz[0], nz[-1]+1):
        if q[k] and s is None: s=k
        elif not q[k] and s is not None:
            if (k-s)*10 >= minms: out.append((s*10, (k-s)*10))
            s=None
    return out
