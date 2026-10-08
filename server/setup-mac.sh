#!/bin/bash
# McD TV server installer for macOS (Apple Silicon or Intel).
# Run once in Terminal:   bash setup-mac.sh
# It copies the server to ~/mcdtv-server and makes macOS start it automatically at login.
set -e

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is not installed. Download the macOS installer from https://nodejs.org (LTS), install it, then run this again."
  exit 1
fi
echo "Node $(node --version) found."

DEST="$HOME/mcdtv-server"
mkdir -p "$DEST/data"
cp "$(dirname "$0")/server.js" "$DEST/server.js"

read -r -p "Invite code people must enter to create an account (leave blank for none): " INVITE

PLIST="$HOME/Library/LaunchAgents/com.mcdtv.server.plist"
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.mcdtv.server</string>
  <key>ProgramArguments</key><array><string>$(command -v node)</string><string>$DEST/server.js</string></array>
  <key>EnvironmentVariables</key><dict>
    <key>PORT</key><string>8787</string>
    <key>INVITE_CODE</key><string>$INVITE</string>
    <key>DATA_DIR</key><string>$DEST/data</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$DEST/server.log</string>
  <key>StandardErrorPath</key><string>$DEST/server.log</string>
</dict></plist>
PL

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
sleep 2

IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "this-mac-ip")
if curl -fsS "http://127.0.0.1:8787/health" >/dev/null; then
  echo ""
  echo "McD TV server is running."
  echo "On TVs in this house, use this server address:  http://$IP:8787"
else
  echo "The server did not start. See $DEST/server.log"
  exit 1
fi

echo ""
echo "Keeping the Mac awake (needs your Mac password once):"
sudo pmset -a sleep 0 disksleep 0 displaysleep 10 womp 1 autorestart 1
echo "Done. The display may sleep; the computer will not."
