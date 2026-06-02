#!/usr/bin/env python3
"""
patch_ai.py — patch a Dreame X40 (r2416) `node_camera_ai.so` so that, while the robot
navigates, it writes every RGB inference frame as a JPEG to /tmp/cam.jpg.

This is a PATCHER, not a binary. You must supply your OWN stock node_camera_ai.so,
pulled from your own robot (see README). No Dreame code is distributed with this repo.

Usage:
    python3 patch_ai.py <stock_node_camera_ai.so> [output.so]
        default output: <stock>.patched.so

What it changes (all verified against stock md5 ad74c34ded63853af4b41d7712c3f76a):
  1. Running::ProcessImage RGB gate @0x6fdfc: `b.ne 0x6fefc` -> `b 0x6fefc` (unconditional)
     so the firmware's own per-frame cv::imencode(BGR, q70) branch always runs for the
     RGB camera. SdCardAvailable() is left STOCK, so the IR/laser path is unaffected.
  2. A position-independent code cave (raw aarch64 syscalls) is written into unused
     executable padding @0x1005a8. It writes the already-encoded JPEG byte vector to
     /tmp/cam.jpg.tmp and atomically renames it to /tmp/cam.jpg. Source: ../patch/cave.s.
  3. bl @0x70198 (was -> CameraAINode::Publish(ava_upload_image_info)@plt, a dead cloud
     call with no cloud) -> retargeted to the cave. At that site x1 = &jpeg_vector.
  4. The executable LOAD segment's p_filesz/p_memsz are extended to map the cave.

The output is a derivative of your robot's firmware — keep it local, do not redistribute.
"""
import struct, sys, os

STOCK_MD5  = "ad74c34ded63853af4b41d7712c3f76a"  # known-good base this patch targets
GATE_ADDR  = 0x6fdfc      # ProcessImage: `b.ne 0x6fefc` (encode iff SdCardAvailable)
GATE_TGT   = 0x6fefc      # RGB encode+publish path
CALL_SITE  = 0x70198      # bl Publish(ava_upload_image_info) inside Running::ProcessImage
PUBLISH_PLT= 0x3fce0      # original bl target (sanity check)
CAVE_VADDR = 0x1005a8     # unused R-X padding after .gcc_except_table (file off == vaddr)

# Verified 162-byte position-independent cave (assembled from patch/cave.s).
# Regenerate with: clang -c -target arm64-apple-macos11 cave.s -o cave.o ; otool -s __TEXT __text cave.o
CAVE_HEX = (
    "fd7bbda9f35301a9f51300f9330040f9"
    "350440f9b40213ebed020054600c8092"
    "21030010224880d2833480d2080780d2"
    "010000d41f0000f1eb010054f50300aa"
    "e10313aae20314aa080880d2010000d4"
    "e00315aa280780d2010000d4600c8092"
    "21010010620c809263010030c80480d2"
    "010000d4f51340f9f35341a9fd7bc3a8"
    "c0035fd62f746d702f63616d2e6a7067"
    "2e746d70002f746d702f63616d2e6a70"
    "6700"
)

def md5hex(b):
    import hashlib
    return hashlib.md5(b).hexdigest()

def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src + ".patched.so"

    data = bytearray(open(src, "rb").read())
    assert data[:4] == b"\x7fELF" and data[4] == 2, "not an ELF64 file"

    got = md5hex(bytes(data))
    if got != STOCK_MD5:
        print(f"WARNING: input md5 {got} != known-good {STOCK_MD5}.")
        print("  This patch targets that exact firmware build. Offsets may not match yours.")
        if "--force" not in sys.argv:
            sys.exit("  Refusing to patch. Re-run with --force only if you know the offsets match.")

    cave = bytes.fromhex(CAVE_HEX)
    assert cave.endswith(b"/tmp/cam.jpg\x00") and b"/tmp/cam.jpg.tmp\x00" in cave
    print(f"[*] cave length = {len(cave)} bytes")

    # locate the executable LOAD segment (offset 0, flag X)
    e_phoff   = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsz = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum   = struct.unpack_from("<H", data, 0x38)[0]
    xseg_off = None
    for i in range(e_phnum):
        off = e_phoff + i*e_phentsz
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = \
            struct.unpack_from("<IIQQQQQQ", data, off)
        if p_type == 1 and (p_flags & 1) and p_offset == 0:
            xseg_off, cur_filesz = off, p_filesz
    assert xseg_off is not None and cur_filesz == CAVE_VADDR, "unexpected segment layout"
    assert data[CAVE_VADDR:CAVE_VADDR+len(cave)] == b"\x00"*len(cave), "cave target not free padding"
    assert CAVE_VADDR + len(cave) < 0x100fe0, "cave would overrun next segment"

    # 1. RGB gate: b.ne GATE_TGT -> b GATE_TGT
    orig_gate = struct.unpack_from("<I", data, GATE_ADDR)[0]
    assert (orig_gate & 0xFF00001F) == 0x54000001, f"gate not b.ne: 0x{orig_gate:08x}"
    new_gate = 0x14000000 | (((GATE_TGT - GATE_ADDR) >> 2) & 0x03FFFFFF)
    data[GATE_ADDR:GATE_ADDR+4] = struct.pack("<I", new_gate)
    print(f"[*] RGB gate @0x{GATE_ADDR:x}: 0x{orig_gate:08x} (b.ne) -> 0x{new_gate:08x} (b)")

    # 2. write cave
    data[CAVE_VADDR:CAVE_VADDR+len(cave)] = cave
    print(f"[*] cave written @0x{CAVE_VADDR:x}")

    # 3. retarget bl -> cave
    orig_bl = struct.unpack_from("<I", data, CALL_SITE)[0]
    assert (orig_bl & 0xFC000000) == 0x94000000, f"call site not a bl: 0x{orig_bl:08x}"
    disp = (orig_bl & 0x03FFFFFF)
    if disp & 0x02000000: disp -= 0x04000000
    assert CALL_SITE + disp*4 == PUBLISH_PLT, "bl target != Publish plt"
    new_bl = 0x94000000 | (((CAVE_VADDR - CALL_SITE) >> 2) & 0x03FFFFFF)
    data[CALL_SITE:CALL_SITE+4] = struct.pack("<I", new_bl)
    print(f"[*] bl @0x{CALL_SITE:x}: 0x{orig_bl:08x} -> 0x{new_bl:08x} (-> cave)")

    # 4. extend exec segment to map cave
    new_sz = CAVE_VADDR + len(cave)
    struct.pack_into("<Q", data, xseg_off + 0x20, new_sz)  # p_filesz
    struct.pack_into("<Q", data, xseg_off + 0x28, new_sz)  # p_memsz
    print(f"[*] exec LOAD seg filesz/memsz: 0x{cur_filesz:x} -> 0x{new_sz:x}")

    open(dst, "wb").write(data)
    print(f"[+] wrote {dst} (md5 {md5hex(bytes(data))})")

if __name__ == "__main__":
    main()
