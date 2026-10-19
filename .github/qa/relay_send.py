"""Sends one encrypted command to the TV through the internet setup link, like the Control page.
Usage: relay_send.py LINK 'JSON'. Waits up to 60 s for the TV's answer and prints its note."""
import json, sys, time, urllib.request
link, cmd = sys.argv[1].strip(), json.loads(sys.argv[2])
import base64, os, zlib
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
rid, k = link.split("#", 1)[1].split(".", 1)
key = AESGCM(base64.urlsafe_b64decode(k + "=" * (-len(k) % 4)))
def enc(obj):
    c = zlib.compressobj(9, zlib.DEFLATED, -15); z = c.compress(json.dumps(obj).encode()) + c.flush(); iv = os.urandom(12)
    return "v1:" + base64.urlsafe_b64encode(iv + key.encrypt(iv, z, None)).decode().rstrip("=")
def dec(m):
    try:
        raw = base64.urlsafe_b64decode(m[3:] + "=" * (-len(m[3:]) % 4)); return json.loads(zlib.decompress(key.decrypt(raw[:12], raw[12:], None), -15))
    except Exception: return None
t0 = time.time()
urllib.request.urlopen(urllib.request.Request(f"https://ntfy.sh/mcdtv-{rid}-in", data=enc(cmd).encode(), method="POST"), timeout=20).read()
while time.time() - t0 < 60:
    time.sleep(4)
    txt = urllib.request.urlopen(f"https://ntfy.sh/mcdtv-{rid}-out/json?poll=1&since=2m", timeout=20).read().decode()
    for line in txt.strip().splitlines():
        ev = json.loads(line); s = dec(ev.get("message", "")) if ev.get("event") == "message" else None
        if s and s.get("note") == "Saved on the TV" and ev.get("time", 0) >= t0 - 5:
            print("TV answered:", s.get("note"), "| wall_url", s.get("wall_url"), "| wall_cycle", s.get("wall_cycle")); sys.exit(0)
print("No answer from the TV"); sys.exit(1)
