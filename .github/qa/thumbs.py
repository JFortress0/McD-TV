"""Prints small JPEG copies of screenshots into the job log (base64 between markers), for readers who can see the
log but not the artifacts. Usage: thumbs.py qa-out/a.png qa-out/b.png ..."""
import base64, io, sys
from PIL import Image
for p in sys.argv[1:]:
    try:
        im = Image.open(p).convert("RGB"); im.thumbnail((640, 360))
        b = io.BytesIO(); im.save(b, "JPEG", quality=62); s = base64.b64encode(b.getvalue()).decode()
    except Exception as e:
        print("THUMB-ERROR", p, e); continue
    print("THUMB-BEGIN", p.rsplit("/", 1)[-1])
    for i in range(0, len(s), 4000): print("T:" + s[i:i + 4000])
    print("THUMB-END")
