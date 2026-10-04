import math, os, struct

OUT = "app/src/main/assets"
RATE = 48000
NOTES = [21, 24, 36, 48, 60, 72, 84, 96, 108]

os.makedirs(OUT, exist_ok=True)

def make_sample(midi):
    f0 = 440.0 * (2.0 ** ((midi - 69) / 12.0))
    dur = 3.4 if midi <= 36 else (2.7 if midi <= 72 else 2.1)
    n = int(RATE * dur)
    partials = [
        (1.0, 1.00, 0.00),
        (2.01, 0.55, 0.001),
        (3.02, 0.32, 0.002),
        (4.04, 0.19, 0.003),
        (5.08, 0.11, 0.004),
        (6.13, 0.065, 0.006),
        (7.20, 0.038, 0.008),
        (8.30, 0.022, 0.010),
        (9.45, 0.012, 0.012),
    ]
    out = bytearray(n * 2)
    peak = 0.0
    vals = [0.0] * n
    for i in range(n):
        t = i / RATE
        # Fast hammer attack, then the characteristic long piano decay.
        attack = 1.0 - math.exp(-t * 140.0)
        decay = math.exp(-t * (0.72 if midi < 48 else 1.15 if midi < 72 else 1.65))
        z = 0.0
        for ratio, amp, detune in partials:
            f = f0 * ratio
            if f >= RATE * 0.47:
                continue
            z += amp * math.sin(2.0 * math.pi * f * t + detune)
        # Two very quiet detuned strings add body without turning into a buzzy synth.
        if f0 < 1200:
            z += 0.09 * math.sin(2.0 * math.pi * f0 * 1.0022 * t)
            z += 0.055 * math.sin(2.0 * math.pi * f0 * 0.9977 * t)
        hammer = math.exp(-t * 32.0) * math.sin(2.0 * math.pi * (f0 * 3.7) * t) * 0.018
        v = (z * attack * decay * 0.42) + hammer
        vals[i] = v
        peak = max(peak, abs(v))
    scale = 0.82 / max(peak, 0.001)
    for i, v in enumerate(vals):
        q = max(-32767, min(32767, int(v * scale * 32767.0)))
        struct.pack_into("<h", out, i * 2, q)
    with open(os.path.join(OUT, f"piano_{midi}.pcm"), "wb") as f:
        f.write(out)

for midi in NOTES:
    make_sample(midi)
print("Generated", len(NOTES), "original piano samples at", RATE, "Hz")
