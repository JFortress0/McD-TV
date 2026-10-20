"""Writes the QA playlist: one playable test channel, a few sports channels and an Entertainment block where most
networks come in several feeds (FHD, HD, West, RAW 60fps, backup, unmarked), like a big IPTV playlist.
Generic names only. Prints the URLs the QA favorites use, one per line, to the file given second."""
import sys

out, favs_file = sys.argv[1], sys.argv[2]
base = "http://10.0.2.2:8765/live/qa/"
lines = ["#EXTM3U"]
n = 0

def add(name, group):
    global n
    n += 1
    url = f"{base}{n}.ts"
    lines.append(f'#EXTINF:-1 tvg-logo="" group-title="{group}",{name}')
    lines.append(url)
    return url

urls = {}
urls["QA Test Channel"] = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
lines.append('#EXTINF:-1 tvg-logo="" group-title="QA Sports",QA Test Channel')
lines.append(urls["QA Test Channel"])
lines.append('#EXTINF:-1 group-title="QA Movies",QA Movie Entry')
lines.append("http://example.com/movie/user/pass/1.mp4")
for s in ["US| ESPN FHD", "US| ESPN HD", "US| NFL RedZone", "US| WNBA League Pass", "US| MLS Season Pass", "US| UFC Fight Pass"]:
    urls[s] = add(s, "US| SPORTS")

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
        urls[name] = add(name, group)
# Channels with no country mark (free ad-supported style), a few of them twice.
for k, show in enumerate(["Classic Sitcoms", "Reality Mix", "Game Show Central", "Retro Drama", "Comedy Mix", "True Crime Now"]):
    urls[show] = add(show, "ENTERTAINMENT")
    if k % 2 == 0:
        add(show + " HD", "ENTERTAINMENT")

open(out, "w").write("\n".join(lines) + "\n")
# J has 7 favorites: the test channel first (the play test opens it), then a mix, two of them a network's lesser feed.
fav_names = ["QA Test Channel", "US| AMC HD", "US| HGTV FHD", "US| ESPN FHD", "TNT West", "US| Food Network FHD", "US| History HD"]
open(favs_file, "w").write("\n".join(urls[f] for f in fav_names if f in urls) + "\n")
print(f"QA playlist: {n + 1} live channels")
