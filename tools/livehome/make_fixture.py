"""Writes the Live TV stand-in fixture: a big IPTV-style playlist and an XMLTV guide snapshot.

Why a stand-in: J's real playlist link (with its login) lives only on his TV and phone, and house rule 3 keeps it
out of the repo, logs and replies. This file copies the shape of his provider's list instead: Xtream groups like
"US| ENTERTAINMENT" and "US| AT&T RAW 60fps", names like "AT&T: AMC RAW", provider mirror groups that repeat
every network, free ad-supported channel blocks, numbered event channels, regional sports networks, hundreds of
local affiliates, and big international blocks. Generic names and fake stream links only.

The guide covers two snapshot days (Central Time):
  A. Sunday 2026-10-25 12:40: Eagles at Giants live on FOX (on the Philadelphia station, not the Huntsville one),
     Cowboys at Commanders on CBS, Bruins at Red Wings on TNT, MLS and Premier League live (must stay out),
     a college football replay (must stay out), war, aviation, history and AI programmes on now.
  B. Tuesday 2026-10-27 19:30: primetime on the networks, Flyers live on NBC Sports Philadelphia, 76ers live on
     NBA TV, Thunder at Lakers later on TNT.
Also writes games.json: the same games as ESPN's scoreboard would list them, for the with-schedules runs.

Run: python3 tools/livehome/make_fixture.py   (writes tools/livehome/fixture/*.gz, deterministic)
"""
import gzip, json, os, random
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixture")
os.makedirs(OUT, exist_ok=True)
R = random.Random(20261025)
CT = ZoneInfo("America/Chicago")

lines = ['#EXTM3U url-tvg="http://fixture.invalid/xmltv.php?username=u&password=p"']
n = 0
tvg_names = {}  # tvg-id -> display name


def add(name, group, tvg=""):
    global n
    n += 1
    attrs = f'tvg-id="{tvg}" tvg-name="{name}" tvg-logo="" group-title="{group}"'
    lines.append(f"#EXTINF:-1 {attrs},{name}")
    lines.append(f"http://fixture.invalid/live/u/p/{n}.ts")
    if tvg and tvg not in tvg_names:
        tvg_names[tvg] = name


def sep(title):
    add(f"##### {title} #####", title)


def tid(net):
    return "".join(c for c in net.lower() if c.isalnum()) + ".us"


# ---------------------------------------------------------------- US cable networks
ENT = [
    "A&E", "AMC", "Bravo", "BET", "Comedy Central", "Discovery", "E!", "Food Network", "Freeform", "FX", "FXX", "FXM",
    "HGTV", "History", "IFC", "Lifetime", "OWN", "Oxygen", "Paramount Network", "Syfy", "TBS", "TLC", "TNT",
    "Travel Channel", "truTV", "TV Land", "USA Network", "WE tv", "Hallmark Channel", "Hallmark Mystery",
    "Investigation Discovery", "Animal Planet", "National Geographic", "Nat Geo Wild", "Cooking Channel", "GSN", "POP",
    "Reelz", "Sundance TV", "Ovation", "Science Channel", "Destination America", "American Heroes Channel",
    "Military History Channel", "Smithsonian Channel", "Magnolia Network", "Vice", "BBC America", "Discovery Life",
    "Crime + Investigation", "MeTV", "Cozi TV", "INSP", "Grit", "Up TV", "Logo", "BET Her", "CMT", "Fuse", "MTV",
    "VH1", "Great American Family", "Heroes & Icons", "Start TV", "Charge!", "Comet", "Laff", "Bounce TV", "Court TV",
    "Ion Mystery", "Story Television", "MotorTrend", "Outdoor Channel", "Sportsman Channel", "Weather Channel",
]
NEWS = ["CNN", "HLN", "Fox News", "MSNBC", "CNBC", "Fox Business", "Bloomberg TV", "NewsNation", "Newsmax", "C-SPAN",
        "C-SPAN 2", "ABC News Live", "CBS News 24/7", "NBC News Now", "Fox Weather", "Scripps News", "Cheddar News", "BBC World News"]
KIDS = ["Cartoon Network", "Disney Channel", "Disney Junior", "Disney XD", "Nickelodeon", "Nick Jr", "Nicktoons",
        "TeenNick", "Boomerang", "Universal Kids", "PBS Kids", "Baby TV", "Cartoonito"]
MOVIES = ["HBO", "HBO 2", "HBO Comedy", "HBO Family", "HBO Signature", "HBO Zone", "Cinemax", "MoreMax", "Showtime",
          "Showtime 2", "Showtime Extreme", "Starz", "Starz Edge", "Starz Encore", "MGM+", "TCM", "Hallmark Movies",
          "Sony Movies", "Movieplex", "Flix", "IndiePlex", "RetroPlex", "Epix Drive-In", "Lifetime Movies"]
SPORTS = ["ESPN", "ESPN2", "ESPNU", "ESPNews", "SEC Network", "ACC Network", "Big Ten Network", "CBS Sports Network",
          "FS1", "FS2", "NFL Network", "NFL RedZone", "MLB Network", "NHL Network", "NBA TV", "Golf Channel",
          "Tennis Channel", "beIN Sports", "Fox Soccer Plus", "Longhorn Network", "SEC Network Plus", "TUDN",
          "ESPN Deportes", "Fox Deportes", "Willow", "Stadium", "Big Ten Network Plus", "NBC Sports Philadelphia",
          "NBC Sports Philadelphia Plus", "NBC Sports Boston", "NBC Sports Chicago", "NBC Sports Bay Area",
          "FanDuel Sports South", "FanDuel Sports Southeast", "FanDuel Sports Detroit", "FanDuel Sports Ohio",
          "FanDuel Sports Sun", "FanDuel Sports Florida", "FanDuel Sports West", "FanDuel Sports SoCal",
          "FanDuel Sports Midwest", "FanDuel Sports North", "FanDuel Sports Wisconsin", "FanDuel Sports Southwest",
          "FanDuel Sports Arizona", "FanDuel Sports Indiana", "FanDuel Sports New Orleans", "FanDuel Sports Oklahoma",
          "FanDuel Sports Kansas City", "FanDuel Sports Great Lakes", "YES Network", "MSG", "MSG Sportsnet", "SNY",
          "NESN", "Marquee Sports Network", "Root Sports", "Altitude", "Monumental Sports", "Spectrum SportsNet LA",
          "Space City Home Network", "Chicago Sports Network", "MASN", "MASN 2"]
LOCAL_HOME = [("CBS 19 WHNT Huntsville", "whnt.us"), ("ABC 31 WAAY Huntsville", "waay.us"), ("NBC 48 WAFF Huntsville", "waff.us"),
              ("FOX 54 WZDX Huntsville", "wzdx.us"), ("CW 15 WHDF Huntsville", "whdf.us")]
LOCAL_PHILLY = [("FOX 29 WTXF Philadelphia", "wtxf.us"), ("CBS 3 KYW Philadelphia", "kyw.us"), ("NBC 10 WCAU Philadelphia", "wcau.us"),
                ("6ABC WPVI Philadelphia", "wpvi.us"), ("PHL17 WPHL Philadelphia", "wphl.us")]
MARKETS = ["New York", "Los Angeles", "Chicago", "Dallas", "Houston", "Atlanta", "Washington", "Boston", "Phoenix",
           "Seattle", "Tampa", "Detroit", "Minneapolis", "Denver", "Orlando", "Cleveland", "Sacramento", "Charlotte",
           "Portland", "Raleigh", "St Louis", "Indianapolis", "Nashville", "Pittsburgh", "Baltimore", "Salt Lake City",
           "San Diego", "San Antonio", "Kansas City", "Columbus", "Milwaukee", "Cincinnati", "Las Vegas", "Austin",
           "Jacksonville", "Birmingham", "Memphis", "New Orleans", "Louisville", "Oklahoma City", "Richmond", "Albany",
           "Buffalo", "Tulsa", "Omaha", "Knoxville", "Chattanooga", "Greenville", "Mobile", "Montgomery", "Little Rock",
           "Des Moines", "Spokane", "Boise", "Wichita", "Honolulu", "Anchorage", "Fresno", "Tucson", "El Paso"]
FAST = ["Westerns", "Classic TV", "Forensic Files", "Unsolved Mysteries", "Cops", "Bar Rescue", "Pawn Stars",
        "American Pickers", "Storage Wars", "Hell's Kitchen", "Kitchen Nightmares", "Top Gear", "MythBusters", "Dog the Bounty Hunter",
        "Cheers", "Frasier", "Star Trek", "Doctor Who", "Antiques Roadshow", "This Old House", "Bob Ross", "Judge Judy",
        "The Price Is Right", "Family Feud", "Deal or No Deal", "Love Island", "Survivor", "Big Brother", "Baywatch",
        "Rescue 911", "Wild Kingdom", "Shark Week", "Ghost Hunters", "Ancient Aliens", "Real Disaster", "Court Cam",
        "Crime 360", "Midsomer Murders", "Inspector Morse", "Hallmark Drama", "Gunsmoke", "Bonanza", "Andy Griffith",
        "I Love Lucy", "Happy Days", "Mad About You", "Night Court", "Murder She Wrote", "Columbo", "Matlock",
        "Perry Mason", "Highway Patrol", "Mystery Science Theater", "Stand-Up Comedy", "Comedy Dynamics", "Dry Bar Comedy",
        "Fail Army", "Funny Videos", "Pet Collective", "Tastemade", "Bon Appetit", "Food52", "Gusto TV", "Fight Network",
        "Real Stories", "Docurama", "History Hit", "Timeline", "WW2 TV", "Military Heroes", "Wings TV", "Aviation Nation",
        "Tech Now", "AI World", "Gadget Lab", "Cyber Crime TV", "Space Channel", "NASA TV", "Science Is Fun",
        "Classic Cartoons", "Kids Flix", "Anime All Day", "Retro Movies", "Action Hits", "Horror Movies", "Rom Com Movies",
        "Sci-Fi Movies", "Western Movies", "Thriller Movies", "Black Cinema", "Asian Cinema", "Indie Films", "Shorts TV"]
INTL = {
    "UK": (["BBC One", "BBC Two", "ITV1", "ITV2", "Channel 4", "Channel 5", "Sky One", "Sky Atlantic", "Dave", "Gold",
            "Sky News", "BBC News", "Sky Sports Main Event", "Sky Sports Premier League", "Sky Sports Football",
            "Sky Sports Cricket", "Sky Sports F1", "Sky Sports Golf", "Sky Sports Arena", "Sky Sports Action",
            "TNT Sports 1", "TNT Sports 2", "TNT Sports 3", "TNT Sports 4", "Eurosport 1", "Eurosport 2",
            "Premier Sports 1", "Premier Sports 2", "Sky Cinema Premiere", "Sky Cinema Action"], 6),
    "CA": (["CBC", "CTV", "Global", "Citytv", "TSN 1", "TSN 2", "TSN 3", "TSN 4", "TSN 5", "Sportsnet One",
            "Sportsnet Ontario", "Sportsnet East", "Sportsnet West", "Sportsnet Pacific", "Sportsnet 360", "CTV Drama",
            "CTV Comedy", "Showcase", "W Network", "Crave 1"], 5),
    "LATINO": (["Univision", "Telemundo", "UniMas", "Galavision", "ESPN Deportes", "Fox Deportes", "TUDN", "beIN Sports Espanol",
                "Azteca", "Canal 5", "Las Estrellas", "Cinelatino", "Pasiones", "Discovery en Espanol", "Nat Geo Mundo"], 8),
    "FR": (["TF1", "France 2", "France 3", "M6", "Canal+", "Canal+ Sport", "beIN Sports 1", "beIN Sports 2", "beIN Sports 3",
            "RMC Sport 1", "RMC Sport 2", "Eurosport 1 FR", "L'Equipe", "BFM TV"], 6),
    "DE": (["Das Erste", "ZDF", "RTL", "Sat.1", "ProSieben", "Sky Sport Bundesliga 1", "Sky Sport Bundesliga 2",
            "Sky Sport Top Event", "DAZN 1", "DAZN 2", "Sport1", "Eurosport 1 DE"], 6),
    "IT": (["Rai 1", "Rai 2", "Canale 5", "Italia 1", "Sky Sport Uno", "Sky Sport Calcio", "Sky Sport Serie A", "DAZN IT 1", "Sky Sport F1"], 6),
    "AR": (["beIN Sports 1 HD", "beIN Sports 2 HD", "beIN Sports 3 HD", "beIN Sports 4 HD", "beIN Sports 5 HD", "SSC 1", "SSC 2",
            "AD Sports 1", "AD Sports 2", "Al Jazeera", "MBC 1", "MBC 2", "Rotana Cinema"], 8),
    "IN": (["Star Plus", "Zee TV", "Sony TV", "Colors", "Star Sports 1", "Star Sports 2", "Star Sports Select", "Sony Ten 1",
            "Sony Ten 2", "Willow India", "Aaj Tak", "NDTV"], 7),
    "PT": (["Sport TV 1", "Sport TV 2", "Sport TV 3", "Benfica TV", "SIC", "TVI", "Premiere FC", "SporTV 1", "SporTV 2", "Globo"], 6),
    "NL": (["NPO 1", "NPO 2", "RTL 4", "Ziggo Sport", "ESPN 1 NL", "ESPN 2 NL", "Viaplay NL"], 5),
    "PL": (["TVP 1", "Polsat", "TVN", "Canal+ Sport PL", "Polsat Sport", "Eleven Sports 1"], 6),
    "TR": (["TRT 1", "ATV", "Show TV", "beIN Sports TR 1", "beIN Sports TR 2", "S Sport", "TRT Spor"], 6),
    "AU": (["Seven", "Nine", "Ten", "Fox Footy", "Fox League", "Fox Cricket", "Kayo 1", "Kayo 2", "ABC Australia"], 6),
    "EX-YU": (["Arena Sport 1", "Arena Sport 2", "Arena Sport 3", "Sport Klub 1", "Sport Klub 2", "RTS 1", "HRT 1", "Nova S"], 7),
    "AF": (["SuperSport Premier League", "SuperSport Football", "SuperSport Rugby", "SuperSport Cricket", "SuperSport Variety 1", "Canal+ Afrique"], 6),
}

# ---------------------------------------------------------------- playlist
sep("US ENTERTAINMENT")
for net in ENT:
    for tag in ["FHD", "HD", "SD"]:
        add(f"US| {net} {tag}", "US| ENTERTAINMENT", tid(net) if tag == "HD" else "")
    if R.random() < 0.6:
        add(f"US| {net} West", "US| ENTERTAINMENT", "")
    if R.random() < 0.3:
        add(f"US| {net} (backup)", "US| ENTERTAINMENT", "")
for net in NEWS:
    for tag in ["FHD", "HD"]:
        add(f"US| {net} {tag}", "US| NEWS", tid(net) if tag == "HD" else "")
for net in KIDS:
    for tag in ["HD", "SD"]:
        add(f"US| {net} {tag}", "US| KIDS", tid(net) if tag == "HD" else "")
for net in MOVIES:
    for tag in ["FHD", "HD", "East", "West"]:
        add(f"US| {net} {tag}", "US| PREMIUM", tid(net) if tag == "HD" else "")
sep("US SPORTS")
for net in SPORTS:
    for tag in ["FHD", "HD"]:
        add(f"US| {net} {tag}", "US| SPORTS", tid(net) if tag == "HD" else "")
    if R.random() < 0.4:
        add(f"US| {net} 4K", "US| SPORTS 4K", "")
sep("US LOCALS")
for name, t in LOCAL_HOME + LOCAL_PHILLY:
    add(f"US| {name}", "US| LOCALS", t)
    add(f"US| {name} HD", "US| LOCALS", "")
for city in MARKETS:
    for netw in ["ABC", "CBS", "NBC", "FOX", "CW", "PBS"]:
        add(f"US| {netw} {R.randint(2, 69)} {city}", "US| LOCALS", "")
add("US| PBS HD", "US| LOCALS", "pbs.us")
add("US| ION HD", "US| LOCALS", "ion.us")
add("US| ABC East HD", "US| LOCALS", "abc.us")
add("US| CBS East HD", "US| LOCALS", "cbs.us")
add("US| NBC East HD", "US| LOCALS", "nbc.us")
add("US| FOX East HD", "US| LOCALS", "fox.us")

# Provider mirror groups: every network again, under the source's name.
MIRRORS = [("US| AT&T RAW 60fps", "AT&T: {} RAW"), ("US| SLING", "SLING: {}"), ("US| DIRECTV", "DTV: {} HD"),
           ("US| FUBO", "FUBO: {}"), ("US| YOUTUBE TV", "YTTV: {}"), ("US| HULU LIVE", "{} (Hulu)"),
           ("US| PHILO", "{} [Philo]")]
for group, fmt in MIRRORS:
    sep(group)
    pool = ENT + NEWS + KIDS + SPORTS[:28] + MOVIES[:10]
    for net in pool:
        if R.random() < 0.75:
            add(fmt.format(net), group, "")

# Free ad-supported blocks: hundreds of single-show channels.
for prov, count in [("Pluto TV", 300), ("Tubi", 160), ("Samsung TV Plus", 200), ("Xumo", 120), ("Roku", 170),
                    ("Plex", 140), ("Freevee", 90), ("Vizio", 80), ("LG Channels", 80), ("Stirr", 60), ("Distro", 50)]:
    sep(prov.upper())
    for k in range(count):
        show = FAST[(k * 7 + len(prov)) % len(FAST)]
        suffix = "" if k < len(FAST) else f" {k // len(FAST) + 1}"
        add(f"{prov}: {show}{suffix}", f"US| {prov.upper()}", "")

# Numbered event channels (one game each while it is on).
EVENTS = [("US| NFL SUNDAY TICKET", "NFL {:02d}", 16), ("US| NCAAF", "NCAAF {:02d}", 120), ("US| NBA LEAGUE PASS", "NBA {:02d}", 15),
          ("US| NHL CENTER ICE", "NHL {:02d}", 16), ("US| MLB EXTRA INNINGS", "MLB {:02d}", 15), ("US| ESPN+", "ESPN+ {:03d}", 320),
          ("US| PEACOCK EVENTS", "Peacock {:02d}", 50), ("US| PARAMOUNT+", "Paramount+ {:02d}", 50), ("US| DAZN", "DAZN {:02d}", 30),
          ("US| FLOSPORTS", "FloSports {:02d}", 40), ("US| B1G+", "B1G+ {:02d}", 60), ("US| SEC+", "SEC+ {:02d}", 50),
          ("US| ACC EXTRA", "ACCNX {:02d}", 40), ("US| MLS SEASON PASS", "MLS {:02d}", 30), ("US| WNBA", "WNBA {:02d}", 12),
          ("US| NCAAB", "NCAAB {:02d}", 60), ("US| VICTORY+", "Victory+ {:02d}", 20), ("US| ESPN EVENTS", "ESPN Events {:02d}", 20)]
EVENT_NAMES = {}  # url index -> name with the game in it (snapshot A)
for group, fmt, count in EVENTS:
    sep(group)
    for k in range(1, count + 1):
        name = fmt.format(k)
        if group == "US| NFL SUNDAY TICKET" and k == 1:
            name += ": Philadelphia Eagles vs New York Giants 1:00PM ET"
        elif group == "US| NFL SUNDAY TICKET" and k == 2:
            name += ": Dallas Cowboys vs Washington Commanders 1:00PM ET"
        elif group == "US| NFL SUNDAY TICKET" and k == 3:
            name += ": Atlanta Falcons vs Tampa Bay Buccaneers 1:00PM ET"
        elif group == "US| MLS SEASON PASS" and k == 1:
            name += ": Inter Miami vs Atlanta United 1:30PM ET"
        elif k > 6:
            name += " | No Event Streaming"
        add(name, group, "")
sep("PPV")
for k in range(1, 41):
    add(f"PPV {k:02d}", "PPV EVENTS", "")
sep("24/7")
for show in FAST[:70]:
    add(f"24/7 {show}", "24/7 CHANNELS", "")
    if R.random() < 0.5:
        add(f"24/7 {show} HD", "24/7 CHANNELS", "")
sep("MUSIC")
for k in range(1, 61):
    add(f"Music Choice {k:02d}", "US| MUSIC", "")

# International blocks: each network in several copies.
for cc, (nets, copies) in INTL.items():
    sep(cc)
    for net in nets:
        for tag in ["FHD", "HD", "SD", "4K", "HEVC", "HD (backup)", "VIP", "Low"][:copies]:
            add(f"{cc}| {net} {tag}", f"{cc}| {'SPORTS' if any(w in net for w in ('Sport', 'beIN', 'TSN', 'DAZN', 'SuperSport', 'Arena', 'Klub', 'Kayo', 'Fox Footy', 'Fox League', 'Fox Cricket', 'SSC', 'AD Sports', 'Willow', 'Benfica', 'Eleven', 'Premiere')) else 'GENERAL'}", "")
    # Filler channels for the block.
    for k in range(1, {"UK": 220, "CA": 160, "LATINO": 520, "FR": 260, "DE": 260, "IT": 220, "AR": 480, "IN": 420, "PT": 200,
                       "NL": 140, "PL": 180, "TR": 220, "AU": 120, "EX-YU": 400, "AF": 180}[cc]):
        add(f"{cc}| Channel {k:03d}", f"{cc}| GENERAL", "")
# Other countries.
for cc in ["GR", "RO", "AL", "HU", "CZ", "RU", "SE", "NO", "DK", "FI", "BE", "CH", "AT", "IR", "KU", "PH", "PK", "KR", "JP", "CN", "VN", "TH", "IL", "CARIB"]:
    sep(cc)
    for k in range(1, 121):
        add(f"{cc}| Channel {k:03d}", f"{cc}| GENERAL", "")
        if k <= 8:
            add(f"{cc}| Sport {k}", f"{cc}| SPORTS", "")

def write_gz(name, text):
    # mtime 0: the same input gives the same bytes, so a re-run never shows up as a change.
    with open(os.path.join(OUT, name), "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
        f.write(text.encode("utf-8"))


write_gz("playlist.m3u.gz", "\n".join(lines) + "\n")

# ---------------------------------------------------------------- guide
progs = {}  # tvg -> list of (start, end, title, desc, [categories], extra)


def p(t, start, mins, title, desc="", cats=(), live=False, replay=False):
    progs.setdefault(t, []).append((start, start + timedelta(minutes=mins), title, desc, list(cats), live, replay))


def day(y, mo, d, h=0, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=CT)


FILLER = {
    "amc.us": [("The Walking Dead", "Drama"), ("Breaking Bad", "Drama"), ("Mad Men", "Drama")],
    "fx.us": [("The Bear", "Comedy"), ("Shogun", "Drama"), ("Archer", "Animation")],
    "fxx.us": [("The Simpsons", "Animation"), ("It's Always Sunny in Philadelphia", "Comedy")],
    "tnt.us": [("Law & Order", "Crime drama"), ("Inside the NBA", "Sports talk")],
    "tbs.us": [("Big Bang Theory", "Sitcom"), ("Family Guy", "Animation")],
    "usanetwork.us": [("WWE Raw", "Wrestling"), ("Chrisley Knows Best", "Reality")],
    "bravo.us": [("Below Deck", "Reality"), ("Real Housewives", "Reality")],
    "ae.us": [("The First 48", "Crime"), ("Duck Dynasty", "Reality")],
    "hgtv.us": [("Fixer Upper", "Home improvement"), ("Property Brothers", "Home improvement")],
    "foodnetwork.us": [("Diners, Drive-Ins and Dives", "Cooking"), ("Chopped", "Cooking")],
    "tlc.us": [("90 Day Fiance", "Reality")],
    "paramountnetwork.us": [("Yellowstone", "Western"), ("Bar Rescue", "Reality")],
    "comedycentral.us": [("The Daily Show", "Talk"), ("South Park", "Animation")],
    "syfy.us": [("Star Trek", "Science fiction")],
    "discovery.us": [("Gold Rush", "Reality"), ("Deadliest Catch", "Reality")],
    "history.us": [("Pawn Stars", "Reality"), ("American Pickers", "Reality")],
    "nationalgeographic.us": [("Drain the Oceans", "Documentary"), ("Life Below Zero", "Reality")],
    "hallmarkchannel.us": [("A Fall Romance", "Movie")],
    "lifetime.us": [("Dance Moms", "Reality")],
    "freeform.us": [("Grown-ish", "Comedy")],
    "hbo.us": [("Movie: Dune Part Two", "Movie")],
    "showtime.us": [("Dexter", "Drama")],
    "starz.us": [("Outlander", "Drama")],
    "smithsonianchannel.us": [("Aerial America", "Documentary")],
    "americanheroeschannel.us": [("Combat Ships", "Documentary")],
    "militaryhistorychannel.us": [("Battle Castle", "Documentary")],
    "sciencechannel.us": [("How It's Made", "Documentary")],
    "pbs.us": [("Antiques Roadshow", "Reality")],
    "cnn.us": [("CNN Newsroom", "News")],
    "foxnews.us": [("Fox News Live", "News")],
    "cnbc.us": [("Squawk Box", "Business")],
    "bloombergtv.us": [("Bloomberg Markets", "Business")],
    "espn.us": [("SportsCenter", "Sports talk")],
    "espn2.us": [("NFL Live", "Sports talk")],
    "fs1.us": [("The Herd", "Sports talk")],
    "nflnetwork.us": [("NFL Total Access", "Sports talk")],
    "nbatv.us": [("NBA Gametime", "Sports talk")],
    "nhlnetwork.us": [("NHL Now", "Sports talk")],
    "mlbnetwork.us": [("MLB Tonight", "Sports talk")],
    "secnetwork.us": [("SEC Now", "Sports talk")],
    "nbcsportsphiladelphia.us": [("Philly Sports Talk", "Sports talk")],
    "whnt.us": [("WHNT News 19", "News")], "waay.us": [("WAAY 31 News", "News")], "waff.us": [("WAFF 48 News", "News")],
    "wzdx.us": [("FOX 54 News", "News")], "whdf.us": [("Judge Mathis", "Court")],
    "wtxf.us": [("FOX 29 News", "News")], "kyw.us": [("CBS News Philadelphia", "News")], "wcau.us": [("NBC10 News", "News")],
    "wpvi.us": [("Action News", "News")], "wphl.us": [("Phl17 Morning", "News")],
    "abc.us": [("ABC News", "News")], "cbs.us": [("CBS News", "News")], "nbc.us": [("NBC News", "News")], "fox.us": [("FOX News Sunday", "News")],
}
# Filler for every guide id, every 30 minutes, both days (covers 06:00 to 24:00).
for t, name in tvg_names.items():
    shows = FILLER.get(t, [(name.replace("US| ", "").replace(" HD", "") + " Presents", "Series")])
    for d in [day(2026, 10, 25), day(2026, 10, 27)]:
        k = 0
        s = d + timedelta(hours=6)
        while s < d + timedelta(hours=24):
            title, cat = shows[k % len(shows)]
            p(t, s, 30, title, f"{title}.", [cat])
            s += timedelta(minutes=30)
            k += 1

# Snapshot A: Sunday 2026-10-25, 12:40 CT. Special programmes replace the filler where they overlap.
A = day(2026, 10, 25)
sp = []
def S(t, h, mi, mins, title, desc="", cats=(), live=False, replay=False):
    sp.append((t, A + timedelta(hours=h, minutes=mi), mins, title, desc, cats, live, replay))
S("wtxf.us", 12, 0, 205, "NFL Football: Philadelphia Eagles at New York Giants", "The Eagles visit MetLife Stadium in an NFC East matchup.", ["Sports event", "Football", "NFL"], live=True)
S("fox.us", 12, 0, 205, "NFL Football: Philadelphia Eagles at New York Giants", "The Eagles visit MetLife Stadium in an NFC East matchup.", ["Sports event", "Football", "NFL"], live=True)
S("wzdx.us", 12, 0, 205, "NFL Football: Atlanta Falcons at Tampa Bay Buccaneers", "NFC South rivals meet in Tampa.", ["Sports event", "Football", "NFL"], live=True)
S("kyw.us", 12, 0, 205, "NFL Football: Dallas Cowboys at Washington Commanders", "", ["Sports event", "Football", "NFL"], live=True)
S("whnt.us", 12, 0, 205, "NFL Football: Dallas Cowboys at Washington Commanders", "", ["Sports event", "Football", "NFL"], live=True)
S("cbs.us", 12, 0, 205, "NFL Football: Dallas Cowboys at Washington Commanders", "", ["Sports event", "Football", "NFL"], live=True)
S("tnt.us", 11, 30, 180, "NHL Hockey: Boston Bruins at Detroit Red Wings", "Original Six matinee.", ["Sports event", "Hockey"], live=True)
S("fs1.us", 12, 30, 120, "MLS Cup Playoffs: Inter Miami CF vs. Atlanta United", "Round one, game one.", ["Sports event", "Soccer"], live=True)
S("usanetwork.us", 11, 30, 120, "Premier League Soccer: Arsenal vs. Liverpool", "", ["Sports event", "Soccer"], live=True)
S("secnetwork.us", 12, 0, 210, "College Football: Alabama at Tennessee", "From Neyland Stadium (replay).", ["Football", "College football"], replay=True)
S("espn2.us", 12, 0, 120, "WNBA Finals: Game 5 (Replay)", "", ["Basketball"], replay=True)
S("espn.us", 12, 0, 60, "NFL Live", "Highlights and analysis.", ["Sports talk"])
S("history.us", 12, 0, 120, "WWII in Color: Road to Victory", "Restored footage of the Allied push across Europe.", ["Documentary", "History", "War"])
S("history.us", 14, 0, 60, "The Food That Built America", "How snack makers changed the American diet.", ["Documentary", "History"])
S("americanheroeschannel.us", 12, 0, 60, "Air Warriors: F-15E Strike Eagle", "The U.S. Air Force's long-range strike fighter.", ["Documentary", "Military", "Aviation"])
S("americanheroeschannel.us", 13, 0, 60, "Hitler's Last Stand", "The final months of the war in Europe.", ["Documentary", "War", "History"])
S("militaryhistorychannel.us", 12, 30, 60, "Dogfights: The Air War Over Korea", "Sabres against MiGs over the Yalu.", ["Documentary", "Military", "War"])
S("smithsonianchannel.us", 12, 0, 60, "Air Disasters", "Investigators piece together a crash.", ["Documentary", "Aviation"])
S("sciencechannel.us", 12, 0, 60, "The Rise of AI", "How machine learning moved from labs to daily life.", ["Documentary", "Science", "Technology"])
S("pbs.us", 12, 0, 60, "NOVA: Cyberwar Threat", "Inside the hidden world of cyber attacks.", ["Documentary", "Technology", "Science"])
S("bloombergtv.us", 12, 0, 60, "Bloomberg Technology", "Artificial intelligence, chips and the companies behind them.", ["Business", "Technology"])
S("discovery.us", 12, 0, 60, "Gold Rush", "", ["Reality"])
S("nationalgeographic.us", 12, 0, 60, "Drain the Oceans: D-Day", "The wrecks off Normandy beaches.", ["Documentary", "History"])
S("nbcsportsphiladelphia.us", 17, 30, 30, "76ers Pregame Live", "", ["Sports talk"])
S("nbcsportsphiladelphia.us", 18, 0, 150, "NBA Basketball: Boston Celtics at Philadelphia 76ers", "", ["Sports event", "Basketball"])
S("nhlnetwork.us", 16, 0, 150, "NHL Hockey: New York Rangers at Philadelphia Flyers", "", ["Sports event", "Hockey"])
S("nbc.us", 19, 20, 200, "NFL Football: Kansas City Chiefs at Buffalo Bills", "Sunday Night Football.", ["Sports event", "Football", "NFL"], live=True)
S("waff.us", 19, 20, 200, "NFL Football: Kansas City Chiefs at Buffalo Bills", "Sunday Night Football.", ["Sports event", "Football", "NFL"], live=True)
S("wcau.us", 19, 20, 200, "NFL Football: Kansas City Chiefs at Buffalo Bills", "Sunday Night Football.", ["Sports event", "Football", "NFL"], live=True)

# Snapshot B: Tuesday 2026-10-27, 19:30 CT.
B = day(2026, 10, 27)
def T(t, h, mi, mins, title, desc="", cats=(), live=False, replay=False):
    sp.append((t, B + timedelta(hours=h, minutes=mi), mins, title, desc, cats, live, replay))
for t in ["nbc.us", "waff.us", "wcau.us"]:
    T(t, 19, 0, 120, "The Voice", "Blind auditions continue.", ["Reality", "Music"])
for t in ["cbs.us", "whnt.us", "kyw.us"]:
    T(t, 19, 0, 60, "FBI", "", ["Crime drama"]); T(t, 20, 0, 60, "FBI: International", "", ["Crime drama"])
for t in ["abc.us", "waay.us", "wpvi.us"]:
    T(t, 19, 0, 120, "Dancing with the Stars", "", ["Reality"])
for t in ["fox.us", "wzdx.us", "wtxf.us"]:
    T(t, 19, 0, 60, "The Floor", "", ["Game show"]); T(t, 20, 0, 60, "Doc", "", ["Medical drama"])
T("whdf.us", 19, 0, 60, "Superman & Lois", "", ["Drama"])
T("nbcsportsphiladelphia.us", 18, 0, 150, "NHL Hockey: Philadelphia Flyers at Pittsburgh Penguins", "", ["Sports event", "Hockey"], live=True)
T("nbatv.us", 19, 0, 150, "NBA Basketball: Philadelphia 76ers at Milwaukee Bucks", "", ["Sports event", "Basketball"], live=True)
T("tnt.us", 21, 30, 150, "NBA Basketball: Oklahoma City Thunder at Los Angeles Lakers", "", ["Sports event", "Basketball"], live=True)
T("fs1.us", 19, 0, 180, "MLB Baseball: World Series Game 3", "", ["Sports event", "Baseball"], live=True)
T("amc.us", 19, 0, 60, "The Walking Dead: Daryl Dixon", "", ["Drama"])
T("fx.us", 19, 0, 30, "The Bear", "", ["Comedy"])
T("history.us", 19, 0, 120, "The Civil War: A Nation Divided", "Battles that decided the war.", ["Documentary", "History", "War"])
T("americanheroeschannel.us", 19, 0, 60, "Top Guns: Inside the Air Force Weapons School", "Training the Air Force's best fighter pilots.", ["Documentary", "Military", "Aviation"])
T("sciencechannel.us", 19, 0, 60, "Hackers: Inside the Cyber Wars", "Ethical hackers test the power grid.", ["Documentary", "Technology"])

overrides = {}
for (t, s, mins, title, desc, cats, live, replay) in sp:
    overrides.setdefault(t, []).append((s, s + timedelta(minutes=mins), title, desc, list(cats), live, replay))
for t, specials in overrides.items():
    base = [x for x in progs.get(t, []) if not any(x[0] < e and x[1] > s for (s, e, *_r) in specials)]
    progs[t] = sorted(base + specials, key=lambda x: x[0])

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")

def stamp(d):
    return d.strftime("%Y%m%d%H%M%S %z")

xml = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="jarvis-fixture">']
for t, name in tvg_names.items():
    xml.append(f'<channel id="{esc(t)}"><display-name>{esc(name.replace("US| ", ""))}</display-name></channel>')
for t, lst in progs.items():
    for (s, e, title, desc, cats, live, replay) in lst:
        x = f'<programme start="{stamp(s)}" stop="{stamp(e)}" channel="{esc(t)}"><title lang="en">{esc(title)}</title>'
        if desc:
            x += f'<desc lang="en">{esc(desc)}</desc>'
        for c in cats:
            x += f'<category lang="en">{esc(c)}</category>'
        if live:
            x += "<live />"
        if replay:
            x += "<previously-shown />"
        xml.append(x + "</programme>")
xml.append("</tv>")
write_gz("guide.xml.gz", "\n".join(xml) + "\n")

# ---------------------------------------------------------------- games (ESPN scoreboard view of the same days)
def g(league, gid, state, start, away, home, casts, detail=""):
    return {"league": league, "id": gid, "state": state, "start": int(start.timestamp() * 1000), "detail": detail,
            "away": away, "home": home, "casts": casts}
def tm(loc, nick, abbr):
    return {"name": f"{loc} {nick}", "short": nick, "nick": nick, "loc": loc, "abbr": abbr}
EAG, NYG, DAL, WSH = tm("Philadelphia", "Eagles", "PHI"), tm("New York", "Giants", "NYG"), tm("Dallas", "Cowboys", "DAL"), tm("Washington", "Commanders", "WSH")
ATL, TB, KC, BUF = tm("Atlanta", "Falcons", "ATL"), tm("Tampa Bay", "Buccaneers", "TB"), tm("Kansas City", "Chiefs", "KC"), tm("Buffalo", "Bills", "BUF")
PHI76, BOS, MIL = tm("Philadelphia", "76ers", "PHI"), tm("Boston", "Celtics", "BOS"), tm("Milwaukee", "Bucks", "MIL")
PHF, NYR, PIT, BOSB, DET = tm("Philadelphia", "Flyers", "PHI"), tm("New York", "Rangers", "NYR"), tm("Pittsburgh", "Penguins", "PIT"), tm("Boston", "Bruins", "BOS"), tm("Detroit", "Red Wings", "DET")
OKC, LAL = tm("Oklahoma City", "Thunder", "OKC"), tm("Los Angeles", "Lakers", "LAL")
games = {
    "A": {"now": int(day(2026, 10, 25, 12, 40).timestamp() * 1000), "games": [
        g("NFL", "a1", "in", A + timedelta(hours=12), EAG, NYG, ["FOX"], "Q2 4:11"),
        g("NFL", "a2", "in", A + timedelta(hours=12), DAL, WSH, ["CBS"], "Q2 7:30"),
        g("NFL", "a3", "in", A + timedelta(hours=12), ATL, TB, ["FOX"], "Q2 1:02"),
        g("NFL", "a4", "pre", A + timedelta(hours=19, minutes=20), KC, BUF, ["NBC"]),
        g("NHL", "a5", "in", A + timedelta(hours=11, minutes=30), BOSB, DET, ["TNT"], "2nd 8:15"),
        g("NHL", "a6", "pre", A + timedelta(hours=16), NYR, PHF, ["NHL Network"]),
        g("NBA", "a7", "pre", A + timedelta(hours=18), BOS, PHI76, ["NBC Sports Philadelphia"]),
    ]},
    "B": {"now": int(day(2026, 10, 27, 19, 30).timestamp() * 1000), "games": [
        g("NHL", "b1", "in", B + timedelta(hours=18), PHF, PIT, ["NBC Sports Philadelphia"], "3rd 12:40"),
        g("NBA", "b2", "in", B + timedelta(hours=19), PHI76, MIL, ["NBA TV"], "Q1 2:10"),
        g("NBA", "b3", "pre", B + timedelta(hours=21, minutes=30), OKC, LAL, ["TNT"]),
        g("NFL", "b4", "pre", day(2026, 11, 1, 12), EAG, DAL, ["FOX"]),
    ]},
}
with open(os.path.join(OUT, "games.json"), "w") as f:
    json.dump(games, f, indent=1)
print(f"fixture: {n} playlist entries, {len(tvg_names)} guide channels, {sum(len(v) for v in progs.values())} programmes")
