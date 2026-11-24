package dev.filmlut.app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * What the user has set each dial to on the camera (Dial/Wheel Setup, Custom Key Settings), so the app can
 * follow it: ScalarInput.getKeyLogicCode(key, shooting mode, status) -> a FUNC_* code. Read-only.
 *
 * Undocumented (the argument order is taken from its constants and getKeyStatus(key)), so only the codes
 * below are trusted; any other answer, or an error, means "unknown" and the app uses its own assignment.
 */
final class Controls {
    private Controls() {}

    /** True in trial builds: the camera's answers go to FILMDRAW/KEYS.TXT. Otherwise only with DEBUG.TXT. */
    private static final boolean TRIAL = false;

    static final int UNKNOWN = 0, APERTURE = 1, SHUTTER = 2, ISO = 3, EV = 4;

    private static final String INPUT = "com.sony.scalar.sysutil.ScalarInput";
    private static Method query;
    private static boolean missing;
    private static final Map<String, Integer> cache = new HashMap<String, Integer>();

    private static int mode(String scene) {          // KeyLogicCode.MODE_SHOOTING_*
        if ("program-auto".equals(scene)) return 0;
        if ("aperture-priority".equals(scene)) return 1;
        if ("shutter-speed".equals(scene)) return 2;
        if ("manual-exposure".equals(scene)) return 3;
        return 4;
    }

    private static int ask(int key, int mode, int status) throws Exception {
        if (query == null) query = Class.forName(INPUT).getMethod("getKeyLogicCode", int.class, int.class, int.class);
        return (Integer) query.invoke(null, key, mode, status);
    }

    /** What turning this dial does on the camera in this exposure mode, or UNKNOWN. */
    static synchronized int function(int key, String scene) {
        if (missing) return UNKNOWN;
        String k = key + " " + scene;
        Integer f = cache.get(k);
        if (f == null) {
            f = UNKNOWN;
            try {
                f = kind(ask(key, mode(scene), 1));
            } catch (ClassNotFoundException e) {
                missing = true;
            } catch (NoSuchMethodException e) {
                missing = true;
            } catch (Throwable t) {
                Diag.trace("key function " + k + ": " + t);
            }
            cache.put(k, f);
        }
        return f;
    }

    private static int kind(int code) {              // KeyLogicCode.FUNC_*
        switch (code) {
            case 1032: case 1153: case 1184: return APERTURE;   // AV_NEXT, AV_PREVIEW, AV_PREVIOUS
            case 1154: case 1033: case 1185: return SHUTTER;    // TV_NEXT, TV_PREVIEW, TV_PREVIOUS
            case 1226: case 1227: return ISO;                   // ISO_NEXT, ISO_PREVIOUS
            case 1031: case 1181: case 1182: return EV;         // EV_NEXT, EV_PREVIEW, EV_PREVIOUS
            default: return UNKNOWN;
        }
    }

    // ---------------------------------------------------------------- diagnostics

    private static boolean dumped;

    /** Once per launch, in the background: what the camera says about its dials and buttons. */
    static synchronized void dump(final Object camera) {
        if (dumped || (!TRIAL && !Diag.verbose())) return;
        dumped = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Diag.write("KEYS.TXT", report(camera)); } catch (Throwable e) { Diag.trace("keys: " + e); }
            }
        }, "filmlut-keys");
        t.setDaemon(true);
        t.start();
    }

    private static String report(Object camera) {
        StringBuilder sb = new StringBuilder("key functions, " + android.os.Build.MODEL + "\n");
        Map<Integer, String> names = new HashMap<Integer, String>();
        try {
            for (Field f : Class.forName(INPUT + "$KeyLogicCode").getFields())
                if (f.getName().startsWith("FUNC_") && f.getType() == int.class && !names.containsKey(f.getInt(null)))
                    names.put(f.getInt(null), f.getName().substring(5));
        } catch (Throwable e) { sb.append("no KeyLogicCode: ").append(e).append('\n'); }
        int[] keys = {525, 526, 528, 529, 635, 634, 522, 523, 232, 622, 623, 659, 595, 533, 532, 103, 108, 105, 106, 520};
        String[] what = {"front +", "front -", "rear +", "rear -", "third +", "third -", "wheel +", "wheel -", "OK", "C1", "C2", "C3",
                "trash", "AF/MF", "AEL", "up", "down", "left", "right", "Fn"};
        try {
            for (int i = 0; i < keys.length; i++) {
                sb.append(what[i]).append(' ').append(keys[i]).append(':');
                for (int m = 0; m <= 4; m++) {
                    int c = ask(keys[i], m, 1);
                    sb.append(' ').append("PASM-".charAt(m)).append('=').append(c).append(names.containsKey(c) ? "(" + names.get(c) + ")" : "");
                }
                sb.append('\n');
            }
        } catch (Throwable e) {
            sb.append("\nnot available: ").append(e).append(e.getCause() == null ? "" : " / " + e.getCause()).append('\n');
        }
        // Is there anything for Eye AF, or AF on a button?
        for (Method m : camera.getClass().getMethods())
            if (m.getName().matches(".*(Trigger|FaceDetection|TrackingFocus|FocusHold|QuickAutoFocus).*")) sb.append(m.toString()).append('\n');
        return sb.toString();
    }
}
