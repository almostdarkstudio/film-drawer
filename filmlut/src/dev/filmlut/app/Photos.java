package dev.filmlut.app;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finds camera photos (DCIM/<folder>/*.JPG) and names their converted copies. */
public final class Photos {
    private Photos() {}

    public static File sd() {
        return Environment.getExternalStorageDirectory();
    }

    /** DCIM sub-folders, sorted by name (100MSDCF, 101MSDCF, ...). */
    public static File[] folders() {
        File[] dirs = new File(sd(), "DCIM").listFiles();
        if (dirs == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File d : dirs) if (d.isDirectory()) out.add(d);
        File[] a = out.toArray(new File[out.size()]);
        Arrays.sort(a);
        return a;
    }

    public static boolean isJpeg(File f) {
        return f.getName().toUpperCase().endsWith(".JPG");
    }

    /** JPEG file NAMES in a folder: one directory read, no per-file stat (cheap on a full card). */
    public static String[] jpegNamesIn(File folder) {
        String[] names = folder.list();
        if (names == null) return new String[0];
        List<String> out = new ArrayList<String>();
        for (String n : names) if (n.toUpperCase().endsWith(".JPG")) out.add(n);
        String[] a = out.toArray(new String[out.size()]);
        Arrays.sort(a);
        return a;
    }

    /** Newest DCIM folder by name (where the camera writes new shots), or null. */
    public static File newestFolder() {
        File[] f = folders();
        return f.length == 0 ? null : f[f.length - 1];
    }

    public static File[] jpegsIn(File folder) {
        File[] fs = folder.listFiles();
        if (fs == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File f : fs) if (f.isFile() && isJpeg(f)) out.add(f);
        File[] a = out.toArray(new File[out.size()]);
        Arrays.sort(a);
        return a;
    }

    /** All JPEGs, oldest first (folder order, then file name). */
    public static List<File> allJpegs() {
        List<File> out = new ArrayList<File>();
        for (File d : folders()) for (String n : jpegNamesIn(d)) out.add(new File(d, n));
        return out;
    }

    /**
     * Converted photos (FILMLUT/<folder>/<TAG><5 digits>.JPG), oldest first by time written.
     * The camera's own playback does not show these (they are outside DCIM).
     */
    public static List<File> allOutputs() {
        List<File> out = new ArrayList<File>();
        File[] dirs = new File(sd(), "FILMLUT").listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            for (String n : jpegNamesIn(d)) {
                if (n.length() == 12 && n.substring(3, 8).matches("[0-9]{5}")) out.add(new File(d, n));
            }
        }
        final java.util.Map<File, Long> t = new java.util.HashMap<File, Long>();
        for (File f : out) t.put(f, f.lastModified());
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                int c = t.get(a).compareTo(t.get(b));
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** The look tag of a converted photo (first 3 letters of its name). */
    public static String tagOf(File output) {
        return output.getName().substring(0, 3);
    }

    /**
     * Output for a photo and look: FILMLUT/<DCF folder>/<TAG><last 5 of name>.JPG.
     * The camera's card only accepts 8.3 upper-case names (e.g. FILMLUT/100MSDCF/P4002097.JPG).
     */
    public static File outputFor(File photo, String tag) {
        String base = photo.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 5) base = base.substring(base.length() - 5);
        String folder = photo.getParentFile().getName().toUpperCase();
        if (folder.length() > 8) folder = folder.substring(0, 8);
        return new File(new File(new File(sd(), "FILMLUT"), folder), (tag + base).toUpperCase() + ".JPG");
    }
}
