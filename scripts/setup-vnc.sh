#!/bin/bash
# Install the Lenix fallback server in a guest.  The canonical implementation is
# scripts/vncserver.py; do not maintain a second, subtly different RFB server here.
set -euo pipefail

VNC_PORT=${1:-5902}
VNC_WIDTH=${2:-640}
VNC_HEIGHT=${3:-480}
mkdir -p "$HOME/bin"

source=""
for candidate in "${LENIX_VNC_SERVER:-}" /shared/vncserver.py /usr/local/share/lenix/vncserver.py; do
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then source="$candidate"; break; fi
done
if [ -z "$source" ]; then
    echo "ERROR: canonical vncserver.py is not available in the guest." >&2
    echo "Copy scripts/vncserver.py to /shared/vncserver.py and run setup-vnc.sh again." >&2
    exit 1
fi
install -m 0755 "$source" "$HOME/bin/vncserver.py"

cat > "$HOME/bin/vnc" <<'VNCCTL'
#!/bin/sh
set -eu
port=${1:-5902}
action=${2:-start}
pidfile="$HOME/.lenix-vnc.pid"
case "$action" in
  start)
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then echo "VNC already running"; exit 0; fi
    nohup python3 "$HOME/bin/vncserver.py" --port "$port" --width 640 --height 480 >"$HOME/vnc.log" 2>&1 < /dev/null &
    echo $! > "$pidfile"
    sleep .2
    if ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then echo "VNC failed; see $HOME/vnc.log" >&2; cat "$HOME/vnc.log" >&2; exit 1; fi
    echo "VNC listening on 127.0.0.1:$port"
    ;;
  stop) [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true; rm -f "$pidfile" ;;
  status) [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null && echo "VNC running" || echo "VNC stopped" ;;
  *) echo "Usage: vnc PORT {start|stop|status}" >&2; exit 2 ;;
esac
VNCCTL
chmod 0755 "$HOME/bin/vnc"
echo "Installed standards-compliant fallback VNC server on port $VNC_PORT"
