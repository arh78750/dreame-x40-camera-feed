#!/bin/sh
# Revert the patch: stop mjpeg_server, restore the stock node_camera_ai.so in tmpfs,
# restart ava. (A plain reboot also fully reverts everything, since the change lives
# only in tmpfs / on /data.)
#
# Config via env vars: ROBOT_IP (default 10.0.0.101), SSH_KEY.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROBOT_IP="${ROBOT_IP:-10.0.0.101}"
HOST="root@$ROBOT_IP"
if [ -n "${SSH_KEY:-}" ]; then KEY="$SSH_KEY"
elif [ -f "$ROOT/valetudo.key" ]; then KEY="$ROOT/valetudo.key"
else KEY="$HOME/.ssh/id_rsa"; fi
SSH="ssh -i $KEY -o StrictHostKeyChecking=no"

$SSH $HOST '
  killall mjpeg_server 2>/dev/null || true
  if [ -f /data/node_camera_ai.stock.so ]; then
    cp /data/node_camera_ai.stock.so /tmp/ava/lib/node_camera_ai.so && echo "restored stock lib"
  else
    echo "no saved stock lib on /data; a reboot will restore it from squashfs"
  fi
  killall ava 2>/dev/null || true
'
echo "[*] waiting for ava..."
$SSH $HOST 'n=0; until pidof ava >/dev/null; do n=$((n+1)); [ $n -gt 45 ] && break; sleep 2; done; sleep 3; echo "    ava pid: $(pidof ava)"; md5sum /tmp/ava/lib/node_camera_ai.so'
echo "[+] Reverted (stock md5 should be ad74c34ded63853af4b41d7712c3f76a). A reboot also fully reverts."
