# How it works — reverse-engineering notes

This documents the approach this project actually ships: a small binary patch to the
on-device AI node (`node_camera_ai.so`) that makes it write each RGB frame it already
processes to `/tmp/cam.jpg`, plus an MJPEG server to stream that file.

These are interoperability notes — observed behavior, offsets, and data layouts. No
Dreame firmware or decompiled source is included here.

## Platform

- SoC: Allwinner "sunxi", aarch64, kernel 4.9.191, glibc 2.34 (Buildroot).
- Cameras behind the Allwinner VIN media pipeline:
  - `sc520cs_mipi` — SmartSens 5MP color = **front / AI obstacle camera** (this is the feed).
  - `sc035gs_mipi` — global-shutter mono = line-laser / navigation sensor.
- The robot has no ffmpeg/gstreamer/python/gcc; busybox 1.36, curl. `/data` is writable
  (~2.7G), `/tmp` is tmpfs (RAM, ~420M).
- The controller is a **single process** `ava` (`/usr/bin/ava -f /ava/conf/r2416.conf`),
  supervised by `check_restart_ava.sh` which respawns it (~30–60s) if it exits. `ava`
  `dlopen()`s all node plugins (`node_*.so`) into that one process.

## Key constraint: the RGB camera only streams while navigating

The `sc520cs` RGB sensor is brought up by the AI/laser navigation pipeline and only
delivers frames while the robot is actively navigating (cleaning). It cannot be driven
standalone while docked — attempting to open it on the dock just power-cycles the sensor
(`PWR_ON`/`PWR_OFF`). The cloud video path (Alibaba Link Visual) is gone on a
Valetudo/factory-reset robot. So the only achievable feed is **RGB while cleaning** — there
is no docked "security cam" mode on this hardware.

## Where the live RGB frames are: `node_camera_ai.so`

During navigation, every RGB frame flows through:

```
camera_ai::Running::ProcessImage(ava_ai_camera_msg const&)   @ 0x6fbe0
```

`ava_ai_camera_msg` layout (the bits we use):
- `+0x30` = raw **NV21** YUV buffer, `+0x1c` = width, `+0x20` = height, `+0x08` = timestamp.

`ProcessImage` builds a YUV `cv::Mat`, runs `cv::cvtColor(..., COLOR_YUV2BGR_NV21)` to get a
**BGR `cv::Mat`**, then feeds it to inference. Crucially, it **already contains a per-frame
JPEG encode**:

```c
if (timestamp_advanced && CameraAINode::SdCardAvailable()) {
    cv::imencode(".jpg", BGR_Mat, jpeg_vector, {IMWRITE_JPEG_QUALITY, 70});
    CameraAINode::Publish(ava_upload_image_info /* on topic CAMERA_PQKL_IMG_UPLOAD */);
}
```

This branch was built for an SD-card data-collection feature. `SdCardAvailable()` (@0x566b0)
queries an `SDCARD_AVAILABILITY` topic; the X40 has no SD card, so it returns false and the
encode never runs in normal operation. **The encoder we need is right there — just gated off.**

(Decompiled with Ghidra headless using `tools/ghidra/DecompAI.java`.)

## Why a binary patch is required (no config / no socket shortcut)

- **No external subscriber is possible.** `ava` is one process and its pub/sub topics
  (including `CAMERA_PQKL_IMG_UPLOAD`) are dispatched **in-memory** to in-process callbacks.
  `/tmp` exposes only `avacmd`/`avaexec`/`avaperf`/`speech`/`videomonitor` sockets — there is
  **no per-topic IPC socket** to tap from outside. Frames can only be reached by code running
  inside `ava`.
- **No config flag does it either.** The streamer node has a `save_pqkl_img` config bool that
  writes frames to disk, but it operates on the *streamer's own* camera buffer, which is empty
  during cleaning (the AI node owns the sensor then). Dead end.

So the minimal, self-contained option is to patch `node_camera_ai.so` to write the
already-encoded JPEG to a file from inside `ProcessImage`.

## The patch (4 edits — see `patch/patch_ai.py`)

Targets stock `node_camera_ai.so` md5 `ad74c34ded63853af4b41d7712c3f76a`
→ patched md5 `6303ad6aefee7d14d8c26536f843be27`.

1. **Force the RGB encode branch on.** `ProcessImage` @ `0x6fdfc`: change the gate
   `b.ne 0x6fefc` (`0x54000801`) to an unconditional `b 0x6fefc` (`0x14000040`). This enables
   only the **RGB** encode path. `SdCardAvailable()` is left **stock**, so the sibling IR/laser
   path (`ProcessIrImage`) is unaffected and does not needlessly encode/publish.
2. **Add a code cave** in unused executable padding @ `0x1005a8` (zero bytes after
   `.gcc_except_table`; file offset == vaddr in segment 0). It is position-independent
   (`adr`-relative) and uses raw aarch64 syscalls — `openat(56)`, `write(64)`, `close(57)`,
   `renameat(38)` — to write the JPEG byte vector to `/tmp/cam.jpg.tmp` and atomically rename
   it to `/tmp/cam.jpg`. Source: `patch/cave.s`.
3. **Redirect the dead cloud call to the cave.** At `0x70198`, `ProcessImage` calls
   `CameraAINode::Publish(ava_upload_image_info)` (the now-useless cloud upload). At that site
   `x1` = `&jpeg_vector` (`{begin, end, ...}`). Retarget that `bl` to the cave.
4. **Map the cave.** Extend the executable `LOAD` segment's `p_filesz`/`p_memsz`
   `0x1005a8 → 0x10064a` so the cave is loaded R-X.

The patcher ships only offsets + our own cave bytes and operates on a binary the user pulls
from their own robot; it verifies the input md5 and refuses to patch a different build.

## Deploying: `ava` loads from `/tmp/ava/lib`, not `/ava/lib`

`/ava/lib` is read-only squashfs. At boot, `/etc/init.d/prepare_ava_env.sh` copies the node
libs to **tmpfs `/tmp/ava/lib`** and `ava` `dlopen()`s from there. So a bind-mount over
`/ava/lib` does nothing. The deploy step (`scripts/deploy.sh`):

1. Back up `/tmp/ava/lib/node_camera_ai.so` to `/data` (once).
2. Overwrite `/tmp/ava/lib/node_camera_ai.so` with the patched copy.
3. `killall ava` — the watchdog respawns it (~30–60s) with the patched lib.

This is **non-persistent**: a reboot restores the stock lib from squashfs. `scripts/revert.sh`
restores the saved stock copy and restarts `ava` without a reboot.

## Serving: `server/mjpeg_server.c`

A tiny static aarch64 HTTP server that watches `/tmp/cam.jpg` and serves it as
`multipart/x-mixed-replace` (`/stream.mjpg`) plus a single still (`/snapshot.jpg`) on `:8081`.

> Note: the stream loop must compare the file's **nanosecond** mtime (`st_mtim`), not the
> whole-second `st_mtime` — otherwise it only emits one frame per second regardless of how
> fast frames are written. (This was the one bug that made the feed look "slow.")

## Framerate

~7 fps, set by the streamer's `fps_out: 7` config (sensor captures at `fps_in: 15`,
downsampled to 7 frames published to the AI node as `ava_ai_camera_msg`). The AI node — and
therefore our per-frame write — runs at that rate. It could be raised toward 15 by editing
`fps_out` in `/ava/conf/r2416.conf` (no binary patch), at the cost of roughly doubling the
AI node's per-frame inference load during navigation.

## Storage

`/tmp/cam.jpg` is a **single file**, overwritten in place every frame (write `.tmp`, atomic
rename). It lives in tmpfs (RAM) and is wiped on reboot. Nothing accumulates; the MJPEG server
stores nothing. Verified: zero JPEG files written to `/tmp` or `/data` over a full clean.

## Reverting / safety

- The only on-device change is the tmpfs lib swap; `scripts/revert.sh` undoes it, and a reboot
  fully reverts to stock regardless.
- `ava` stayed stable under the patch across restarts and full cleans (no crash/segfault). If a
  future firmware build differs, the patcher's md5 guard prevents applying mismatched offsets.
