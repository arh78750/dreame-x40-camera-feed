#!/bin/sh
# Cross-compile mjpeg_server for the robot (aarch64, glibc; static so there are no
# runtime library deps on the robot).
#
# You need an aarch64 Linux C cross-compiler. Options:
#   - Debian/Ubuntu host:   sudo apt install gcc-aarch64-linux-gnu
#                           CC=aarch64-linux-gnu-gcc sh build.sh
#   - Bootlin toolchain:    https://toolchains.bootlin.com/  (aarch64 glibc)
#                           CC=/path/to/bin/aarch64-linux-gcc sh build.sh
#   - Docker (no host cc):  uses the CC you set inside a container of your choice.
#
# Output: ./mjpeg_server  (static aarch64 ELF)
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CC="${CC:-aarch64-linux-gnu-gcc}"

command -v "$CC" >/dev/null 2>&1 || {
  echo "error: cross-compiler '$CC' not found. Set CC= to your aarch64 gcc." >&2
  echo "       e.g. sudo apt install gcc-aarch64-linux-gnu" >&2
  exit 1
}

"$CC" -O2 -static "$HERE/mjpeg_server.c" -o "$HERE/mjpeg_server"
echo "built: $HERE/mjpeg_server"
file "$HERE/mjpeg_server" 2>/dev/null || true
