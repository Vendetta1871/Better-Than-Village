#!/bin/bash
# Starts the dev client with the demo director (see demo/README.md).
# Usage: demo/client.sh <project-dir> [scene[,scene...]] [--record] [--quit]
# Environment: BTV_JAVA_HOME (a JDK 21, else JAVA_HOME is used as is), BTV_PROXY (host:port of an HTTP proxy for
# Gradle, optional) and the DISPLAY (or WAYLAND_DISPLAY) of the screen the window goes to.
set -e
DIR=$(cd "$1" && pwd)
shift
SCENES=
unset BTV_DEMO_RECORD BTV_DEMO_QUIT
for a in "$@"; do
    case $a in
        --record) export BTV_DEMO_RECORD=1 ;;
        --quit) export BTV_DEMO_QUIT=1 ;;
        *) SCENES=$a ;;
    esac
done
export BTV_DEMO=$SCENES BTV_DEMO_DIR=$DIR/demo
[ -n "$BTV_JAVA_HOME" ] && export JAVA_HOME=$BTV_JAVA_HOME
PROXY=()
if [ -n "$BTV_PROXY" ]; then
    h=${BTV_PROXY%:*} p=${BTV_PROXY##*:}
    PROXY=(-Dhttp.proxyHost=$h -Dhttp.proxyPort=$p -Dhttps.proxyHost=$h -Dhttps.proxyPort=$p)
fi
cd "$DIR"
# A fresh daemon per run, so the game sees this environment.
exec ./gradlew --console=plain --no-daemon "${PROXY[@]}" runClient
