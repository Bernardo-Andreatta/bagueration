"""Python port of Narrator.kt's pipeline BEFORE the timing work (baseline) (sentence split, phrasing, clauses, pauses, trim, squeeze)."""
import re, numpy as np
from eng import gen

MAX_SENTENCE = 300; MIN_CLAUSE_WORDS = 3
ABBR = set("mr mrs ms mx dr st jr sr sra srta dra prof rev fr hon mt ft vs etc e.g i.e cf approx vol vols ch pp fig sgt capt col gen lt maj cmdr adm gov sen pres insp jan feb apr jun jul aug sep sept oct nov dec inc ltd corp".split())
SPOKEN = [("Mrs.","Missus"),("Mr.","Mister"),("Ms.","Miz"),("Dr.","Doctor"),("Prof.","Professor"),("etc.","etcetera"),("e.g.","for example"),("i.e.","that is")]
SPOKEN = [(re.compile(r"(?<![^\W\d_])(?<!\.)" + re.escape(a)), w) for a, w in SPOKEN]
CLOSERS = "\"'”’»)]"

class P:
    def __init__(s, text, speed=0.9, pause=380, comma=150, gap=60, phrasing=2, sid=108, comma_pauses=True):
        s.t = text; s.speed=speed; s.pause=pause; s.comma=comma; s.gap=gap; s.phr=phrasing; s.sid=sid; s.cp=comma_pauses
    def next_is(s, i, f):
        while i < len(s.t) and s.t[i] == ' ': i += 1
        return i < len(s.t) and f(s.t[i])
    def is_abbr(s, dot):
        t=s.t; k=dot
        while k > 0 and (t[k-1].isalpha() or t[k-1]=='.'): k -= 1
        w = t[k:dot].lower()
        if not w: return False
        if w in ("no","nos"): return s.next_is(dot+1, str.isdigit)
        return w in ABBR or (len(w)==1 and t[k].isupper() and w != "i")
    def next_sentence(s, pos, mx=MAX_SENTENCE):
        t=s.t; st=pos
        while st < len(t) and t[st].isspace(): st += 1
        if st >= len(t): return None
        lim = min(len(t), st+mx); i=st; e=-1
        while i < lim:
            c=t[i]
            if c == '\n': e=i; break
            if c == '.' and s.is_abbr(i): i+=1; continue
            if c in '.!?…':
                j=i+1
                while j < len(t) and t[j] in ".!?…\"'”’»)]": j+=1
                if j >= len(t) or (t[j].isspace() and not s.next_is(j, lambda ch: ch.islower() or ch.isdigit())): e=j; break
                i=j; continue
            i+=1
        if e < 0: e = lim
        return st, e
    def para_end(s, e):
        k=e
        while k < len(s.t) and s.t[k]==' ': k+=1
        return k >= len(s.t) or s.t[k]=='\n'
    def words(s, a, b): return sum(1 for w in re.split(r"[ \n]", s.t[a:b]) if any(ch.isalnum() for ch in w))
    def clauses(s, a, e):
        if not s.cp: return [(a,e)]
        out=[]; start=a; i=a; t=s.t
        while i < e:
            if t[i] in ',;:':
                j=i+1
                while j < e and t[j] in "\"'”’»)": j+=1
                if j < e and t[j].isspace() and s.words(start,j) >= MIN_CLAUSE_WORDS and s.words(j,e) >= MIN_CLAUSE_WORDS:
                    out.append((start,j)); start=j
                i=j; continue
            i+=1
        out.append((start,e)); return out
    @staticmethod
    def last_punct(c):
        c = c.rstrip().rstrip(CLOSERS); return c[-1] if c else ' '
    def clause_pause(s, c): return {',':1.0, ';':1.5, ':':1.5, '—':1.2, '–':1.2, '-':1.2}.get(s.last_punct(c), 0.35)
    def sentence_pause(s, c, e):
        t = c.rstrip().rstrip(CLOSERS)
        f = 1.6 if (t.endswith("...") or t.endswith("…")) else 1.25 if t.endswith(("?","!")) else 1.0 if t.endswith(".") else 0.45
        if s.para_end(e): return max(f, 2.0)
        w = sum(1 for x in t.split(' ') if any(ch.isalnum() for ch in x))
        return min(f, 0.5) if w <= 2 else f
    def speakable(s, t):
        for r, w in SPOKEN: t = r.sub(w, t)
        return t
    def pieces(s):
        """yields (start, end, spoken_text, silence_after_ms)"""
        pos=0; out=[]
        while pos < len(s.t):
            ns = s.next_sentence(pos)
            if not ns: break
            a, e = ns; sents=[(a,e)]
            if s.phr > 1:
                ms, mc = (3,300) if s.phr==2 else (8,480)
                while len(sents) < ms and not s.para_end(e):
                    n = s.next_sentence(e)
                    if not n or n[1]-a > mc: break
                    sents.append(n); e = n[1]
            pos = e
            if not any(ch.isalnum() for ch in s.t[a:e]): continue
            parts = s.clauses(a, e)
            for k,(p0,p1) in enumerate(parts):
                shown = s.t[p0:p1].strip()
                isc = k < len(parts)-1
                f = s.clause_pause(shown) if isc else s.sentence_pause(shown, e)
                out.append((p0, p1, s.speakable(shown), (s.comma if isc else s.pause)*f))
        return out
    def trim(s, x, rate):
        th=0.008; a=0
        nz = np.where(np.abs(x) >= th)[0]
        if len(nz) < 2: return x
        a = max(0, nz[0]-rate//100*2); b = min(len(x)-1, nz[-1]+rate//100*5)
        return x[a:b+1]
    def squeeze(s, x, rate, spoken):
        mg = s.gap
        if mg <= 0: return x
        fr = rate//100; n = len(x)//fr
        if n < 20: return x
        r = np.sqrt((x[:n*fr].reshape(n,fr)**2).mean(1)); quiet = r < 0.01
        nq = np.where(~quiet)[0]
        if len(nq)==0: return x
        first, last = nq[0], nq[-1]
        if last <= first: return x
        L = len(spoken); te = len(spoken.rstrip())
        marks = [first + (m.start()+1)/L*(last-first) for m in re.finditer(r"[,;:?!.—…](?=[\s\"'”’»)])", spoken) if m.start() < te-2]
        prot = lambda r0, r1: any(r0-30 <= m <= r1+30 for m in marks)
        headMs = min(40, mg//2); head = rate*headMs//1000; tail = rate*(mg-headMs)//1000; minRun=(mg+40)//10
        cuts=[]; r0=-1
        for k in range(first, last+2):
            q = k <= last and quiet[k]
            if q and r0 < 0: r0=k
            elif not q and r0 >= 0:
                if k-r0 >= minRun and not prot(r0,k): cuts.append((r0*fr+head, k*fr-tail))
                r0=-1
        if not cuts: return x
        xf = rate//200; out=[]; prev=0
        for a,b in cuts:
            if b-a <= xf: continue
            out.append(x[prev:a]); w = np.arange(xf)/xf
            out.append(x[a:a+xf]*(1-w) + x[b:b+xf]*w); prev = b+xf
        out.append(x[prev:]); return np.concatenate(out)
    def synth(s, text):
        x, rate = gen(text, sid=s.sid, speed=s.speed)
        return s.squeeze(s.trim(x, rate), rate, text), rate
    def render(s):
        """-> audio, rate, segs [(start,end,spoken,a0,a1)] audio in samples"""
        segs=[]; buf=[]; o=0; rate=24000
        for p0,p1,sp,sil in s.pieces():
            x, rate = s.synth(sp)
            segs.append((p0,p1,sp,o,o+len(x))); buf.append(x); o += len(x)
            z = int(rate*sil/1000); buf.append(np.zeros(z, np.float32)); o += z
        return np.concatenate(buf), rate, segs
