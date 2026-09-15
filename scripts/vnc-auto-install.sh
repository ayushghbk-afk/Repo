#!/bin/bash
# Install/check the real Xvnc server.  The Android launcher starts it; this
# script only prepares the guest and reports failures with the real log.
set -euo pipefail
VNC_PORT=${1:-5901}
DISPLAY_NUM=${2:-1}
GEOMETRY=${3:-1280x720}

command -v Xvnc >/dev/null 2>&1 || {
  echo "Xvnc is missing; installing TigerVNC..." >&2
  apt-get update
  apt-get install -y tigervnc-standalone-server
}
command -v Xvnc >/dev/null 2>&1 || { echo "ERROR: Xvnc was not installed" >&2; exit 1; }

log=/tmp/xvnc.log
pidfile=/tmp/xvnc.pid
if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
  kill "$(cat "$pidfile")" 2>/dev/null || true
  sleep 1
fi
rm -f "$pidfile" "$log"
if ! python3 - "$VNC_PORT" <<'PY'
import socket, sys
s = socket.socket(); s.bind(("127.0.0.1", int(sys.argv[1]))); s.close()
PY
then echo "ERROR: VNC port $VNC_PORT is already in use" >&2; exit 1; fi

export DISPLAY=:$DISPLAY_NUM
Xvnc ":$DISPLAY_NUM" -localhost -geometry "$GEOMETRY" -depth 24 \
  -rfbport "$VNC_PORT" -SecurityTypes None >"$log" 2>&1 &
pid=$!
echo "$pid" > "$pidfile"
for _ in $(seq 1 30); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "ERROR: Xvnc exited during startup (display $DISPLAY, port $VNC_PORT)" >&2
    cat "$log" >&2 || true
    exit 1
  fi
  if python3 - "$VNC_PORT" <<'PY'
import socket, sys
try:
 s=socket.create_connection(("127.0.0.1", int(sys.argv[1])), .5); s.close(); raise SystemExit(0)
except OSError: raise SystemExit(1)
PY
  then
    echo "Xvnc ready: DISPLAY=$DISPLAY port=$VNC_PORT pid=$pid"
    exit 0
  fi
  sleep .2
done
echo "ERROR: Xvnc stayed alive but port $VNC_PORT never opened" >&2
cat "$log" >&2 || true
exit 1
