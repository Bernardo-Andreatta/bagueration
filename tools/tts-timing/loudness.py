"""Python mirror of Loudness.kt: K-weighted gated level per chunk, running level per voice, gain with a soft limiter.
Keep in step with the Kotlin."""
import math, numpy as np

TARGET_DB = -20.0
MAX_GAIN, MIN_GAIN = 3.0, 0.5
GATE_DB, RELATIVE_GATE_DB = -40.0, -10.0
TAU_SEC, MIN_VOICED_SEC = 30.0, 0.3
KNEE = 0.85
BLOCK_MS = 100


def _shelf(rate):
    f0, g, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = math.tan(math.pi * f0 / rate); vh = 10 ** (g / 20); vb = vh ** 0.4996667741545416
    a0 = 1 + k / q + k * k
    return ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)


def _highpass(rate):
    f0, q = 38.13547087602444, 0.5003270373238773
    k = math.tan(math.pi * f0 / rate)
    a0 = 1 + k / q + k * k
    return (1 / a0, -2 / a0, 1 / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)


def _biquad(c, x):
    try:
        from scipy.signal import lfilter
        return lfilter([c[0], c[1], c[2]], [1.0, c[3], c[4]], x)
    except ImportError:
        b0, b1, b2, a1, a2 = c
        y = np.empty(len(x)); x1 = x2 = y1 = y2 = 0.0
        for i, x0 in enumerate(x.astype(np.float64)):
            y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            y[i] = y0; x2, x1, y2, y1 = x1, x0, y1, y0
        return y


def k_weight(x, rate):
    return _biquad(_highpass(rate), _biquad(_shelf(rate), np.asarray(x, np.float64)))


def measure(x, rate):
    """-> (level dB of the voiced part, voiced seconds); (nan, 0) when nothing passes the gate."""
    block = rate * BLOCK_MS // 1000; n = len(x) // block
    if n == 0: return float('nan'), 0.0
    y = k_weight(x[:n * block], rate)
    power = (y.reshape(n, block) ** 2).mean(1)
    keep = power >= 10 ** (GATE_DB / 10)
    if not keep.any(): return float('nan'), 0.0
    keep &= power >= power[keep].mean() * 10 ** (RELATIVE_GATE_DB / 10)
    if not keep.any(): return float('nan'), 0.0
    return 10 * math.log10(power[keep].mean()), keep.sum() * BLOCK_MS / 1000


def gain(level_db):
    return min(MAX_GAIN, max(MIN_GAIN, 10 ** ((TARGET_DB - level_db) / 20)))


class VoiceLevel:
    def __init__(s, db=float('nan'), sec=0.0): s.db = db; s.sec = sec
    def update(s, m):
        db, voiced = m
        if math.isnan(db) or voiced < MIN_VOICED_SEC: return False
        if math.isnan(s.db): s.db = db; s.sec = min(voiced, TAU_SEC); return True
        w = min(1.0, voiced / min(s.sec + voiced, TAU_SEC))
        s.db += w * (db - s.db); s.sec = min(s.sec + voiced, TAU_SEC)
        return True
    @property
    def gain(s): return 1.0 if math.isnan(s.db) else gain(s.db)


def apply(x, g):
    v = np.asarray(x, np.float32) * g
    a = np.abs(v); span = 1 - KNEE
    out = np.where(a <= KNEE, v, np.sign(v) * (KNEE + span * np.tanh((a - KNEE) / span)))
    return out.astype(np.float32)


def rms_db(x):
    x = np.asarray(x, np.float64)
    return 20 * math.log10(math.sqrt((x ** 2).mean())) if len(x) else -math.inf
