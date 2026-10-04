import os, subprocess, tempfile, urllib.request

OUT = "app/src/main/assets"
RATE = 48000
MAX_SECONDS = 2.20

# Salamander Grand Piano V3: real Yamaha C5 recordings by Alexander Holm.
# Sampled every minor third, matching the original Tone.js distribution.
SAMPLES = {
    21:"A0", 24:"C1", 27:"Ds1", 30:"Fs1",
    33:"A1", 36:"C2", 39:"Ds2", 42:"Fs2",
    45:"A2", 48:"C3", 51:"Ds3", 54:"Fs3",
    57:"A3", 60:"C4", 63:"Ds4", 66:"Fs4",
    69:"A4", 72:"C5", 75:"Ds5", 78:"Fs5",
    81:"A5", 84:"C6", 87:"Ds6", 90:"Fs6",
    93:"A6", 96:"C7", 99:"Ds7", 102:"Fs7",
    105:"A7", 108:"C8",
}
BASE = "https://tonejs.github.io/audio/salamander/"
os.makedirs(OUT, exist_ok=True)

def download(url, path):
    req = urllib.request.Request(url, headers={"User-Agent":"StablePiano/1.0"})
    with urllib.request.urlopen(req, timeout=60) as src, open(path, "wb") as dst:
        while True:
            block = src.read(1024 * 256)
            if not block:
                break
            dst.write(block)

for midi, name in SAMPLES.items():
    target = os.path.join(OUT, f"piano_{midi}.pcm")
    mp3 = os.path.join(tempfile.gettempdir(), f"stable_piano_{midi}.mp3")
    download(BASE + name + ".mp3", mp3)

    # Convert the genuine recording to compact raw PCM for Android's low-latency mixer.
    # A short fade at the end prevents an audible hard cut while keeping long presses bounded.
    subprocess.run([
        "ffmpeg","-y","-hide_banner","-loglevel","error",
        "-i",mp3,
        "-t",str(MAX_SECONDS),
        "-af","afade=t=out:st=1.85:d=0.35",
        "-ar",str(RATE),"-ac","1","-f","s16le",target
    ], check=True)

print("Downloaded and converted", len(SAMPLES), "real Salamander Yamaha C5 samples.")
