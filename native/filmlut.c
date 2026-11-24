/*
 * Streaming 3D-LUT for JPEGs, pipelined across cores:
 *
 *   decoder (calling thread) -> LUT workers (2 threads) -> encoder (1 thread)
 *
 * Strips of ~16 rows travel through a small ring of buffers, so memory stays
 * around 3 MB for any image size. The encoder writes strips strictly in order.
 *
 * LUT format (see film-app/luts/convert.py): "FLUT", N, then N^3 RGB triples,
 * red index fastest, then green, then blue.
 */
#include <math.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "jpeglib.h"
#include "jerror.h"
#include "filmlut.h"

/* ---- LUT ------------------------------------------------------------- */

int filmlut_lut_init(lut3d *l, const unsigned char *lut_file, size_t lut_len)
{
    int v, s;
    if (lut_len < 5 || memcmp(lut_file, "FLUT", 4) != 0) return 0;
    l->n = lut_file[4];
    if (l->n < 2 || lut_len != 5 + (size_t)l->n * l->n * l->n * 3) return 0;
    l->t = lut_file + 5;
    s = l->n - 1;
    for (v = 0; v < 256; v++) {
        int p = v * s * 256 / 255;      /* fixed point, 8 fractional bits */
        int i = p >> 8, f = p & 255;
        if (i >= s) { i = s - 1; f = 256; }
        l->idx[v] = i;
        l->frac[v] = f;
        l->roff[v] = i * 3;
        l->goff[v] = i * 3 * l->n;
        l->boff[v] = i * 3 * l->n * l->n;
    }
    return 1;
}

/* Tetrahedral interpolation of one RGB pixel. */
static inline void lut_px(const lut3d *l, int r, int g, int b, unsigned char *o)
{
    const int n = l->n, dr = 3, dg = 3 * n, db = 3 * n * n;
    int fr = l->frac[r], fg = l->frac[g], fb = l->frac[b];
    const unsigned char *c000 = l->t + l->boff[b] + l->goff[g] + l->roff[r];
    const unsigned char *c111 = c000 + dr + dg + db;
    const unsigned char *ca, *cb;
    int f1, f2, f3, k;
    if (fr >= fg) {
        if (fg >= fb)      { ca = c000 + dr; cb = ca + dg; f1 = fr; f2 = fg; f3 = fb; }  /* r>g>b */
        else if (fr >= fb) { ca = c000 + dr; cb = ca + db; f1 = fr; f2 = fb; f3 = fg; }  /* r>b>g */
        else               { ca = c000 + db; cb = ca + dr; f1 = fb; f2 = fr; f3 = fg; }  /* b>r>g */
    } else {
        if (fb >= fg)      { ca = c000 + db; cb = ca + dg; f1 = fb; f2 = fg; f3 = fr; }  /* b>g>r */
        else if (fb >= fr) { ca = c000 + dg; cb = ca + db; f1 = fg; f2 = fb; f3 = fr; }  /* g>b>r */
        else               { ca = c000 + dg; cb = ca + dr; f1 = fg; f2 = fr; f3 = fb; }  /* g>r>b */
    }
    (void)k;
    /* Same arithmetic as before (byte-identical output), unrolled over R, G, B. */
#define FL_CH(k) do { \
        int v = (c000[k] << 8) + f1 * (ca[k] - c000[k]) + f2 * (cb[k] - ca[k]) + f3 * (c111[k] - cb[k]); \
        v = (v + 128) >> 8; \
        o[k] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v); \
    } while (0)
    FL_CH(0); FL_CH(1); FL_CH(2);
#undef FL_CH
}

void filmlut_lut_rgb(const lut3d *l, unsigned char *px, unsigned int count)
{
    unsigned int x;
    for (x = 0; x < count; x++, px += 3) lut_px(l, px[0], px[1], px[2], px);
}

void filmlut_lut_argb(const lut3d *l, const int *src, int *dst, unsigned int count)
{
    unsigned int x;
    unsigned char o[3];
    for (x = 0; x < count; x++) {
        unsigned int p = (unsigned int)src[x];
        lut_px(l, (p >> 16) & 255, (p >> 8) & 255, p & 255, o);
        dst[x] = (int)(0xFF000000u | ((unsigned int)o[0] << 16) | ((unsigned int)o[1] << 8) | o[2]);
    }
}

/* ---- Adobe RGB input ------------------------------------------------- */

/* Tetrahedral interpolation at a float position (grid units 0..n-1), one channel. */
static float lut_at(const lut3d *l, const float *pos, int k)
{
    const int step[3] = { 3, 3 * l->n, 3 * l->n * l->n };
    const unsigned char *c = l->t + k;
    float f[3], v;
    int o[3] = { 0, 1, 2 }, i, j;
    for (i = 0; i < 3; i++) {
        int b = (int)pos[i];
        if (b > l->n - 2) b = l->n - 2;
        f[i] = pos[i] - b;
        c += b * step[i];
    }
    for (i = 0; i < 2; i++)                       /* axes by descending fraction */
        for (j = 0; j < 2 - i; j++)
            if (f[o[j]] < f[o[j + 1]]) { int t = o[j]; o[j] = o[j + 1]; o[j + 1] = t; }
    v = c[0];
    for (i = 0; i < 3; i++) {
        const unsigned char *d = c + step[o[i]];
        v += f[o[i]] * ((float)d[0] - c[0]);
        c = d;
    }
    return v;
}

unsigned char *filmlut_lut_adobe(const unsigned char *lut_file, size_t lut_len)
{
    lut3d l;
    unsigned char *out;
    float lin[256];
    int n, r, g, b, k;
    size_t i = 5;
    if (!filmlut_lut_init(&l, lut_file, lut_len) || !(out = (unsigned char *)malloc(lut_len))) return NULL;
    memcpy(out, lut_file, 5);
    n = l.n;
    for (r = 0; r < n; r++) lin[r] = (float)pow(r / (double)(n - 1), 563.0 / 256.0);
    for (b = 0; b < n; b++)
        for (g = 0; g < n; g++)
            for (r = 0; r < n; r++) {
                float p[3];
                /* Adobe RGB -> sRGB, both linear and D65; out-of-gamut colours are clipped. */
                p[0] = 1.3982832f * lin[r] - 0.3982831f * lin[g];
                p[1] = lin[g];
                p[2] = 1.0429383f * lin[b] - 0.0429383f * lin[g];
                for (k = 0; k < 3; k++) {
                    float v = p[k] < 0 ? 0 : p[k] > 1 ? 1 : p[k];
                    v = v <= 0.0031308f ? 12.92f * v : 1.055f * (float)pow(v, 1 / 2.4) - 0.055f;
                    p[k] = (v < 0 ? 0 : v > 1 ? 1 : v) * (n - 1);
                }
                for (k = 0; k < 3; k++) {
                    float v = lut_at(&l, p, k) + 0.5f;
                    out[i++] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
                }
            }
    return out;
}

static unsigned int ex16(const unsigned char *p, int le) { return le ? p[0] | p[1] << 8 : p[0] << 8 | p[1]; }
static unsigned long ex32(const unsigned char *p, int le)
{
    return le ? (unsigned long)p[0] | (unsigned long)p[1] << 8 | (unsigned long)p[2] << 16 | (unsigned long)p[3] << 24
              : (unsigned long)p[0] << 24 | (unsigned long)p[1] << 16 | (unsigned long)p[2] << 8 | p[3];
}

/* The 12-byte entry for `tag` in the IFD at `ifd`, or NULL. */
static unsigned char *exif_entry(unsigned char *t, size_t len, unsigned long ifd, unsigned int tag, int le)
{
    unsigned int i, cnt;
    if (len < 2 || ifd > len - 2) return NULL;
    cnt = ex16(t + ifd, le);
    for (i = 0; i < cnt && ifd + 2 + 12ul * (i + 1) <= len; i++) {
        unsigned char *e = t + ifd + 2 + 12ul * i;
        if (ex16(e, le) == tag) return e;
    }
    return NULL;
}

/* APP1 data (from "Exif"): 1 if the photo is Adobe RGB (ColorSpace 2, or uncalibrated with interop "R03",
 * as cameras write it). mark_srgb: rewrites those two entries in place to sRGB (ColorSpace 1, "R98"). */
static int exif_adobe(unsigned char *a, size_t n, int mark_srgb)
{
    unsigned char *t, *e, *cs, *ip;
    int le, adobe;
    if (n < 14 || memcmp(a, "Exif\0\0", 6)) return 0;
    t = a + 6; n -= 6;
    if (!((t[0] == 'I' && t[1] == 'I') || (t[0] == 'M' && t[1] == 'M'))) return 0;
    le = t[0] == 'I';
    if (!(e = exif_entry(t, n, ex32(t + 4, le), 0x8769, le))) return 0;
    {
        unsigned long sub = ex32(e + 8, le);
        cs = exif_entry(t, n, sub, 0xA001, le);
        ip = exif_entry(t, n, sub, 0xA005, le);
    }
    if (ip) ip = exif_entry(t, n, ex32(ip + 8, le), 1, le);
    if (!cs) return 0;
    adobe = ex16(cs + 8, le) == 2 || (ex16(cs + 8, le) == 0xFFFF && ip && !memcmp(ip + 8, "R03", 3));
    if (adobe && mark_srgb) {
        cs[8] = le ? 1 : 0; cs[9] = le ? 0 : 1;
        if (ip) memcpy(ip + 8, "R98", 3);
    }
    return adobe;
}

int filmlut_is_adobe(const char *path)
{
    unsigned char hdr[4], *seg;
    int res = 0, i;
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    if (fread(hdr, 1, 2, f) == 2 && hdr[0] == 0xFF && hdr[1] == 0xD8) {
        for (i = 0; i < 8; i++) {                     /* EXIF is one of the first segments */
            unsigned int len;
            if (fread(hdr, 1, 4, f) != 4 || hdr[0] != 0xFF || hdr[1] == 0xDA) break;
            len = hdr[2] << 8 | hdr[3];
            if (len < 2) break;
            if (hdr[1] != 0xE1) { if (fseek(f, (long)len - 2, SEEK_CUR)) break; continue; }
            if ((seg = (unsigned char *)malloc(len)) != NULL) {
                if (fread(seg, 1, len - 2, f) == len - 2) res = exif_adobe(seg, len - 2, 0);
                free(seg);
            }
            break;
        }
    }
    fclose(f);
    return res;
}

static void markers_to_srgb(jpeg_saved_marker_ptr m)
{
    for (; m; m = m->next)
        if (m->marker == JPEG_APP0 + 1) exif_adobe(m->data, m->data_length, 1);
}

/* ---- film grain ------------------------------------------------------ */

static int g_grain = 0;
static int g_gtab[256];          /* strength x tone weight per luma value */

void filmlut_set_grain(int strength)
{
    int v;
    g_grain = strength < 0 ? 0 : strength > 100 ? 100 : strength;
    for (v = 0; v < 256; v++) {
        /* Tone weight (0.13+): like grain seen in negative scans, strongest in the shadows and low
         * midtones (peak ~30% grey, 0.6 in deep black), weakest in the highlights (~0.45).
         * Bell-shaped rational curve (no libm): 0.45 + 0.55 / (1 + x^2 + x^4/2), x = (L - 0.3) / 0.28. */
        float x = (v / 255.0f - 0.30f) / 0.28f, x2 = x * x;
        float w = 0.45f + 0.55f / (1.0f + x2 + 0.5f * x2 * x2);
        g_gtab[v] = (int)(g_grain * w * 256.0f + 0.5f);   /* <= 100 * 256 */
    }
}

/* Triangular noise -65535..65535 from a position hash. */
static inline int tri_noise(unsigned int x, unsigned int y, unsigned int seed)
{
    unsigned int h = x * 0x8da6b343u ^ y * 0xd8163841u ^ seed;
    h ^= h >> 13; h *= 0x5bd1e995u; h ^= h >> 15;
    return (int)(h & 0xFFFF) + (int)(h >> 16) - 65535;
}

/* Adds grain to one row of RGB pixels at absolute row y (same noise wherever the row is processed).
 * Clumpy like film: two offset grids of 2x2-pixel cells (so clumps are 2-3 px and the variance is
 * even everywhere) plus fine per-pixel noise. Luminance only. */
static void grain_row(unsigned char *px, unsigned int width, unsigned int y)
{
    unsigned int x, cy = y >> 1, cy2 = (y + 1) >> 1;
    if (!g_grain) return;
    for (x = 0; x < width; x++, px += 3) {
        int coarse = tri_noise(x >> 1, cy, 0x5bd1e995u) + tri_noise((x + 1) >> 1, cy2, 0x27d4eb2fu);
        int fine = tri_noise(x, y, 0x165667b1u);
        int n = (coarse * 3 + fine * 2) >> 3;                    /* -65535..65535, sd ~15700 */
        int d, k, L = (px[0] * 77 + px[1] * 150 + px[2] * 29) >> 8;
        d = (n * g_gtab[L]) >> 25;                               /* sd ~12 levels at peak, strength 100 */
        for (k = 0; k < 3; k++) {
            int v = px[k] + d;
            px[k] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
        }
    }
}

/* ---- embedded preview (MPF) ------------------------------------------ */

static unsigned int rd16(const unsigned char *p, int le) { return le ? p[0] | p[1] << 8 : p[0] << 8 | p[1]; }
static unsigned long rd32(const unsigned char *p, int le)
{
    return le ? (unsigned long)p[0] | (unsigned long)p[1] << 8 | (unsigned long)p[2] << 16 | (unsigned long)p[3] << 24
              : (unsigned long)p[0] << 24 | (unsigned long)p[1] << 16 | (unsigned long)p[2] << 8 | p[3];
}

/* Sony (and most cameras) store a screen-size preview (a7R II: 1616x1080) as a second image in the
 * file, listed in the APP2 "MPF" segment. Returns its file offset (validated: starts with SOI), or 0
 * if there is none. Leaves the file position undefined. Only the header segments are read. */
static long mpf_preview_offset(FILE *f)
{
    unsigned char hdr[4], *seg = NULL;
    long pos = 2, found = 0;
    if (fseek(f, 0, SEEK_SET) || fread(hdr, 1, 2, f) != 2 || hdr[0] != 0xFF || hdr[1] != 0xD8) return 0;
    for (;;) {
        unsigned int len;
        if (fread(hdr, 1, 4, f) != 4 || hdr[0] != 0xFF) break;
        if (hdr[1] == 0xDA || hdr[1] == 0xD9) break;               /* image data: no MPF before it */
        len = rd16(hdr + 2, 0);
        if (len < 2) break;
        if (hdr[1] == 0xE2 && len >= 16) {
            size_t n = len - 2;
            seg = (unsigned char *)malloc(n);
            if (!seg || fread(seg, 1, n, f) != n) break;
            if (!memcmp(seg, "MPF", 4) && n >= 12) {
                const unsigned char *t = seg + 4;                   /* TIFF header: offsets are from here */
                size_t tl = n - 4;
                int le = t[0] == 'I';
                unsigned long ifd = rd32(t + 4, le), i, cnt;
                long base = pos + 8;                                /* FF E2 LL LL 'MPF\0' */
                if (ifd + 2 <= tl) {
                    cnt = rd16(t + ifd, le);
                    for (i = 0; i < cnt && ifd + 2 + 12 * (i + 1) <= tl; i++) {
                        const unsigned char *e = t + ifd + 2 + 12 * i;
                        unsigned long count = rd32(e + 4, le), val = rd32(e + 8, le), k;
                        if (rd16(e, le) != 0xB002) continue;            /* MP Entry list, 16 bytes each */
                        for (k = 1; k < count / 16 && val + 16 * (k + 1) <= tl; k++) {
                            const unsigned char *m = t + val + 16 * k;
                            unsigned long type = rd32(m, le) & 0xFFFFFFul, off = rd32(m + 8, le);
                            if ((type == 0x010001ul || type == 0x010002ul) && off) { found = base + (long)off; break; }
                        }
                    }
                }
            }
            free(seg);
            seg = NULL;
            if (found) break;
        } else if (fseek(f, (long)len - 2, SEEK_CUR)) {
            break;
        }
        pos += 2 + (long)len;
    }
    free(seg);
    if (found && (fseek(f, found, SEEK_SET) || fread(hdr, 1, 2, f) != 2 || hdr[0] != 0xFF || hdr[1] != 0xD8)) found = 0;
    return found;
}

/* ---- halation -------------------------------------------------------- */

static int g_halation = 0;
void filmlut_set_halation(int strength) { g_halation = strength < 0 ? 0 : strength > 100 ? 100 : strength; }

typedef struct {
    int w, h;          /* map size (about 1/8 of the image) */
    float *g;          /* outward glow map, 0..1 */
    float *c;          /* light-source cores (widely softened), 0..1: glow colour amber near lights */
    float *k;          /* light-source cores (tightly softened), 0..1: lifted to neutral white */
    float s;           /* strength 0..1 */
} halo_t;

static void halo_free(halo_t *H) { free(H->g); free(H->c); free(H->k); H->g = H->c = H->k = NULL; }

/* Separable box blur (3 passes ~ Gaussian), in place, radius r. */
static void box_blur(float *a, float *tmp, int w, int h, int r)
{
    int pass, x, y;
    for (pass = 0; pass < 3; pass++) {
        for (y = 0; y < h; y++) {                       /* horizontal */
            float *row = a + (size_t)y * w, acc = 0;
            int n = 0;
            for (x = -r; x <= r; x++) if (x >= 0 && x < w) { acc += row[x]; n++; }
            for (x = 0; x < w; x++) {
                tmp[x] = acc / n;
                if (x - r >= 0) { acc -= row[x - r]; n--; }
                if (x + r + 1 < w) { acc += row[x + r + 1]; n++; }
            }
            memcpy(row, tmp, (size_t)w * sizeof(float));
        }
        for (x = 0; x < w; x++) {                       /* vertical */
            float acc = 0;
            int n = 0;
            for (y = -r; y <= r; y++) if (y >= 0 && y < h) { acc += a[(size_t)y * w + x]; n++; }
            for (y = 0; y < h; y++) {
                tmp[y] = acc / n;
                if (y - r >= 0) { acc -= a[(size_t)(y - r) * w + x]; n--; }
                if (y + r + 1 < h) { acc += a[(size_t)(y + r + 1) * w + x]; n++; }
            }
            for (y = 0; y < h; y++) a[(size_t)y * w + x] = tmp[y];
        }
    }
}

typedef struct { struct jpeg_error_mgr pub; jmp_buf jump; } halo_err;
static void on_error_halo(j_common_ptr c) { longjmp(((halo_err *)c->err)->jump, 1); }
static void on_message_halo(j_common_ptr c) { (void)c; }

/* Pre-pass: 1/8-scale decode -> highlight mask -> blur. Returns 1 if built (H->g set).
 * Any failure just means no halation for this photo. */
/* use_preview: build the map from the embedded preview instead (fast; for screen previews only). */
static int halo_build(const char *path, halo_t *H, int strength, int use_preview)
{
    struct jpeg_decompress_struct d;
    halo_err e;
    FILE *f;
    JSAMPARRAY row;
    float *volatile m = NULL;
    float *volatile km = NULL;
    float *tmp;
    int x, y, r;
    memset(H, 0, sizeof *H);
    if (!strength || !(f = fopen(path, "rb"))) return 0;
    jpeg_std_error(&e.pub);
    e.pub.error_exit = on_error_halo;
    e.pub.output_message = on_message_halo;
    d.err = &e.pub;
    if (setjmp(e.jump)) { jpeg_destroy_decompress(&d); fclose(f); free(m); free(km); return 0; }
    {
        long off = use_preview ? mpf_preview_offset(f) : 0;
        if (fseek(f, off, SEEK_SET)) off = -1;
        if (off < 0) { fclose(f); return 0; }
        use_preview = off > 0;
    }
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.dct_method = JDCT_IFAST;
    d.do_fancy_upsampling = FALSE;
    d.scale_num = 1;
    /* Full photo: 1/4 decode, then 2x2 MAX-pool to 1/8, so small lights survive. Embedded preview
     * (1616 wide): 1/2 decode, pooled to ~400 wide; the blur radii scale with the map width. */
    d.scale_denom = use_preview ? 2 : 4;
    jpeg_start_decompress(&d);
    H->w = (int)(d.output_width + 1) / 2;
    H->h = (int)(d.output_height + 1) / 2;
    m = (float *)calloc((size_t)H->w * H->h, sizeof(float));
    km = (float *)calloc((size_t)H->w * H->h, sizeof(float));
    if (!m || !km) longjmp(e.jump, 1);
    row = (*d.mem->alloc_sarray)((j_common_ptr)&d, JPOOL_IMAGE, d.output_width * 3, 1);
    for (y = 0; d.output_scanline < d.output_height; y++) {
        const JSAMPLE *p;
        float *cell = m + (size_t)(y / 2) * H->w;
        float *kcell = km + (size_t)(y / 2) * H->w;
        jpeg_read_scanlines(&d, row, 1);
        p = row[0];
        for (x = 0; x < (int)d.output_width; x++, p += 3) {
            /* Only real light sources / near-clipped highlights glow. Use the brightest channel
             * (lamps and fire are yellow, so luma understates them); ramp from 0.86 to 0.99. */
            int mx = p[0] > p[1] ? p[0] : p[1];
            int mn = p[0] < p[1] ? p[0] : p[1];
            float v, t;
            mx = mx > p[2] ? mx : p[2];
            mn = mn < p[2] ? mn : p[2];
            v = mx / 255.0f;
            t = (v - 0.86f) / 0.13f;
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            t = t * t * (3 - 2 * t);
            if (t > cell[x / 2]) cell[x / 2] = t;
            /* Core map: clipped in ALL channels (white-hot lamps), 0.94 -> 0.99 on the darkest
             * channel, so a bright yellow sunset sky (blue well below clip) does not count. */
            v = (mn / 255.0f - 0.94f) / 0.05f;
            v = v < 0 ? 0 : v > 1 ? 1 : v;
            if (v > kcell[x / 2]) kcell[x / 2] = v;
        }
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(f);
    {
        /* Two-scale glow (a tight, intense halo plus a faint wide spread) that only spreads
         * OUTWARD from light sources: blurred minus the source itself, so the inside of a
         * uniformly bright area (a lit wall, sky) gets nothing, and dark surroundings get the
         * red-orange halo, as light scattered back from the film base would. */
        size_t n = (size_t)H->w * H->h, i;
        float *tight = (float *)malloc(n * sizeof(float));
        float *wide = (float *)malloc(n * sizeof(float));
        float *area = (float *)malloc(n * sizeof(float));
        tmp = (float *)malloc((size_t)(H->w > H->h ? H->w : H->h) * sizeof(float));
        if (!tmp || !wide || !tight || !area) { free(tmp); free(wide); free(tight); free(area); free(m); free(km); return 0; }
        memcpy(area, m, n * sizeof(float));
        memcpy(tight, m, n * sizeof(float));
        memcpy(wide, m, n * sizeof(float));
        r = H->w / 400; if (r < 1) r = 1;      /* tight: ~0.25% of width per pass */
        box_blur(tight, tmp, H->w, H->h, r);
        box_blur(km, tmp, H->w, H->h, r);      /* white-hot cores, softened the same way */
        r = H->w / 70; if (r < 2) r = 2;       /* wide: ~1.4% of width per pass (0.13: 0.9%) */
        box_blur(wide, tmp, H->w, H->h, r);
        r = H->w / 24; if (r < 3) r = 3;       /* area: ~4% of width per pass */
        box_blur(area, tmp, H->w, H->h, r);
        for (i = 0; i < n; i++) {
            float a = tight[i] - m[i], b = wide[i] - m[i], g;
            a = a < 0 ? 0 : a;
            b = b < 0 ? 0 : b;
            g = 3.0f * a + 3.2f * b;            /* more weight on the wide, diffuse fringe */
            g = g > 1 ? 1 : g;
            /* 0.30: large blown areas (a lit wall) glow less than lamps, or dark objects in front of
             * them turn red all over: area = share of near-clipped pixels in a wide neighbourhood. */
            b = (area[i] - 0.12f) / 0.35f;
            b = b < 0 ? 0 : b > 1 ? 1 : b;
            m[i] = g * (1.0f - 0.65f * b * b * (3 - 2 * b));
            wide[i] = wide[i] > 1 ? 1 : wide[i];      /* cores: the source, widely softened */
            /* 0.29: white-hot cores (km) get lifted back to white in halo_row. The 800T LUT maps
             * white to ~224 grey and the glow brightened the surroundings above that, so lamp
             * centres read as grey discs. */
            km[i] = km[i] > 1 ? 1 : km[i];
        }
        free(tmp);
        free(tight);
        free(area);
        H->c = wide;
        H->k = km;
    }
    H->g = m;
    H->s = strength / 100.0f;
    return 1;
}

/* Adds the glow to one output row y (of out_h rows): screen blend of a red-orange tint. */
static void halo_row(const halo_t *H, unsigned char *px, unsigned int width, unsigned int out_h, unsigned int y)
{
    static const float tint[3] = { 1.0f, 0.22f, 0.06f };   /* redder than 0.13 (1, .30, .10) */
    float fy, wy, sx;
    int y0, y1;
    unsigned int x;
    const float *r0, *r1, *c0, *c1;
    if (!H || !H->g) return;
    fy = ((float)y + 0.5f) * H->h / out_h - 0.5f;
    if (fy < 0) fy = 0;
    if (fy > H->h - 1) fy = (float)(H->h - 1);
    y0 = (int)fy; y1 = y0 + 1 < H->h ? y0 + 1 : y0; wy = fy - y0;
    r0 = H->g + (size_t)y0 * H->w;
    r1 = H->g + (size_t)y1 * H->w;
    c0 = H->c ? H->c + (size_t)y0 * H->w : NULL;
    c1 = H->c ? H->c + (size_t)y1 * H->w : NULL;
    sx = (float)H->w / width;
    for (x = 0; x < width; x++, px += 3) {
        float fx = ((float)x + 0.5f) * sx - 0.5f, wx, g, a, tg = tint[1], tb = tint[2];
        int x0, x1, k;
        if (fx < 0) fx = 0;
        if (fx > H->w - 1) fx = (float)(H->w - 1);
        x0 = (int)fx; x1 = x0 + 1 < H->w ? x0 + 1 : x0; wx = fx - x0;
        /* Glow colour: amber close to the light (where the soft source map c is high), shading to
         * red further out, like the warm roll-off around lamps on 800T. The source itself gets no
         * glow (the outward map is ~0 there), so lamp centres stay white-hot. (0.22 tried tinting
         * the cores directly: a clipped white core turned khaki.) */
        if (c0) {
            float c = (c0[x0] * (1 - wx) + c0[x1] * wx) * (1 - wy) + (c1[x0] * (1 - wx) + c1[x1] * wx) * wy;
            tg = 0.22f + 0.40f * c;
            tb = 0.06f + 0.12f * c;
        }
        if (H->k) {
            /* Light-source cores back to neutral white (no tint: tinted cores went khaki). */
            const float *k0 = H->k + (size_t)y0 * H->w, *k1 = H->k + (size_t)y1 * H->w;
            float kc = (k0[x0] * (1 - wx) + k0[x1] * wx) * (1 - wy) + (k1[x0] * (1 - wx) + k1[x1] * wx) * wy;
            float lift = kc * (H->s * 1.25f > 1 ? 1 : H->s * 1.25f);
            if (lift > 0.002f)
                for (k = 0; k < 3; k++) {
                    float v = 255.0f - (255.0f - px[k]) * (1.0f - lift);
                    px[k] = (unsigned char)(v > 255.0f ? 255 : (int)(v + 0.5f));
                }
        }
        g = (r0[x0] * (1 - wx) + r0[x1] * wx) * (1 - wy) + (r1[x0] * (1 - wx) + r1[x1] * wx) * wy;
        a = H->s * 0.9f * g;
        if (a < 0.002f) continue;
        {
            /* 0.30: the glow fades out on already bright pixels (film's shoulder): looks that cap white
             * below 255 otherwise got peach contours around lit walls. */
            int mx = px[0] > px[1] ? px[0] : px[1];
            float u;
            mx = mx > px[2] ? mx : px[2];
            u = (mx - 110) / 110.0f;
            u = u < 0 ? 0 : u > 1 ? 1 : u;
            a *= 1.0f - u * u * (3 - 2 * u);
        }
        for (k = 0; k < 3; k++) {
            float tk = k == 0 ? tint[0] : k == 1 ? tg : tb;
            float c = 255.0f - (255.0f - px[k]) * (1.0f - a * tk);
            px[k] = (unsigned char)(c > 255.0f ? 255 : (int)(c + 0.5f));
        }
    }
}

/* ---- libjpeg error handling ------------------------------------------ */

typedef struct {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
    char msg[JMSG_LENGTH_MAX];
} err_mgr;

static void on_error(j_common_ptr cinfo)
{
    err_mgr *e = (err_mgr *)cinfo->err;
    (*cinfo->err->format_message)(cinfo, e->msg);
    longjmp(e->jump, 1);
}

static void on_message(j_common_ptr cinfo) { (void)cinfo; }

static void err_init(err_mgr *e)
{
    jpeg_std_error(&e->pub);
    e->pub.error_exit = on_error;
    e->pub.output_message = on_message;
    e->msg[0] = 0;
}

/* ---- progress / cancel (one conversion at a time) --------------------- */

static volatile int g_progress = 0;   /* 0..1000 */
static volatile int g_cancel = 0;
static char g_stats[256] = "";

int filmlut_progress(void) { return g_progress; }
void filmlut_cancel(void) { g_cancel = 1; }
const char *filmlut_stats(void) { return g_stats; }

static int g_dec_ifast = 0, g_dec_plain = 0;
void filmlut_set_decode_options(int ifast, int plain) { g_dec_ifast = ifast; g_dec_plain = plain; }

static int g_scale8 = 8;
void filmlut_set_scale(int eighths) { g_scale8 = eighths < 1 ? 1 : eighths > 8 ? 8 : eighths; }

static void dec_options(struct jpeg_decompress_struct *d)
{
    d->dct_method = g_dec_ifast ? JDCT_IFAST : JDCT_ISLOW;
    d->do_fancy_upsampling = g_dec_plain ? FALSE : TRUE;
    d->scale_num = (unsigned)g_scale8;
    d->scale_denom = 8;
}

static long online_cpus(void)
{
#if defined(_SC_NPROCESSORS_ONLN)
    return sysconf(_SC_NPROCESSORS_ONLN);
#else
    return -1;   /* PC test build (Windows) */
#endif
}

static double tnow(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec * 1e-9;
}

/* ---- pipeline --------------------------------------------------------- */

#define NSLOT 6
#define NWORKERS 2
enum { S_FREE, S_DECODED, S_MAPPING, S_MAPPED };

typedef struct {
    JSAMPARRAY rows;
    int nrows;
    int seq;
    int state;
} slot_t;

typedef struct {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    slot_t slot[NSLOT];
    int rows_per_slot;
    JDIMENSION width, height;
    int next_map;      /* next sequence number a LUT worker will take */
    int total;         /* number of strips, known once decoding finishes (-1 before) */
    int failed;
    char msg[256];
    const lut3d *lut;
    struct jpeg_compress_struct *c;
    err_mgr *cerr;
    /* timing (seconds), added under mu */
    double lut_busy, lut_wait, enc_busy, enc_wait;
    halo_t halo;
} pipe_t;

static void pipe_fail(pipe_t *p, const char *what, const char *detail)
{
    pthread_mutex_lock(&p->mu);
    if (!p->failed) {
        p->failed = 1;
        snprintf(p->msg, sizeof p->msg, "%s%s", what, detail ? detail : "");
    }
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
}

static void *lut_worker(void *arg)
{
    pipe_t *p = (pipe_t *)arg;
    for (;;) {
        slot_t *s;
        int seq, i;
        double t0 = tnow(), t1;
        pthread_mutex_lock(&p->mu);
        for (;;) {
            if (p->failed || (p->total >= 0 && p->next_map >= p->total)) {
                p->lut_wait += tnow() - t0;
                pthread_mutex_unlock(&p->mu);
                return NULL;
            }
            s = &p->slot[p->next_map % NSLOT];
            if (s->state == S_DECODED && s->seq == p->next_map) break;
            pthread_cond_wait(&p->cv, &p->mu);
        }
        seq = p->next_map++;
        s->state = S_MAPPING;
        pthread_mutex_unlock(&p->mu);
        (void)seq;
        t1 = tnow();

        for (i = 0; i < s->nrows; i++) {
            unsigned y = (unsigned)(s->seq * p->rows_per_slot + i);
            filmlut_lut_rgb(p->lut, s->rows[i], p->width);
            halo_row(&p->halo, s->rows[i], p->width, p->height, y);
            grain_row(s->rows[i], p->width, y);
        }

        pthread_mutex_lock(&p->mu);
        p->lut_wait += t1 - t0;
        p->lut_busy += tnow() - t1;
        s->state = S_MAPPED;
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
    }
}

static void *encoder(void *arg)
{
    pipe_t *p = (pipe_t *)arg;
    volatile int seq = 0;
    if (setjmp(p->cerr->jump)) { pipe_fail(p, "jpeg write: ", p->cerr->msg); return NULL; }
    for (;;) {
        slot_t *s;
        double t0 = tnow(), t1;
        pthread_mutex_lock(&p->mu);
        for (;;) {
            if (p->failed || (p->total >= 0 && seq >= p->total)) {
                p->enc_wait += tnow() - t0;
                pthread_mutex_unlock(&p->mu);
                return NULL;
            }
            s = &p->slot[seq % NSLOT];
            if (s->state == S_MAPPED && s->seq == seq) break;
            pthread_cond_wait(&p->cv, &p->mu);
        }
        pthread_mutex_unlock(&p->mu);
        t1 = tnow();

        jpeg_write_scanlines(p->c, s->rows, s->nrows);
        g_progress = (int)((long long)p->c->next_scanline * 1000 / p->height);

        pthread_mutex_lock(&p->mu);
        p->enc_wait += t1 - t0;
        p->enc_busy += tnow() - t1;
        s->state = S_FREE;
        seq++;
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
    }
}

/* ---- public: full-size conversion ------------------------------------- */

/* ---- preview and thumbnail inside the output --------------------------- *
 * Like the camera's own JPEGs: a screen-size preview (MPF) after the end of the main picture, so viewing a
 * converted photo does not need a full decode, and an EXIF thumbnail in the look (the original's was kept
 * until 0.40). Both come from the camera's own embedded preview: LUT and halation, no grain. Optional
 * throughout: if anything fails, the photo is written as before. One conversion at a time. */
#define PREVIEW_W 808            /* half of the cameras' 1616: an exact, fast DCT reduction */
#define MPF_LEN 86               /* APP2 data: "MPF\0", TIFF header, 3 entries, 2 picture records */
static unsigned char *g_prev, *g_thumb;
static size_t g_prev_len, g_thumb_len;
static double g_prev_s;
static int g_no_extras;
void filmlut_no_extras(int on) { g_no_extras = on; }

static void extras_free(void)
{
    free(g_prev); free(g_thumb);
    g_prev = g_thumb = NULL;
    g_prev_len = g_thumb_len = 0;
}

/* RGB -> JPEG in memory (malloc'd), or NULL. */
static unsigned char *encode_mem(const unsigned char *rgb, int w, int h, int quality, int h_samp, int v_samp, size_t *len)
{
    struct jpeg_compress_struct c;
    err_mgr e;
    unsigned char *volatile buf = NULL;
    unsigned char *b = NULL;
    unsigned long n = 0;
    err_init(&e);
    c.err = &e.pub;
    if (setjmp(e.jump)) { jpeg_destroy_compress(&c); free(b); return NULL; }
    jpeg_create_compress(&c);
    jpeg_mem_dest(&c, &b, &n);
    c.image_width = (JDIMENSION)w; c.image_height = (JDIMENSION)h;
    c.input_components = 3; c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    c.dct_method = JDCT_IFAST;
    c.comp_info[0].h_samp_factor = h_samp; c.comp_info[0].v_samp_factor = v_samp;
    c.write_JFIF_header = FALSE;
    jpeg_start_compress(&c, TRUE);
    while (c.next_scanline < c.image_height) {
        JSAMPROW r = (JSAMPROW)(rgb + (size_t)c.next_scanline * w * 3);
        jpeg_write_scanlines(&c, &r, 1);
    }
    jpeg_finish_compress(&c);
    jpeg_destroy_compress(&c);
    buf = b;
    *len = (size_t)n;
    return buf;
}

static void extras_build(const char *in_path, const lut3d *lut)
{
    char msg[200];
    int w = 0, h = 0, x, y, *px;
    unsigned char *rgb;
    halo_t H;
    double t0 = tnow();
    extras_free();
    g_prev_s = 0;
    if (g_no_extras || !(px = filmlut_preview(in_path, PREVIEW_W, PREVIEW_W, &w, &h, msg, sizeof msg))) return;
    rgb = (unsigned char *)malloc((size_t)w * h * 3);
    if (!rgb) { free(px); return; }
    for (y = 0; y < w * h; y++) {
        rgb[y * 3] = (unsigned char)(px[y] >> 16); rgb[y * 3 + 1] = (unsigned char)(px[y] >> 8); rgb[y * 3 + 2] = (unsigned char)px[y];
    }
    free(px);
    filmlut_lut_rgb(lut, rgb, (unsigned)(w * h));
    memset(&H, 0, sizeof H);
    if (g_halation && halo_build(in_path, &H, g_halation, 1)) {
        for (y = 0; y < h; y++) halo_row(&H, rgb + (size_t)y * w * 3, (unsigned)w, (unsigned)h, (unsigned)y);
        halo_free(&H);
    }
    g_prev = encode_mem(rgb, w, h, 88, 2, 1, &g_prev_len);
    {
        /* 160x120 thumbnail, letterboxed as the camera's. */
        static const int TW = 160, TH = 120;
        unsigned char *t = (unsigned char *)calloc((size_t)TW * TH, 3);
        int tw = TW, th = h * TW / w, oy;
        if (th > TH) { th = TH; tw = w * TH / h; }
        if (t && tw > 0 && th > 0) {
            int ox = (TW - tw) / 2;
            oy = (TH - th) / 2;
            for (y = 0; y < th; y++)
                for (x = 0; x < tw; x++) {
                    int x0 = x * w / tw, x1 = (x + 1) * w / tw, y0 = y * h / th, y1 = (y + 1) * h / th, sx, sy, k, s[3] = { 0, 0, 0 }, n = 0;
                    if (x1 <= x0) x1 = x0 + 1;
                    if (y1 <= y0) y1 = y0 + 1;
                    for (sy = y0; sy < y1; sy++)
                        for (sx = x0; sx < x1; sx++, n++)
                            for (k = 0; k < 3; k++) s[k] += rgb[((size_t)sy * w + sx) * 3 + k];
                    for (k = 0; k < 3; k++) t[((size_t)(y + oy) * TW + x + ox) * 3 + k] = (unsigned char)(s[k] / n);
                }
            g_thumb = encode_mem(t, TW, TH, 80, 2, 1, &g_thumb_len);
        }
        free(t);
    }
    free(rgb);
    g_prev_s = tnow() - t0;
}

/* Replaces the EXIF thumbnail in place, if the new one fits where the old one was. */
static void exif_set_thumb(unsigned char *a, size_t n)
{
    unsigned char *t, *off, *len;
    unsigned long ifd0, ifd1, o, l;
    int le;
    if (!g_thumb || n < 14 || memcmp(a, "Exif\0\0", 6)) return;
    t = a + 6; n -= 6;
    if (!((t[0] == 'I' && t[1] == 'I') || (t[0] == 'M' && t[1] == 'M'))) return;
    le = t[0] == 'I';
    ifd0 = ex32(t + 4, le);
    if (n < 6 || ifd0 > n - 6) return;
    o = ifd0 + 2 + 12ul * ex16(t + ifd0, le);
    if (o + 4 > n) return;
    ifd1 = ex32(t + o, le);
    if (!ifd1) return;
    off = exif_entry(t, n, ifd1, 0x0201, le);
    len = exif_entry(t, n, ifd1, 0x0202, le);
    if (!off || !len) return;
    o = ex32(off + 8, le); l = ex32(len + 8, le);
    if (o > n || l > n - o || g_thumb_len > l) return;
    memcpy(t + o, g_thumb, g_thumb_len);
    memset(t + o + g_thumb_len, 0, l - g_thumb_len);
    for (l = 0; l < 4; l++) len[8 + l] = (unsigned char)(g_thumb_len >> (le ? 8 * l : 24 - 8 * l));
}

static void put32(unsigned char *p, unsigned long v) { int i; for (i = 0; i < 4; i++) p[i] = (unsigned char)(v >> (8 * i)); }

/* The APP2 segment, written with the header. Until extras_append() succeeds it is named "FDP\0", which nothing
 * reads, so a failure in between leaves an ordinary JPEG. */
static void mpf_marker(struct jpeg_compress_struct *c)
{
    static const unsigned char tiff[] = {
        'F','D','P',0, 'I','I',42,0, 8,0,0,0, 3,0,
        0x00,0xB0, 7,0, 4,0,0,0, '0','1','0','0',
        0x01,0xB0, 4,0, 1,0,0,0, 2,0,0,0,
        0x02,0xB0, 7,0, 32,0,0,0, 50,0,0,0,
        0,0,0,0 };
    unsigned char m[MPF_LEN];
    if (!g_prev) return;
    memset(m, 0, sizeof m);
    memcpy(m, tiff, sizeof tiff);
    put32(m + 54, 0xA0030000ul);
    put32(m + 70, 0x40010002ul);
    jpeg_write_marker(c, JPEG_APP0 + 2, m, MPF_LEN);
}

/* Appends the preview to the finished file and fills in the segment. On failure the file is cut back. */
static void extras_append(const char *path)
{
    unsigned char h[4], m[MPF_LEN];
    long end, pos = 2, seg = 0;
    FILE *f;
    int ok = 0;
    if (!g_prev || !(f = fopen(path, "r+b"))) return;
    fseek(f, 0, SEEK_END);
    end = ftell(f);
    while (pos + 4 < end && pos < 300000) {
        unsigned int len;
        if (fseek(f, pos, SEEK_SET) || fread(h, 1, 4, f) != 4 || h[0] != 0xFF || h[1] == 0xDA) break;
        len = h[2] << 8 | h[3];
        if (h[1] == 0xE2 && len == MPF_LEN + 2) {
            if (fread(m, 1, MPF_LEN, f) == MPF_LEN && !memcmp(m, "FDP", 4)) seg = pos + 4;
            break;
        }
        pos += 2 + (long)len;
    }
    if (seg && !fseek(f, end, SEEK_SET) && fwrite(g_prev, 1, g_prev_len, f) == g_prev_len && fflush(f) == 0) {
        memcpy(m, "MPF", 4);
        put32(m + 58, (unsigned long)end);                     /* main picture: size, offset 0 */
        put32(m + 74, (unsigned long)g_prev_len);
        put32(m + 78, (unsigned long)(end - (seg + 4)));       /* from the TIFF header */
        ok = !fseek(f, seg, SEEK_SET) && fwrite(m, 1, MPF_LEN, f) == MPF_LEN;
    }
    if (fclose(f)) ok = 0;
    if (!ok && truncate(path, end)) { /* the main picture is complete either way */ }
}

static void prepare_markers(jpeg_saved_marker_ptr m)
{
    markers_to_srgb(m);
    for (; m; m = m->next)
        if (m->marker == JPEG_APP0 + 1) exif_set_thumb(m->data, m->data_length);
}

/* Original pipeline: one encoder thread. Kept as the fallback for apply_parallel(). */
static int apply_serial(const char *in_path, const char *out_path,
                        const unsigned char *lut_file, size_t lut_len,
                        int quality, char *msg, size_t msglen)
{
    struct jpeg_decompress_struct d;
    struct jpeg_compress_struct c;
    err_mgr derr, cerr;
    lut3d lut;
    pipe_t p;
    pthread_t th_enc, th_lut[NWORKERS];
    volatile int d_ok = 0, c_ok = 0, threads = 0, seq = 0;
    volatile double t_start = tnow(), dec_busy = 0, dec_wait = 0;
    FILE *fin = NULL, *fout = NULL;
    char tmp_path[1024];
    jpeg_saved_marker_ptr m;
    int i, rc = 0;

    msg[0] = 0;
    g_progress = 0;
    g_cancel = 0;
    if (!filmlut_lut_init(&lut, lut_file, lut_len)) { snprintf(msg, msglen, "bad LUT file"); return 1; }

    /* Temp file = out_path with its extension replaced by .TMP (the card only takes 8.3 names). */
    {
        const char *slash = strrchr(out_path, '/');
        const char *dot = strrchr(out_path, '.');
        size_t stem = (dot && (!slash || dot > slash)) ? (size_t)(dot - out_path) : strlen(out_path);
        if (stem + 5 >= sizeof tmp_path) { snprintf(msg, msglen, "path too long"); return 1; }
        memcpy(tmp_path, out_path, stem);
        memcpy(tmp_path + stem, ".TMP", 5);
        if (strcmp(tmp_path, out_path) == 0) { snprintf(msg, msglen, "output must not end in .TMP"); return 1; }
    }
    if (!(fin = fopen(in_path, "rb"))) { snprintf(msg, msglen, "cannot open %s", in_path); return 1; }
    if (!(fout = fopen(tmp_path, "wb"))) { fclose(fin); snprintf(msg, msglen, "cannot create %s", tmp_path); return 1; }
    setvbuf(fout, NULL, _IOFBF, 1 << 20);   /* Android 2.3 stdio defaults to a 1 KB buffer */

    memset(&p, 0, sizeof p);
    pthread_mutex_init(&p.mu, NULL);
    pthread_cond_init(&p.cv, NULL);
    p.total = -1;
    p.lut = &lut;
    p.c = &c;
    p.cerr = &cerr;

    err_init(&derr);
    err_init(&cerr);
    d.err = &derr.pub;
    c.err = &cerr.pub;

    /* Errors during setup (both objects, this thread) and during decoding land here. */
    if (setjmp(derr.jump)) {
        pipe_fail(&p, "jpeg read: ", derr.msg);
        goto finish;
    }
    if (setjmp(cerr.jump)) {   /* compressor errors before the encoder thread takes over */
        pipe_fail(&p, "jpeg write: ", cerr.msg);
        goto finish;
    }

    jpeg_create_decompress(&d); d_ok = 1;
    jpeg_stdio_src(&d, fin);
    jpeg_save_markers(&d, JPEG_APP0 + 1, 0xFFFF);      /* EXIF (date, settings, orientation) */
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    dec_options(&d);
    jpeg_start_decompress(&d);                          /* also initialises SIMD detection on this thread */

    jpeg_create_compress(&c); c_ok = 1;
    jpeg_stdio_dest(&c, fout);
    c.image_width = d.output_width;
    c.image_height = d.output_height;
    c.input_components = 3;
    c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    c.dct_method = JDCT_ISLOW;
    c.comp_info[0].h_samp_factor = 2; c.comp_info[0].v_samp_factor = 1;   /* 4:2:2, like the camera */
    c.comp_info[1].h_samp_factor = 1; c.comp_info[1].v_samp_factor = 1;
    c.comp_info[2].h_samp_factor = 1; c.comp_info[2].v_samp_factor = 1;
    c.write_JFIF_header = FALSE;                                          /* EXIF instead, as the camera does */
    jpeg_start_compress(&c, TRUE);
    prepare_markers(d.marker_list);
    for (m = d.marker_list; m; m = m->next)
        jpeg_write_marker(&c, m->marker, m->data, m->data_length);
    mpf_marker(&c);

    p.width = d.output_width;
    p.height = d.output_height;
    halo_build(in_path, &p.halo, g_halation, 0);
    p.rows_per_slot = d.rec_outbuf_height < 16 ? 16 : d.rec_outbuf_height;
    for (i = 0; i < NSLOT; i++) {
        p.slot[i].rows = (*d.mem->alloc_sarray)((j_common_ptr)&d, JPOOL_IMAGE, d.output_width * 3, p.rows_per_slot);
        p.slot[i].state = S_FREE;
        p.slot[i].seq = -1;
    }

    /* From here the encoder thread owns `c` (and its own setjmp). */
    if (pthread_create(&th_enc, NULL, encoder, &p) != 0) { pipe_fail(&p, "cannot start encoder thread", NULL); goto finish; }
    threads = 1;
    for (i = 0; i < NWORKERS; i++) {
        if (pthread_create(&th_lut[i], NULL, lut_worker, &p) != 0) { pipe_fail(&p, "cannot start LUT thread", NULL); break; }
        threads = 2 + i;
    }

    /* Decoder loop (this thread). */
    while (!p.failed && d.output_scanline < d.output_height) {
        slot_t *s = &p.slot[seq % NSLOT];
        int got;
        double t0, t1;
        if (g_cancel) { pipe_fail(&p, "cancelled", NULL); break; }
        t0 = tnow();
        pthread_mutex_lock(&p.mu);
        while (!p.failed && s->state != S_FREE) pthread_cond_wait(&p.cv, &p.mu);
        pthread_mutex_unlock(&p.mu);
        if (p.failed) break;
        t1 = tnow();
        dec_wait += t1 - t0;

        got = 0;
        while (got < p.rows_per_slot && d.output_scanline < d.output_height)
            got += jpeg_read_scanlines(&d, s->rows + got, p.rows_per_slot - got);
        dec_busy += tnow() - t1;
        /* libjpeg reports truncated/corrupt data as warnings and pads with grey: refuse instead,
         * e.g. if a file is picked up before the camera has finished writing it. */
        if (derr.pub.num_warnings > 0) { pipe_fail(&p, "damaged or incomplete JPEG", NULL); break; }

        pthread_mutex_lock(&p.mu);
        s->nrows = got;
        s->seq = seq;
        s->state = S_DECODED;
        seq++;
        pthread_cond_broadcast(&p.cv);
        pthread_mutex_unlock(&p.mu);
    }

finish:
    pthread_mutex_lock(&p.mu);
    if (p.total < 0) p.total = seq;   /* lets workers/encoder drain and exit */
    pthread_cond_broadcast(&p.cv);
    pthread_mutex_unlock(&p.mu);
    if (threads >= 1) pthread_join(th_enc, NULL);
    for (i = 0; i < threads - 1; i++) pthread_join(th_lut[i], NULL);

    if (!p.failed) {
        /* All strips written: finish both streams. Errors here are still caught. */
        if (setjmp(cerr.jump)) { pipe_fail(&p, "jpeg write: ", cerr.msg); }
        else if (setjmp(derr.jump)) { pipe_fail(&p, "jpeg read: ", derr.msg); }
        else {
            jpeg_finish_compress(&c);
            jpeg_finish_decompress(&d);
        }
    }
    if (c_ok) jpeg_destroy_compress(&c);
    if (d_ok) jpeg_destroy_decompress(&d);
    fclose(fin);
    if (fclose(fout) != 0 && !p.failed) pipe_fail(&p, "write failed (card full?)", NULL);

    snprintf(g_stats, sizeof g_stats,
             "cpus=%ld total=%.1fs | decode busy %.1f wait %.1f | lut(x%d) busy %.1f wait %.1f | encode busy %.1f wait %.1f",
             online_cpus(), tnow() - t_start, dec_busy, dec_wait, NWORKERS,
             p.lut_busy, p.lut_wait, p.enc_busy, p.enc_wait);
    if (p.failed) {
        unlink(tmp_path);
        snprintf(msg, msglen, "%s", p.msg);
        rc = 2;
    } else {
        extras_append(tmp_path);
        unlink(out_path);
        if (rename(tmp_path, out_path) != 0) { unlink(tmp_path); snprintf(msg, msglen, "rename failed"); rc = 3; }
        else g_progress = 1000;
    }
    halo_free(&p.halo);
    pthread_cond_destroy(&p.cv);
    pthread_mutex_destroy(&p.mu);
    return rc;
}

/* ---- parallel encoding (restart markers, rows dealt round-robin) ------- *
 *
 * The output image is cut into MCU rows (8 pixel rows: 4:2:2 has v_samp 1). Row u goes to
 * encoder u % NENC, each of which also applies the LUT to its rows. Every encoder uses
 * restart_in_rows = 1, so each MCU row is an independent restart interval (DC prediction
 * resets; 4:2:2 downsampling is horizontal only), i.e. its entropy-coded bytes do not depend
 * on the rows around it. The encoders write to memory; afterwards the rows are stitched back
 * in order behind encoder 0's header (SOF height patched), with RST markers renumbered.
 * The decoded result is pixel-identical to the serial encoder's output.
 */
#define NENC 3
#define PSLOTS (NENC * 4)
#define CHUNK (256 * 1024)

typedef struct {
    struct jpeg_destination_mgr pub;
    unsigned char **chunks;
    size_t nchunks, cap;
} memdest;

static boolean md_empty(j_compress_ptr c);

/* libjpeg stores a byte before checking free space, so a buffer must exist from the start. */
static void md_init(j_compress_ptr c) { md_empty(c); }

static boolean md_empty(j_compress_ptr c)
{
    memdest *m = (memdest *)c->dest;
    unsigned char *b;
    if (m->nchunks == m->cap) {
        size_t ncap = m->cap ? m->cap * 2 : 64;
        unsigned char **nc = (unsigned char **)realloc(m->chunks, ncap * sizeof *nc);
        if (!nc) ERREXIT(c, JERR_OUT_OF_MEMORY);
        m->chunks = nc;
        m->cap = ncap;
    }
    b = (unsigned char *)malloc(CHUNK);
    if (!b) ERREXIT(c, JERR_OUT_OF_MEMORY);
    m->chunks[m->nchunks++] = b;
    m->pub.next_output_byte = b;
    m->pub.free_in_buffer = CHUNK;
    return TRUE;
}

static void md_term(j_compress_ptr c) { (void)c; }

static size_t md_size(const memdest *m)
{
    return m->nchunks ? (m->nchunks - 1) * (size_t)CHUNK + (CHUNK - m->pub.free_in_buffer) : 0;
}

static unsigned char md_byte(const memdest *m, size_t o) { return m->chunks[o / CHUNK][o % CHUNK]; }

static int md_write(const memdest *m, size_t a, size_t b, FILE *f)
{
    while (a < b) {
        size_t ci = a / CHUNK, off = a % CHUNK, n = CHUNK - off;
        if (n > b - a) n = b - a;
        if (fwrite(m->chunks[ci] + off, 1, n, f) != n) return 0;
        a += n;
    }
    return 1;
}

static void md_free(memdest *m)
{
    size_t i;
    for (i = 0; i < m->nchunks; i++) free(m->chunks[i]);
    free(m->chunks);
    m->chunks = NULL;
    m->nchunks = m->cap = 0;
}

typedef struct par_s par_t;

typedef struct {
    par_t *par;
    int k;                              /* encoder index */
    struct jpeg_compress_struct c;
    err_mgr err;
    memdest dest;
    int c_ok;
    JDIMENSION height;                  /* rows this encoder receives */
    double busy, wait;
    size_t *seg_start, *seg_end;        /* per MCU row, offsets in dest */
    int nseg;
} enc_t;

struct par_s {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    slot_t slot[PSLOTS];
    int units;                          /* MCU rows in the image */
    JDIMENSION width, height;
    int quality;
    int failed;
    char msg[256];
    const lut3d *lut;
    jpeg_saved_marker_ptr markers;      /* EXIF etc., written by encoder 0 */
    int done_units;
    halo_t halo;
    enc_t enc[NENC];
};

static void par_fail(par_t *p, const char *what, const char *detail)
{
    pthread_mutex_lock(&p->mu);
    if (!p->failed) {
        p->failed = 1;
        snprintf(p->msg, sizeof p->msg, "%s%s", what, detail ? detail : "");
    }
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
}

static void *par_encoder(void *arg)
{
    enc_t *e = (enc_t *)arg;
    par_t *p = e->par;
    struct jpeg_compress_struct *c = &e->c;
    volatile int u = e->k;
    jpeg_saved_marker_ptr m;

    if (setjmp(e->err.jump)) { par_fail(p, "jpeg write: ", e->err.msg); return NULL; }
    jpeg_create_compress(c); e->c_ok = 1;
    c->dest = &e->dest.pub;
    e->dest.pub.init_destination = md_init;
    e->dest.pub.empty_output_buffer = md_empty;
    e->dest.pub.term_destination = md_term;
    e->dest.pub.next_output_byte = NULL;
    e->dest.pub.free_in_buffer = 0;
    c->image_width = p->width;
    c->image_height = e->height;
    c->input_components = 3;
    c->in_color_space = JCS_RGB;
    jpeg_set_defaults(c);
    jpeg_set_quality(c, p->quality, TRUE);
    c->dct_method = JDCT_ISLOW;
    c->comp_info[0].h_samp_factor = 2; c->comp_info[0].v_samp_factor = 1;   /* 4:2:2, like the camera */
    c->comp_info[1].h_samp_factor = 1; c->comp_info[1].v_samp_factor = 1;
    c->comp_info[2].h_samp_factor = 1; c->comp_info[2].v_samp_factor = 1;
    c->write_JFIF_header = FALSE;
    c->restart_in_rows = 1;            /* one restart interval per MCU row */
    jpeg_start_compress(c, TRUE);
    if (e->k == 0)
    {
        for (m = p->markers; m; m = m->next) jpeg_write_marker(c, m->marker, m->data, m->data_length);
        mpf_marker(c);
    }

    for (; u < p->units; u += NENC) {
        slot_t *s = &p->slot[u % PSLOTS];
        double t0 = tnow(), t1;
        int i;
        pthread_mutex_lock(&p->mu);
        while (!p->failed && !(s->state == S_DECODED && s->seq == u)) pthread_cond_wait(&p->cv, &p->mu);
        if (p->failed) { pthread_mutex_unlock(&p->mu); return NULL; }
        s->state = S_MAPPING;
        pthread_mutex_unlock(&p->mu);
        t1 = tnow();
        e->wait += t1 - t0;

        for (i = 0; i < s->nrows; i++) {
            unsigned y = (unsigned)(u * 8 + i);
            filmlut_lut_rgb(p->lut, s->rows[i], p->width);
            halo_row(&p->halo, s->rows[i], p->width, p->height, y);
            grain_row(s->rows[i], p->width, y);
        }
        jpeg_write_scanlines(c, s->rows, s->nrows);
        e->busy += tnow() - t1;

        pthread_mutex_lock(&p->mu);
        s->state = S_FREE;
        p->done_units++;
        g_progress = (int)((long long)p->done_units * 990 / p->units);
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
    }
    jpeg_finish_compress(c);
    return NULL;
}

/* Finds the entropy-coded data after SOS and splits it at RST markers into one segment per
 * MCU row. Returns the offset where scan data starts (the header is [0, that)), or 0 on error. */
static size_t split_segments(enc_t *e, int expected)
{
    const memdest *m = &e->dest;
    size_t n = md_size(m), o = 2, start;
    int s = 0;
    if (n < 4 || md_byte(m, 0) != 0xFF || md_byte(m, 1) != 0xD8) return 0;
    for (;;) {                                         /* walk header markers to SOS */
        unsigned mk, len;
        if (o + 4 > n || md_byte(m, o) != 0xFF) return 0;
        mk = md_byte(m, o + 1);
        len = ((unsigned)md_byte(m, o + 2) << 8) | md_byte(m, o + 3);
        o += 2 + len;
        if (mk == 0xDA) break;
    }
    start = o;
    e->seg_start = (size_t *)malloc(expected * sizeof(size_t));
    e->seg_end = (size_t *)malloc(expected * sizeof(size_t));
    if (!e->seg_start || !e->seg_end) return 0;
    e->seg_start[0] = o;
    while (o + 1 < n) {
        /* Jump to the next 0xFF within the current chunk (memchr is far faster than byte calls). */
        size_t ci = o / CHUNK, off = o % CHUNK, lim = CHUNK - off;
        const unsigned char *base = m->chunks[ci] + off, *hit;
        if (lim > n - o) lim = n - o;
        hit = (const unsigned char *)memchr(base, 0xFF, lim);
        if (!hit) { o += lim; continue; }
        o += (size_t)(hit - base);
        if (o + 1 >= n) break;
        {
            unsigned b = md_byte(m, o + 1);            /* may sit in the next chunk */
            if (b >= 0xD0 && b <= 0xD7) {               /* RST: end of this row's segment */
                if (s + 1 >= expected) return 0;
                e->seg_end[s++] = o;
                e->seg_start[s] = o + 2;
                o += 2;
                continue;
            }
            if (b == 0xD9) { e->seg_end[s++] = o; break; }   /* EOI */
            o += 2;                                    /* FF 00 stuffing */
        }
    }
    e->nseg = s;
    return s == expected ? start : 0;
}

/* Returns 0 OK, 2 failure (msg set), or 4 = could not run in parallel (caller falls back). */
static int apply_parallel(const char *in_path, const char *tmp_path,
                          const lut3d *lut, int quality, char *msg, size_t msglen)
{
    struct jpeg_decompress_struct d;
    err_mgr derr;
    par_t *p;
    FILE *fin, *fout = NULL;
    pthread_t th[NENC];
    volatile int d_ok = 0, nthreads = 0, u = 0, rc = 0;
    volatile double t_start = tnow(), dec_busy = 0, dec_wait = 0;
    size_t hdr_len = 0;
    int i;
    double t_dec_end = 0, t_split = 0, t_write = 0;

    p = (par_t *)calloc(1, sizeof *p);
    if (!p) return 4;
    if (!(fin = fopen(in_path, "rb"))) { free(p); snprintf(msg, msglen, "cannot open %s", in_path); return 2; }
    pthread_mutex_init(&p->mu, NULL);
    pthread_cond_init(&p->cv, NULL);
    p->lut = lut;
    p->quality = quality;

    err_init(&derr);
    d.err = &derr.pub;
    if (setjmp(derr.jump)) {
        par_fail(p, "jpeg read: ", derr.msg);
        goto finish;
    }
    jpeg_create_decompress(&d); d_ok = 1;
    jpeg_stdio_src(&d, fin);
    jpeg_save_markers(&d, JPEG_APP0 + 1, 0xFFFF);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    dec_options(&d);
    jpeg_start_decompress(&d);                         /* also initialises SIMD detection on this thread */

    p->width = d.output_width;
    p->height = d.output_height;
    prepare_markers(d.marker_list);                    /* what the LUT gives is sRGB */
    p->markers = d.marker_list;
    p->units = (int)((d.output_height + 7) / 8);
    halo_build(in_path, &p->halo, g_halation, 0);
    for (i = 0; i < PSLOTS; i++) {
        p->slot[i].rows = (*d.mem->alloc_sarray)((j_common_ptr)&d, JPOOL_IMAGE, d.output_width * 3, 8);
        p->slot[i].state = S_FREE;
        p->slot[i].seq = -1;
    }
    for (i = 0; i < NENC; i++) {
        enc_t *e = &p->enc[i];
        int k, cnt = 0;
        JDIMENSION h = 0;
        for (k = i; k < p->units; k += NENC) {
            JDIMENSION r = (k == p->units - 1) ? d.output_height - (JDIMENSION)k * 8 : 8;
            h += r;
            cnt++;
        }
        e->par = p;
        e->k = i;
        e->height = h;
        e->nseg = 0;
        err_init(&e->err);
        e->c.err = &e->err.pub;
        if (cnt == 0) { par_fail(p, "image too small for parallel encode", NULL); rc = 4; goto finish; }
    }
    for (i = 0; i < NENC; i++) {
        if (pthread_create(&th[i], NULL, par_encoder, &p->enc[i]) != 0) {
            par_fail(p, "cannot start encoder thread", NULL);
            rc = 4;
            goto finish;
        }
        nthreads = i + 1;
    }

    /* Decoder loop: one MCU row (8 pixel rows) per slot. */
    for (u = 0; u < p->units; u++) {
        slot_t *s = &p->slot[u % PSLOTS];
        int got = 0, want;
        double t0, t1;
        if (g_cancel) { par_fail(p, "cancelled", NULL); break; }
        t0 = tnow();
        pthread_mutex_lock(&p->mu);
        while (!p->failed && s->state != S_FREE) pthread_cond_wait(&p->cv, &p->mu);
        pthread_mutex_unlock(&p->mu);
        if (p->failed) break;
        t1 = tnow();
        dec_wait += t1 - t0;
        want = (u == p->units - 1) ? (int)(d.output_height - (JDIMENSION)u * 8) : 8;
        while (got < want) got += jpeg_read_scanlines(&d, s->rows + got, want - got);
        dec_busy += tnow() - t1;
        if (derr.pub.num_warnings > 0) { par_fail(p, "damaged or incomplete JPEG", NULL); break; }
        pthread_mutex_lock(&p->mu);
        s->nrows = got;
        s->seq = u;
        s->state = S_DECODED;
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
    }

finish:
    t_dec_end = tnow();
    for (i = 0; i < nthreads; i++) pthread_join(th[i], NULL);
    t_dec_end = tnow() - t_dec_end;     /* now: encoder tail after the decoder finished */
    if (!p->failed && d_ok) {
        if (setjmp(derr.jump)) par_fail(p, "jpeg read: ", derr.msg);
        else jpeg_finish_decompress(&d);
    }

    /* Stitch: encoder 0's header (SOF height patched), rows in order, RSTs renumbered, EOI. */
    if (!p->failed) {
        int ok = 1;
        t_split = tnow();
        for (i = 0; i < NENC && ok; i++) {
            int expected = (p->units - i + NENC - 1) / NENC;
            size_t st = split_segments(&p->enc[i], expected);
            if (!st) { par_fail(p, "internal: cannot split encoder output", NULL); ok = 0; }
            if (i == 0) hdr_len = st;
        }
        t_write = tnow();
        t_split = t_write - t_split;
        if (ok && !(fout = fopen(tmp_path, "wb"))) { par_fail(p, "cannot create ", tmp_path); ok = 0; }
        /* Android 2.3's stdio buffer is 1 KB; the stitch issues thousands of small writes
         * (one per MCU row + 2-byte RSTs), which took 4-8 s on the camera. Batch them. */
        if (ok) setvbuf(fout, NULL, _IOFBF, 1 << 20);
        if (ok) {
            memdest *m0 = &p->enc[0].dest;
            unsigned char *hdr = (unsigned char *)malloc(hdr_len);
            size_t o;
            int patched = 0;
            if (!hdr) { par_fail(p, "out of memory", NULL); rc = 4; ok = 0; }
            if (ok) {
                for (o = 0; o < hdr_len; o++) hdr[o] = md_byte(m0, o);
                for (o = 2; o + 9 < hdr_len; ) {      /* patch SOF0 height */
                    unsigned len = ((unsigned)hdr[o + 2] << 8) | hdr[o + 3];
                    if (hdr[o] == 0xFF && hdr[o + 1] == 0xC0) {
                        hdr[o + 5] = (unsigned char)(p->height >> 8);
                        hdr[o + 6] = (unsigned char)(p->height & 0xFF);
                        patched = 1;
                        break;
                    }
                    o += 2 + len;
                }
                if (!patched) { par_fail(p, "internal: no SOF0", NULL); ok = 0; }
                if (ok && fwrite(hdr, 1, hdr_len, fout) != hdr_len) ok = 0;
                free(hdr);
            }
            for (u = 0; ok && u < p->units; u++) {
                enc_t *e = &p->enc[u % NENC];
                int j = u / NENC;
                if (!md_write(&e->dest, e->seg_start[j], e->seg_end[j], fout)) { ok = 0; break; }
                if (u < p->units - 1) {
                    unsigned char rst[2];
                    rst[0] = 0xFF;
                    rst[1] = (unsigned char)(0xD0 + (u % 8));
                    if (fwrite(rst, 1, 2, fout) != 2) { ok = 0; break; }
                }
            }
            if (ok) {
                unsigned char eoi[2] = { 0xFF, 0xD9 };
                if (fwrite(eoi, 1, 2, fout) != 2) ok = 0;
            }
            if (fclose(fout) != 0) ok = 0;
            fout = NULL;
            t_write = tnow() - t_write;
            if (!ok && !p->failed) par_fail(p, "write failed (card full?)", NULL);
        }
    }

    {
        double eb = 0, ew = 0;
        for (i = 0; i < NENC; i++) { eb += p->enc[i].busy; ew += p->enc[i].wait; }
        snprintf(g_stats, sizeof g_stats,
                 "parallel x%d cpus=%ld total=%.1fs | decode busy %.1f wait %.1f | lut+encode busy %.1f wait %.1f (sum of %d)"
                 " | tail %.1f split %.2f write %.1f",
                 NENC, online_cpus(), tnow() - t_start, dec_busy, dec_wait, eb, ew, NENC,
                 t_dec_end, t_split, t_write);
    }
    for (i = 0; i < NENC; i++) {
        if (p->enc[i].c_ok) jpeg_destroy_compress(&p->enc[i].c);
        md_free(&p->enc[i].dest);
        free(p->enc[i].seg_start);
        free(p->enc[i].seg_end);
    }
    if (d_ok) jpeg_destroy_decompress(&d);
    fclose(fin);
    if (fout) fclose(fout);
    if (p->failed) {
        unlink(tmp_path);
        snprintf(msg, msglen, "%s", p->msg);
        if (rc != 4) rc = (strstr(p->msg, "out of memory") || strstr(p->msg, "Insufficient memory")) ? 4 : 2;
    }
    halo_free(&p->halo);
    pthread_cond_destroy(&p->cv);
    pthread_mutex_destroy(&p->mu);
    free(p);
    return rc;
}

static int g_force_serial = 0;
void filmlut_force_serial(int on) { g_force_serial = on; }

static int apply_any(const char *in_path, const char *out_path,
                     const unsigned char *lut_file, size_t lut_len,
                     int quality, char *msg, size_t msglen)
{
    lut3d lut;
    char tmp_path[1024];
    int rc;
    msg[0] = 0;
    g_progress = 0;
    g_cancel = 0;
    if (!filmlut_lut_init(&lut, lut_file, lut_len)) { snprintf(msg, msglen, "bad LUT file"); return 1; }
    {
        const char *slash = strrchr(out_path, '/');
        const char *dot = strrchr(out_path, '.');
        size_t stem = (dot && (!slash || dot > slash)) ? (size_t)(dot - out_path) : strlen(out_path);
        if (stem + 5 >= sizeof tmp_path) { snprintf(msg, msglen, "path too long"); return 1; }
        memcpy(tmp_path, out_path, stem);
        memcpy(tmp_path + stem, ".TMP", 5);
        if (strcmp(tmp_path, out_path) == 0) { snprintf(msg, msglen, "output must not end in .TMP"); return 1; }
    }
    extras_build(in_path, &lut);
    if (g_cancel) { snprintf(msg, msglen, "cancelled"); return 2; }
    rc = g_force_serial ? 4 : apply_parallel(in_path, tmp_path, &lut, quality, msg, msglen);
    if (rc == 4) {   /* not enough memory / threads: use the proven single-encoder pipeline */
        g_cancel = 0;
        return apply_serial(in_path, out_path, lut_file, lut_len, quality, msg, msglen);
    }
    if (rc != 0) return rc;
    extras_append(tmp_path);
    unlink(out_path);
    if (rename(tmp_path, out_path) != 0) { unlink(tmp_path); snprintf(msg, msglen, "rename failed"); return 3; }
    g_progress = 1000;
    return 0;
}

int filmlut_apply(const char *in_path, const char *out_path,
                  const unsigned char *lut_file, size_t lut_len,
                  int quality, char *msg, size_t msglen)
{
    unsigned char *adobe = NULL;
    int rc;
    if (filmlut_is_adobe(in_path) && !(adobe = filmlut_lut_adobe(lut_file, lut_len))) {
        snprintf(msg, msglen, "bad LUT file or out of memory");
        return 1;
    }
    rc = apply_any(in_path, out_path, adobe ? adobe : lut_file, lut_len, quality, msg, msglen);
    {
        size_t n = strlen(g_stats);
        if (rc == 0 && g_prev) snprintf(g_stats + n, sizeof g_stats - n, " preview %.1f (%lu KB)", g_prev_s, (unsigned long)(g_prev_len / 1024));
    }
    extras_free();
    free(adobe);
    return rc;
}

/* ---- test only: single-threaded stage timing --------------------------- */

int filmlut_bench(const char *in_path, const lut3d *l, const char *out_path, char *msg, size_t msglen)
{
    struct jpeg_decompress_struct d;
    struct jpeg_compress_struct c;
    err_mgr e;
    FILE *fin = fopen(in_path, "rb"), *fout = NULL;
    JSAMPARRAY rows;
    if (!fin) { snprintf(msg, msglen, "open"); return 1; }
    err_init(&e);
    d.err = c.err = &e.pub;
    if (setjmp(e.jump)) { snprintf(msg, msglen, "%s", e.msg); return 2; }
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, fin);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    dec_options(&d);
    jpeg_start_decompress(&d);
    if (out_path) {
        fout = fopen(out_path, "wb");
        jpeg_create_compress(&c);
        jpeg_stdio_dest(&c, fout);
        c.image_width = d.output_width; c.image_height = d.output_height;
        c.input_components = 3; c.in_color_space = JCS_RGB;
        jpeg_set_defaults(&c);
        jpeg_set_quality(&c, 95, TRUE);
        c.dct_method = JDCT_ISLOW;
        c.comp_info[0].h_samp_factor = 2; c.comp_info[0].v_samp_factor = 1;
        c.comp_info[1].h_samp_factor = 1; c.comp_info[1].v_samp_factor = 1;
        c.comp_info[2].h_samp_factor = 1; c.comp_info[2].v_samp_factor = 1;
        jpeg_start_compress(&c, TRUE);
    }
    rows = (*d.mem->alloc_sarray)((j_common_ptr)&d, JPOOL_IMAGE, d.output_width * 3, 16);
    while (d.output_scanline < d.output_height) {
        JDIMENSION got = jpeg_read_scanlines(&d, rows, 16), i;
        if (l) for (i = 0; i < got; i++) filmlut_lut_rgb(l, rows[i], d.output_width);
        if (out_path) jpeg_write_scanlines(&c, rows, got);
    }
    if (out_path) { jpeg_finish_compress(&c); jpeg_destroy_compress(&c); fclose(fout); }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(fin);
    return 0;
}

/* ---- public: small preview ------------------------------------------- */

int *filmlut_preview(const char *path, int max_w, int max_h, int *out_w, int *out_h, char *msg, size_t msglen)
{
    struct jpeg_decompress_struct d;
    err_mgr e;
    FILE *f;
    JSAMPARRAY row;
    unsigned char *volatile img = NULL;      /* decoded RGB at 1/1..1/8 scale */
    int *out = NULL;
    volatile int d_ok = 0;
    unsigned int w, h, y, ow, oh, ox, oy, denom;

    if (!(f = fopen(path, "rb"))) { snprintf(msg, msglen, "cannot open %s", path); return NULL; }
    err_init(&e);
    d.err = &e.pub;
    if (setjmp(e.jump)) {
        snprintf(msg, msglen, "jpeg: %s", e.msg);
        if (d_ok) jpeg_destroy_decompress(&d);
        fclose(f);
        free(img);
        return NULL;
    }
    {
        /* The camera's embedded screen-size preview (1616x1080) decodes ~10x faster than the full
         * photo and reads ~1 MB instead of the whole file; used when it is at least max_w wide. */
        long off = mpf_preview_offset(f);
        if (off > 0) {
            struct jpeg_decompress_struct p;
            err_mgr pe;
            volatile int wide = 0;
            err_init(&pe);
            p.err = &pe.pub;
            if (!setjmp(pe.jump)) {
                jpeg_create_decompress(&p);
                fseek(f, off, SEEK_SET);
                jpeg_stdio_src(&p, f);
                jpeg_read_header(&p, TRUE);
                wide = p.image_width >= (unsigned)max_w && p.image_height >= (unsigned)max_h / 2;
            }
            jpeg_destroy_decompress(&p);
            if (!wide) off = 0;
        }
        if (fseek(f, off > 0 ? off : 0, SEEK_SET)) { snprintf(msg, msglen, "seek failed"); fclose(f); return NULL; }
    }
    jpeg_create_decompress(&d); d_ok = 1;
    jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.dct_method = JDCT_IFAST;
    d.do_fancy_upsampling = FALSE;
    /* DCT scaling (42 MP -> ~994x663 at 1/8, very fast): the largest reduction that
     * still leaves at least max_w columns to box-filter down from. */
    denom = 8;
    while (denom > 1 && d.image_width / denom < (unsigned)max_w) denom /= 2;
    d.scale_num = 1;
    d.scale_denom = denom;
    jpeg_start_decompress(&d);
    w = d.output_width;
    h = d.output_height;
    img = (unsigned char *)malloc((size_t)w * h * 3);
    if (!img) { snprintf(e.msg, sizeof e.msg, "out of memory"); longjmp(e.jump, 1); }
    row = (*d.mem->alloc_sarray)((j_common_ptr)&d, JPOOL_IMAGE, w * 3, 1);
    for (y = 0; d.output_scanline < h; y++) {
        jpeg_read_scanlines(&d, row, 1);
        memcpy(img + (size_t)y * w * 3, row[0], (size_t)w * 3);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d); d_ok = 0;
    fclose(f);

    /* Box-filter down to fit max_w x max_h, keeping aspect. */
    ow = w; oh = h;
    if (ow > (unsigned)max_w) { oh = (unsigned)((unsigned long long)oh * max_w / ow); ow = max_w; }
    if (oh > (unsigned)max_h) { ow = (unsigned)((unsigned long long)ow * max_h / oh); oh = max_h; }
    if (ow < 1) ow = 1;
    if (oh < 1) oh = 1;
    out = (int *)malloc((size_t)ow * oh * sizeof(int));
    if (!out) { free(img); snprintf(msg, msglen, "out of memory"); return NULL; }
    for (oy = 0; oy < oh; oy++) {
        unsigned int y0 = oy * h / oh, y1 = (oy + 1) * h / oh;
        if (y1 <= y0) y1 = y0 + 1;
        for (ox = 0; ox < ow; ox++) {
            unsigned int x0 = ox * w / ow, x1 = (ox + 1) * w / ow, sx, sy, n = 0;
            unsigned int sr = 0, sg = 0, sb = 0;
            if (x1 <= x0) x1 = x0 + 1;
            for (sy = y0; sy < y1; sy++) {
                const unsigned char *px = img + ((size_t)sy * w + x0) * 3;
                for (sx = x0; sx < x1; sx++, px += 3) { sr += px[0]; sg += px[1]; sb += px[2]; n++; }
            }
            out[(size_t)oy * ow + ox] = (int)(0xFF000000u | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n));
        }
    }
    free(img);
    *out_w = (int)ow;
    *out_h = (int)oh;
    return out;
}

/* ---- public: halation on a preview ----------------------------------- */

/* The highlight map depends only on the photo (not the look), so the last one is kept: scrolling
 * looks on the same photo costs only the cheap per-pixel blend. Preview thread only. */
static char g_ph_path[1024];
static halo_t g_ph;

int filmlut_preview_halation(const char *path, int *argb, int w, int h, int strength)
{
    halo_t H;
    unsigned char *row;
    int x, y;
    if (strength <= 0 || w <= 0 || h <= 0) return 0;
    if (!g_ph.g || strcmp(g_ph_path, path) != 0) {
        halo_free(&g_ph);
        memset(&g_ph, 0, sizeof g_ph);
        g_ph_path[0] = 0;
        if (strlen(path) >= sizeof g_ph_path) return 0;
        if (!halo_build(path, &g_ph, 100, 1)) return 0;  /* from the embedded preview when present */
        strcpy(g_ph_path, path);
    }
    H = g_ph;
    H.s = (strength > 100 ? 100 : strength) / 100.0f;
    row = (unsigned char *)malloc((size_t)w * 3);
    if (!row) return 0;
    for (y = 0; y < h; y++) {
        int *p = argb + (size_t)y * w;
        for (x = 0; x < w; x++) {
            row[x * 3] = (unsigned char)(p[x] >> 16);
            row[x * 3 + 1] = (unsigned char)(p[x] >> 8);
            row[x * 3 + 2] = (unsigned char)p[x];
        }
        halo_row(&H, row, (unsigned)w, (unsigned)h, (unsigned)y);
        for (x = 0; x < w; x++)
            p[x] = (int)(0xFF000000u | ((unsigned)row[x * 3] << 16) | ((unsigned)row[x * 3 + 1] << 8) | row[x * 3 + 2]);
    }
    free(row);
    return 1;
}
