# Dreame X40 Ultra (r2416) — camera live-streaming reverse engineering

Goal: get a live camera feed locally on a rooted, Valetudo-running X40 Ultra
(no cloud). Robot at 10.0.0.101, root over SSH (key: ./valetudo.key).

## Hardware / platform
- SoC: Allwinner "sunxi", aarch64, kernel 4.9.191, glibc 2.34 (Buildroot).
- Cameras (V4L2 subdevs behind Allwinner VIN media pipeline /dev/media0):
  - `sc520cs_mipi` — SmartSens 5MP color = **front / AI obstacle camera** (the app's video).
  - `sc035gs_mipi` — global-shutter mono = structured-light / line-laser sensor.
- `/dev/video0,1,2` = `vin_video0/1/2`. They reject plain V4L2 capture ("No such
  device" from ffmpeg) — sunxi VIN requires full media-controller pipeline setup
  (subdev links + formats via /dev/media0) + ISP tuning. Not directly grabbable.
- No ffmpeg/gstreamer/v4l2-ctl/python/gcc on the robot. busybox 1.36, curl, wget,
  scp present. `/data` 2.7G free. `/tmp` is tmpfs (~420M free).

## Software architecture
- Robot controller is a single binary `ava` (`/usr/bin/ava`, PID watched by
  monitor.sh/exec_monitor.sh which restart it). Config: `/ava/conf/r2416.conf`.
- `ava` loads node plugins from `/ava/lib/node_*.so`. Relevant:
  - `node_camera_streamer.so` — the camera streamer (class AvaNodeCameraStreamer,
    inner class `RealyVideoMonitor` = "relay video monitor").
  - `node_media.so`, `node_recorder.so`, `node_camera_laser.so`, `node_camera_ai.so`.
- Control plane = `avacmd` → unix socket `/tmp/avacmd.socket` → ava node graph.
  Envelope (from /ava/script/msg_cvt.sh):
      avacmd <node> '{"type":"<type>","cmd":"<cmd>", ...}'
  Known nodes: iot, msg_cvt, media, clb, ota, signal, sys, test, streamer.
- Cloud/app path was: miio (127.0.0.1:54320 miio_agent) -> AvaNodeIot -> AvaNodeMsgCvt
  -> `MsgCVT2CameraStreamer` -> streamer node. Valetudo removed the cloud side.

## The streamer node (from r2416.conf)
    "ID":"AvaNodeCameraStreamer","name":"streamer","index":2,
    "width":672,"height":504,"fps_in":15,"fps_out":7,
    "enable_sync":true,"open_camera":false,"file_path":"/data","file_num":5
- `open_camera:false` => camera not opened until told to.
- dconf `/ava/conf/ava_camera_streamer_dconf.mk`: `ENABLE_VIDEO_STREAMER=no`,
  `ENABLE_VIDEO_MONITOR=no`, `ENABLE_PET_GENERATE_GIF=yes`.

## ⭐ Where frames come out (no cloud needed)
1. **nanomsg IPC socket `ipc:///tmp/videomonitor.socket`** — EXISTS right now
   (created by RealyVideoMonitor). Robot has `libnanomsg.so.5`. Subscribe with a
   nanomsg client (SUB or PULL) to receive frames once the monitor is started.
2. **JPEG to disk** — format strings present:
   - `/data/www/data/camera_jpg/camera_jpg_%lu.jpg`
   - `/data/monitor/rgb_image_%ld_timestamp_%ld.jpg`
   (dirs don't exist until that mode runs.)
- Encoding via OpenCV imgcodecs (`libopencv_imgcodecs.so.4.2`) — JPEG. Also links
  libsunxicamera.so (Allwinner camera HAL), libcurl/libssl (cloud upload).

## RealyVideoMonitor message handlers (demangled)
- `PublicInit()`
- `SolveRecord(AvaJsonMsg*)`        — record video
- `SolveIntercom(AvaJsonMsg*)`      — two-way audio
- `Get/SetFillLight(AvaJsonMsg*)`   — camera LED light
- `PostJsonByCurl(...)`             — cloud upload (aiphotos.dreame.tech) — avoid
- Master on/off appears to be an internal `ava_msg_switch` message (not a plain
  avacmd JSON) posted by msg_cvt when the proprietary "Monitor" command arrives.
- Streamer JSON keys seen: type, cmd, method, action, mode, monitor, open,
  open_camera, start, stop, quality, key. Status report it emits:
  `{"type":"system","key":"ai_camera_status","value":"%d, %ld"}`.

## Why it's not a quick win
- vacuumstreamer (tihmstar) targets a standalone `video_monitor` binary that does
  NOT exist on the X40 — streamer is inside monolithic `ava`.
- The "Monitor on" trigger is a **proprietary RPC**, not a public MIoT siid/piid
  (r2416 is not in the miot-spec DB). Exact type/cmd still unknown.
- Camera may refuse to stream without the cloud P2P session context (unverified).

## Remaining work (Path A)
1. Find the trigger: disassemble streamer handlers OR empirically probe
   `avacmd streamer '{...}'` / `avacmd msg_cvt '{...}'` while watching
   `dmesg | tail` for `[sc520cs_mipi]sensor_s_stream on = 1` (instant feedback).
2. Build an aarch64 nanomsg client (cross-compile; robot has no toolchain) to read
   `/tmp/videomonitor.socket`, OR if a mode dumps JPEGs to /data, just read those.
3. Determine frame format on the socket (JPEG vs H.264) and re-serve as MJPEG/HTTP
   (optionally feed go2rtc) for the pygame app or a browser.

## Recovery / safety
- Full backup on the laptop: ../backups/robot-20260601-160159/ (incl. _root_postboot.sh
  and a 32MB /ava/conf tarball). No persistent changes made to the robot.
- ava is auto-restarted by monitor.sh; `killall -9 ava monitor.sh` + reboot recovers.

## Live probe results (session 2026-06-01)
- Control plane CONFIRMED: `avacmd streamer '{"type":"videomonitor","cmd":"<cmd>"}'`
  works. `get_camera_state`->{"state":"closed"}; `open_camera`->{"ret":"ok"};
  `takephoto`,`close_camera`,`open_debug_log` all accepted.
- `open_camera` DID power the sensor once (dmesg sc520cs power seq) but I2C
  sensor_detect FAILED ("not target chip"); state stayed "closed".
- Subsequent open_camera calls return ok but do NOT re-attempt the hardware
  (no new dmesg) -> streamer is `enable_sync:true`: it ATTACHES to the camera
  pipeline run by camera_laser/camera_ai during navigation; it does not drive
  the sensor standalone. Bare power-on fails I2C without camera_laser's full
  sunxi ISP/MIPI/TDM bring-up.
- Tested docked, driven ~30cm off dock, and a brief `start` clean: sc520cs never
  streamed (no `sensor_s_stream on`) because the robot never entered active
  obstacle-avoidance navigation (init -> sent home).
- Transport is Alibaba Cloud: `/data/log/video_monitor_startup.conf` = "ali";
  lib has `oss_upload_url`, curl publish. User factory-reset the robot, so NO
  cloud account/session exists. Local `ipc:///tmp/videomonitor.socket` tap is the
  only hope, still unverified (needs a nanomsg client + camera actually streaming).

## Conclusion / remaining options
- The camera only streams when camera_laser/camera_ai run it (real navigation).
- Remaining levers (both heavier, uncertain):
  1. Enable streamer standalone: bind-mount patched ava_camera_streamer_dconf.mk
     (ENABLE_VIDEO_STREAMER=yes, ENABLE_VIDEO_MONITOR=yes) + restart ava, hope the
     streamer then does its own pipeline bring-up. Restarts the robot controller.
  2. During a REAL clean (robot navigating, camera up), fire streamer open_camera
     to sync, and tap /tmp/videomonitor.socket with a cross-compiled aarch64
     nanomsg client (still to be built). Verify frames flow without cloud session.

## BREAKTHROUGH + final wall (session 2026-06-01, cont.)
- ENABLING the streamer fixed camera init: bind-mount patched
  ava_camera_streamer_dconf.mk (ENABLE_VIDEO_STREAMER=yes, ENABLE_VIDEO_MONITOR=yes)
  over the read-only squashfs + restart ava (kill; monitor.sh/exec_proc auto-restart,
  ~36s). Then `avacmd streamer '{"type":"videomonitor","cmd":"open_camera"}'` ->
  state "open", dmesg `sc520cs_mipi PWR_ON / sensor_detect success`. CAMERA OPENS.
- BUT open_camera only powers the sensor; it does NOT start publishing. No frames on
  /tmp/videomonitor.socket (sub & pull both time out), takephoto writes nothing.
- Real command format (from node_msg_cvt.so) is NOT cmd-based but:
    {"operType":"monitor"|"takephoto"|"clould","operation":"start"|"end","session":"clb2videmonitor"}
  These are internal MsgCVT2CameraStreamer messages; avacmd to streamer/clb/msg_cvt/iot
  all return {} (can't inject them - they're generated only by a real miio property write).
- Live trigger = miot property `StreamerSwitch` (avanodemsgcvt::MsgClean::Get/RptStreamerSwitch),
  handled by AvaMsgCvtCameraStreamerProcess. Setting it requires a local miio set_properties.
- THE WALL: transport is Alibaba Cloud Link Visual. /mnt/private/ULI/factory/video_vendor_lic.json
  = Aliyun IoT triple {productKey a1408BoU2FI, deviceName, deviceSecret, vendor:ali}.
  support.json = ["ali","agora","tx"] (all cloud). The streamer publishes to Aliyun;
  the app viewed via Aliyun + the PIN-authorized cloud session. Factory reset + Valetudo
  removed all cloud session machinery. The local relay socket only carries frames when the
  monitor pipeline is started by that cloud flow.
- TO GO FURTHER would need: find StreamerSwitch siid/piid, inject via local miio, AND
  almost certainly binary-patch node_camera_streamer.so to publish to the local socket
  without a successful Aliyun session. That's binary-patching territory, low odds.

## Current robot state (IMPORTANT)
- dconf bind-mount ACTIVE (/data/vacuumcam/ava_camera_streamer_dconf.mk over
  /ava/conf/...), ava restarted with streamer enabled. Robot docked.
- TO REVERT to factory state: `umount /ava/conf/ava_camera_streamer_dconf.mk` then
  restart ava (killall ava). Patched copy stays in /data/vacuumcam (harmless).
- Tools left in /tmp (non-persistent, gone on reboot): cam_tap, *.sh.

## CORRECTION (post-revert verification)
- The camera-open fix was the **ava RESTART**, NOT the ENABLE_VIDEO_STREAMER=yes flag.
  After reverting dconf to factory (=no) and the ava restart, `open_camera` STILL
  succeeds (dmesg PWR_ON + sensor_detect success). The original boot had left the
  camera I2C/TDM/ISP in a bad state ("not target chip"); a clean `ava` restart clears it.
- So to reproduce "camera opens": just restart ava (killall ava; auto-restarts ~36s),
  no dconf patch needed. The dconf patch was a red herring for the open step.
- This does NOT change the wall: open_camera powers the sensor but does not start the
  publish; the live stream still needs the StreamerSwitch->Aliyun cloud monitor flow.

## Robot reverted to factory state (end of session)
- dconf=factory (ENABLE_VIDEO_STREAMER=no), no bind-mounts, /data/vacuumcam removed,
  /tmp tools removed, camera closed/powered-off, ava up, robot docked & charging.

## Verification pass (Codex, 2026-06-01)
- Pulled fresh local copies of `/ava/lib/node_msg_cvt.so`,
  `/ava/lib/node_camera_streamer.so`, `/ava/conf/r2416.conf`,
  `/ava/conf/ava_camera_streamer_dconf.mk`, and video monitor vendor config into
  `camera-re/artifacts/`.
- Confirmed `StreamerSwitch` mapping:
  - internal int-property enum: `23`
  - MIoT service/property: `siid=4`, `piid=22`
  - direct local read works:
    `avacmd msg_cvt '{"id":9101,"method":"get_properties","params":[{"did":"909576060","siid":4,"piid":22}]}'`
- Current value on this robot was `22`. Older logs showed `3`; this is not a simple
  boolean. Treat it as a persisted setting/bitmask unless proven otherwise.
- Verified local write syntax by setting the property to the existing value `22`;
  `msg_cvt` returned `code:0` and the value stayed `22`.
- Ran one bounded change test: set `StreamerSwitch` to `3` for five seconds, then
  restored `22`. Result: camera state stayed `closed`; no new monitor/session
  activity appeared in `/tmp/log/log_err`. This weakens the earlier claim that
  `StreamerSwitch` alone starts live monitor publishing.
- Backups before the write test:
  - laptop: `camera-re/backups/20260601-174723/robot-pre-streamerswitch.tar`
  - robot: `/data/camera-re-backups/20260601-174723/`
- Downloaded Bootlin aarch64 glibc toolchain and pulled matching robot
  `libnanomsg.so.5.1.0` into `camera-re/robolibs/`. The Bootlin compiler is a Linux
  x86_64 binary, so on this Mac it must be run inside Docker or replaced with a
  macOS-native cross linker. Added `build_cam_tap_docker.sh`; Docker was installed
  but the daemon was not running during this pass.

## cam_tap build + live socket tests (Codex, 2026-06-01 cont.)
- Docker was started and `cam_tap` built successfully by forcing an amd64 container
  on the M4 Mac:
  `docker run --rm --platform linux/amd64 ... aarch64-linux-gcc ...`
  Result: `camera-re/cam_tap` is an aarch64 Linux ELF using `/lib/ld-linux-aarch64.so.1`.
- Pushed `cam_tap` to `/tmp/cam_tap` on the robot and verified it connects to
  `ipc:///tmp/videomonitor.socket`.
- Passive tap with camera closed: connects, times out, zero messages.
- `open_camera` before `ava` restart reproduced the I2C failure:
  `sensor_detect fail` / `not target chip`; state stayed `closed`.
- Restarted `ava` using `killall ava`; supervisor relaunched it as PID `16868`.
  After initialization, `avacmd streamer get_camera_state` returned `closed`.
- After the restart, `open_camera` succeeded and state became `open`, but socket tap
  still timed out with zero messages. Camera was closed after the test.
- Combined test: `open_camera`, start `cam_tap`, set `StreamerSwitch` from `22` to
  `3`, restore `22`, then close camera. Result: zero messages on
  `/tmp/videomonitor.socket`; `StreamerSwitch` restored to `22`; camera final state
  `closed`.
- Current conclusion: `open_camera` only powers/opens the sensor. `StreamerSwitch`
  is a valid property but is not sufficient to start local video monitor publishing,
  even when the camera is open and the local nanomsg socket is being tapped.

## Static RE of node_camera_streamer.so and node_msg_cvt.so (Claude, 2026-06-01)

### Critical finding: wrong nanomsg socket type — explains all zero-message results

The robot's libnanomsg.so.5.1.0 uses NON-STANDARD protocol constants (verified from
nn_*_socktype data sections):
- NN_PAIR = 16 (standard = 0)
- NN_PUB  = 32 (standard = 16)
- NN_SUB  = 33 (standard = 17)
- NN_PUSH = 80
- NN_PULL = 81 (standard = 33)

The `VideoMonitorInit` in RealyVideoMonitor calls `nn_socket(AF_SP=1, protocol=16)` =
**NN_PAIR**. The socket is BOUND to `ipc:///tmp/videomonitor.socket`.

cam_tap was using NN_SUB=33 and NN_PULL=81 — **both incompatible with a PAIR socket**.
This is why cam_tap connected but received 0 messages even when the camera was open.

### Architecture of ipc:///tmp/videomonitor.socket

This is a **bidirectional PAIR control channel**, NOT a frame-delivery socket:
- RealyVideoMonitor BINDS a NN_PAIR socket
- The cloud VideoMonitor SDK (Aliyun/Agora/Tencent component) CONNECTS as the PAIR peer
- Cloud→Robot: `$X`-format control commands (see below)
- Robot→Cloud: JSON status/config responses via `SendMsg2VideoMonitor`

**Frames do NOT flow through this socket.** Frames go to the cloud via
`PostJsonByCurl`, Agora SDK, or similar. The socket is for signaling only.

### Internal `$X` control message protocol

`thread_RceiveMsgFromVideoMonitorEv` (at 0x5d020) runs in a loop:
1. Calls `ReciveMsgFromVideoMonitor` (nn_recv on the PAIR socket)
2. Passes received string to `ProcInternalMsg`
3. If ProcInternalMsg returns false (unhandled), queues msg for msg_cvt

`ProcInternalMsg` dispatches on strings starting with `$`:
- `$0`: set flag at +0x588 = 1
- `$2`: set frame quality/compression at +0x58c = 100
- `$5`: set streaming_active +0x5f8 = 1, save timestamp at +0x5f0
- `$6<byte><data>`: call functor registered at +0x600(data); set +0x5f9 = 1
- `$7<byte><data>`: call functor registered at +0x620(data)

Setting `$5` enables the streaming_active flag. The functors at +0x600/+0x620 are
registered by the cloud SDK at session start; if not registered, they're null.

### avacmd streamer command set (full list)

`AvaJsonMsgProcess` handles `{"type":"videomonitor","cmd":"X"}`:
- open_camera, close_camera, get_camera_state (known)
- set_exposure_gain, set_auto_exposure_gain
- open_debug_log, close_debug_log
- reset_sync, stop_sync
- test_gif, calibrate, save_image

No monitor-start command exists in this dispatch table. Monitor-start comes from
the cloud path only (via ReciveMsgFromCVT or the PAIR socket).

### Session flag at RealyVideoMonitor+0x52

Set in the RealyVideoMonitor constructor from the JSON config. Controls whether
`ReciveMsgFromCVT` and `SendMsg2VideoMonitor` will proceed (gate check).
Corresponds to the ENABLE_VIDEO_MONITOR=yes dconf flag.

### ReciveMsgFromCVT dispatch (for future reference)

Receives MIOT-format JSON {method, taskid, ...} from msg_cvt node:
- method "get_properties" + session flag + taskid 9/11/99/1003: local responses
- method "action"  + session flag + default taskid: → SendMsg2VideoMonitor (forwards to cloud)
- method "oss_upload_url" + default taskid: → SendMsg2VideoMonitor

None of these start local frame streaming.

### cam_tap rebuilt with correct protocol

cam_tap.c rewritten to support:
- `pair` mode using NN_PAIR=16 ← the correct protocol
- Send capability: inject `$5` and other control messages
- Heartbeat: re-send every N ms to keep streaming_active set

Build: `bash camera-re/build_cam_tap_docker.sh`

### Next test: inject $5 via PAIR socket

With ENABLE_VIDEO_MONITOR=yes (dconf bind-mount) so session flag is set:
1. Connect NN_PAIR cam_tap
2. Send `$5` to set streaming_active
3. Monitor /data/monitor/ and /data/www/data/camera_jpg/ for JPEG files
4. Also try `$0`, `$2` (quality), and repeated `$5` heartbeats
Note: functors at +0x600/+0x620 may be null (not registered without cloud SDK),
in which case only flag-setting commands will have effect.

## Live PAIR-socket test results (Claude, 2026-06-01)

Confirmed the static-RE predictions against the live robot:

**PAIR connection works — got a handshake reply.** With ENABLE_VIDEO_MONITOR=yes
(dconf bind-mount) + ava restart + camera open, running:
  `cam_tap pair ipc:///tmp/videomonitor.socket /tmp/cap 30 3000 '$5' 2000`
connects (proto=16) and after sending `$5` the robot REPLIES with one message:
  `{"method":"action","taskid":8,"session":"null",
    "value":"{\"operType\":\"clould\",\"operation\":\"cloud_connected\",\"session\":\"null\"}"}`
So the robot treats our PAIR peer as the cloud VideoMonitor SDK and acknowledges a
"cloud_connected" event. This is the first time ANY data has flowed on this socket —
prior sessions used NN_SUB and got nothing. **The protocol fix is validated.**

**But no frames on the socket, and no JPEG files.** Confirmed the socket is
control-only:
- `$5`/`$0`/`$2` accepted; only `$5` produces the cloud_connected reply.
- `save_image` avacmd returns `{"ret":"ok"}` but writes NO file (frame cache empty
  when not navigating).
- `takephoto` returns `{}` and writes nothing.
- /data/monitor and /data/www/data/camera_jpg never get created/populated.

**No cloud SDK on the robot.** /ava/lib has only node_*.so (32 files), no Aliyun/
Agora/Tencent lib. RealyVideoMonitor uploads directly via PostJsonByCurl. So the
PAIR peer that normally connects is a cloud-side component we can impersonate for
signaling, but the frame bytes are pushed out by curl, NOT onto the local socket.

**Camera streaming requires real navigation — and only the laser cam came up.**
- `enable_sync:false` + `open_camera:true` in r2416.conf CRASHED ava (SSH dropped,
  watchdog recovered it, conf auto-reverted). Do NOT set enable_sync:false.
- Brief cleaning via Valetudo BasicControlCapability start→stop→home: during the
  ~12s of movement, dmesg showed `[sc035gs_mipi]sensor_s_stream on = 1` (the MONO
  line-laser camera) but **sc520cs (RGB) never reached s_stream on=1**. open_camera
  powers the RGB sensor but a few seconds near the dock doesn't engage the RGB
  obstacle-avoidance capture pipeline.

**/tmp is tmpfs** — cam_tap and scripts are wiped on every reboot; must re-push after
any ava crash/reboot.

### Conclusion: pivot to binary patching

The local PAIR socket is fundamentally a control channel; frames are curl'd to the
cloud and never touch a local IPC the way the architecture is wired. Getting sc520cs
to stream during a real clean still wouldn't put frames on the socket. The path that
can actually work is **patching node_camera_streamer.so** to divert the encoded JPEG
to local disk (or a new NN_PUB socket). That requires Ghidra to locate the
capture→encode(cv::imencode)→upload path. Next session: install Ghidra, decompile
CameraThread (0x38eb0), UploadPhotoToServer (0x62830), and the imencode call site.

## Ghidra decompilation of the frame path (Claude, 2026-06-01)

Installed Ghidra 12.1.1 (brew), JDK 21, ran headless analyzeHeadless + a decomp
script (camera-re/ghidra/scripts/DecompCamera.java). Output in
camera-re/ghidra/out/streamer/. Key findings:

### The local JPEG-write capability ALREADY EXISTS in the binary
- Two `YUVToJPG` helpers: `YUVToJPG(uchar*,...,vector<uchar>&,...)` @0x680e0 uses
  `cv::imencode` (to memory, for cloud upload); `YUVToJPG(const char* path,...)`
  @0x689f0 uses `cv::imwrite` (to a FILE). The file-write variant already exists.
- `AvaNodeCameraStreamer::SaveImageFromCache(path, type)` @0x39ee0:
  - reads the cached YUV frame at `this+0x440`
  - **if that pointer is NULL it does NOTHING** (silent)
  - type 0 = BMP, type 1 = raw YUV, type 2 = JPEG (calls YUVToJPG file variant),
    type 3 = all three
- `save_image` avacmd is fully wired: `AvaJsonMsgProcess` @0x42390 handles
  `{"type":"videomonitor","cmd":"save_image","basedir":"<dir>","mode":"jpg"}` and
  calls `SaveImageFromCache(path, 2)`, writing
  `<basedir>/camera_jpg_<n>_width=<w>_heig=<h>.jpg`. Gated by `this[0x514]` (open flag);
  if not open it logs "[camera]camera not open".

So we do NOT necessarily need to patch anything to get a local JPEG — `save_image`
already writes one, IF the frame cache is populated.

### The frame cache (this+0x440) and why it's empty when docked
- `CameraMsgProcess` @0x38a60 (runs in capture STATE 5) reads a frame from the
  SunxiCam HAL (vtable call), then `UpdataImageToCache(this, frame)` @0x38bcc fills
  `this+0x440`. `GetImageFromCache`/`SaveImageFromCache` read it under mutex this+0x4e0.
- `CameraThread` @0x38eb0 is the state machine. State at this+0x64 drives it; this+0x60
  is the REQUESTED state. State 2=OpenCamera, 3=idle, 5=capture(CameraMsgProcess),
  7=CloseCamera, 0xb/0xc/0xd=calibration.
- `open_camera` avacmd sets this+0x60 = 5 (request capture). `AvaMsgOpenCameraProcess`
  @0x13f8e0 also sets this+0x60 = 5 when this+0x570 != 0, else publishes an AskOpen msg.

### DECISIVE live test: streamer can't drive sc520cs VIN standalone
With ENABLE_VIDEO_MONITOR=yes + camera open via avacmd, dmesg showed the sc520cs
sensor POWER-CYCLING repeatedly (PWR_ON -> PWR_OFF, 1120s/1121s/1123s...) and
get_camera_state returned "closed". The streamer's attempt to drive the sensor
standalone fails: it bounces power and never reaches stable VIN streaming. save_image
returned `{}` and wrote nothing (cache empty / not open).

**Conclusion: the sc520cs RGB sensor only delivers frames when camera_ai/camera_laser
run the full MIPI/ISP/TDM bring-up — i.e. during NAVIGATION.** The streamer attaches
to that pipeline (enable_sync:true); it cannot bring it up alone. open_camera right
after an ava restart powers the sensor, but that is not the same as VIN streaming
frames into the cache.

### Implication for the deliverable
A standalone DOCKED live feed is not reachable without driving the VIN/ISP pipeline
ourselves (conflicts with ava — out of scope/high risk). BUT during a real cleaning
run the cache WILL fill, so:
- Option A (NO binary patch): poll `save_image` (basedir=/tmp) every ~1s during a
  clean and serve the newest JPEG as MJPEG. Validates trivially.
- Option B (small patch): patch `CameraMsgProcess` right after UpdataImageToCache to
  also dump the cache to a fixed file (e.g. /tmp/cam.jpg) every cycle, so any consumer
  can read it without the avacmd round-trip.
Both only produce frames while the robot is navigating — which is the primary use
case for a vacuum camera anyway. Next: do a ~1 min real clean and poll save_image to
confirm the cache fills during navigation (RGB s_stream on=1).

## Cleaning test + the REAL RGB-frame path: node_camera_ai.so (Claude, 2026-06-01)

Ran a 60s real cleaning run, polling streamer `save_image` every 1s. RESULT: zero
JPEGs, and dmesg showed only `sc035gs` (mono laser) streaming — `sc520cs` (RGB)
never hit `s_stream on=1`, even though ALL obstacle-avoidance settings are enabled
(ObstacleAvoidance/Pet/CollisionAvoidant/ObstacleImages all `enabled:true` via
Valetudo). Calling streamer `open_camera` at the start may also conflict with the AI
camera pipeline (power-cycle). The streamer's `save_image` cache approach does NOT
fill during a normal clean.

### Where RGB frames actually live: obstacle images + node_camera_ai.so
- Valetudo's `DreameObstacleImagesCapability` reads RGB JPEGs from
  `/data/record/<n>.jpg` or `/data/record/ai_image/<n>.jpg`. After our clean,
  `/data/record/ai_image/` existed with mtime = clean time but was EMPTY: obstacle
  images are **event-driven** (saved only when the robot detects an obstacle to
  avoid), not a continuous stream. Open floor => no images.
- Pulled `node_camera_ai.so` to artifacts/. It contains:
  - `cv::imwrite`, `cv::imencode`
  - `camera_ai::DataCollectionJob::SaveImageAndReturnPath(string path, cv::Mat, vector<int>)`
    — saves a DECODED RGB frame (cv::Mat) to a JPEG path. This is the obstacle-image
    saver.
  - MNN (Alibaba neural-net inference) for obstacle detection.
- So `node_camera_ai` has the live RGB frame as a `cv::Mat` every inference cycle
  during cleaning, but only WRITES it on obstacle detection.

### Final landscape (what is achievable)
1. **RGB obstacle snapshots during cleaning** — ALREADY WORKS (Valetudo
   ObstacleImagesCapability, enabled). Event-driven, /data/record/ai_image/*.jpg.
   Real RGB images, but not a stream. Zero patching. Test: clean with an obstacle
   placed in the path.
2. **Continuous RGB feed *while cleaning*** — feasible by patching node_camera_ai.so
   to call imwrite/SaveImageAndReturnPath on EVERY inference frame (not just
   obstacles), then poll/serve as MJPEG. Needs a Ghidra pass on node_camera_ai.so to
   find the inference loop + cv::Mat. Medium effort. Only works while navigating.
3. **Live feed while DOCKED** — NOT feasible: sc520cs won't stream standalone (power-
   cycles), and the cloud Link Visual path is gone. Would require driving the sunxi
   VIN/ISP pipeline ourselves (conflicts with ava, high risk).

The mono laser cam (sc035gs) DOES stream continuously during nav (640x480 grayscale),
but it's a navigation sensor, not a useful "camera".

### Robot left in factory state: docked, 99%, no bind-mounts, dconf=no, camera closed.

## Valetudo source review (Codex, 2026-06-01)
- Pulled Valetudo source into `camera-re/valetudo-src/` for local inspection.
- Valetudo confirms the X40 is a Dreame GEN2 robot and registers AI-camera-adjacent
  capabilities, but only for:
  - AI camera obstacle avoidance toggle.
  - line-laser + AI-camera obstacle avoidance toggle.
  - AI camera go-to mode (`goToModeId = 23`).
  - camera fill light (`MISC_TUNABLES` key `FillinLight`).
- Important correction: `siid=4`, `piid=22` is Valetudo's
  `AI_CAMERA_SETTINGS`, not a proven live-stream start switch. Valetudo decodes it
  as a bitmask:
  - bit `0b00000010`: obstacle detection
  - bit `0b00000100`: obstacle images
  - bit `0b00010000`: pet obstacle detection
  Therefore the observed value `22` means obstacle detection + pet obstacle
  detection are enabled, plus an unknown/ignored bit; it is not a monitor boolean.
- Valetudo's `DreameGen2ValetudoRobot.onIncomingCloudMessage()` explicitly handles
  cloud `properties_changed` messages for synthetic `siid=10001` with a sample
  value:
  `{"operType":"properties_changed","operation":"monitor","result":0,"status":0}`.
  This is the strongest Valetudo-side evidence that live monitor status travels as
  a cloud callback/event, not through the normal public GEN2 MIOT settings.
- Valetudo's dummy cloud handles normal miio cloud handshake plus map-upload URL
  requests. It does not implement any Link Visual / Aliyun live-video negotiation,
  and it has no camera stream endpoint.
- Updated conclusion: the next useful target is not toggling `siid=4/piid=22`.
  The target is recovering the real cloud/app monitor-start request that causes
  the robot to emit the synthetic `siid=10001` monitor callback, then seeing whether
  that path can be replayed locally or bypassed.

## Monitor-action replay tests (Codex, 2026-06-01 cont.)
- Fresh pre-test backup:
  - laptop: `camera-re/backups/20260601-170700/pre-monitor-action.tar`
  - robot: `/data/camera-re-backups/20260601-170700/pre-monitor-action.tar`
  Missing-file warnings were expected for `/data/aclcode` and
  `/tmp/log/log_switch.json`; existing config/log files were archived.
- Robot started this pass with the camera already `open`; `siid=4/piid=22` still
  read back as `22`.
- Re-pushed `cam_tap` to `/tmp/cam_tap` via SSH stdin because the robot lacks
  `sftp-server`, so `scp` fails. Passive tap with camera open still received zero
  messages from `ipc:///tmp/videomonitor.socket`.
- Local MIOT action shape confirmed for the synthetic camera service:
  `avacmd msg_cvt '{"id":9401,"method":"action","params":{"did":"909576060","siid":10001,"aiid":1,"in":[{"piid":100}]}}'`
  reached `MsgCameraStreamer` and returned:
  `{"piid":100,"value":"error"}`.
- Sending the suspected monitor-start JSON as a string `value` also reached the
  handler and returned `ok`:
  `{"operType":"monitor","operation":"start","session":"clb2videmonitor"}`.
  However, `cam_tap` still timed out with zero messages.
- Direct `avacmd streamer` injection of the internal JSON variants returned `{}` and
  also produced zero messages:
  - `{"operType":"monitor","operation":"start","session":"clb2videmonitor"}`
  - `{"type":"videomonitor","operType":"monitor","operation":"start","session":"clb2videmonitor"}`
  - `{"type":"videomonitor","cmd":"monitor","operation":"start","session":"clb2videmonitor"}`
- A lower action-path test using `piid=8` with
  `{"method":"monitor","operation":"camera","session":"clb2videmonitor"}` returned
  `code:-1`; that local action wrapper does not accept method `monitor`.
- Important new interpretation: accepted `siid=10001/piid=100` monitor JSON is not
  sufficient. `/tmp/log/log_err` showed:
  `caijing--ConverStringToInt--invalid_argument{"operType":"monitor"`, indicating this
  path treats the value as a synthetic property/status value, not as the live
  `RealyVideoMonitor` control message.
- Cleanup: sent monitor `operation:end`, called `close_camera`, and verified camera
  state became `closed`. dmesg showed `[sc520cs_mipi]PWR_OFF!`.

---

## 2026-06-01/02 — Option 2 deep RE: node_camera_ai.so frame path (the live RGB feed-while-cleaning effort)

Goal of this pass: find a clean way to get the *continuous* RGB frame stream that the
AI node sees during cleaning, and serve it as MJPEG. Decompiled node_camera_ai.so with
Ghidra (script camera-re/ghidra/scripts/DecompAI.java -> out/ai/) and the streamer's
PQKL path (DecompPqkl.java -> out/pqkl/).

### The per-frame RGB path (THE source of live frames)
- `camera_ai::Running::ProcessImage(ava_ai_camera_msg const&)` @ 0x6fbe0 runs for EVERY
  RGB frame during navigation. Frame layout in ava_ai_camera_msg:
  - +0x30 = raw NV21 YUV buffer, +0x1c = width, +0x20 = height (rows = h*3/2),
    +0x08 = timestamp, +0x10/+0x60/+0x24 = ids/seq, +0x38 = flag.
  - Builds a YUV cv::Mat, `cv::cvtColor(..., 0x5d = COLOR_YUV2BGR_NV21)` -> BGR cv::Mat
    stored at **Running+0x58**. Optionally cv::resize to (CameraAINode+0x634,+0x638).
  - Always feeds the BGR Mat to inference via `Synchronizer::AddImage`.
- **Built-in per-frame JPEG path (gated):** inside ProcessImage, if timestamp advanced
  AND `CameraAINode::SdCardAvailable()` returns true, it does
  `cv::imencode(".jpg"/.pqkl, BGR_Mat, jpeg_vector, {IMWRITE_JPEG_QUALITY,70})` then
  `CameraAINode::Publish(ava_upload_image_info)` on topic **CAMERA_PQKL_IMG_UPLOAD**.
  Same gate+publish also in `Running::ProcessIrImage` @ 0x70340 (the mono/IR image).

### SdCardAvailable — the single gate
- `CameraAINode::SdCardAvailable()` @ 0x566b0, called via PLT 0x3f350. ONLY 2 callers:
  ProcessImage @0x6fdf0 and ProcessIrImage @0x70544. Both only imencode-to-memory +
  Publish on the bus — **neither writes to any SD-card filesystem path**. So forcing it
  true is self-contained (no attempt to mount/write a nonexistent SD card).
- It queries availability over the `SDCARD_AVAILABILITY` topic via an `sdcard_query`
  publisher. The X40 has no SD card, so it returns false in normal operation -> the
  per-frame publish never fires. This is an engineering/data-collection feature.
- PATCH CANDIDATE: overwrite first 8 bytes of 0x566b0 with `mov w0,#1; ret`
  (0x52800020, 0xd65f03c0) -> per-frame CAMERA_PQKL_IMG_UPLOAD publish turns on.

### Why the "no-patch / config" shortcut does NOT exist
- The streamer DOES have a save-to-disk path: `TimerPqklImgUpload` @ 0x41530, gated by
  `NeedSendPqklImg` (mode `this+0xdc0` in [2,5] U [0x12,0x18]) and a flag `this[0x541]`.
  When `this[0x541]` is set it calls FUN_0x39c80 to write the JPEG to
  **/data/monitor/rgb_image_<counter>_timestamp_<ts>.jpg**; else it cloud-publishes.
- `this[0x541]` IS a config bool: the streamer ctor (0x4b9c0) does
  `HasMember("save_pqkl_img")` and sets the flag from it. `save_pqkl_img` sits among the
  AvaNodeCameraStreamer keys (index/fps_in/fps_out/enable_sync) in .rodata. NOT in the
  current r2416.conf (defaults off). ignore_topics = only ["AVA_SCAN"].
- BUT the streamer's PQKL source is its OWN camera mode object (this+0x550 ->
  GetPqklUploadImgInfo, filled by PushPqklUploadImgInfo). The streamer **AdvertiseImpl**s
  CAMERA_PQKL_IMG_UPLOAD (0x4c85c) — it PUBLISHES, does not subscribe. During cleaning
  the streamer has no RGB frames (sensor is driven by the AI node), so `save_pqkl_img`
  on the streamer captures nothing useful. Dead end for the cleaning feed.

### Decisive: the ava bus is IN-PROCESS, not tappable IPC
- `ava` is ONE process (PID 5657) that dlopen()s all node_*.so. /tmp has only 5 sockets:
  avacmd, avaexec, avaperf, speech, videomonitor — **no per-topic socket**.
- Therefore CAMERA_PQKL_IMG_UPLOAD is an in-memory pub/sub (Publish() dispatches to
  in-process subscriber callbacks). There is NOTHING external to subscribe to. The
  topic->transport mapping lives in libava, but it's in-process for same-process nodes.
- CONSEQUENCE: extracting frames REQUIRES code running inside the ava process. The only
  realistic option-2 implementations are:
   (B1) Patch node_camera_ai.so to write frames to a file from inside ProcessImage:
        SdCardAvailable->1 to enable the existing imencode, then repurpose the now-dead
        `Publish(ava_upload_image_info)` call site @0x70198 with a `bl code_cave` that
        does fopen/fwrite/fclose of the already-encoded JPEG vector (local_b0) to
        /tmp(or /data)/cam.jpg. Reuses existing encode; args are simple (ptr+size).
        Then serve that file as MJPEG over HTTP (or HA local-file/MJPEG camera).
   (B2) Write/inject a new ava node .so that subscribes in-process to
        CAMERA_PQKL_IMG_UPLOAD and writes/streams frames. Cleaner (no patching existing
        binaries) but needs the ava node SDK/registration — higher build effort.
- Recovery story for any patch: bind-mount over /ava/lib/<node>.so on writable /data;
  reverts on reboot; ava watchdog (check_restart_ava.sh) restarts ava if it crashes.

### Status
- Mechanism fully mapped. No config-only path exists. Next action requires firmware
  binary patching (B1) or a custom in-process node (B2). Awaiting go-ahead before
  doing binary surgery on the AI node.

---

## 2026-06-02 — OPTION 2 (B1) WORKS: continuous RGB feed while cleaning

End-to-end success. Patched node_camera_ai.so writes every RGB inference frame to
/tmp/cam.jpg during navigation; mjpeg_server serves it. Verified a real 672x504x3 JPEG
of the floor/obstacles fetched over the network (patch/sample_frame.jpg).

### The patch (camera-re/patch/, applied by patch_ai.py to a verified-identical base)
- Base md5 (stock node_camera_ai.so, == robot's): ad74c34ded63853af4b41d7712c3f76a
  Patched md5: a5e5f19c7b50dbf02605eb09562092b6
- Edit 1: SdCardAvailable() @0x566b0 -> `mov w0,#1; ret` (52800020 d65f03c0). Enables the
  firmware's own per-frame cv::imencode(BGR, q70) in Running::ProcessImage.
- Edit 2: code cave @0x1005a8 (unused R-X zero padding after .gcc_except_table; file off
  == vaddr). Raw aarch64 syscalls openat(56)/write(64)/close(57)/renameat(38): writes the
  already-encoded JPEG byte vector (x1 = &vector at the call site) to /tmp/cam.jpg.tmp then
  atomically renames to /tmp/cam.jpg. Source: patch/cave.s (assembled w/ native clang,
  position-independent via adr).
- Edit 3: bl @0x70198 (was -> Publish(ava_upload_image_info)@plt, dead cloud call) ->
  retargeted to the cave (0x94024104).
- Edit 4: exec LOAD segment p_filesz/p_memsz 0x1005a8 -> 0x10064a so the cave maps R-X.

### DEPLOY GOTCHA (important): ava loads from /tmp/ava/lib, NOT /ava/lib
- /ava/lib is read-only squashfs. At boot, /etc/init.d/prepare_ava_env.sh copies node_*.so
  to tmpfs /tmp/ava/lib and ava dlopen()s from there (main.cc:832 "load:/tmp/ava/lib//...").
- So a bind-mount over /ava/lib does NOTHING. Correct method: overwrite
  /tmp/ava/lib/node_camera_ai.so (tmpfs, writable) and `killall ava` (watchdog
  check_restart_ava.sh respawns it in ~30-60s). Non-persistent: reboot restores stock.
- Scripts: patch/deploy_patch.sh (push+overwrite+restart+start mjpeg), patch/revert_patch.sh.

### Consumer: mjpeg_server (camera-re/mjpeg/, static aarch64 binary)
- Serves /stream.mjpg (multipart/x-mixed-replace) + /snapshot.jpg on :8081 from /tmp/cam.jpg.
- Home Assistant: platform: mjpeg, mjpeg_url http://10.0.0.101:8081/stream.mjpg,
  still_image_url http://10.0.0.101:8081/snapshot.jpg.

### Behavior / limits (as predicted)
- Feed is live ONLY while the robot is navigating (RGB sc520cs sensor is driven by the
  AI/laser pipeline; docked = no frames). Frame rate ~ several fps, 672x504, ~30-44KB JPEG.
- ava stayed stable under the patch across restarts + a full clean. No crash/segfault.

### TODO (not yet done)
- Persistence across reboot (currently tmpfs-only). Options: hook prepare_ava_env.sh or a
  Valetudo/startup script to re-apply on boot, or run mjpeg_server from a startup hook.
- Auto-start mjpeg_server on boot.

### 2026-06-02 v2 — tightened to RGB-only (no global SD flip)
Concern: flipping SdCardAvailable()->1 globally also enabled ProcessIrImage's per-frame
encode+publish (extra CPU + in-process bus traffic; not a disk issue). v2 instead leaves
SdCardAvailable() STOCK and makes ONLY the RGB gate unconditional:
  - Edit 1 (replaces old SD patch): ProcessImage @0x6fdfc `b.ne 0x6fefc` (0x54000801) ->
    `b 0x6fefc` (0x14000040). Forces only the RGB encode+publish branch; IR path stays off.
  - Edits 2-4 (cave @0x1005a8, bl@0x70198->cave, segment extend) unchanged.
Patched md5 (v2): 6303ad6aefee7d14d8c26536f843be27.
STORAGE: /tmp/cam.jpg is a SINGLE file, overwritten in place each frame (write .tmp +
renameat), in tmpfs (RAM) — never accumulates, wiped on reboot. Verified: 0 jpg files
written to /tmp or /data over a clean; /data steady at 5-8%. mjpeg_server stores nothing.
NOTE: deploy_patch.sh must `killall mjpeg_server` before scp (else "Text file busy").

### 2026-06-02 framerate
- Feed ceiling = ~7 fps, set by AvaNodeCameraStreamer "fps_out":7 (sensor capture
  "fps_in":15, downsampled to 7 published as ava_ai_camera_msg to camera_ai). The
  streamer (CCameraModeSuxi::GetImageFrame) is the RGB capture+distribution node;
  camera_ai consumes. Raw write rate to /tmp/cam.jpg measured = 7 fps.
- BUG FOUND+FIXED in mjpeg_server: stream loop compared whole-second st_mtime -> capped
  output at 1 fps. Fixed to nanosecond st_mtim (sec+nsec) + 15ms poll. Now serves full 7 fps.
- To raise beyond 7: set "fps_out" toward 15 (== fps_in) in the streamer conf
  (/ava/conf/r2416.conf, which is writable rw-rw-r--; no binary patch). NOT DONE — user
  prefers 7 fps to avoid doubling AI inference CPU/power load during navigation.
