import os, re, sys, numpy as np, soundfile as sf
from eng import frames

CLOSERS = "\"'”’»)]"
# target pause ranges (ms) for audiobook-style narration
TARGET = {"comma":(170,320), "comma-short":(100,250), "semi":(300,480), "colon":(280,450), "dash":(180,350),
          "stammer":(40,160), "ellipsis-mid":(350,650), "ellipsis-end":(650,1000), "period":(420,650),
          "question":(450,700), "exclaim":(450,700), "q/!+tag":(120,300), "./,+tag":(100,260), "para":(750,1100),
          "paren":(120,280)}

def categorize(t, i):
    """category of the punctuation at i (or None) and the index right after it (incl. closers)."""
    c = t[i]
    if c == '.' and t[i:i+3] == '...': j = i+3; kind = 'ellipsis'
    elif c == '…': j = i+1; kind = 'ellipsis'
    elif c in ',;:!?.—(': j = i+1; kind = c
    elif c == ')': j=i+1; kind=')'
    else: return None, i+1
    while j < len(t) and t[j] in ".!?" + CLOSERS and kind not in '()': j += 1
    nxt = t[j:j+1]
    if kind == '.' :
        # abbreviation / time
        w = re.search(r"(\w+)$", t[:i]); 
        if w and w.group(1).lower() in ("dr","mr","mrs","ms","st"): return None, j
        if nxt and not nxt.isspace(): return None, j
    if kind in ',:' and nxt and not nxt.isspace(): return None, j     # 6:45, 1,000
    if kind == '—':
        prev = re.search(r"(\w+)$", t[:i]); after = re.match(r"(\w+)", t[i+1:])
        if prev and after and prev.group(1).lower() == after.group(1).lower(): return 'stammer', j
        return 'dash', j
    if kind in '()': return 'paren', j
    rest = t[j:].lstrip(' ')
    para = rest.startswith('\n') or not rest
    if kind == 'ellipsis': return ('ellipsis-end' if para or (rest[:1].isupper() and not t[i-2:i].strip()=='I') and rest[:2] != 'I ' else 'ellipsis-mid'), j
    if para: return 'para', j
    tag = rest[:1].islower()
    if kind in '!?': return ('q/!+tag' if tag else {'!':'exclaim','?':'question'}[kind]), j
    if kind == '.': return 'period', j
    if kind == ',':
        if t[j-1] in CLOSERS and tag: return './,+tag', j
        words_before = len(re.findall(r"\w+", re.split(r"[.!?\"\n—:;,]\s*", t[:i])[-1]))
        return ('comma-short' if words_before <= 1 else 'comma'), j
    if kind == ';': return 'semi', j
    if kind == ':': return 'colon', j
    return None, j

def syllables(w):
    w = w.lower(); g = re.findall(r"[aeiouy]+", w)
    n = len(g) - (1 if w.endswith('e') and len(g) > 1 and not w.endswith(('le','ee'))  else 0)
    return max(1, n)

def quiet_runs(x, rate, th=0.01, minms=30):
    r = frames(x, rate); q = r < th; out=[]; s=None
    for k in range(len(q)+1):
        qq = k < len(q) and q[k]
        if qq and s is None: s=k
        elif not qq and s is not None:
            if (k-s)*10 >= minms: out.append((s*10, k*10))
            s=None
    return out

def analyze(text, audio, rate, segs, label="", verbose=True):
    runs = quiet_runs(audio, rate)
    ms = lambda smp: smp*1000/rate
    # estimated audio time for each text index
    def t_of(idx):
        for (p0,p1,sp,a0,a1) in segs:
            if p0 <= idx < p1 or idx == p1:
                seg = text[p0:p1]; lead = len(seg)-len(seg.lstrip()); body = seg.strip()
                if not body: continue
                frac = min(1, max(0, (idx-p0-lead+1)/len(body)))
                return ms(a0) + frac*(ms(a1)-ms(a0)), frac >= 0.97
        return None, False
    rows=[]; i=0
    used=set()
    while i < len(text):
        cat, j = categorize(text, i)
        if cat:
            t, at_end = t_of(i)
            if t is not None:
                tol = 60 if at_end else 260
                best=None
                for k,(r0,r1) in enumerate(runs):
                    if k in used: continue
                    d = 0 if r0 <= t <= r1 else min(abs(t-r0), abs(t-r1))
                    if d <= tol and (best is None or d < best[0] or (d == best[0] and r1-r0 > best[2])): best=(d,k,r1-r0)
                g = 0
                if best: used.add(best[1]); g = best[2]
                ctx = text[max(0,i-18):j+14].replace('\n','⏎')
                rows.append((cat, g, ctx))
        i = j if cat else i+1
    # speech rate per segment (syllables / voiced time)
    rates=[]
    for (p0,p1,sp,a0,a1) in segs:
        seg = audio[a0:a1]; dur = len(seg)/rate
        q = sum((r1-r0) for r0,r1 in quiet_runs(seg, rate, minms=100))/1000
        syl = sum(syllables(w) for w in re.findall(r"[A-Za-z']+", sp))
        if dur-q > 0.15: rates.append((syl/(dur-q), syl, sp))
    if verbose:
        print(f"\n===== {label} =====")
        for cat,g,ctx in rows:
            lo,hi = TARGET[cat]; flag = "" if lo <= g <= hi else ("  << SHORT" if g < lo else "  >> LONG")
            print(f"{cat:13s} {g:5.0f} ms  [{lo}-{hi}]{flag:10s}  …{ctx}…")
    cats = {}
    for cat,g,_ in rows: cats.setdefault(cat, []).append(g)
    score = 0
    print(f"\n{'category':13s} {'n':>3s} {'mean':>6s} {'min':>5s} {'max':>5s}  target   off-target")
    for cat in TARGET:
        if cat not in cats: continue
        v = np.array(cats[cat]); lo,hi = TARGET[cat]
        err = np.maximum(lo-v, 0) + np.maximum(v-hi, 0); score += err.sum()
        print(f"{cat:13s} {len(v):3d} {v.mean():6.0f} {v.min():5.0f} {v.max():5.0f}  {lo}-{hi}  {int((err>0).sum())}/{len(v)}  err {err.mean():.0f}ms")
    r = np.array([x[0] for x in rates]); sy = np.array([x[1] for x in rates])
    short = r[sy <= 6]; long_ = r[sy > 6]
    print(f"syll/s: all {r.mean():.2f} ±{r.std():.2f} | short pieces (<=6 syl) {short.mean() if len(short) else 0:.2f} n={len(short)} | long {long_.mean():.2f} n={len(long_)}")
    print(f"total {len(audio)/rate:.1f}s  pause-error sum {score:.0f}ms  mean {score/len(rows):.0f}ms/mark over {len(rows)} marks")
    if verbose:
        print("fastest / slowest pieces:")
        for x in sorted(rates)[:4] + sorted(rates)[-4:]: print(f"   {x[0]:.2f} syl/s  {x[2]!r}")
    return rows, rates, score
