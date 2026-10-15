import sys, importlib, soundfile as sf, io, contextlib, os
from analyze import analyze
from wt import evaluate
f = sys.argv[1]; text = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), f"{f}.txt")).read()
mod = importlib.import_module(sys.argv[2]); kw = dict(a.split('=') for a in sys.argv[3:])
kw = {k:(float(v) if '.' in v else int(v)) for k,v in kw.items()}
os.makedirs("out", exist_ok=True); name = f"out/{f}_{sys.argv[2]}{''.join('_'+k+str(v) for k,v in kw.items())}.wav"
p = mod.P(text, **kw); a, r, segs = p.render(); sf.write(name, a, r)
o = io.StringIO()
with contextlib.redirect_stdout(o): analyze(text, a, r, segs, verbose=False)
print([l for l in o.getvalue().splitlines() if l.startswith("syll/s")][0])
rows = evaluate(text, name, name, segs)
from analyze import TARGET
if os.environ.get('V'):
    for c,g,ctx in rows:
        lo,hi=TARGET[c]
        if not lo<=g<=hi: print(f'  {c:13s} {g:5.0f} {("SHORT" if g<lo else "LONG "):5s} …{ctx}…')
