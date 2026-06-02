# Disclaimer

**This is not legal advice, and the software is provided "AS IS" with no warranty
(see LICENSE).** Read this before using.

## Risk to your device
- This project **modifies a running firmware component** of your robot vacuum. A bad
  patch, a mismatched firmware build, or an interrupted deploy can crash the controller
  or, in the worst case, leave the device needing a power cycle or factory reset.
- The patch is intentionally **non-persistent**: it lives in `tmpfs` / on the `/data`
  partition and a **reboot fully reverts it** to stock. The deploy script also keeps a
  backup of your stock library on `/data` and `scripts/revert.sh` restores it.
- You do this **at your own risk**. You are responsible for your own device, your
  warranty, and your data. Modifying firmware will likely void your warranty.

## What this project does and does not include
- It includes **only original code** (the patcher recipe, the assembly cave source, a
  small MJPEG server, helper scripts, and reverse-engineering notes).
- It includes **no Dreame firmware** and **no decompiled firmware source**. You must
  supply your own `node_camera_ai.so`, pulled from your own robot.
- The binary the patcher produces is a **derivative of your device's firmware**. Keep it
  on your own device; do not redistribute it.

## Legality
- Reverse engineering for **interoperability** is broadly permitted in many
  jurisdictions (e.g. EU Software Directive Art. 6; fair-use / §1201 exemptions in the
  US), but laws vary. It is your responsibility to comply with the laws that apply to
  you and with any agreements covering your device.
- This project is an independent, unofficial effort and is **not affiliated with,
  endorsed by, or supported by Dreame or the Valetudo project**.

## Privacy
- The robot's camera sees the inside of your home. The feed is served **unauthenticated
  over your LAN** on a plain HTTP port. Do **not** expose it to the internet. Restrict it
  to a trusted network / VLAN, and treat the imagery accordingly.
- The feed is only live while the robot is actively navigating; it cannot stream while docked.
