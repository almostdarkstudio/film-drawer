package dev.filmlut.app;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Film mode (0.58): a roll of one stock. Its photos convert in the background as usual, but stay hidden until the roll is
 * finished (or rewound) and every frame of it is done: then the whole roll is "developed" at once. The roll in the camera
 * keeps its stock, grain, base style and JPEG size; turning Film mode off pauses it, turning it on again carries on. Everything is
 * kept in the app's settings, so a roll survives the camera going to sleep.
 */
final class Roll {
    static final int[] LENGTHS = {24, 36};

    /** A developed roll, for the note. */
    static final class Released {
        final String lookId;
        final int frames;
        Released(String lookId, int frames) { this.lookId = lookId; this.frames = frames; }
    }

    private boolean on;
    private int nextLength = 36;          // for the next roll loaded
    /* The roll in the camera (lookId null: none). */
    private String lookId;
    private int rollNo, lastNo;           /* this roll's folder number (FILMDRAW/ROLL0001), the last one used */
    private int grainIndex, baseIndex, sizeIndex, length, frames;
    private final List<String> current = new ArrayList<String>();
    /* Finished rolls, waiting for their last frames to convert: look id, then the output paths. */
    private final List<List<String>> lab = new ArrayList<List<String>>();

    private final SharedPreferences prefs;

    Roll(SharedPreferences prefs) {
        this.prefs = prefs;
        on = prefs.getBoolean("film_on", false);
        nextLength = prefs.getInt("film_len", 36) == 24 ? 24 : 36;
        lastNo = prefs.getInt("roll_last", 0);
        rollNo = prefs.getInt("roll_no", 0);
        lookId = prefs.getString("roll_look", null);
        grainIndex = prefs.getInt("roll_grain", 0);
        baseIndex = prefs.getInt("roll_base", 0);
        sizeIndex = prefs.getInt("roll_size", 0);
        length = prefs.getInt("roll_length", 36);
        frames = prefs.getInt("roll_frames", 0);
        split(prefs.getString("roll_files", ""), current);
        for (String r : prefs.getString("roll_lab", "").split("\u001e")) {
            List<String> l = new ArrayList<String>();
            split(r, l);
            if (l.size() > 1) lab.add(l);
        }
        if (lookId != null && rollNo == 0) rollNo = nextFree();
        publish();
    }

    /** The next roll number whose folder does not exist yet. */
    private int nextFree() {
        int n = lastNo + 1;
        while (n < 9999 && new File(Photos.appDir(), folderName(n)).exists()) n++;
        return n;
    }

    static String folderName(int n) { return "ROLL" + (n < 10 ? "000" : n < 100 ? "00" : n < 1000 ? "0" : "") + n; }

    /** Where this roll's photos go: FILMDRAW/ROLL0001 (8.3, as the card needs). */
    synchronized String folder() { return folderName(rollNo); }

    private static void split(String s, List<String> out) {
        for (String p : s.split("\n")) if (p.length() > 0) out.add(p);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? "\n" : "").append(s);
        return sb.toString();
    }

    private void save() {
        StringBuilder labs = new StringBuilder();
        for (List<String> r : lab) labs.append(labs.length() > 0 ? "\u001e" : "").append(join(r));
        SharedPreferences.Editor e = prefs.edit().putBoolean("film_on", on).putInt("film_len", nextLength)
                .putInt("roll_grain", grainIndex).putInt("roll_base", baseIndex).putInt("roll_size", sizeIndex).putInt("roll_length", length)
                .putInt("roll_frames", frames).putInt("roll_no", rollNo).putInt("roll_last", lastNo).putString("roll_files", join(current)).putString("roll_lab", labs.toString());
        if (lookId == null) e.remove("roll_look"); else e.putString("roll_look", lookId);
        e.commit();
        publish();
    }

    /** What PLAY must not show yet: the roll in the camera and the rolls at the lab. */
    private void publish() {
        Set<String> h = new HashSet<String>(current);
        for (List<String> r : lab) h.addAll(r.subList(1, r.size()));
        Photos.setHidden(h);
    }

    synchronized boolean on() { return on; }
    synchronized boolean loaded() { return lookId != null; }
    synchronized String lookId() { return lookId; }
    synchronized int grainIndex() { return grainIndex; }
    synchronized int baseIndex() { return baseIndex; }
    synchronized int sizeIndex() { return sizeIndex; }
    synchronized int frames() { return frames; }
    synchronized int length() { return lookId != null ? length : nextLength; }
    synchronized int atLab() { return lab.size(); }

    /** Film mode on: carries on with a paused roll. Otherwise the next roll is loaded by its first frame (shot()). */
    synchronized void turnOn() {
        on = true;
        save();
    }

    /** Film mode off: a started roll stays in the camera, paused; an unused one is taken out (any stock next time). */
    synchronized void turnOff() {
        on = false;
        if (frames == 0) lookId = null;
        save();
    }

    private void load(String look, int grain, int base, int size) {
        lookId = look;
        grainIndex = grain;
        baseIndex = base;
        sizeIndex = size;
        length = nextLength;
        frames = 0;
        rollNo = nextFree();
        current.clear();
    }

    /** Roll length: for the roll in the camera while it has fewer frames than that, and for the next ones. */
    synchronized boolean setLength(int len) {
        nextLength = len;
        boolean fits = lookId == null || frames < len;
        if (fits && lookId != null) length = len;
        save();
        return fits;
    }

    /**
     * A frame was shot (its converted file will be out). The first frame loads a roll with the film, grain, base style and
     * size it was shot with. True if that was the last one: the roll went to the lab.
     */
    synchronized boolean shot(File out, String look, int grain, int base, int size) {
        if (!on) return false;
        if (lookId == null) load(look, grain, base, size);
        current.add(out.getAbsolutePath());
        if (frames == 0) lastNo = rollNo;    // the number is taken once the roll has a frame
        frames++;
        boolean last = frames >= length;
        if (last) rewind();
        else save();
        return last;
    }

    /** Sends the roll in the camera to the lab (if any frame was shot). The next roll is loaded by its first frame. */
    synchronized int rewind() {
        int n = frames;
        if (lookId != null && n > 0) {
            List<String> r = new ArrayList<String>();
            r.add(lookId);
            r.addAll(current);
            lab.add(r);
        }
        lookId = null;
        frames = 0;
        current.clear();
        save();
        return n;
    }

    /** The folder the next frame goes to: this roll's, or the next number if no roll is loaded yet. */
    synchronized String nextFolder() { return folderName(lookId != null ? rollNo : nextFree()); }

    /** Rolls at the lab whose frames are all done (converted, or failed) are released. */
    synchronized List<Released> develop(JobQueue jobs) {
        List<Released> out = new ArrayList<Released>();
        for (int i = 0; i < lab.size(); i++) {
            List<String> r = lab.get(i);
            boolean done = true;
            for (int k = 1; k < r.size() && done; k++) if (jobs.writes(new File(r.get(k)))) done = false;
            if (!done) continue;
            out.add(new Released(r.get(0), r.size() - 1));
            lab.remove(i--);
        }
        if (!out.isEmpty()) save();
        return out;
    }
}
