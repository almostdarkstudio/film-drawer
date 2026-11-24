package dev.filmlut.app;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Diagnostics for testing on the camera (there is no readable system log):
 *  - trace(): ring buffer of the last 80 app events (keys, mode changes, renders, jobs);
 *  - crashes (uncaught exceptions on any thread) -> FILMDRAW/CRASH.TXT;
 *  - watchdog: if the UI thread does not answer for 3 s -> FILMDRAW/HANG.TXT with the UI thread's
 *    stack (where it is stuck) and the event trace; a line when it recovers.
 * Files are appended and capped at ~200 KB.
 */
public final class Diag {
    private Diag() {}

    private static final int N = 80;
    private static final String[] ring = new String[N];
    private static int next;
    private static final long T0 = System.currentTimeMillis();

    public static void trace(String s) {
        long ms = System.currentTimeMillis() - T0;
        synchronized (ring) {
            ring[next % N] = String.format("%7.2f [%s] %s", ms / 1000.0, Thread.currentThread().getName(), s);
            next++;
        }
    }

    private static String traceText() {
        StringBuilder sb = new StringBuilder("  recent events (seconds since start):\n");
        synchronized (ring) {
            for (int i = Math.max(0, next - N); i < next; i++) sb.append("    ").append(ring[i % N]).append('\n');
        }
        return sb.toString();
    }

    static synchronized void write(String name, String text) {
        try {
            File f = new File(Photos.appDir(), name);
            f.getParentFile().mkdirs();
            if (f.length() > 200000) return;
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "  " + text + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static Boolean verbose;

    /**
     * Verbose diagnostics (TRACE.TXT on every exit, PARAMS.TXT camera dump) only when the user has
     * created FILMDRAW/DEBUG.TXT on the card. HANG.TXT / CRASH.TXT are always on: they are written
     * only when something goes wrong, and are what a bug report needs.
     */
    public static boolean verbose() {
        if (verbose == null) {
            boolean v = false;
            try { v = Photos.debugFile(); } catch (Throwable ignored) {}
            verbose = v;
        }
        return verbose;
    }

    /** Writes the event trace to FILMDRAW/TRACE.TXT (replacing it), e.g. when the app is left. Verbose only. */
    public static void dumpTrace(String why) {
        if (!verbose()) return;
        try {
            File f = new File(Photos.appDir(), "TRACE.TXT");
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f, false);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "  " + why + "\n" + traceText());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ---- input diagnostics (0.24): learn the a7R II's EV dial / AF-MF button from the camera itself.
    private static final String SCALAR_INPUT = "com.sony.scalar.sysutil.ScalarInput";
    private static boolean inputDumped;

    /** Names of the camera's ScalarInput int constants equal to `code` (e.g. "ISV_KEY_..."), or "". */
    public static String keyName(int code) {
        StringBuilder sb = new StringBuilder();
        try {
            for (java.lang.reflect.Field f : Class.forName(SCALAR_INPUT).getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == int.class && f.getInt(null) == code) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(f.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** ScalarInput.getKeyStatus(code) as "field=value ..." (e.g. a dial's position), or "". */
    public static String keyStatus(int code) {
        try {
            java.lang.reflect.Method m = Class.forName(SCALAR_INPUT).getMethod("getKeyStatus", int.class);
            Object ks = m.invoke(null, code);
            if (ks == null) return "null";
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : ks.getClass().getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                sb.append(f.getName()).append('=').append(f.get(ks)).append(' ');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "(" + t.getClass().getSimpleName() + ")";
        }
    }

    /** The "status" field of ScalarInput.getKeyStatus(code) (a dial's position), or null. */
    public static Integer keyStatusValue(int code) {
        try {
            java.lang.reflect.Method m = Class.forName(SCALAR_INPUT).getMethod("getKeyStatus", int.class);
            Object ks = m.invoke(null, code);
            if (ks == null) return null;
            return ks.getClass().getField("status").getInt(ks);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean cameraDumped;

    /** Once: writes FILMDRAW/CAMERAEX.TXT with every public method of Sony's CameraEx and its nested
     *  classes (read-only reflection), to find how MF is switched on this body. */
    public static synchronized void dumpCameraEx(Object camera) {
        if (cameraDumped || camera == null) return;
        cameraDumped = true;
        StringBuilder sb = new StringBuilder("CameraEx on this camera (" + android.os.Build.MODEL + ")\n");
        try {
            java.util.List<String> lines = new java.util.ArrayList<String>();
            for (java.lang.reflect.Method m : camera.getClass().getMethods()) lines.add(m.toString());
            java.util.Collections.sort(lines);
            for (String l : lines) sb.append(l).append('\n');
            for (Class<?> k : camera.getClass().getClasses()) {
                sb.append("\n").append(k.getName()).append('\n');
                java.util.List<String> ks = new java.util.ArrayList<String>();
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) ks.add("  " + m.toString());
                for (java.lang.reflect.Field f : k.getFields()) ks.add("  field " + f.toString());
                java.util.Collections.sort(ks);
                for (String l : ks) sb.append(l).append('\n');
            }
        } catch (Throwable t) {
            sb.append("failed: ").append(t).append('\n');
        }
        try {
            File f = new File(Photos.appDir(), "CAMERAEX.TXT");
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** Once: writes FILMDRAW/INPUT.TXT with every ScalarInput constant and method (read-only lookups). */
    public static synchronized void dumpInputClass() {
        if (inputDumped) return;
        inputDumped = true;
        StringBuilder sb = new StringBuilder("ScalarInput on this camera (" + android.os.Build.MODEL + ")\n\nConstants:\n");
        try {
            Class<?> c = Class.forName(SCALAR_INPUT);
            java.util.List<String> lines = new java.util.ArrayList<String>();
            for (java.lang.reflect.Field f : c.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { lines.add(f.getName() + " = " + f.get(null)); } catch (Throwable ignored) {}
            }
            java.util.Collections.sort(lines);
            for (String l : lines) sb.append(l).append('\n');
            sb.append("\nMethods:\n");
            for (java.lang.reflect.Method m : c.getMethods()) sb.append(m.toString()).append('\n');
            sb.append("\nNested classes:\n");
            for (Class<?> k : c.getClasses()) {
                sb.append(k.getName()).append('\n');
                for (java.lang.reflect.Field f : k.getFields()) sb.append("  field ").append(f.toString()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("not available: ").append(t).append('\n');
        }
        try {
            File f = new File(Photos.appDir(), "INPUT.TXT");
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String stack(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : st) sb.append("    at ").append(e).append('\n');
        return sb.toString();
    }

    private static boolean installed;

    /** Call once from the UI thread. */
    public static synchronized void install(String version) {
        if (installed) return;
        installed = true;
        trace("start " + version);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                write("CRASH.TXT", "CRASH in thread " + t.getName() + "\n" + sw + traceText());
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        final Handler ui = new Handler(Looper.getMainLooper());
        final Thread uiThread = Looper.getMainLooper().getThread();
        final long[] pong = {System.currentTimeMillis()};
        final Runnable ping = new Runnable() {
            public void run() { synchronized (pong) { pong[0] = System.currentTimeMillis(); } }
        };
        Thread w = new Thread(new Runnable() {
            public void run() {
                boolean hung = false;
                long hungSince = 0;
                for (;;) {
                    ui.post(ping);
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    long last;
                    synchronized (pong) { last = pong[0]; }
                    long late = System.currentTimeMillis() - last;
                    if (!hung && late > 3000) {
                        hung = true;
                        hungSince = last;
                        write("HANG.TXT", "UI thread not responding for " + late + " ms. It is at:\n"
                                + stack(uiThread.getStackTrace()) + traceText());
                    } else if (hung && late < 1500) {
                        hung = false;
                        write("HANG.TXT", "  ... UI thread responsive again after ~"
                                + (System.currentTimeMillis() - hungSince) / 1000 + " s\n");
                    }
                }
            }
        }, "filmlut-watchdog");
        w.setDaemon(true);
        w.start();
    }
}
