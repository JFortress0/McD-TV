"""Mock Jarvis Wall server for the emulator QA (the real one is the wall/ Netlify site).
Serves /api/feed (a few events for today), /api/photos and /api/photos/<id> (generated PNG photos).
Run: python3 wall_mock.py 8766. The emulator reaches it at http://10.0.2.2:8766."""
import http.server, json, struct, sys, time, zlib, datetime

def png(w, h, top, bottom):
    """A vertical gradient with a lighter band, so each photo is easy to tell apart in a screenshot."""
    rows = []
    for y in range(h):
        t = y / (h - 1)
        c = [int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)]
        if h * 0.42 < y < h * 0.58: c = [min(255, v + 70) for v in c]
        rows.append(b"\x00" + bytes(c) * w)
    raw = zlib.compress(b"".join(rows), 6)
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")

PHOTOS = {"sunset": png(960, 540, (255, 140, 60), (90, 20, 80)), "lake": png(960, 540, (60, 170, 230), (10, 40, 90))}

def feed():
    now = int(time.time() * 1000)
    today = datetime.datetime.utcnow().date()
    d = lambda n: (today + datetime.timedelta(days=n)).isoformat()
    return {"generated": now,
            "sources": [{"name": "Family", "tag": "Family", "color": "#4FC3F7", "kind": "personal"},
                        {"name": "School", "tag": "School", "color": "#FFB300", "kind": "school"}],
            "events": [{"s": 0, "t": "Soccer practice", "start": now + 3 * 3600_000, "end": now + 4 * 3600_000},
                       {"s": 0, "t": "Grandma visits", "day": d(1)},
                       {"s": 1, "t": "Science project due", "day": d(2)},
                       {"s": 0, "t": "Dentist", "start": now + 26 * 3600_000, "end": now + 27 * 3600_000}],
            "weather": {"now": 64, "code": 2, "days": [{"day": d(i), "hi": 66 + i, "lo": 50 + i, "code": 2, "rain": 10} for i in range(4)]}}

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, body, ctype):
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/api/feed": return self.send(json.dumps(feed()).encode(), "application/json")
        if p == "/api/photos": return self.send(json.dumps({"photos": list(PHOTOS)}).encode(), "application/json")
        if p.startswith("/api/photos/") and p.rsplit("/", 1)[1] in PHOTOS: return self.send(PHOTOS[p.rsplit("/", 1)[1]], "image/png")
        self.send_response(404); self.end_headers()

http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
