"""Python mirror of Narrator.kt's current pipeline (exact pauses at every boundary, short-input pace fix, loudness)."""
import re, math, numpy as np
import pipeline_before as pipe_old
import speak
import loudness
from eng import gen
VOWELS = re.compile(r"[aeiouyàáâãäåèéêëìíîïòóôõöùúûüæœ]+", re.I)
def syllables(t):
    n = 0
    for w in re.findall(r"[^\W\d_]+", t):
        g = len(VOWELS.findall(w))
        if g > 1 and w.lower().endswith('e') and not w.lower().endswith(('le','ee')): g -= 1
        n += max(1, g)
    return n + 2*len(re.findall(r"\d+", t))

CLOSERS = "\"'”’»)]"

class P(pipe_old.P):
    def __init__(s, text, minw=2, minl=1, bk=0.7, loud=1, **kw):
        super().__init__(text, **kw); s.minw=minw; s.minl=minl; s.bk=bk
        s.loud = bool(loud); s.level = loudness.VoiceLevel()   # the voice's running level (prefs on the phone)
    def speakable(s, t):
        return speak.speakable(t, "en", pipe_old.SPOKEN)
    def spaced_ellipsis_end(s, i):
        """i at '.', returns index after '. . .' if it starts here, else -1"""
        m = re.compile(r"\.( \.){2,}").match(s.t, i)
        return m.end() if m else -1
    def next_sentence(s, pos, mx=pipe_old.MAX_SENTENCE):
        t=s.t; st=pos
        while st < len(t) and t[st].isspace(): st += 1
        if st >= len(t): return None
        lim = min(len(t), st+mx); i=st; e=-1
        while i < lim:
            c=t[i]
            if c == '\n': e=i; break
            if c == '.' and s.spaced_ellipsis_end(i) < 0 and s.is_abbr(i): i+=1; continue
            if c in '.!?…':
                j = s.spaced_ellipsis_end(i) if c == '.' else -1
                if j < 0: j = i+1
                while j < len(t) and t[j] in ".!?…" + CLOSERS: j+=1
                if j >= len(t) or (t[j].isspace() and not s.next_is(j, lambda ch: ch.islower() or ch.isdigit())): e=j; break
                i=j; continue
            i+=1
        if e < 0: e = lim
        return st, e
    def pieces(s):
        out=[]; pos=0
        while pos < len(s.t):
            ns = s.next_sentence(pos)
            if not ns: break
            a, e = ns; sents=[(a,e)]
            if s.phr > 1 and not s.cp:
                ms, mc = (3,300) if s.phr==2 else (8,480)
                while len(sents) < ms and not s.para_end(e):
                    n = s.next_sentence(e)
                    if not n or n[1]-a > mc: break
                    sents.append(n); e = n[1]
            pos = e
            if not any(ch.isalnum() for ch in s.t[a:e]):
                if s.para_end(e) and out: out[-1] = out[-1][:3] + (out[-1][3] + s.pause*2,)   # scene break
                continue
            parts = s.clauses(a, e)
            for k,(p0,p1) in enumerate(parts):
                shown = s.t[p0:p1].strip(); isc = k < len(parts)-1
                f = s.clause_pause(shown) if isc else s.sentence_pause(shown, e)
                sp = s.speakable(shown)
                if not isc and s.para_end(e) and sp.rstrip(CLOSERS)[-1:].isalnum(): sp += "."   # heading: let it fall
                out.append((p0, p1, sp, (s.comma if isc else s.pause)*f))
        return out
    def clauses(s, a, e):
        if not s.cp: return [(a,e)]
        t=s.t; out=[]; start=a; i=a
        while i < e:
            c=t[i]; cut=None; nxt=i+1
            if c in ',;:':
                j=i+1
                while j < e and t[j] in CLOSERS: j+=1
                if j < e and t[j].isspace(): cut=j
                nxt=j
            elif c == '—' or (c in '–-' and t[i-1:i] == ' ' and t[i+1:i+2] == ' ') or (c == '-' and t[i+1:i+2] == '-'):
                j=i+1
                while j < e and t[j] in '-—–': j+=1
                pw = re.search(r"(\w+)\W*$", t[start:i]); nw = re.match(r"\W*(\w+)", t[j:e])
                if not (pw and nw and pw.group(1).lower()==nw.group(1).lower()): cut=j
                nxt=j
            elif c in '.…':
                j = s.spaced_ellipsis_end(i) if c == '.' else i+1
                if c == '.' and j < 0: j = i+1
                k=j
                while k < e and t[k] in '.…': k+=1
                if (c == '…' or k-i >= 3 or j > i+1) and k < e and t[k].isspace(): cut=k
                nxt=k
            elif c == '(' and i > start and t[i-1] == ' ':
                cut=i
            if cut is not None and s.words(start,cut) >= s.minl and s.words(cut,e) >= s.minw:
                out.append((start,cut)); start=cut
            i=nxt
        out.append((start,e))
        # a short dialogue tag ("he said,") stays with the quote before it
        k=1
        while k < len(out):
            a0,b0 = out[k]; body = t[a0:b0].strip()
            if s.words(a0,b0) <= 3 and body[:1].islower() and t[out[k-1][0]:out[k-1][1]].rstrip()[-1:] in CLOSERS :
                out[k-1] = (out[k-1][0], b0); del out[k]
            else: k+=1
        return out
    def clause_pause(s, c):
        t = c.rstrip().rstrip(CLOSERS)
        if t.endswith(("...", "…", ". .")): return 2.0
        last = t[-1:] 
        return {',':1.0, ';':1.5, ':':1.5, '—':1.2, '–':1.2, '-':1.2}.get(last, 0.7)
    def sentence_pause(s, c, e):
        t = c.rstrip().rstrip(CLOSERS)
        ell = t.endswith(("...", "…", ". ."))
        f = 1.6 if ell else 1.25 if t.endswith(("?","!")) else 1.0 if t.endswith(".") else 0.45
        if s.para_end(e):
            heading = not t[-1:] in '.!?…:;,—–-' and len(t.split()) <= 8
            return 2.5 if heading else max(f, 2.0)
        w = sum(1 for x in t.split(' ') if any(ch.isalnum() for ch in x))
        return min(f, 0.75) if (w <= 2 and not ell) else f
    def speakable(s, t):
        t = re.sub(r"\.( \.){2,}", "…", t)
        t = re.sub(r"(?<=[^\W\d_])(\[\d{1,3}\]|[¹²³⁴⁵⁶⁷⁸⁹⁰]+|\*+)(?=[\s\W]|$)", "", t)   # footnote markers
        t = re.sub(r"\s*--+\s*", " — ", t)
        return super().speakable(t)
    def synth(s, text):
        sp = s.speed * (1 + s.bk*math.exp(-(max(syllables(text),1)-1)/1.6))
        x, rate = gen(text, sid=s.sid, speed=sp)
        x = s.squeeze(s.trim(x, rate), rate, text)
        if s.loud:
            s.level.update(loudness.measure(x, rate))
            x = loudness.apply(x, s.level.gain)
        return x, rate
