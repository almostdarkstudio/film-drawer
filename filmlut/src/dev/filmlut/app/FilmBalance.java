package dev.filmlut.app;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * White balance: Film (0.64). Real film has a fixed balance (800T 3200 K, daylight stocks 5500 K); a lab scan then
 * took out part of each frame's cast (much less for slides, which were matched rather than corrected). The camera's
 * auto white balance plays the lab scanner: it has already neutralised the frame, and the JPEG records what it chose
 * (Sony maker note 0x9408, the RAW's as-shot multipliers). This puts back the share of the film's cast a lab left in:
 * a per-photo copy of the look's LUT, with the colour shift folded into its input.
 *
 * No Android or camera calls: tested on a PC (luts/FilmBalanceTest). Only the a7R II's maker note layout and colour
 * matrix are known; any other body (or a photo without readable levels) keeps the camera's white balance.
 */
final class FilmBalance {
    private FilmBalance() {}

    /** The a7R II's XYZ -> camera matrix (Adobe DNG ColorMatrix2, as LibRaw has it). */
    private static final double[][] XYZ2CAM_7RM2 = {{0.6629, -0.1900, -0.0483}, {-0.4618, 1.2349, 0.2550}, {-0.0622, 0.1381, 0.6514}};
    private static final double[][] SRGB2XYZ = {{0.4124, 0.3576, 0.1805}, {0.2126, 0.7152, 0.0722}, {0.0193, 0.1192, 0.9505}};
    /** Our kelvin (black body / daylight locus through this matrix) read ~150 K below the camera's own setting. */
    private static final double SCALE_K = 150;

    /** The result for one photo: what auto chose and what the film balance asks for, or why not. */
    static final class Result {
        final byte[] lut;           // null: use the look's own
        final String note;          // for LOG.TXT
        Result(byte[] lut, String note) { this.lut = lut; this.note = note; }
    }

    /**
     * The look's LUT for this photo with the film's balance: `filmK` (camera kelvin) and `share` of the cast a lab
     * scan removed (0 = all of the film's cast kept, "Film (full)"; 0.65 = a negative's lab scan).
     */
    static Result apply(byte[] lut, File jpg, int filmK, double share) {
        if (filmK <= 0) return new Result(null, null);
        int[] lv;
        try { lv = levels(jpg); } catch (Throwable t) { lv = null; }
        if (lv == null) return new Result(null, "film balance: no as-shot white balance in this photo (camera's kept)");
        double[] awb = {lv[0] / (double) lv[1], 1, lv[2] / (double) lv[1]};
        double tAwb = kelvinOf(awb);
        double tFilm = filmK - SCALE_K;
        double mFinal = 1e6 / tFilm + share * (1e6 / tAwb - 1e6 / tFilm);       // mired: the lab's correction
        double tFinal = 1e6 / mFinal;
        double[][] m = shift(tAwb, tFinal);
        String note = String.format(java.util.Locale.US, "film balance: auto %.0f K -> %.0f K (film %d K, lab %.0f%%)",
                tAwb + SCALE_K, tFinal + SCALE_K, filmK, share * 100);
        return new Result(fold(lut, m), note);
    }

    // ---------------------------------------------------------------- the JPEG's as-shot levels

    /** R, G, B as-shot white balance levels from an a7R II JPEG, or null. */
    static int[] levels(File jpg) throws Exception {
        byte[] b = new byte[(int) Math.min(jpg.length(), 256 * 1024)];
        RandomAccessFile f = new RandomAccessFile(jpg, "r");
        try { f.readFully(b); } finally { f.close(); }
        int app1 = -1;
        for (int i = 2; i + 10 < b.length; ) {                   // walk the JPEG segments to APP1 "Exif"
            if ((b[i] & 0xFF) != 0xFF) return null;
            int marker = b[i + 1] & 0xFF, len = u16be(b, i + 2);
            if (marker == 0xE1 && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') { app1 = i + 10; break; }
            if (marker == 0xDA) return null;
            i += 2 + len;
        }
        if (app1 < 0) return null;
        final int t = app1;                                       // TIFF header: offsets are relative to it
        boolean le = b[t] == 'I';
        if (!le) return null;                                     // Sony writes little-endian
        int ifd0 = t + u32(b, t + 4);
        String model = null;
        int exif = -1;
        for (int n = u16(b, ifd0), k = 0; k < n; k++) {
            int e = ifd0 + 2 + 12 * k, tag = u16(b, e);
            if (tag == 0x0110) model = ascii(b, t + u32(b, e + 8), u32(b, e + 4));
            if (tag == 0x8769) exif = t + u32(b, e + 8);
        }
        if (!"ILCE-7RM2".equals(model) || exif < 0) return null;  // other bodies: layout not known
        int mn = -1, mnLen = 0;
        for (int n = u16(b, exif), k = 0; k < n; k++) {
            int e = exif + 2 + 12 * k;
            if (u16(b, e) == 0x927C) { mn = t + u32(b, e + 8); mnLen = u32(b, e + 4); }
        }
        if (mn < 0 || mn + 14 > b.length) return null;
        int ifd = (b[mn] == 'S' && b[mn + 1] == 'O' && b[mn + 2] == 'N' && b[mn + 3] == 'Y') ? mn + 12 : mn;
        for (int n = u16(b, ifd), k = 0; k < n; k++) {
            int e = ifd + 2 + 12 * k;
            if (u16(b, e) != 0x9408) continue;
            int cnt = u32(b, e + 4), off = t + u32(b, e + 8);
            if (cnt < 704 || off + 704 > b.length) return null;
            int r = u16d(b, off + 698), g = u16d(b, off + 700), bl = u16d(b, off + 702);
            // plausible as-shot multipliers (G ~1024): R/G and B/G between 0.6 and 5
            if (g < 256 || g > 8192 || r < 0.6 * g || r > 5 * g || bl < 0.6 * g || bl > 5 * g) return null;
            return new int[]{r, g, bl};
        }
        return null;
    }

    /** Sony's enciphered maker note blocks: byte b came from i where i^3 mod 249 == b (b < 249). */
    private static final int[] DECIPHER = new int[256];
    static {
        for (int i = 0; i < 256; i++) DECIPHER[i] = i;
        for (int i = 0; i < 249; i++) DECIPHER[(i * i * i) % 249] = i;
    }

    private static int u16d(byte[] b, int o) { return DECIPHER[b[o] & 0xFF] | (DECIPHER[b[o + 1] & 0xFF] << 8); }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int u16be(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }
    private static int u32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }

    private static String ascii(byte[] b, int o, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && o + i < b.length && b[o + i] != 0; i++) sb.append((char) b[o + i]);
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- colour

    private static double[] cctXy(double T) {
        double x, y;
        if (T < 4000) {
            x = -0.2661239e9 / (T * T * T) - 0.2343589e6 / (T * T) + 0.8776956e3 / T + 0.179910;
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            x = T <= 7000 ? -4.6070e9 / (T * T * T) + 2.9678e6 / (T * T) + 0.09911e3 / T + 0.244063
                          : -2.0064e9 / (T * T * T) + 1.9018e6 / (T * T) + 0.24748e3 / T + 0.237040;
            y = -3.000 * x * x + 2.870 * x - 0.275;
        }
        return new double[]{x, y};
    }

    /** Camera multipliers (G = 1) that make a light of T kelvin neutral. */
    static double[] mult(double T) {
        double[] xy = cctXy(T);
        double[] xyz = {xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]};
        double[] cam = mul(XYZ2CAM_7RM2, xyz), m = new double[3];
        for (int i = 0; i < 3; i++) m[i] = 1 / cam[i];
        return new double[]{m[0] / m[1], 1, m[2] / m[1]};
    }

    /** The kelvin whose multipliers are nearest these (log R/G, B/G). */
    static double kelvinOf(double[] m) {
        double best = 5000, be = 1e9;
        for (double T = 2000; T <= 12000; T += 25) {
            double[] k = mult(T);
            double e = sq(Math.log(k[0] / m[0])) + sq(Math.log(k[2] / m[2]));
            if (e < be) { be = e; best = T; }
        }
        return best;
    }

    /** Linear sRGB 3x3 taking a photo balanced for light `from` to one balanced for `to` (column vectors). */
    static double[][] shift(double from, double to) {
        double[][] camRgb = mul(XYZ2CAM_7RM2, SRGB2XYZ);
        for (int i = 0; i < 3; i++) {                            // rows normalised to 1, as LibRaw does
            double s = camRgb[i][0] + camRgb[i][1] + camRgb[i][2];
            for (int j = 0; j < 3; j++) camRgb[i][j] /= s;
        }
        double[][] rgbCam = inv(camRgb);
        double[] a = mult(from), b = mult(to);
        double[][] d = {{b[0] / a[0], 0, 0}, {0, 1, 0}, {0, 0, b[2] / a[2]}};
        return mul(mul(rgbCam, d), camRgb);
    }

    // ---------------------------------------------------------------- folding the shift into the LUT

    private static final double[] LIN = new double[256];
    static { for (int i = 0; i < 256; i++) { double v = i / 255.0; LIN[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); } }

    private static double enc(double l) {
        l = l < 0 ? 0 : l > 1 ? 1 : l;
        return l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
    }

    /** A new LUT (same header and size): each grid input goes through `m` (linear light) and then the look. */
    static byte[] fold(byte[] lut, double[][] m) {
        int n = lut[4] & 0xFF, hdr = 5;
        byte[] out = new byte[lut.length];
        System.arraycopy(lut, 0, out, 0, hdr);
        double step = 255.0 / (n - 1);
        for (int bi = 0; bi < n; bi++)
            for (int gi = 0; gi < n; gi++)
                for (int ri = 0; ri < n; ri++) {
                    double r = LIN[(int) Math.round(ri * step)], g = LIN[(int) Math.round(gi * step)], bl = LIN[(int) Math.round(bi * step)];
                    double r2 = enc(m[0][0] * r + m[0][1] * g + m[0][2] * bl) * (n - 1);
                    double g2 = enc(m[1][0] * r + m[1][1] * g + m[1][2] * bl) * (n - 1);
                    double b2 = enc(m[2][0] * r + m[2][1] * g + m[2][2] * bl) * (n - 1);
                    int o = hdr + 3 * ((bi * n + gi) * n + ri);
                    sample(lut, n, hdr, r2, g2, b2, out, o);
                }
        return out;
    }

    /** Trilinear sample of the cube (index order b, g, r; RGB bytes) at fractional grid position. */
    private static void sample(byte[] lut, int n, int hdr, double r, double g, double b, byte[] out, int o) {
        int r0 = Math.min((int) r, n - 2), g0 = Math.min((int) g, n - 2), b0 = Math.min((int) b, n - 2);
        double fr = r - r0, fg = g - g0, fb = b - b0;
        for (int c = 0; c < 3; c++) {
            double v = 0;
            for (int db = 0; db < 2; db++)
                for (int dg = 0; dg < 2; dg++)
                    for (int dr = 0; dr < 2; dr++) {
                        double w = (db == 1 ? fb : 1 - fb) * (dg == 1 ? fg : 1 - fg) * (dr == 1 ? fr : 1 - fr);
                        v += w * (lut[hdr + 3 * (((b0 + db) * n + g0 + dg) * n + r0 + dr) + c] & 0xFF);
                    }
            out[o + c] = (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
        }
    }

    // ---------------------------------------------------------------- small matrix helpers

    private static double sq(double v) { return v * v; }

    private static double[] mul(double[][] a, double[] v) {
        return new double[]{a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2], a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2],
                a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2]};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) c[i][j] += a[i][k] * b[k][j];
        return c;
    }

    private static double[][] inv(double[][] a) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        double[][] r = new double[3][3];
        r[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det; r[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det;
        r[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det; r[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det;
        r[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det; r[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
        r[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det; r[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det;
        r[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
        return r;
    }
}
