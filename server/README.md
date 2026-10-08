# McD TV server (optional)

You need this server only if you want accounts: each person signs in and gets their own lists, history, addons and Real-Debrid link on any TV. Without it, each TV keeps its own setup.

It is one file (`server.js`) with no extra packages. It needs Node.js 20 or newer. A spare Mac (an M1 with 8 GB is plenty) can run it for a household and friends.

## What it stores

- Accounts: username and a scrambled (hashed) password. Nobody can read the passwords.
- Each account's McD TV data: lists, history, settings, addons and Real-Debrid sign-in.
- Everything lives in `~/mcdtv-server/data/db.json` on the Mac.

## Set up on the Mac

1. Sign in to the Mac and connect it to your home network. Ethernet is best.
2. Install Node.js: open **nodejs.org**, download the macOS **LTS** installer and run it.
3. Copy this `server` folder to the Mac (AirDrop works).
4. Open **Terminal**. Type `bash ` (with a space), drag `setup-mac.sh` into the window, and press Return.
5. Enter an invite code when asked. Only people with the code can create an account.
6. Enter the Mac's password when asked. This stops the Mac from sleeping.
7. Terminal prints the server address, for example `http://192.168.1.50:8787`.
8. On each TV: **Settings > Phone setup**, paste the server address. Then **Settings > Account** to sign in or create an account.

The server starts by itself whenever the Mac restarts and you sign in.

## Use outside your home

The address above works only on your home Wi-Fi. For TVs in other homes, the server needs a public address. The usual way is a free Cloudflare Tunnel, which needs a domain name you own (about $10 a year). Ask Claude to set this up when you are ready.

## Real-Debrid and other homes

Real-Debrid flags accounts that stream from several internet connections at the same time. Every home should connect its own Real-Debrid account under its own McD TV account.

## Test

`node test.js` starts a throwaway server and checks every feature.
