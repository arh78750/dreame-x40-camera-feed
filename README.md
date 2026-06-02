# Dreame X40 Ultra — local camera feed (no cloud)

Stream the front (RGB obstacle) camera of a **rooted, [Valetudo](https://valetudo.cloud)-running
Dreame X40 Ultra** (`dreame.vacuum.r2416`) over your LAN — entirely **on-device, no cloud**,
ready to drop into Home Assistant.

It works by applying a tiny binary patch to the robot's on-device AI node so it writes
each RGB frame it already processes to a file, plus a small MJPEG server to stream it.

> [!IMPORTANT]
> **The feed is live only while the robot is navigating (cleaning).** The RGB sensor is
> powered down by the firmware while docked, so there is no docked/"security cam" feed —
> this is a hardware reality, not a limitation of the patch. Expect ~7 fps, 672×504.

> [!WARNING]
> This modifies firmware on your robot. It is **reversible** (non-persistent; a reboot
> reverts it) but it carries risk. Read **[DISCLAIMER.md](DISCLAIMER.md)** first. No Dreame
> firmware is included in this repo — you supply your own, pulled from your own device.

---

## How it works (short version)

The on-device `node_camera_ai.so` already decodes every RGB frame to a BGR `cv::Mat` and
contains a JPEG-encode path that's normally gated off (it was for an SD-card data-collection
feature). The patch:

1. Forces that per-frame JPEG encode **on** for the RGB camera only (one branch flipped).
2. Adds a small position-independent code cave (raw syscalls) that writes the encoded JPEG
   atomically to `/tmp/cam.jpg` (a single file, overwritten each frame — **nothing
   accumulates**; `/tmp` is a RAM disk).
3. Redirects a now-dead cloud-upload call to that cave.

`mjpeg_server` then serves `/tmp/cam.jpg` as an MJPEG stream. Full technical write-up:
**[docs/FINDINGS.md](docs/FINDINGS.md)**.

## Requirements

- A Dreame X40 Ultra (`r2416`) that is **rooted and running Valetudo**, reachable over SSH as root.
- The patch targets one specific firmware build:
  `node_camera_ai.so` md5 **`ad74c34ded63853af4b41d7712c3f76a`**. The patcher refuses to run
  on a different build unless you pass `--force` and have confirmed the offsets.
- Host tools: Python 3, and an **aarch64 Linux C cross-compiler** to build the server
  (`gcc-aarch64-linux-gnu`, or a [Bootlin toolchain](https://toolchains.bootlin.com/)).

## Usage

```sh
# 0. Set your robot's address/key (optional; defaults shown)
export ROBOT_IP=10.0.0.101
export SSH_KEY=~/.ssh/your_robot_key       # or drop the key as ./valetudo.key

# 1. Pull YOUR stock node_camera_ai.so from YOUR robot (the robot has no sftp; use cat)
ssh -i "$SSH_KEY" root@$ROBOT_IP 'cat /ava/lib/node_camera_ai.so' > node_camera_ai.so

# 2. Patch it (produces patch/node_camera_ai.patched.so)
python3 patch/patch_ai.py node_camera_ai.so patch/node_camera_ai.patched.so

# 3. Build the MJPEG server (static aarch64)
CC=aarch64-linux-gnu-gcc sh server/build.sh

# 4. Deploy (pushes files, swaps the tmpfs lib, restarts ava, starts the server)
sh scripts/deploy.sh

# 5. Start a clean so the camera streams, then open:
#    http://$ROBOT_IP:8081/stream.mjpg
```

Revert at any time with `sh scripts/revert.sh` (or just reboot the robot).

## Home Assistant

```yaml
camera:
  - platform: mjpeg
    name: Robovac Cam
    mjpeg_url: http://10.0.0.101:8081/stream.mjpg
    still_image_url: http://10.0.0.101:8081/snapshot.jpg
```

Keep it on a trusted LAN/VLAN — the stream is unauthenticated. Never expose it to the internet.

## Persistence

The change lives in `tmpfs`, so it is wiped on every reboot. Re-run `scripts/deploy.sh`
after a reboot. (Auto-applying on boot via a Valetudo/startup hook is left as an exercise;
contributions welcome.)

## Repo layout

```
patch/patch_ai.py     # the patcher (operates on YOUR pulled binary)
patch/cave.s          # human-readable source of the injected code cave
server/mjpeg_server.c # the MJPEG/snapshot HTTP server
server/build.sh       # cross-compile helper
scripts/deploy.sh     # deploy / revert.sh — revert
docs/FINDINGS.md      # full reverse-engineering write-up
tools/ghidra/*.java   # Ghidra headless decompile scripts used during RE
```

## License

[Apache-2.0](LICENSE) for the code in this repo. See [NOTICE](NOTICE) and
[DISCLAIMER.md](DISCLAIMER.md). Not affiliated with Dreame or Valetudo.
