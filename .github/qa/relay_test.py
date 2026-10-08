"""End-to-end test of the internet setup link: encrypt a command like the Control page does,
send it through ntfy, and check the TV applied it and answered."""
import base64, json, os, sys, time, urllib.request, zlib
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

link = sys.argv[1].strip()
rid, k = link.split("#", 1)[1].split(".", 1)
key = AESGCM(base64.urlsafe_b64decode(k + "=" * (-len(k) % 4)))

def enc(obj):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    z = c.compress(json.dumps(obj).encode()) + c.flush()
    iv = os.urandom(12)
    return "v1:" + base64.urlsafe_b64encode(iv + key.encrypt(iv, z, None)).decode().rstrip("=")

def dec(m):
    try:
        raw = base64.urlsafe_b64decode(m[3:] + "=" * (-len(m[3:]) % 4))
        return json.loads(zlib.decompress(key.decrypt(raw[:12], raw[12:], None), -15))
    except Exception:
        return None

def post(topic, body):
    urllib.request.urlopen(urllib.request.Request("https://ntfy.sh/" + topic, data=body.encode(), method="POST"), timeout=20).read()

out = []
def result(ok, name):
    out.append(("PASS  " if ok else "FAIL  ") + name)
    print(out[-1])

start = time.time()
post(f"mcdtv-{rid}-in", enc({"cmd": "set", "data": {"websites": [["Relay QA", "https://relay.example"]]}}))
got = None
while time.time() - start < 90 and not got:
    time.sleep(4)
    txt = urllib.request.urlopen(f"https://ntfy.sh/mcdtv-{rid}-out/json?poll=1&since=5m", timeout=20).read().decode()
    for line in txt.strip().splitlines():
        ev = json.loads(line)
        s = dec(ev.get("message", "")) if ev.get("event") == "message" else None
        if s and s.get("note") == "Saved on the TV" and ["Relay QA", "https://relay.example"] in s.get("websites", []):
            got = s
result(bool(got), "Internet setup link: TV received an encrypted change and confirmed it")
page = urllib.request.urlopen("http://127.0.0.1:8642/", timeout=10).read().decode()
result("Relay QA" in page, "Internet setup link: change is saved on the TV")
with open("qa-out/results.txt", "a") as f:
    f.write("\n".join(out) + "\n")
sys.exit(0 if all(l.startswith("PASS") for l in out) else 1)
