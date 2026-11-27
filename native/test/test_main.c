/* PC test harness for filmlut.c (built 32-bit like the camera; plain C libjpeg-turbo, no NEON).
 *
 *   filmlut_test apply   <in.jpg> <out.jpg> <lut> [repeat]   full pipeline, timed; repeat = stress
 *   filmlut_test cancel  <in.jpg> <out.jpg> <lut>            cancel after 300 ms; expects no files left
 *   filmlut_test preview <in.jpg> <out.ppm> <lut|-> <maxw> <maxh>
 *   filmlut_test expectfail <in.jpg> <out.jpg> <lut>         must fail cleanly (no out, no .TMP)
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "../filmlut.h"

static unsigned char *read_file(const char *p, size_t *len)
{
    FILE *f = fopen(p, "rb");
    unsigned char *b;
    long n;
    if (!f) { fprintf(stderr, "cannot open %s\n", p); exit(2); }
    fseek(f, 0, SEEK_END); n = ftell(f); fseek(f, 0, SEEK_SET);
    b = (unsigned char *)malloc(n);
    if (fread(b, 1, n, f) != (size_t)n) { fprintf(stderr, "read %s failed\n", p); exit(2); }
    fclose(f);
    *len = (size_t)n;
    return b;
}

static int exists(const char *p) { FILE *f = fopen(p, "rb"); if (f) { fclose(f); return 1; } return 0; }

static void tmp_name(const char *out, char *tmp)
{
    const char *dot = strrchr(out, '.');
    size_t stem = dot ? (size_t)(dot - out) : strlen(out);
    memcpy(tmp, out, stem);
    strcpy(tmp + stem, ".TMP");
}

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

typedef struct { const char *in, *out; unsigned char *lut; size_t len; int rc; char msg[256]; } job_t;
static void *run_job(void *a) { job_t *j = (job_t *)a; j->rc = filmlut_apply(j->in, j->out, j->lut, j->len, 95, j->msg, sizeof j->msg); return NULL; }

int main(int argc, char **argv)
{
    size_t len = 0;
    unsigned char *lut = NULL;
    char msg[256], tmp[1024];
    if (argc < 5) { fprintf(stderr, "usage: see source\n"); return 2; }
    /* FILMLUT_DEC=ifast,plain (e.g. "1,0") selects decoder options for this run. */
    {
        const char *o = getenv("FILMLUT_DEC");
        if (o && strlen(o) >= 3) filmlut_set_decode_options(o[0] == '1', o[2] == '1');
        o = getenv("FILMLUT_SCALE");      /* eighths, e.g. 4 = half size */
        if (o) filmlut_set_scale(atoi(o));
        o = getenv("FILMLUT_GRAIN");      /* 0..100 */
        if (o) filmlut_set_grain(atoi(o));
        o = getenv("FILMLUT_NOEXTRAS");
        if (o) filmlut_no_extras(atoi(o));
        o = getenv("FILMLUT_HALATION");   /* 0..100 */
        if (o) filmlut_set_halation(atoi(o));
    }

    if (!strcmp(argv[1], "apply") || !strcmp(argv[1], "serial")) {
        int i, n = argc > 5 ? atoi(argv[5]) : 1;
        filmlut_force_serial(!strcmp(argv[1], "serial"));
        lut = read_file(argv[4], &len);
        for (i = 0; i < n; i++) {
            double t0 = now();
            int rc = filmlut_apply(argv[2], argv[3], lut, len, 95, msg, sizeof msg);
            tmp_name(argv[3], tmp);
            printf("run %d: rc=%d %.2fs progress=%d tmp_left=%d %s\n    %s\n", i + 1, rc, now() - t0, filmlut_progress(), exists(tmp), msg, filmlut_stats());
            if (rc != 0 || exists(tmp)) return 1;
        }
        return 0;
    }
    if (!strcmp(argv[1], "cancel")) {
        pthread_t th;
        job_t j;
        memset(&j, 0, sizeof j);
        j.in = argv[2]; j.out = argv[3];
        j.lut = read_file(argv[4], &j.len);
        unlink(argv[3]);
        pthread_create(&th, NULL, run_job, &j);
        usleep(300000);
        filmlut_cancel();
        pthread_join(th, NULL);
        tmp_name(argv[3], tmp);
        printf("cancel: rc=%d msg='%s' out_exists=%d tmp_left=%d\n", j.rc, j.msg, exists(argv[3]), exists(tmp));
        return (j.rc != 0 && !exists(argv[3]) && !exists(tmp)) ? 0 : 1;
    }
    if (!strcmp(argv[1], "expectfail")) {
        int rc;
        lut = read_file(argv[4], &len);
        unlink(argv[3]);
        rc = filmlut_apply(argv[2], argv[3], lut, len, 95, msg, sizeof msg);
        tmp_name(argv[3], tmp);
        printf("expectfail: rc=%d msg='%s' out_exists=%d tmp_left=%d\n", rc, msg, exists(argv[3]), exists(tmp));
        return (rc != 0 && !exists(argv[3]) && !exists(tmp)) ? 0 : 1;
    }
    if (!strcmp(argv[1], "bench")) {
        /* Single-threaded stage timing: decode only, decode+LUT, decode+LUT+encode (to a temp file). */
        lut3d l;
        double t0, td, tl, te;
        lut = read_file(argv[4], &len);
        filmlut_lut_init(&l, lut, len);
        t0 = now(); filmlut_bench(argv[2], NULL, NULL, msg, sizeof msg); td = now() - t0;
        t0 = now(); filmlut_bench(argv[2], &l, NULL, msg, sizeof msg); tl = now() - t0;
        t0 = now(); filmlut_bench(argv[2], &l, argv[3], msg, sizeof msg); te = now() - t0;
        printf("decode %.2fs | LUT %.2fs | encode %.2fs  (decode %.0f%%, LUT %.0f%%, encode %.0f%%)\n",
               td, tl - td, te - tl, 100 * td / te, 100 * (tl - td) / te, 100 * (te - tl) / te);
        return 0;
    }
    if (!strcmp(argv[1], "preview")) {
        int w, h, *px, i;
        FILE *f;
        double t0 = now();
        if (argc < 7) return 2;
        px = filmlut_preview(argv[2], atoi(argv[5]), atoi(argv[6]), &w, &h, msg, sizeof msg);
        if (!px) { printf("preview failed: %s\n", msg); return 1; }
        if (strcmp(argv[4], "-")) {
            lut3d l;
            lut = read_file(argv[4], &len);
            if (!filmlut_lut_init(&l, lut, len)) { printf("bad lut\n"); return 1; }
            filmlut_lut_argb(&l, px, px, (unsigned)(w * h));
        }
        if (getenv("FILMLUT_HALATION")) {
            int s = atoi(getenv("FILMLUT_HALATION"));
            double t1 = now();
            int ok = filmlut_preview_halation(argv[2], px, w, h, s, lut, len);
            double t2 = now();
            ok = ok && filmlut_preview_halation(argv[2], px, w, h, 0, lut, len) == 0;   /* strength 0 = no-op */
            printf("preview halation %d: %s, %.3fs (cached map: ", s, ok ? "ok" : "FAILED", t2 - t1);
            t1 = now(); filmlut_preview_halation(argv[2], px, w, h, 1, lut, len); printf("%.3fs)\n", now() - t1);
        }
        printf("preview: %dx%d in %.2fs\n", w, h, now() - t0);
        f = fopen(argv[3], "wb");
        fprintf(f, "P6\n%d %d\n255\n", w, h);
        for (i = 0; i < w * h; i++) { unsigned p = (unsigned)px[i]; fputc((p >> 16) & 255, f); fputc((p >> 8) & 255, f); fputc(p & 255, f); }
        fclose(f);
        free(px);
        return 0;
    }
    return 2;
}
