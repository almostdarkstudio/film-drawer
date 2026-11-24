package dev.filmlut.app;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's own camera settings, saved before the app changes them, and put back. A saved value is forgotten only
 * when the camera reports that value again (or cannot report it): if the camera ignores a change, it is tried again
 * on its own, then every time the camera opens or closes, MAX_FAILS times in all. (The focus area is in Focus.)
 *
 * No Android or Sony camera calls in here, so it can be tested on a PC.
 */
final class UserSettings {
    /** The camera: values by the names below. */
    interface Cam {
        Map<String, String> read() throws Exception;
        void set(Map<String, String> values) throws Exception;
    }

    static final String FOCUS = "focus mode", SHARPNESS = "sharpness", MODE = "Creative Style", CONTRAST = "contrast",
            SATURATION = "saturation", SIZE = "JPEG size", DRO = "DRO", DRO_LEVEL = "DRO level", FORMAT = "quality", AF = "AF mode";

    /** Groups, saved and restored as one. */
    static final int G_FOCUS = 0, G_SHARPNESS = 1, G_STYLE = 2, G_SIZE = 3, G_DRO = 4, G_FORMAT = 5, G_AF = 6;

    /** What is sent to the camera together, in this order. The quality is last: the JPEG size needs a JPEG. */
    private static final int[][] SENT = {{G_FOCUS}, {G_AF}, {G_SHARPNESS}, {G_STYLE, G_SIZE, G_DRO}, {G_FORMAT}};

    private static final int MAX_FAILS = 6;

    private final SharedPreferences prefs;
    private boolean gaveUp;                       // the last restore() did, for something

    UserSettings(SharedPreferences prefs) { this.prefs = prefs; }

    boolean gaveUp() { return gaveUp; }

    /** Is the user's own value saved (so must not be saved again)? */
    boolean saved(int group) { return values(group) != null; }

    private Map<String, String> values(int group) {
        Map<String, String> v = new LinkedHashMap<String, String>();
        boolean any = prefs.getBoolean("userSaved", false);
        switch (group) {
            case G_FOCUS: put(v, FOCUS, prefs.getString("userFocusMode", null)); break;
            case G_AF: put(v, AF, prefs.getString("userAfMode", null)); break;
            case G_SHARPNESS: if (prefs.contains("userSharpness")) put(v, SHARPNESS, "" + prefs.getInt("userSharpness", 0)); break;
            case G_STYLE:
                // Older saves (0.6/0.7) have no flags: they only ever saved the style.
                if (!any || !prefs.getBoolean("userStyleSaved", true)) break;
                put(v, MODE, prefs.getString("userMode", null));
                put(v, CONTRAST, "" + prefs.getInt("userContrast", 0));
                put(v, SATURATION, "" + prefs.getInt("userSaturation", 0));
                break;
            case G_SIZE: if (any && prefs.getBoolean("userSizeSaved", false)) put(v, SIZE, prefs.getString("userImageSize", null)); break;
            case G_DRO:
                if (!any || !prefs.getBoolean("userDroSaved", false)) break;
                String dro = prefs.getString("userDroMode", null);
                put(v, DRO, dro);
                if ("on".equals(dro)) put(v, DRO_LEVEL, "" + prefs.getInt("userDroLevel", 1));
                break;
            default: put(v, FORMAT, prefs.getString("userStorageFmt", null));
        }
        return v.isEmpty() ? null : v;
    }

    private static void put(Map<String, String> m, String k, String v) { if (v != null) m.put(k, v); }

    /**
     * Saves what the camera has now for these groups (before the app changes them), except those already saved: their
     * user's value is still waiting to be put back, and what the camera has is the app's. Written before returning.
     */
    void saveBase(Map<String, String> now, boolean style, boolean size, boolean dro) {
        boolean s0 = saved(G_STYLE), z0 = saved(G_SIZE), d0 = saved(G_DRO);
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("userStyleSaved", s0 || style).putBoolean("userSizeSaved", z0 || size).putBoolean("userDroSaved", d0 || dro);
        e.putBoolean("userSaved", s0 || style || z0 || size || d0 || dro);
        if (style && !s0) e.putString("userMode", now.get(MODE)).putInt("userContrast", num(now.get(CONTRAST))).putInt("userSaturation", num(now.get(SATURATION)));
        if (size && !z0) e.putString("userImageSize", now.get(SIZE));
        if (dro && !d0) e.putString("userDroMode", now.get(DRO)).putInt("userDroLevel", num(now.get(DRO_LEVEL)));
        e.commit();
    }

    /** The same for the focus mode (AF / MF / DMF) and the AF mode (AF-S / AF-C / AF-A). */
    void saveFocus(Map<String, String> now) {
        SharedPreferences.Editor e = prefs.edit();
        if (!saved(G_FOCUS) && now.get(FOCUS) != null) e.putString("userFocusMode", now.get(FOCUS));
        if (!saved(G_AF) && now.get(AF) != null) e.putString("userAfMode", now.get(AF));
        e.commit();
    }

    private static int num(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** In one write: it goes to the camera's flash. */
    private void forget(List<Integer> groups) {
        if (groups.isEmpty()) return;
        SharedPreferences.Editor e = prefs.edit();
        boolean left = false;
        for (int g : new int[]{G_STYLE, G_SIZE, G_DRO}) if (!groups.contains(g) && saved(g)) left = true;
        for (int g : groups) {
            e.remove("restoreFails" + g);
            switch (g) {
                case G_FOCUS: e.remove("userFocusMode"); break;
                case G_AF: e.remove("userAfMode"); break;
                case G_SHARPNESS: e.remove("userSharpness"); break;
                case G_FORMAT: e.remove("userStorageFmt"); break;
                default:
                    e.putBoolean(g == G_STYLE ? "userStyleSaved" : g == G_SIZE ? "userSizeSaved" : "userDroSaved", false);
                    e.putBoolean("userSaved", left);
            }
        }
        e.commit();
    }

    /** Puts back these groups, those that are saved. Returns what did not go back ("JPEG size: yours large, now small; ..."), or null. */
    String restore(Cam cam, int... groups) {
        gaveUp = false;
        List<Integer> todo = new ArrayList<Integer>();
        String problem = null;
        for (int[] together : SENT) {
            Map<String, String> v = new LinkedHashMap<String, String>();
            for (int g : together) if (in(groups, g) && saved(g)) { v.putAll(values(g)); todo.add(g); }
            if (v.isEmpty()) continue;
            try { cam.set(v); } catch (Throwable t) { problem = "" + t; }
        }
        if (todo.isEmpty()) return null;
        todo = notBack(cam, todo);
        if (!todo.isEmpty()) {                    // each on its own: one the camera refuses must not stop the others
            for (int g : todo) try { cam.set(values(g)); } catch (Throwable t) { problem = "" + t; }
            todo = notBack(cam, todo);
        }
        if (todo.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        Map<String, String> got = null;
        try { got = cam.read(); } catch (Throwable t) { problem = "" + t; }
        for (int g : todo) {
            for (Map.Entry<String, String> e : values(g).entrySet()) {
                String now = got == null ? null : got.get(e.getKey());
                if (now != null && now.equals(e.getValue())) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append(": yours ").append(e.getValue()).append(", now ").append(now == null ? "?" : now);
            }
            int fails = prefs.getInt("restoreFails" + g, 0) + 1;
            if (fails >= MAX_FAILS) {             // e.g. changed for good by something else: do not fight it for ever
                gaveUp = true;
                forget(java.util.Collections.singletonList(g));
            } else prefs.edit().putInt("restoreFails" + g, fails).commit();
        }
        if (problem != null) sb.append(" (").append(problem).append(')');
        return sb.toString();
    }

    private static boolean in(int[] a, int x) {
        for (int i : a) if (i == x) return true;
        return false;
    }

    /** Those the camera does not report as put back. The others are forgotten. */
    private List<Integer> notBack(Cam cam, List<Integer> groups) {
        Map<String, String> got;
        try { got = cam.read(); } catch (Throwable t) { return groups; }
        List<Integer> left = new ArrayList<Integer>(), back = new ArrayList<Integer>();
        for (int g : groups) {
            boolean ok = true;
            for (Map.Entry<String, String> e : values(g).entrySet()) {
                String now = got.get(e.getKey());
                if (now != null && !now.equals(e.getValue())) ok = false;      // not reported: cannot be checked
            }
            (ok ? back : left).add(g);
        }
        forget(back);
        return left;
    }
}
