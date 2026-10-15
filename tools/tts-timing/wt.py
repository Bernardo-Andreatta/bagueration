"""Ground-truth pause measurement: Whisper word timestamps -> silence at each punctuation mark."""
import re, os, numpy as np, soundfile as sf, functools
from analyze import categorize, TARGET
@functools.lru_cache(None)
def model():
    from faster_whisper import WhisperModel
    return WhisperModel('small.en', device='cpu', compute_type='int8')
def words(path):
    x, r = sf.read(path, dtype='float32')
    t = np.arange(0, len(x)/r, 1/16000); y = np.interp(t, np.arange(len(x))/r, x).astype(np.float32)
    segs,_ = model().transcribe(y, word_timestamps=True, beam_size=5, language='en', condition_on_previous_text=False)
    return [(w.word.strip(), w.start, w.end) for s in segs for w in s.words]
def norm(w): return re.sub(r"[^a-z0-9]", "", w.lower())
def evaluate(text, path, label="", segs=None):
    ws = words(path); x, r = sf.read(path, dtype='float32')
    # text words with the char index after each
    tw = [(m.group(), m.start(), m.end()) for m in re.finditer(r"[A-Za-z0-9']+(?:[-:][A-Za-z0-9']+)*", text)]
    # align text words to whisper words (difflib on normalized tokens)
    import difflib
    a = [norm(w) for w,_,_ in tw]; b = [norm(w) for w,_,_ in ws]
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False); amap={}
    for blk in sm.get_matching_blocks():
        for k in range(blk.size): amap[blk.a+k] = blk.b+k
    def silence(t0, t1):
        """quiet ms between two times, by energy (whisper word edges are +-50ms)"""
        a0, a1 = int(t0*r), int(t1*r)
        if a1 <= a0: return 0
        seg = x[a0:a1]; fr = r//100; n = len(seg)//fr
        if n == 0: return 0
        q = np.sqrt((seg[:n*fr].reshape(n,fr)**2).mean(1)) < 0.01
        best=cur=0
        for v in q: cur = cur+1 if v else 0; best=max(best,cur)
        return best*10
    rows=[]; i=0
    while i < len(text):
        cat, j = categorize(text, i)
        if cat:
            # previous and next text words
            junc = None
            if segs:
                for (p0,p1,sp,a0,a1) in segs:
                    body_end = p0 + len(text[p0:p1].rstrip())
                    if j <= body_end + 1 and j >= body_end - 1 and i < p1: junc = a1
            if junc is not None:
                # cut point: quiet run spanning the junction, measured exactly
                fr = r//100; k0 = junc//fr; q = lambda k: 0 <= k*fr < len(x)-fr and np.sqrt((x[k*fr:(k+1)*fr]**2).mean()) < 0.01
                a_ = k0
                while q(a_-1): a_ -= 1
                b_ = k0
                while q(b_): b_ += 1
                rows.append((cat, (b_-a_)*10, text[max(0,i-18):j+14].replace('\n','⏎'))); i = j; continue
            pk = max([k for k,(w,s,e) in enumerate(tw) if e <= i+1] or [-1])
            nk = pk+1
            if pk >= 0 and nk < len(tw) and pk in amap and nk in amap:
                w0 = ws[amap[pk]]; w1 = ws[amap[nk]]
                g = silence(w0[2]-0.15, w1[1]+0.15)
                rows.append((cat, g, text[max(0,i-18):j+14].replace('\n','⏎')))
        i = j if cat else i+1
    # word rate: syllable-free -> words per minute of voiced time between paragraph pauses
    cats={}; score=0
    for c,g,_ in rows: cats.setdefault(c,[]).append(g)
    print(f"\n== {label}  ({len(rows)} marks matched, {len(x)/r:.1f}s) ==")
    print(f"{'category':13s} {'n':>3s} {'mean':>5s} {'min':>5s} {'max':>5s} target    off")
    for c in TARGET:
        if c not in cats: continue
        v=np.array(cats[c]); lo,hi=TARGET[c]; err=np.maximum(lo-v,0)+np.maximum(v-hi,0); score+=err.sum()
        print(f"{c:13s} {len(v):3d} {v.mean():5.0f} {v.min():5.0f} {v.max():5.0f} {lo}-{hi:<5d} {int((err>0).sum())}/{len(v)}")
    print(f"pause error: mean {score/max(1,len(rows)):.0f} ms/mark")
    return rows
if __name__ == "__main__":
    import sys
    text = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "timing_test.txt")).read()
    for p in sys.argv[1:]:
        rows = evaluate(text, p, p)
        if os.environ.get("V"):
            for c,g,ctx in rows:
                lo,hi=TARGET[c]; print(f"  {c:13s} {g:5.0f} {'' if lo<=g<=hi else ('SHORT' if g<lo else 'LONG '):5s} …{ctx}…")
