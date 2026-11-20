#!/bin/bash
# Convert wallpaper clips to H.264 seamless loops plus poster and blurred poster.
# Usage: wallpapers/convert.sh <folder with morning-clear.mp4 etc.>            720p, writes <folder>/tv/
#        RES=1080 wallpapers/convert.sh <folder with morning-clear.mp4 etc.>   1080p, writes <folder>/tv1080/
# 720p suits TVs whose home screen runs at 1280x720; 1080p suits Full HD and 4K TVs (their home screen is 1080p).
set -e
# ffmpeg to use: set FFMPEG=/path/to/ffmpeg, or have ffmpeg on the PATH.
FF="${FFMPEG:-ffmpeg}"
SRC="${1:?folder with the clips}"; MAXLEN=24
case "${RES:-720}" in
  720)  W=1280; H=720;  OUT="$SRC/tv";     RATE="-crf 22 -maxrate 3M -bufsize 6M -level 4.0" ;;
  1080) W=1920; H=1080; OUT="$SRC/tv1080"; RATE="-crf 21 -maxrate 6M -bufsize 12M -level 4.1" ;;
  *) echo "RES must be 720 or 1080" >&2; exit 2 ;;
esac
mkdir -p "$OUT"
for f in "$SRC"/*.mp4; do
  n=$(basename "$f" .mp4)
  dur=$("$FF" -hide_banner -i "$f" 2>&1 | grep -oE "Duration: [0-9:.]+" | head -1 | awk -F'[ :]' '{print $3*3600+$4*60+$5}')
  L=$(python3 -c "print(min($dur-0.1,$MAXLEN))")
  F=$(python3 -c "print(1.5 if $L<12 else 2.0)")
  OFF=$(python3 -c "print(round($L-2*$F,3))")
  fps=$("$FF" -hide_banner -i "$f" 2>&1 | grep -m1 "Video:" | grep -oE "[0-9.]+ fps" | cut -d' ' -f1)
  FPSF=",fps=$fps"; python3 -c "import sys;sys.exit(0 if $fps>31 else 1)" && FPSF=",fps=30"
  w0=$("$FF" -hide_banner -i "$f" 2>&1 | grep -m1 "Video:" | grep -oE "[0-9]{3,4}x[0-9]{3,4}" | head -1 | cut -dx -f1)
  [ "${w0:-0}" -lt "$W" ] && echo "   note: $n is only ${w0}px wide; it will be upscaled to $W"
  echo "== $n: dur=$dur L=$L fade=$F fps=$fps$FPSF"
  "$FF" -hide_banner -loglevel error -y -i "$f" -filter_complex \
   "[0:v]scale=$W:$H:flags=lanczos,setsar=1${FPSF},format=yuv420p,split[a][b];\
    [a]trim=$F:$L,setpts=PTS-STARTPTS${FPSF}[main];[b]trim=0:$F,setpts=PTS-STARTPTS${FPSF}[head];\
    [main][head]xfade=transition=fade:duration=$F:offset=$OFF[v]" \
   -map "[v]" -pix_fmt yuv420p -an -c:v libx264 -preset slow $RATE \
   -profile:v high -g 60 -movflags +faststart "$OUT/$n.mp4"
  "$FF" -hide_banner -loglevel error -y -i "$OUT/$n.mp4" -frames:v 1 -q:v 3 "$OUT/$n.jpg"
  "$FF" -hide_banner -loglevel error -y -i "$OUT/$n.jpg" -vf "scale=320:180,gblur=sigma=8,scale=1280:720:flags=bicubic" -q:v 4 "$OUT/$n-blur.jpg"
done
ls -la "$OUT"; du -sh "$OUT"
