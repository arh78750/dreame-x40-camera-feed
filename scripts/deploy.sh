#!/bin/sh
# Deploy the patched node_camera_ai.so + mjpeg_server to a rooted, Valetudo-running
# Dreame X40 (r2416). NON-PERSISTENT by design: a reboot reverts everything to stock.
#
# Prereqs (see README):
#   - patch/node_camera_ai.patched.so   (run patch/patch_ai.py on YOUR stock .so)
#   - server/mjpeg_server               (run server/build.sh)
#   - SSH access to the robot as root.
#
# Config via env vars:
#   ROBOT_IP   (default 10.0.0.101)
#   SSH_KEY    (default ./valetudo.key, then ~/.ssh/id_rsa)
#   MJPEG_PORT (default 8081)
#
# Usage:  sh scripts/deploy.sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROBOT_IP="${ROBOT_IP:-10.0.0.101}"
MJPEG_PORT="${MJPEG_PORT:-8081}"
HOST="root@$ROBOT_IP"

# pick an SSH key
if [ -n "${SSH_KEY:-}" ]; then KEY="$SSH_KEY"
elif [ -f "$ROOT/valetudo.key" ]; then KEY="$ROOT/valetudo.key"
else KEY="$HOME/.ssh/id_rsa"; fi
SSH="ssh -i $KEY -o StrictHostKeyChecking=no"
SCP="scp -O -i $KEY -o StrictHostKeyChecking=no"   # -O = legacy scp (robot has no sftp-server)

PATCHED="$ROOT/patch/node_camera_ai.patched.so"
MJPEG="$ROOT/server/mjpeg_server"
[ -f "$PATCHED" ] || { echo "missing $PATCHED — run patch/patch_ai.py first"; exit 1; }
[ -f "$MJPEG" ]   || { echo "missing $MJPEG — run server/build.sh first"; exit 1; }

echo "[*] robot: $HOST   key: $KEY"
echo "[*] ava loads node libs from tmpfs /tmp/ava/lib (NOT /ava/lib). Overwriting that copy."

echo "[*] stop any running mjpeg_server + push files to /data"
$SSH $HOST 'killall mjpeg_server 2>/dev/null; sleep 1; true'
$SCP "$PATCHED" $HOST:/data/node_camera_ai.patched.so
$SCP "$MJPEG"   $HOST:/data/mjpeg_server
$SSH $HOST 'chmod +x /data/mjpeg_server'

echo "[*] back up stock tmpfs lib (once), overwrite with patched, restart ava"
$SSH $HOST '
  [ -f /data/node_camera_ai.stock.so ] || cp -a /tmp/ava/lib/node_camera_ai.so /data/node_camera_ai.stock.so
  cp /data/node_camera_ai.patched.so /tmp/ava/lib/node_camera_ai.so
  echo "    tmpfs lib md5: $(md5sum /tmp/ava/lib/node_camera_ai.so | cut -d" " -f1)"
  killall ava 2>/dev/null || true
'
echo "[*] waiting for ava (watchdog respawns it; can take ~30-60s)..."
$SSH $HOST 'n=0; until pidof ava >/dev/null; do n=$((n+1)); [ $n -gt 45 ] && { echo "    TIMEOUT"; break; }; sleep 2; done; sleep 3; echo "    ava pid: $(pidof ava)"'

echo "[*] start mjpeg_server on :$MJPEG_PORT"
$SSH $HOST "killall mjpeg_server 2>/dev/null; (setsid /data/mjpeg_server $MJPEG_PORT /tmp/cam.jpg >/tmp/mjpeg.log 2>&1 &); sleep 1; echo \"    mjpeg pid: \$(pidof mjpeg_server)\""

cat <<EOF

[+] Deployed. The feed is live ONLY while the robot is navigating (start a clean).
    Stream:   http://$ROBOT_IP:$MJPEG_PORT/stream.mjpg
    Snapshot: http://$ROBOT_IP:$MJPEG_PORT/snapshot.jpg
    Revert:   sh scripts/revert.sh   (or just reboot the robot)
EOF
