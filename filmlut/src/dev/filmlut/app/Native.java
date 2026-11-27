package dev.filmlut.app;

/** Bridge to libfilmlut.so (film-app/native). Methods are registered in JNI_OnLoad. */
public final class Native {
    public static final Throwable LOAD_ERROR;

    static {
        Throwable err = null;
        try {
            System.loadLibrary("filmlut");
        } catch (Throwable t) {
            err = t;
        }
        LOAD_ERROR = err;
    }

    private Native() {}

    /** Full-size conversion (4 threads). Returns null on success, otherwise the reason. One at a time. */
    public static native String apply(String inPath, String outPath, byte[] lut, int quality);

    /** Progress of the running conversion, 0..1000. */
    public static native int progress();

    /** Film grain for the next conversion, 0 (off) .. 100. */
    public static native void setGrain(int strength);

    /** Halation (CineStill glow around light sources) for the next conversion, 0 (off) .. 100. */
    public static native void setHalation(int strength);

    /** Stage timings of the last conversion (for LOG.TXT). */
    public static native String stats();

    /** Asks the running conversion to stop (it then deletes its temp file). */
    public static native void cancel();

    /** Stops the preview() decodes running now (they return null). */
    public static native void previewAbort();

    /** Small decode that fits maxW x maxH: [w, h, argb...], or null. */
    public static native int[] preview(String path, int maxW, int maxH);

    /**
     * Adds the glow (0..100) to a w x h ARGB preview of `path` (px[offset..]) that already has the look
     * `lut` applied, as the full conversion does: halation (map cached for the last photo), or for black
     * and white and colour infrared looks a bloom from their own bright areas. Preview thread only.
     */
    public static native boolean previewHalation(String path, int[] px, int offset, int w, int h, int strength, byte[] lut);

    /** True if the photo is Adobe RGB (apply() handles that itself; previews use adobeLut). */
    public static native boolean isAdobe(String path);

    /** The LUT for Adobe RGB input, or null. */
    public static native byte[] adobeLut(byte[] lut);

    /** Applies a LUT to ARGB pixels src[offset..offset+count) into dst. */
    public static native boolean lutArgb(int[] src, int[] dst, int offset, int count, byte[] lut);
}
