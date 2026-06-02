/*
 * mjpeg_server — minimal MJPEG/HTTP server for the Dreame X40 camera-RE project.
 *
 * Serves the latest JPEG written by the patched node_camera_ai.so (/tmp/cam.jpg)
 * as multipart/x-mixed-replace (MJPEG) and as a single still on /snapshot.jpg.
 * Static aarch64 binary; robot has no python/gcc/ffmpeg.
 *
 * Usage: mjpeg_server [port] [jpeg_path]
 *   defaults: port 8081, /tmp/cam.jpg
 *
 * Home Assistant:
 *   camera:
 *     - platform: mjpeg
 *       mjpeg_url: http://<robot-ip>:8081/stream.mjpg
 *       still_image_url: http://<robot-ip>:8081/snapshot.jpg
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

static const char *JPATH = "/tmp/cam.jpg";

static int read_jpeg(unsigned char **buf, size_t *len) {
    int fd = open(JPATH, O_RDONLY);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return -1; }
    size_t n = (size_t)st.st_size;
    unsigned char *b = malloc(n);
    if (!b) { close(fd); return -1; }
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, b + got, n - got);
        if (r <= 0) break;
        got += (size_t)r;
    }
    close(fd);
    if (got != n) { free(b); return -1; }
    *buf = b; *len = n;
    return 0;
}

static ssize_t writeall(int fd, const void *p, size_t n) {
    const char *c = p; size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, c + off, n - off);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        off += (size_t)w;
    }
    return (ssize_t)off;
}

static void serve_stream(int cfd) {
    const char *hdr =
        "HTTP/1.0 200 OK\r\n"
        "Server: x40-mjpeg\r\n"
        "Connection: close\r\n"
        "Cache-Control: no-cache, private\r\n"
        "Pragma: no-cache\r\n"
        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n";
    if (writeall(cfd, hdr, strlen(hdr)) < 0) return;

    time_t last_sec = 0; long last_nsec = -1;
    time_t last_activity = 0;
    char part[256];
    for (;;) {
        struct stat st;
        int have = (stat(JPATH, &st) == 0);
        int fresh = have && (st.st_mtim.tv_sec != last_sec || st.st_mtim.tv_nsec != last_nsec);
        time_t now = time(NULL);
        /* Send a NEW frame (nanosecond mtime, so we catch every one), or refresh the
         * current frame at least once a second. */
        if (have && (fresh || now - last_activity >= 1)) {
            unsigned char *buf; size_t len;
            if (read_jpeg(&buf, &len) == 0) {
                last_sec = st.st_mtim.tv_sec; last_nsec = st.st_mtim.tv_nsec;
                last_activity = now;
                int hn = snprintf(part, sizeof part,
                    "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %zu\r\n\r\n", len);
                if (writeall(cfd, part, (size_t)hn) < 0) { free(buf); return; }
                if (writeall(cfd, buf, len) < 0) { free(buf); return; }
                if (writeall(cfd, "\r\n", 2) < 0) { free(buf); return; }
                free(buf);
            }
        } else if (now - last_activity >= 1) {
            /* No frame to send (e.g. fresh boot, never navigated -> /tmp/cam.jpg
             * doesn't exist yet). Send a harmless inter-part keep-alive so writeall()
             * still detects a disconnected client and this child exits instead of
             * looping forever. MJPEG parsers skip whitespace between parts. */
            if (writeall(cfd, "\r\n", 2) < 0) return;
            last_activity = now;
        }
        usleep(15 * 1000); /* ~66 Hz poll; actual rate bound by frame writes */
    }
}

static void serve_snapshot(int cfd) {
    unsigned char *buf; size_t len;
    if (read_jpeg(&buf, &len) != 0) {
        const char *e = "HTTP/1.0 503 Service Unavailable\r\nConnection: close\r\n\r\nno frame yet\n";
        writeall(cfd, e, strlen(e));
        return;
    }
    char hdr[256];
    int hn = snprintf(hdr, sizeof hdr,
        "HTTP/1.0 200 OK\r\nServer: x40-mjpeg\r\nConnection: close\r\n"
        "Cache-Control: no-cache\r\nContent-Type: image/jpeg\r\nContent-Length: %zu\r\n\r\n", len);
    writeall(cfd, hdr, (size_t)hn);
    writeall(cfd, buf, len);
    free(buf);
}

int main(int argc, char **argv) {
    int port = (argc > 1) ? atoi(argv[1]) : 8081;
    if (argc > 2) JPATH = argv[2];
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);  /* kernel auto-reaps exited children -> no zombies */

    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = INADDR_ANY;
    a.sin_port = htons((unsigned short)port);
    if (bind(sfd, (struct sockaddr *)&a, sizeof a) < 0) { perror("bind"); return 1; }
    if (listen(sfd, 8) < 0) { perror("listen"); return 1; }
    fprintf(stderr, "mjpeg_server: port %d, jpeg %s\n", port, JPATH);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; break; }
        /* one connection at a time is fine for a single HA viewer; fork for robustness */
        pid_t pid = fork();
        if (pid == 0) {
            close(sfd);
            char req[1024];
            ssize_t r = read(cfd, req, sizeof req - 1);
            if (r > 0) {
                req[r] = 0;
                if (strncmp(req, "GET ", 4) == 0) {
                    char *path = req + 4;
                    if (strncmp(path, "/snapshot", 9) == 0 || strncmp(path, "/cam", 4) == 0)
                        serve_snapshot(cfd);
                    else
                        serve_stream(cfd);
                }
            }
            close(cfd);
            _exit(0);
        }
        close(cfd);
        while (waitpid(-1, NULL, WNOHANG) > 0) {}
    }
    return 0;
}
