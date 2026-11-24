package dev.filmlut.app;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** The film looks bundled in assets/luts (looks.txt: id|TAG|Name|grain|halation|group). */
public final class Looks {
    public static final class Look {
        public final String id, tag, name;
        public final int grain;          // default grain 0..100 for "Grain: Auto"
        public final int halation;       // 0..100, CineStill-style glow around light sources
        public final String group;       // menu group, e.g. "Kodak negative" (left/right jump)
        public final String groupShort;  // e.g. "Kodak", for the group strip on the look card
        public int sharpness;            // camera sharpness -3..3 while shooting this look (0 = Standard)
        private byte[] lut;

        Look(String id, String tag, String name, int grain, int halation, String group, String groupShort) {
            this.id = id;
            this.tag = tag;
            this.name = name;
            this.grain = grain;
            this.halation = halation;
            this.group = group;
            this.groupShort = groupShort;
        }
    }

    private final AssetManager assets;
    public final List<Look> all = new ArrayList<Look>();

    public Looks(AssetManager assets) throws Exception {
        this.assets = assets;
        BufferedReader r = new BufferedReader(new InputStreamReader(assets.open("luts/looks.txt"), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\\|");
            if (p.length >= 3) {
                String g = p.length > 5 ? p[5].trim() : "", gs = g;
                int semi = g.indexOf(';');                       // "Full name;Short"
                if (semi >= 0) { gs = g.substring(semi + 1).trim(); g = g.substring(0, semi).trim(); }
                Look lk = new Look(p[0].trim(), p[1].trim(), p[2].trim(), num(p, 3), num(p, 4), g, gs);
                if (p.length > 6) {
                    try { lk.sharpness = Math.max(-3, Math.min(3, Integer.parseInt(p[6].trim()))); } catch (NumberFormatException ignored) {}
                }
                all.add(lk);
            }
        }
        r.close();
        if (all.isEmpty()) throw new Exception("no looks in assets/luts/looks.txt");
    }

    /** The look with this 3-letter file tag (e.g. "P40"), or null. */
    public Look byTag(String tag) {
        for (Look l : all) if (l.tag.equalsIgnoreCase(tag)) return l;
        return null;
    }

    /** The look with this id, or null. */
    public Look byId(String id) {
        for (Look l : all) if (l.id.equals(id)) return l;
        return null;
    }

    /** Groups in menu order (one Look per group: its first). */
    public List<Look> groupHeads() {
        List<Look> out = new ArrayList<Look>();
        for (int i = 0; i < all.size(); i++)
            if (i == 0 || !all.get(i).group.equals(all.get(i - 1).group)) out.add(all.get(i));
        return out;
    }

    /** {position within its group (1-based), group size} for the look at index i. */
    public int[] posInGroup(int i) {
        String g = all.get(i).group;
        int start = i, end = i;
        while (start > 0 && all.get(start - 1).group.equals(g)) start--;
        while (end < all.size() - 1 && all.get(end + 1).group.equals(g)) end++;
        return new int[] {i - start + 1, end - start + 1};
    }

    /** Index of the first look of the next (dir 1) or previous (dir -1) group, wrapping around. */
    public int groupJump(int from, int dir) {
        int n = all.size(), i = from;
        String g = all.get(from).group;
        if (dir > 0) {
            do { i = (i + 1) % n; } while (i != from && all.get(i).group.equals(g));
            return i;
        }
        // Back: to the start of the current group, or, if already there, the start of the previous one.
        int start = from;
        while (start > 0 && all.get(start - 1).group.equals(g)) start--;
        if (start != from) return start;
        i = (start - 1 + n) % n;
        String pg = all.get(i).group;
        while (i > 0 && all.get(i - 1).group.equals(pg)) i--;
        return i;
    }

    private static int num(String[] p, int i) {
        if (p.length <= i) return 0;
        try { return Math.max(0, Math.min(100, Integer.parseInt(p[i].trim()))); } catch (NumberFormatException e) { return 0; }
    }

    /** LUT bytes (~108 KB), loaded on first use and kept. */
    public synchronized byte[] lut(Look look) throws Exception {
        if (look.lut == null) {
            InputStream in = assets.open("luts/" + look.id + ".lut");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(110000);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            look.lut = bo.toByteArray();
        }
        return look.lut;
    }
}
