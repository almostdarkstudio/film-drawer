package dev.filmlut.app;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds camera photos (DCIM/<folder>/*.JPG) and names their converted copies. */
public final class Photos {
    private Photos() {}

    /** The app's folder on the card (8.3 name). Up to 0.34 it was FILMLUT: still read, never moved. */
    private static final String DIR = "FILMDRAW", OLD_DIR = "FILMLUT";

    public static File appDir() { return new File(sd(), DIR); }

    private static File oldDir() { return new File(sd(), OLD_DIR); }

    /** FILMDRAW/DEBUG.TXT (or in the old folder) turns detailed logs on. */
    public static boolean debugFile() {
        return new File(appDir(), "DEBUG.TXT").exists() || new File(oldDir(), "DEBUG.TXT").exists();
    }

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

    /**
     * All JPEGs, oldest first: folder order, then file number. Not by full name: Adobe RGB photos are
     * _DSC1234 among DSC01234, share one numbering with them, and would all sort last.
     */
    public static List<File> allJpegs() {
        List<File> out = new ArrayList<File>();
        for (File d : folders()) {
            String[] names = jpegNamesIn(d);
            Arrays.sort(names, BY_NUMBER);
            for (String n : names) out.add(new File(d, n));
        }
        return out;
    }

    /** By the last 4 characters of the name (the DCF file number), then the whole name. */
    private static final java.util.Comparator<String> BY_NUMBER = new java.util.Comparator<String>() {
        public int compare(String a, String b) {
            int c = number(a).compareTo(number(b));
            return c != 0 ? c : a.compareTo(b);
        }

        private String number(String n) {
            int dot = n.lastIndexOf('.');
            return dot < 4 ? n : n.substring(dot - 4, dot);
        }
    };

    /**
     * Converted photos (FILMDRAW/<folder>/<TAG><last 5 of name>.JPG; _DSC1234 ends in C1234), oldest first by time written.
     * The camera's own playback does not show these (they are outside DCIM).
     */
    public static List<File> allOutputs() {
        List<File> out = new ArrayList<File>();
        List<File> dirs = new ArrayList<File>();
        for (File top : new File[]{appDir(), oldDir()}) {
            File[] l = top.listFiles();
            if (l != null) dirs.addAll(Arrays.asList(l));
        }
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            for (String n : jpegNamesIn(d)) {
                if (isOutputName(n) && !isHidden(new File(d, n))) out.add(new File(d, n));
            }
        }
        final java.util.Map<File, Long> t = new java.util.HashMap<File, Long>();
        for (File f : out) {
            Long m;
            synchronized (written) { m = written.get(f.getPath()); }
            if (m == null) {
                m = f.lastModified();                  // slow while the camera writes to the card: once per file
                synchronized (written) { written.put(f.getPath(), m); }
            }
            t.put(f, m);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                int c = t.get(a).compareTo(t.get(b));
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** Film mode: converted photos of rolls not developed yet (absolute paths). PLAY does not list them. */
    private static volatile Set<String> hidden = new HashSet<String>();

    static synchronized void setHidden(Set<String> h) { hidden = h; }

    /** One more, at once (the roll saves its full list right after). */
    static synchronized void hideNow(File f) {
        Set<String> h = new HashSet<String>(hidden);
        h.add(f.getAbsolutePath());
        hidden = h;
    }

    public static boolean isHidden(File f) { return hidden.contains(f.getAbsolutePath()); }

    /** When each converted photo was written, as read so far. */
    private static final java.util.Map<String, Long> written = new java.util.HashMap<String, Long>();

    /** This converted photo was written again or deleted. */
    public static void outputChanged(File f) {
        synchronized (written) {
            written.remove(f.getPath());
            if (byName != null) {
                if (f.exists()) byName.put(f.getName(), f);
                else if (f.equals(byName.get(f.getName()))) byName.remove(f.getName());
            }
        }
    }

    /** The look tag of a converted photo (first 3 letters of its name). */
    public static String tagOf(File output) {
        return output.getName().substring(0, 3);
    }

    /** Is this one of the app's own converted photos (in its folder, with its kind of name)? Nothing else is ever deleted. */
    private static boolean isOutput(File f) {
        File folder = f.getParentFile(), top = folder == null ? null : folder.getParentFile();
        return (appDir().equals(top) || oldDir().equals(top)) && isOutputName(f.getName()) && f.isFile();
    }

    private static boolean isOutputName(String n) {
        return n.length() == 12 && n.substring(3, 8).matches("[A-Z0-9_][0-9]{4}") && n.toUpperCase().endsWith(".JPG");
    }

    /** Deletes a converted photo; false if it is not one, or the camera refused. */
    public static boolean deleteOutput(File f) {
        if (!isOutput(f) || !f.delete() || f.exists()) return false;
        outputChanged(f);
        return true;
    }

    /** The name of the photo (JPEG, else RAW) a converted photo was made from, or null if it is not on the card. */
    public static String originalOf(File output) {
        String last5 = output.getName().substring(3, 8), raw = null, folder = output.getParentFile().getName();
        // A roll or date folder is not a DCIM folder: then look in each of them, newest first.
        File dcf = new File(new File(sd(), "DCIM"), folder);
        File[] dirs = dcf.isDirectory() ? new File[]{dcf} : folders();
        for (int i = dirs.length - 1; i >= 0; i--) {
            String[] names = dirs[i].list();
            if (names != null) for (String n : names) {
                String u = n.toUpperCase();
                if (u.endsWith(last5 + ".JPG")) return n;
                if (u.endsWith(last5 + ".ARW") && raw == null) raw = n;
            }
            if (raw != null) return raw;
        }
        return raw;
    }

    /**
     * Output for a photo and look: FILMDRAW/<DCF folder>/<TAG><last 5 of name>.JPG.
     * The camera's card only accepts 8.3 upper-case names (e.g. FILMDRAW/100MSDCF/P4002097.JPG).
     */
    public static File outputFor(File photo, String tag) {
        File f = findOutput(photo, tag);
        return f != null ? f : new File(new File(appDir(), dateFolder(photo)), outputName(photo, tag));
    }

    /**
     * The converted photo of this photo and look wherever it is (a DCF folder up to 0.57, a date or roll folder from
     * 0.58, or the old FILMLUT folder), or null. From a list of the app's folders read once, not a search of each.
     */
    public static File findOutput(File photo, String tag) {
        File f;
        synchronized (written) { f = index().get(outputName(photo, tag)); }
        return f != null && f.exists() ? f : null;
    }

    /** Reads the list of converted photos now (start-up, background thread), so the first lookup is quick. */
    public static void warm() { synchronized (written) { index(); } }

    /** Converted photos by name. Read once (on a background thread at start), then kept up to date by outputChanged(). */
    private static java.util.Map<String, File> byName;

    private static java.util.Map<String, File> index() {
        if (byName != null) return byName;
        java.util.Map<String, File> m = new java.util.HashMap<String, File>();
        for (File top : new File[]{appDir(), oldDir()}) {
            String[] dirs = top.list();
            if (dirs == null) continue;
            for (String dn : dirs) {
                File dir = new File(top, dn);
                for (String n : jpegNamesIn(dir)) if (isOutputName(n) && !m.containsKey(n)) m.put(n, new File(dir, n));
            }
        }
        byName = m;
        return m;
    }

    /**
     * 0.58: outside Film mode, a converted photo goes in a folder of the day it was taken, FILMDRAW/20261126, from the
     * photo's own EXIF date (the app's clock is not the camera's: it says 1970). Without one: its DCF folder, as before.
     */
    private static String dateFolder(File photo) {
        try {
            String dt = new android.media.ExifInterface(photo.getAbsolutePath()).getAttribute(android.media.ExifInterface.TAG_DATETIME);
            if (dt != null && dt.length() >= 10) {
                String day = dt.substring(0, 4) + dt.substring(5, 7) + dt.substring(8, 10);
                if (day.matches("(19|20)[0-9]{6}")) return day;
            }
        } catch (Throwable ignored) {}
        String folder = photo.getParentFile().getName().toUpperCase();
        return folder.length() > 8 ? folder.substring(0, 8) : folder;
    }

    /** Film mode: a frame's converted photo goes in its roll's folder, FILMDRAW/ROLL0001/<TAG><last 5 of name>.JPG. */
    public static File rollOutput(File photo, String tag, String rollFolder) {
        return new File(new File(appDir(), rollFolder), outputName(photo, tag));
    }

    private static String outputName(File photo, String tag) {
        String base = photo.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 5) base = base.substring(base.length() - 5);
        return (tag + base).toUpperCase() + ".JPG";
    }


}
