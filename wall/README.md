# Jarvis Wall (Netlify)

Backend for the Wall screen in the app: a photo frame plus a merged family calendar for a wall TV.

- Live at `https://jarvis-wall.netlify.app` (Netlify project `jarvis-wall`).
- `GET /api/feed`: every calendar in `WALL_CALENDARS`, merged into one JSON list for the next 10 weeks, plus the weather. Repeats, moved repeats and cancelled repeats are handled (ical.js).
- `/api/photos`: photo list, photo files, upload and delete. Photos are kept in Netlify Blobs (store `wall-photos`).
- `/` (public/index.html): phone page to add and remove photos (shrunk to 1920 px JPEG in the browser) and to paste calendar links.
- `/api/calendars`: the calendar list from the phone page, kept in Netlify Blobs (store `wall-config`). Links are write-only: the page never shows a saved link again. The feed uses this list first, then `WALL_CALENDARS`.

## Environment variables (Netlify > jarvis-wall > Project configuration > Environment variables)

| Name | What it is |
|---|---|
| `WALL_KEY` | Read-only key. Goes on the Control page (Wall section). The TV uses it to read the calendar and photos. |
| `UPLOAD_CODE` | Code for the photo page. Can add and remove photos. Share it with family members who add photos. |
| `WALL_CALENDARS` | Optional fallback: JSON list of calendars, see below. Normally the list is set on the phone page. |
| `WALL_LAT`, `WALL_LON`, `WALL_TZ` | Optional. Weather location and time zone. Default: Huntsville, AL, America/Chicago. |

`WALL_CALENDARS` example (one line in Netlify):

```json
[
  {"name":"J Work","tag":"Work","kind":"work","color":"#00E5FF","url":"https://calendar.google.com/calendar/ical/.../basic.ics"},
  {"name":"J Personal","tag":"J","kind":"personal","color":"#7C9CFF","url":"https://calendar.google.com/calendar/ical/.../basic.ics"},
  {"name":"J USAF Gmail","tag":"J2","kind":"personal","color":"#4ADE80","url":"https://calendar.google.com/calendar/ical/.../basic.ics"},
  {"name":"Taylor Work","tag":"Taylor","kind":"work","color":"#FF7AB6","url":"https://calendar.google.com/calendar/ical/.../basic.ics"},
  {"name":"SNHU","tag":"SNHU","kind":"school","color":"#FFB300","url":"https://.../feed.ics"}
]
```

- `kind` is `work`, `personal`, `family` or `school`. `school` events also show in the "Due soon" box.
- `tag` is the short name on the screen. `color` is `#RRGGBB`.
- `webcal://` links work too.

## Where each calendar link comes from

- **Google Calendar (personal Gmail, Workspace work account, or Taylor's):** calendar.google.com on a computer > Settings (gear) > the calendar on the left > Integrate calendar > **Secret address in iCal format**. Workspace admins can turn this off. If it is missing on the work account, ask the admin to allow it, or share the work calendar to a personal account and use a calendar that has the secret address.
- **Taylor's shifts:** Inovalon (ShiftHound) already copies them to her Google Calendar. Use the secret address of the Google calendar the shifts land in.
- **SNHU (Brightspace):** Brightspace > Calendar > Subscribe > copy the link.

Treat these links like passwords: anyone with one can read that calendar. Never put them in the repo.

## Deploy

The project is linked to this GitHub repo with base directory `wall`. A push that changes this folder redeploys it. Changing an environment variable needs a redeploy too (Deploys > Trigger deploy).
