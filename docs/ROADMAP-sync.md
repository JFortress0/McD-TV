# Profile sync: architecture and roadmap

## Goals

- You or your wife can pick your profile on any TV in the house, or on your phone, and see the same Continue Watching, My List, favorites, episode progress and Live TV favorites.
- It works with 8 to 10 TVs (or more) and no accounts or servers.
- No lost changes and no duplicates, even when a TV was off for days.
- Each TV still picks its own "Who's watching" profile. That choice is not synced.

## What syncs

Per profile (Dad, Mom, Kids, or whatever you renamed them):

| Data | Synced | Notes |
|---|---|---|
| Watch history (Continue Watching) | Yes | Newest 300 titles kept |
| Episode progress | Yes | Newest 3,000 episodes kept |
| My List, Favorites | Yes | |
| "Not for me" (hidden), Background Noise | Yes | |
| Live TV favorites | Yes | |
| Profile names | Yes | |
| Live TV recents, last channel | No | Phase 2 |
| Which profile is active on a TV | No | On purpose: each TV has its own |

Settings (keys, addons, playlist, Real-Debrid) were already shared by the house and work as before.

## How it works

```
  TV A (living room)                 ntfy.sh relay                 TV B (bedroom)
  ------------------                 -------------                 --------------
  You add a movie to My List
  Library saves it  --> ProfileSync records
                        {key, value, time, TV id}
                        waits 3 s, encrypts  -->  topic mcdtv-<house>-house  -->  decrypts, merges
                                                  (random bytes only)            rebuilds My List
  Phone (web app)
  "Add to My List" --> paired TV --> same path as above
```

1. Every TV keeps its normal lists (what the screens read). Next to them it keeps a sync store per profile in a small file.
2. Every change becomes an entry with a time and the id of the TV that made it. A removal is kept as a "deleted" marker, so it can win over an older add.
3. When two TVs disagree about one item, the newer change wins. If two changes have the same time, the TV id decides. Every TV picks the same winner, whatever order the messages arrive in.
4. Changes go out as one small "pdelta" message that holds everything pending. List changes go 5 seconds after the last one (at most 30 seconds after the first). Watch progress goes when playback has been quiet for a minute (pause, stop, leaving the app), and at most every 25 minutes while it keeps playing.
5. A TV sends a "pdigest" (one short hash per profile and list) 5 to 60 seconds after it starts, and about every 12 hours. It skips this when any TV of the house sent one in the last 6 hours. Since ntfy replays the last 12 hours, that is enough.
6. If hashes differ, exactly one TV answers with a "pstate" that holds only the parts that differ (each list is split into 16 buckets). Each TV waits a random 0 to 10 seconds; the first to answer wins, and the others drop their answer when they see it.
7. A big answer (a new TV) goes out as one message every 20 seconds, most useful first: profile names, then Continue Watching (newest first, all profiles), then My List and favorites, and episode marks last.
8. Messages use the house topic and house key of the existing settings sharing. The relay only sees encrypted bytes.

## Guarantees

- Same data everywhere: once TVs have exchanged messages, every TV has the same lists for every profile. The unit tests check this with up to 5 simulated TVs, lost, late and duplicated messages, and wrong clocks.
- Nothing lost: changes made on two TVs at the same time are both kept unless they touch the same item. For the same item, the newer change wins.
- No duplicates: each item has one key (for example "movie:603"), so it can only appear once.
- TVs that were off: ntfy keeps messages for about 12 hours. After longer, the hash check on start fills in everything that was missed, however long the TV was off.
- Deleted items stay deleted: see "Pruning" below.
- Upgrading: on first start with this version, each TV adds its existing lists to its sync store. The first sync between two TVs is a union. Nothing is wiped.
- "Copy once" (a friend's TV) never gets or sends profile data. Profile sync is also paused on a TV that is still joining a house.

## Pruning (how old data is removed safely)

- History keeps the newest 300 titles per profile. Episode progress keeps the newest 3,000 episodes.
- A deleted marker is kept for at least 180 days. After that, if a list has more than 200 markers, the oldest ones are removed.
- Pruning uses a shared "forget before" time for each list. Every TV forgets everything older than that time, and ignores anything older that arrives later. So a TV that was off for a year cannot bring a deleted item back.
- The "forget before" time never passes the oldest item still in the list on the TV that prunes. Old My List items stay.
- The one rare case of loss: a change made on a TV that then had no contact with the house for more than 180 days, older than the house's "forget before" time. It is dropped instead of coming back as a duplicate or a deleted item.

## Limits

- Message size: each encrypted message stays under 3,500 characters (ntfy allows 4 KB). Measured: about 26 history entries, 30 My List titles, 120 episode marks or 300 deleted markers fit in one message. One hash check message is about 1,200 characters (3,100 at most).
- Message count: see "Household message budget" below.
- Devices: designed for 10 TVs. 20 should still work. Beyond that, the 0 to 10 second answer wait should grow.
- Clocks: "newer wins" uses each TV's clock. A TV whose clock is minutes off can lose a race against another TV's change made at nearly the same time. A TV always wins over anything it has already seen, even with a wrong clock.
- Phones: the web app talks to the TV it was paired with. That TV must be on for the phone to read or save profile data.
- Screens do not refresh live: a change from another TV shows the next time you open that screen.

## Household message budget

ntfy.sh free use allows about 250 messages per day per home internet address, plus bursts of 60 (then 1 every 5 seconds). All TVs in the house share one address, and they also send settings and phone (Control page, web app) messages. So profile sync aims for well under 150 messages a day for the whole house.

Limits built in:
- Each TV: bursts of 25 messages, then 1 every 20 minutes. At most 97 a day per TV, and only if it is busy all day.
- Whole house: every TV counts the profile-sync messages it sees on the house topic (its own and the others') over the last 24 hours. Above 150, only list changes go out. Above 200, nothing goes out until the count drops.
- After a "too many messages" answer (HTTP 429) from ntfy, the TV stops posting for 30 minutes, then 1, 2, 4 and at most 6 hours if it keeps happening. A server or network error waits 30 seconds, doubling up to 30 minutes. Nothing is lost: changes stay saved on the TV and go out after the pause.
- Messages in one send are 1 second apart, so a TV never uses much of the shared burst.

Typical day for a 10 TV house (2 adults and kids, about 6 hours of viewing in total):

| What | Messages per day |
|---|---|
| Progress (about 12 viewing sessions: 1 at each pause or stop, plus 1 per 25 minutes of playing) | about 26 |
| List changes (My List, favorites, names), grouped | about 8 |
| Hash checks (2 on schedule for the whole house, plus a few TVs starting after a long time off) | about 6 |
| Answers (TVs that were off more than 12 hours catch up) | about 8 |
| Total | about 50 |

A heavy day is about 90. The 150 house limit stops non-urgent traffic before ntfy's limit is reached.

Filling a new TV: one message every 20 seconds. A typical profile (50 titles watched, 40 in My List, 300 episodes) is about 8 messages, so three profiles arrive in about 8 minutes. A very large set (300 titles and 3,000 episodes per profile, about 125 messages) uses the 25 message burst first, then 1 every 20 minutes: names and recent Continue Watching arrive at once, the rest over about a day.

## Phase 1 (this work)

- Pure merge core: `data/sync/ProfileStore.kt` (no Android code, unit tested on a plain JVM).
- Android glue: `data/sync/ProfileSync.kt` (hooks in Library and Prefs, files in `filesDir/profile_sync`, messages on the house topic).
- Hooks: Library (history, episode progress, My List, Favorites, hidden, Background Noise), Prefs (Live TV favorites, profile names, account import).
- HouseSync: carries the new messages, pauses profile sync while joining or during "Copy once", and starts a hash check when a TV joins.
- "Who's watching" shows "Profiles sync with N other TVs in your house."
- Message budgets: per TV token bucket, house budget, 429 backoff (shared with settings sharing), grouped sends, spread out answers.
- Tests: `app/src/test/java/com/mcd/tv/data/sync/ProfileStoreTest.kt` (17 tests) and `SyncPolicyTest.kt` (7 tests).

## Phase 2 ideas

- Names for each TV ("Living room", "Bedroom") shown in Settings and on the Control page.
- "Who's watching" remembers the last profile used on each TV, and can skip the picker when only one person uses that TV.
- Optional 4 digit PIN per profile.
- Kids lock: the Kids profile cannot open Settings or switch to an adult profile without the PIN.
- Sync Live TV recents and the last channel.
- Live screen refresh when another TV changes the profile you are looking at.
- A "Sync now" button and a small status page (last sync time per TV, messages used today).
- Let the web app read profile data from any TV of the house, not only its paired TV.

## Test plan

Automated (runs on any machine with Java):
- Merge laws: order, grouping and repeats of merges never change the result (200 random rounds).
- Random house: 3 to 5 TVs, hundreds of random changes, skewed clocks, 25% message loss, duplicates and late messages, random pruning. After one hash check round, all TVs are identical (60 random houses).
- Bucket exchange: two TVs converge in one round using only the parts that differ.
- Deletes: a delete beats an older add, a newer add beats a delete, same-time ties are decided the same way everywhere.
- Pruning: history cap, a deleted item cannot come back, old My List items survive, recent deleted markers are kept for 180 days.
- Upgrade: two TVs with existing lists end with the union.
- Hash stability: a fixed hash value, so TVs on different versions still agree.
- Message packing: every part fits, nothing is lost or reordered, parts are as full as possible.
- Budgets: token bucket burst and refill (at most 97 a day), house soft and hard limits, 429 backoff doubling to 6 hours, error backoff capped at 30 minutes.
- Grouping: list changes wait for a 5 second pause (30 seconds at most); a 2 hour movie sends progress 4 times while playing, then once on pause.
- Fill order: names, then the newest history across all profiles, then My List, episodes last; one message per round.

Manual, with 2 TVs and a phone:
1. Link TV B to TV A ("Use its settings here"). Within a minute, B shows A's Continue Watching and My List for every profile.
2. Add a movie to Mom's My List on A. It appears on B within about 10 seconds.
3. Watch 10 minutes of a show on A as Dad. Pause or stop. About a minute later, B resumes at the same spot.
4. Turn B off. Make changes on A for 2 days. Turn B on. B catches up within a minute.
5. Remove an item on A while B is off. Turn B on. The item stays removed.
6. Add different items on A and B at the same time. Both end up on both TVs.
7. Rename "Mom" on A. B shows the new name.
8. From the phone (paired with A), add to Kids' My List. It appears on B.
9. "Copy once" to a friend's TV. It gets settings but no profiles, and our TVs do not count it.
10. Block ntfy.sh for a while (or force 429s with a test proxy). Make changes. The TV waits, then sends them after the pause, without a burst of retries.

## How to add an 11th TV

1. Install Jarvis on the new TV.
2. On your phone, open the Control page for any TV already in the house and use "Use its settings here" for the new TV (or open the new TV's Control page and link it to the house).
3. The new TV copies the settings, then asks the house for profile data. Everything arrives within a minute or two.
4. Nothing else changes. There is no hard limit of 10.
