#ifndef FILMLUT_H
#define FILMLUT_H
#include <stddef.h>

typedef struct {
    int n;
    const unsigned char *t;   /* n^3 * 3 bytes, red fastest */
    int idx[256];             /* lower grid index per 8-bit input */
    int frac[256];            /* 0..256 weight toward the next grid point */
    int roff[256], goff[256], boff[256];   /* idx * byte stride per axis (precomputed) */
} lut3d;

/* Stage timings of the last filmlut_apply(), for tuning (text, e.g. "cpus=4 dec=12.1s ..."). */
const char *filmlut_stats(void);

/* Parses a FLUT file (kept by reference). Returns 1 if valid. */
int filmlut_lut_init(lut3d *l, const unsigned char *lut_file, size_t lut_len);
void filmlut_lut_rgb(const lut3d *l, unsigned char *px, unsigned int count);
void filmlut_lut_argb(const lut3d *l, const int *src, int *dst, unsigned int count);

/* Adobe RGB photos (camera "Color Space: AdobeRGB", files named _DSC....JPG): the looks expect sRGB.
 * filmlut_is_adobe: 1 if the JPEG's EXIF says Adobe RGB. filmlut_lut_adobe: a malloc'd FLUT (same length)
 * that takes Adobe RGB input and gives what `lut_file` gives for the same colour in sRGB; NULL on error.
 * filmlut_apply does this itself, and marks the output as sRGB. */
int filmlut_is_adobe(const char *path);
unsigned char *filmlut_lut_adobe(const unsigned char *lut_file, size_t lut_len);

/* Applies a FLUT 3D LUT to in_path and writes out_path (via a .TMP file next to it).
 * Uses 4 threads. Returns 0 on success; otherwise writes a reason into msg.
 * Never modifies in_path. One conversion at a time. */
int filmlut_apply(const char *in_path, const char *out_path,
                  const unsigned char *lut_file, size_t lut_len,
                  int quality, char *msg, size_t msglen);

/* Progress of the running conversion, 0..1000, and a cancel request. */
int filmlut_progress(void);
void filmlut_cancel(void);

/* Fast small decode (DCT-scaled, box-filtered) into a malloc'd ARGB array that
 * fits max_w x max_h. Caller frees. NULL on error (reason in msg). */
/* Screen previews only (not the one made during a conversion): stop the decodes running now; they return NULL. */
void filmlut_preview_abort(void);
int *filmlut_preview_abortable(const char *path, int max_w, int max_h, int *out_w, int *out_h, char *msg, size_t msglen);
int *filmlut_preview(const char *path, int max_w, int max_h, int *out_w, int *out_h, char *msg, size_t msglen);

/* Decoder speed options for the full-size path: ifast = JDCT_IFAST IDCT, plain = no fancy
 * (smoothed) chroma upsampling. Default 0/0 = most accurate. */
void filmlut_set_decode_options(int ifast, int plain);

/* Output size in eighths of the original (8 = full size, 6 ~ 24 MP, 5 ~ 16 MP, 4 ~ 10.5 MP from 42 MP).
 * Done by libjpeg's DCT scaling while decoding, so every stage gets faster. */
void filmlut_set_scale(int eighths);

/* Film grain added after the LUT, 0 (off) .. 100 (heavy). Luminance grain, strongest in the
 * midtones; deterministic per pixel position, so parallel and serial outputs stay identical. */
void filmlut_set_grain(int strength);

/* Halation (CineStill-style red-orange glow around bright highlights), 0 (off) .. 100.
 * Uses a quick 1/8-scale pre-pass to build a blurred highlight map; deterministic. */
void filmlut_set_halation(int strength);

/* Adds the halation glow (0..100) to a w x h ARGB preview of `path`, using the same highlight map as
 * the full conversion (cached for the last photo). Returns 1 if applied. Preview thread only. */
int filmlut_preview_halation(const char *path, int *argb, int w, int h, int strength);

/* Test only: 1 = no preview / thumbnail in the output. */
void filmlut_no_extras(int on);

/* Test only: 1 = always use the single-encoder pipeline (for comparisons). */
void filmlut_force_serial(int on);

/* Test only: single-threaded decode [+ LUT if l] [+ encode to out_path if set]. */
int filmlut_bench(const char *in_path, const lut3d *l, const char *out_path, char *msg, size_t msglen);

#endif
