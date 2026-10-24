#!/bin/bash
# Turns a recorded clip (a directory of numbered frames from #record) into <dir>.mp4 (the whole clip) and <dir>.gif
# (the part from --from to --to seconds of the mp4, sped up --speed times).
# Usage: demo/gif.sh <frames-dir> [--from S] [--to S] [--speed X] [--width W (default 720)]
set -e
D=${1%/}
shift
W=720
FROM=0
TO=
SPEED=1
while [ $# -gt 0 ]; do
    case $1 in
        --from) FROM=$2; shift 2 ;;
        --to) TO=$2; shift 2 ;;
        --speed) SPEED=$2; shift 2 ;;
        --width) W=$2; shift 2 ;;
        *) echo "unknown option $1" >&2; exit 1 ;;
    esac
done
ffmpeg -loglevel error -y -framerate 15 -i "$D/%05d.png" -c:v libx264 -pix_fmt yuv420p -crf 20 "$D.mp4"
ffmpeg -loglevel error -y -ss "$FROM" ${TO:+-to "$TO"} -i "$D.mp4" \
    -vf "setpts=PTS/$SPEED,fps=15,scale=$W:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
    "$D.gif"
ls -la "$D.gif" | awk '{print $5, $9}'
