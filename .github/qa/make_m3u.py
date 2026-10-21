"""Writes the QA playlist: one playable test channel, a few sports channels and an Entertainment block where most
networks come in several feeds (FHD, HD, West, RAW 60fps, backup, unmarked), like a big IPTV playlist.
Generic names only. Prints the URLs the QA favorites use, one per line, to the file given second.
Also writes guide.xml next to the playlist (the playlist header points at it): an XMLTV snapshot built around the
emulator's own clock, so Live TV's What's On rows have something on now. The Eagles game started 40 minutes ago on
Philadelphia's FOX station (Huntsville's FOX shows another game), war and AI programmes are on, and an MLS game is
live (it must stay out of Live Games)."""
import os, sys, time

out, favs_file = sys.argv[1], sys.argv[2]
base = "http://10.0.2.2:8765/live/qa/"
lines = ['#EXTM3U url-tvg="http://10.0.2.2:8765/guide.xml"']
n = 0
guide_ids = {}

def tid(net):
    return "".join(c for c in net.lower() if c.isalnum()) + ".us"

def add(name, group, tvg=""):
    global n
    n += 1
    url = f"{base}{n}.ts"
    lines.append(f'#EXTINF:-1 tvg-id="{tvg}" tvg-logo="" group-title="{group}",{name}')
    lines.append(url)
    if tvg:
        guide_ids[tvg] = name
    return url

urls = {}
urls["QA Test Channel"] = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
lines.append('#EXTINF:-1 tvg-logo="" group-title="QA Sports",QA Test Channel')
lines.append(urls["QA Test Channel"])
lines.append('#EXTINF:-1 group-title="QA Movies",QA Movie Entry')
lines.append("http://example.com/movie/user/pass/1.mp4")
for s in ["US| ESPN FHD", "US| ESPN HD", "US| NFL RedZone", "US| WNBA League Pass", "US| MLS Season Pass", "US| UFC Fight Pass"]:
    urls[s] = add(s, "US| SPORTS", "espn.us" if s == "US| ESPN HD" else "")
# Local stations: Huntsville's and Philadelphia's FOX, and the national feed.
for s, t in [("US| FOX 54 WZDX Huntsville", "wzdx.us"), ("US| FOX 29 WTXF Philadelphia", "wtxf.us"), ("US| FOX East HD", "fox.us"),
             ("US| NBC Sports Philadelphia HD", "nbcsportsphiladelphia.us"), ("US| American Heroes Channel HD", "americanheroeschannel.us"),
             ("US| Military History Channel", "militaryhistorychannel.us")]:
    urls[s] = add(s, "US| LOCALS" if "FOX" in s else "US| ENTERTAINMENT", t)

NETWORKS = [
    "AMC", "A&E", "Bravo", "BET", "Comedy Central", "Discovery", "E!", "Food Network", "Freeform", "FX", "FXX", "HGTV",
    "History", "IFC", "Lifetime", "OWN", "Oxygen", "Paramount Network", "Syfy", "TBS", "TLC", "TNT", "Travel Channel",
    "truTV", "TV Land", "USA Network", "WE tv", "Hallmark Channel", "Investigation Discovery", "Animal Planet",
    "National Geographic", "Nat Geo Wild", "Cooking Channel", "GSN", "POP", "Reelz", "Sundance TV", "Ovation",
    "Science Channel", "Destination America",
]
FEEDS = [
    ("US| {} FHD", "US| ENTERTAINMENT"),
    ("US| {} HD", "US| ENTERTAINMENT"),
    ("{} West", "US| ENTERTAINMENT"),
    ("US| {} RAW 60fps", "US| RAW 60FPS"),
    ("{} (backup)", "US| ENTERTAINMENT"),
    ("{} East", "ENTERTAINMENT"),
    ("US| {} SD", "US| ENTERTAINMENT"),
]
for i, net in enumerate(NETWORKS):
    # 1 to 7 feeds per network, so the counts are uneven like a real playlist.
    for fmt, group in FEEDS[: 1 + (i * 3 + 4) % 7]:
        name = fmt.format(net)
        urls[name] = add(name, group, tid(net) if fmt == "US| {} HD" else "")
# Channels with no country mark (free ad-supported style), a few of them twice.
for k, show in enumerate(["Classic Sitcoms", "Reality Mix", "Game Show Central", "Retro Drama", "Comedy Mix", "True Crime Now"]):
    urls[show] = add(show, "ENTERTAINMENT")
    if k % 2 == 0:
        add(show + " HD", "ENTERTAINMENT")

open(out, "w").write("\n".join(lines) + "\n")

# The guide: every guide id has a programme on now, some have the ones the checks look for.
now = int(time.time()) // 60 * 60
def stamp(t):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
SPECIAL = {
    "wtxf.us": ("NFL Football: Philadelphia Eagles at New York Giants", -40, 205, ["Sports event", "Football", "NFL"], True),
    "fox.us": ("NFL Football: Philadelphia Eagles at New York Giants", -40, 205, ["Sports event", "Football", "NFL"], True),
    "wzdx.us": ("NFL Football: Atlanta Falcons at Tampa Bay Buccaneers", -40, 205, ["Sports event", "Football", "NFL"], True),
    "tnt.us": ("NHL Hockey: Boston Bruins at Detroit Red Wings", -30, 150, ["Sports event", "Hockey"], True),
    "espn.us": ("MLS Soccer: Inter Miami CF vs. Atlanta United", -20, 120, ["Sports event", "Soccer"], True),
    "history.us": ("WWII in Color: Road to Victory", -10, 120, ["Documentary", "History", "War"], False),
    "americanheroeschannel.us": ("Air Warriors: F-15E Strike Eagle", -10, 60, ["Documentary", "Military", "Aviation"], False),
    "militaryhistorychannel.us": ("Dogfights: The Air War Over Korea", -10, 60, ["Documentary", "Military", "War"], False),
    "sciencechannel.us": ("The Rise of AI", -10, 60, ["Documentary", "Science", "Technology"], False),
}
xml = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv>']
for t, name in guide_ids.items():
    xml.append(f'<channel id="{t}"><display-name>{name.replace("US| ", "").replace("&", "&amp;")}</display-name></channel>')
for t in guide_ids:
    title, start, mins, cats, live = SPECIAL.get(t, (guide_ids[t].replace("US| ", "").replace(" HD", "") + " Tonight", -15, 60, ["Series"], False))
    s0 = now + start * 60
    x = f'<programme start="{stamp(s0)}" stop="{stamp(s0 + mins * 60)}" channel="{t}"><title>{title.replace("&", "&amp;")}</title>'
    x += "".join(f"<category>{c}</category>" for c in cats) + ("<live />" if live else "") + "</programme>"
    xml.append(x)
# 76ers tonight on NBC Sports Philadelphia: My Teams shows the start time in Central Time.
s0 = now + 3 * 3600
xml.append(f'<programme start="{stamp(s0)}" stop="{stamp(s0 + 9000)}" channel="nbcsportsphiladelphia.us"><title>NBA Basketball: Boston Celtics at Philadelphia 76ers</title><category>Basketball</category></programme>')
xml.append("</tv>")
open(os.path.join(os.path.dirname(os.path.abspath(out)), "guide.xml"), "w").write("\n".join(xml) + "\n")
# J has 7 favorites: the test channel first (the play test opens it), then a mix, two of them a network's lesser feed.
fav_names = ["QA Test Channel", "US| AMC HD", "US| HGTV FHD", "US| ESPN FHD", "TNT West", "US| Food Network FHD", "US| History HD"]
open(favs_file, "w").write("\n".join(urls[f] for f in fav_names if f in urls) + "\n")
print(f"QA playlist: {n + 1} live channels")
