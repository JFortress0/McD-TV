# Jarvis configuration map

How every setting is entered, stored, synced and read across the TV app, the Control page and the web app. Checked against the code on October 19, 2026. Read it before changing any setting screen.

- **TV:** SharedPreferences file `mcdtv_settings` (`data/Prefs.kt`). Per-profile keys end in `@p1`, `@p2`, `@p3`.
- **Control page:** `docs/index.html`. It stores only `mcdtv_link` and `mcdtv_tvs` in the browser. Everything else goes to the TV through the relay `set` command.
- **Web app:** `docs/app/index.html`. Browser localStorage, keys `mcdtv_*`.

## How values travel

| Path | What it carries | Code |
|---|---|---|
| Relay `set` (Control page to one TV) | `addon_urls`, `m3u_url`, `custom_stream_url`, `origin_filter`, `us_only`, `slow_connection`, `play_intro`, `wall_url`, `wall_start`, `wall_due`, `wall_baby`, `wall_mode`, `tv_label`. Sent only when typed: `wall_key`, `tmdb_key`, `mdblist_key`, `jarvis_key`. A blank key is ignored, so `set` can never clear a key. | `docs/index.html` `save()`, `data/Relay.kt` `apply()` |
| HouseSync (TV to TV, house topic) | The `SHARED` list: `addon_urls`, `websites`, `m3u_url`, `custom_stream_url`, `tmdb_key`, `mdblist_key`, `jarvis_key`, `jarvis_model`, `wall_url`, `wall_key`, `wall_due`, `wall_baby`, `origin_filter`, `us_only`, `slow_connection`, `play_intro`, `max_movie_gb`, `max_episode_gb`, `rd_client_id`, `rd_client_secret`, `rd_access`, `rd_refresh`, `rd_expires`. "Copy once" sends the same list without `rd_*`. | `data/HouseSync.kt` |
| ProfileSync (TV to TV, house topic) | Per-profile history, My List, favorites, Live TV favorites, taste, profile names. Merged entry by entry. | `data/sync/ProfileSync.kt` |
| `web_init` (TV to phone, relay) | TMDB and MDBList keys, addons, playlist link, websites, origin, profiles and lists, `rd_connected` (never the RD tokens), the house link, the Jarvis key (kept on the phone only while "Use Jarvis on this device" is on). | `data/Relay.kt` |
| Setup link and invite (phone to phone) | `SETUP_KEYS`: `mcdtv_link`, `mcdtv_web_init`, `mcdtv_web_profile`, `mcdtv_web_plists`, `mcdtv_web_jarvis`, `mcdtv_web_jarvis_local`, `mcdtv_web_rdc`, `mcdtv_web_rdt`, `mcdtv_web_addons_bak`, `mcdtv_web_addons_mine`, `mcdtv_web_extplayer`, `mcdtv_web_tmdb`, `mcdtv_web_mdb`, `mcdtv_web_origin`. Invites leave out `mcdtv_web_rdc`, and leave out `mcdtv_web_rdt` unless it is ticked. | `docs/app/index.html` `setupLink()` |

## Movies and shows

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| TMDB key | Built into the APK from the GitHub secret `TMDB_API_KEY`; Control page; TV home Wi-Fi page | `tmdb_key` (a saved key wins over the built-in one) | HouseSync, `web_init` | yes |
| TMDB key, this phone only | Web Settings > Keys | `mcdtv_web_tmdb` (the TV's key wins when the TV answers) | setup link, invite | yes |
| MDBList key | Built in from `MDBLIST_API_KEY`; Control page; web Settings > Keys | `mdblist_key`; web `mcdtv_web_mdb` | HouseSync, `web_init` | yes |
| Addons | Control page, TV home Wi-Fi page | `addon_urls` | HouseSync, `web_init`; the phone keeps a backup in `mcdtv_web_addons_bak` | addon links can hold tokens |
| Addons, this phone only | Web Settings > Addons | `mcdtv_web_addons_mine` | setup link, invite | maybe |
| Websites | TV home Wi-Fi page | `websites` | HouseSync | no |
| Titles, US release dates, slow connection, size limits, intro | TV Settings; Control page (not the size limits) | `origin_filter`, `us_only`, `slow_connection`, `max_movie_gb`, `max_episode_gb`, `play_intro` | HouseSync. The phone can override titles with `mcdtv_web_origin`. | no |
| My Stream link | Control page | `custom_stream_url` | HouseSync | maybe |
| Real-Debrid, TVs | TV Settings > Playback > Connect (device code) | `rd_client_id`, `rd_client_secret`, `rd_access`, `rd_refresh`, `rd_expires`; `rd_last_add` is per TV | HouseSync: one sign-in for the whole house. Phones only see `rd_connected`. | yes |
| Real-Debrid, phone sign-in | Web Settings > Real-Debrid > Connect (device code, through `jarvis-rd.netlify.app`) | `mcdtv_web_rdc` = `{id, secret, t, r, exp}` | setup link only | yes |
| Real-Debrid API token (iPhone playback) | Web Settings > Real-Debrid > Add token | `mcdtv_web_rdt` | setup link; invite only when ticked | yes |
| Play through the TV | none | none: the phone sends `rd_resolve` and the TV makes the RD calls | relay | no |
| RD relay | Netlify site `jarvis-rd` (`rd-proxy/`) | stores nothing; adds CORS headers for `jfortress0.github.io` | none | no |

## Live TV

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Playlist (M3U or Xtream Codes) | Control page, TV home Wi-Fi page | `m3u_url`. Xtream user and password are read from the `get.php?username=&password=` link; there are no separate fields. | HouseSync, `web_init`. The phone loads it through `jarvis-rd.netlify.app/iptv`. | yes (the link holds the login) |
| Guide (XMLTV) | no field | taken from the playlist header (`url-tvg`, `x-tvg-url`) or Xtream `xmltv.php`; kept in memory on the TV; phone cache `mcdtv_web_epg` | none | through the link |
| Live TV favorites | star on the TV or phone | `live_favorites@pN`; phone `mcdtv_web_plists` | ProfileSync, `web_init` | no |
| Recents, last channel, channel stats | automatic | `live_recents@pN`, `live_last_url@pN`, `live_stats` | this TV only | no |

## Profiles and the house

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Profile names | TV Settings > Profiles | `profile_name_p1` to `p3`; ids `p1`, `p2`, `p3` never change | ProfileSync, `web_init` | no |
| Who is watching | TV picker; web Settings > Profile | `active_profile` (this TV); web `mcdtv_web_profile` | none | no |
| Per-profile data | automatic | `lib_*@pN`, `taste@pN` | ProfileSync | no |
| House | made automatically | `house_id`, `house_key`, `house_since`, `house_stamp` | the house link `id.key` goes to phones in `web_init` | yes |
| Join my other TVs | TV Settings > Network shows a code; phone Settings > House and devices > Add a TV | short-lived ntfy topic from the code | ntfy | short-lived |
| Connect a phone | TV Settings > Network shows a code; phone Settings > House and devices > Connect with a code | `/house` blob, 15 minutes | hands over the house link | short-lived |
| Invites | web Settings > House and devices | `mcdtv_web_invite`, `mcdtv_web_welcome` | invite link | yes |

## Jarvis Wall

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Wall address | Control page | `wall_url` | HouseSync | no |
| Wall key (read only) | Control page; equals Netlify `WALL_KEY` | `wall_key` | HouseSync | yes |
| Baby due date, baby's first name | Control page | `wall_due`, `wall_baby` | HouseSync | the name is private |
| Open in Wall on start, Wall shows | TV Settings > Jarvis Wall; Control page | `wall_start`, `wall_mode` | this TV only | no |
| Calendars | Netlify env `WALL_CALENDARS`, or the wall phone page | Netlify env and Blobs `wall-config` | never leaves Netlify | yes |
| Upload code | Netlify env `UPLOAD_CODE`; the wall phone page keeps it in `wall_code` | Netlify | none | yes |

## Links and identity

| Setting | Stored as | Notes |
|---|---|---|
| TV link | `relay_id`, `relay_key`, `relay_since` | QR link `https://jfortress0.github.io/McD-TV/#id.key`; topics `mcdtv-<id>-in` and `-out`. "New link" disconnects every phone. |
| Linked TV on a phone | `mcdtv_link` | shared by the Control page and the web app |
| Known TVs | `mcdtv_tvs` | `{id, key, name, house, dev, label, platform, model, version, seen}` |
| This TV's name | `tv_label` | TV Settings > This TV or the Control page; never shared |
| Device id | `device_id` | targets `to` and lets a TV ignore its own messages |
| Ask Jarvis key | `jarvis_key`; phone `mcdtv_web_jarvis` while `mcdtv_web_jarvis_local` is not "0" | Control page; HouseSync |

## Never rename or clear

1. `mcdtv_settings` and every pref key. Each `SHARED` name is also the HouseSync wire name, so a rename breaks sync with older TVs.
2. `house_id`, `house_key`: clearing them makes a new house and cuts the TV off from the others and from phones.
3. `relay_id`, `relay_key`: clearing them breaks every Control page and phone link.
4. `rd_*`: clearing them signs Real-Debrid out on every TV in the house.
5. Profile ids `p1` to `p3` and the per-profile keys.
6. `device_id`.
7. Web keys `mcdtv_link`, `mcdtv_tvs`, `mcdtv_web_init` (holds the house link), `mcdtv_web_rdc`, `mcdtv_web_rdt`, `mcdtv_web_addons_mine` and every `SETUP_KEYS` name: old setup links and invites depend on them.
8. ntfy topic formats and the key derivation strings (`jarvis-resume|`, `jarvis-pair|`, `jarvis-join|` and their `-key` forms).
9. Netlify env vars `WALL_KEY`, `UPLOAD_CODE`, `WALL_CALENDARS` and the Blobs stores.

## What the Settings redesign (October 2026) changed in storage

Nothing was renamed or removed. Added, all per device and never synced: TV `update_check` (last update check) and `recent_errors`; web `mcdtv_web_verified` (when each key last worked).
