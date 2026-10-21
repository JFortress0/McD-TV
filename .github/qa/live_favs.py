"""Stars Live TV channels for profile p1 through the internet setup link, like the web app's star does
(relay command live_fav). Usage: live_favs.py <link> <file with one stream URL per line>."""
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

urls = [u.strip() for u in open(sys.argv[2]) if u.strip()]
for u in urls:
    body = enc({"cmd": "live_fav", "profile": "p1", "url": u, "on": True}).encode()
    urllib.request.urlopen(urllib.request.Request(f"https://ntfy.sh/mcdtv-{rid}-in", data=body, method="POST"), timeout=20).read()
    time.sleep(3)  # one at a time, so the favorites keep this order
print(f"sent {len(urls)} favorites")
