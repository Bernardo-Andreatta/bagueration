import re, numpy as np, sys
from eng import gen
from analyze import syllables, quiet_runs
frags = ["Yes.", "He stopped.", "Of course.", "She laughed softly.", "The train was late.",
 "Nobody answered the door.", "He looked at her for a moment.", "She had packed three things and a letter.",
 "It was such a simple sentence, so quiet and so plain.",
 "The long grey road that wound between the hills toward the coast had never seemed so empty.",
 "Outside a dog barked twice and somewhere far away a door slammed shut while the clock on the mantelpiece ticked on.",
 "It was such a simple sentence, so quiet and so plain, that for a moment neither of them was sure it had been spoken at all, or whether the rain had simply played a trick on them both.",
 "The long grey road that wound between the hills toward the coast had never seemed so empty or so far away as it did that evening, when the last of the light was fading over the water and the gulls had gone quiet."]
def rate(text, sid, speed=1.0):
    x, r = gen(text, sid=sid, speed=speed)
    nz = np.where(np.abs(x) > 0.008)[0]; x = x[nz[0]:nz[-1]]
    q = sum(b-a for a,b in quiet_runs(x, r, minms=100))/1000
    syl = sum(syllables(w) for w in re.findall(r"[A-Za-z']+", text))
    return syl, syl/(len(x)/r - q), len(x)/r
for sid in map(int, sys.argv[1:]):
    res = [rate(f, sid) for f in frags]
    base = np.median([r for s,r,_ in res if s > 30])
    print(f"sid {sid}: long-text rate {base:.2f} syl/s; ratio by length:", " ".join(f"{s}:{r/base:.2f}" for s,r,_ in res))
