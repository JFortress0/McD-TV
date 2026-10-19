#!/usr/bin/env bash
# Test streams for tools/webtest/qf-net.mjs: 40 s of random noise (it can't be compressed, so the bitrate is exact)
# in 2 s VP9 fMP4 HLS segments (the Chromium in the tests has no H.264). Small pictures, real bitrates:
#   streams/uhd  6.0 Mbps  (plays the 4K channel)
#   streams/fhd  1.5 Mbps  (plays its 1080p copy)
# srv.py serves them as live channels. Needs ffmpeg. Output is git-ignored. Run: bash tools/webtest/mkstreams.sh
set -euo pipefail
cd "$(dirname "$0")"
mk() { # name bitrate
  local d="streams/$1"
  [ -f "$d/index.m3u8" ] && return 0
  mkdir -p "$d"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i "nullsrc=s=426x240:r=30,geq=lum='random(1)*255':cb=128:cr=128" -t 40 \
    -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -b:v "$2" -minrate "$2" -maxrate "$2" -bufsize 2M \
    -g 60 -keyint_min 60 -pix_fmt yuv420p -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_type fmp4 \
    -hls_fmp4_init_filename init.mp4 -hls_segment_filename "$d/seg_%03d.m4s" "$d/index.m3u8"
  echo "made $d"
}
mk uhd 6M
mk fhd 1.5M
