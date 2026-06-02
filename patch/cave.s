// AArch64 code cave for node_camera_ai.so option-2 patch.
// On entry (replacing `bl CameraAINode::Publish(ava_upload_image_info)` @0x70198):
//   x1 = &ava_upload_image_info, where [x1+0]=jpeg begin ptr, [x1+8]=jpeg end ptr.
// Writes the JPEG bytes atomically to /tmp/cam.jpg via raw Linux syscalls, then returns.
// Preserves callee-saved regs (x19-x21, x29, x30). Position-independent (adr).
.text
.globl _cave
_cave:
    stp     x29, x30, [sp, #-48]!
    stp     x19, x20, [sp, #16]
    str     x21, [sp, #32]
    ldr     x19, [x1]            // buf begin
    ldr     x21, [x1, #8]        // buf end
    subs    x20, x21, x19        // len
    b.le    .Lout               // nothing to write

    // fd = openat(AT_FDCWD=-100, tmppath, O_WRONLY|O_CREAT|O_TRUNC=0x241, 0644=0x1a4)
    mov     x0, #-100
    adr     x1, .Ltmp
    movz    x2, #0x241
    movz    x3, #0x1a4
    movz    x8, #56              // __NR_openat
    svc     #0
    cmp     x0, #0
    b.lt    .Lout
    mov     x21, x0             // fd

    // write(fd, buf, len)
    mov     x1, x19
    mov     x2, x20
    movz    x8, #64              // __NR_write
    svc     #0

    // close(fd)
    mov     x0, x21
    movz    x8, #57              // __NR_close
    svc     #0

    // renameat(AT_FDCWD, tmppath, AT_FDCWD, finalpath)
    mov     x0, #-100
    adr     x1, .Ltmp
    mov     x2, #-100
    adr     x3, .Lfinal
    movz    x8, #38              // __NR_renameat
    svc     #0

.Lout:
    ldr     x21, [sp, #32]
    ldp     x19, x20, [sp, #16]
    ldp     x29, x30, [sp], #48
    ret

.Ltmp:
    .asciz  "/tmp/cam.jpg.tmp"
.Lfinal:
    .asciz  "/tmp/cam.jpg"
