package dev.filmlut.app;

import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Converts photos one at a time on a low-priority background thread, so shooting and
 * the live view keep priority. (Native worker threads inherit the low priority.)
 *
 * Unfinished jobs (queued + running) are kept in the app's private preferences (internal
 * storage, never the card), so photos still waiting when the app is closed are converted the
 * next time it starts (see restore()).
 */
public final class JobQueue {
    public interface Listener {
        /** Called on the worker thread. error == null means success. */
        void onJobDone(Job job, String error, long millis);
        /** Queue went from idle to busy (busy=true) or back to idle (false). */
        void onBusyChanged(boolean busy);
    }

    public static final class Job {
        public final File in, out;
        public final Looks.Look look;
        public final int grain;          // 0..100, resolved when the job was queued
        public int wbMode;               // White balance when queued: 0 camera, 1 Film (lab scan), 2 Film (full)
        int restores;                    // times resumed after the app was closed (crash-loop guard)

        Job wb(int mode) { wbMode = mode; return this; }

        public Job(File in, File out, Looks.Look look, int grain) {
            this.in = in;
            this.out = out;
            this.look = look;
            this.grain = grain;
        }
    }

    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<Job>();
    private final Looks looks;
    private final Listener listener;
    private volatile Job current;
    private volatile int pending;        // queued + running
    private volatile boolean stopping;
    private volatile int done, failed;   // read by the UI twice a second: no lock
    private final SharedPreferences prefs;
    private final LinkedList<Job> outstanding = new LinkedList<Job>();   // queued + running, oldest first

    private static final String KEY = "pendingJobs";
    /** A converted photo smaller than this is not a photo (the smallest real ones are hundreds of KB). */
    private static final long MIN_OUTPUT = 16 * 1024;

    public JobQueue(Looks looks, Listener listener, SharedPreferences prefs) {
        this.looks = looks;
        this.listener = listener;
        this.prefs = prefs;
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "filmlut-queue");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void add(Job job) {
        // A new date or roll folder is made now, seconds before anything is written into it.
        File dir = job.out.getParentFile();
        try { if (dir != null && !dir.isDirectory()) dir.mkdirs(); } catch (Throwable ignored) {}
        if (pending++ == 0) listener.onBusyChanged(true);
        outstanding.add(job);
        save();
        Diag.trace("queued " + job.in.getName() + " " + job.look.tag + " (pending " + pending + ")");
        queue.add(job);
    }

    /** One line per unfinished job: input path, look id, grain, output path, restores, white balance mode (tab-separated). */
    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Job j : outstanding) {
            sb.append(j.in.getAbsolutePath()).append('\t').append(j.look.id).append('\t')
              .append(j.grain).append('\t').append(j.out.getAbsolutePath()).append('\t')
              .append(j.restores).append('\t').append(j.wbMode).append('\n');
        }
        // apply(): written in the background, in order (never blocks a thread holding this lock).
        try { prefs.edit().putString(KEY, sb.toString()).apply(); } catch (Throwable ignored) {}
    }

    /**
     * Jobs left unfinished by an earlier session whose photo still exists and has no output yet
     * (a job that finished just before the app closed is skipped). Clears the saved list; the
     * caller re-adds what it wants with add().
     */
    public static List<Job> restore(SharedPreferences prefs, Looks looks) {
        List<Job> out = new ArrayList<Job>();
        String s = prefs.getString(KEY, "");
        for (String line : s.split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 4) continue;
            Looks.Look lk = looks.byId(p[1]);
            File in = new File(p[0]), o = new File(p[3]);
            if (lk == null || !in.isFile() || o.exists()) continue;
            int g;
            try { g = Math.max(0, Math.min(100, Integer.parseInt(p[2]))); } catch (NumberFormatException e) { continue; }
            int r = 0;
            try { r = p.length > 4 ? Integer.parseInt(p[4]) : 0; } catch (NumberFormatException ignored) {}
            if (r >= 3) continue;        // resumed 3 times without finishing: give up on it
            Job j = new Job(in, o, lk, g);
            j.restores = r + 1;
            try { j.wbMode = p.length > 5 ? Math.max(0, Math.min(2, Integer.parseInt(p[5].trim()))) : 0; } catch (NumberFormatException ignored) {}
            out.add(j);
        }
        prefs.edit().remove(KEY).commit();
        return out;
    }

    /** Is a queued or running conversion going to write this file? */
    public synchronized boolean writes(File out) {
        for (Job j : outstanding) if (j.out.equals(out)) return true;
        return false;
    }

    public int pending() { return pending; }
    public Job current() { return current; }
    public int done() { return done; }
    public int failed() { return failed; }

    /** Drops queued jobs and asks the running one to stop. They stay saved for the next start. */
    public synchronized void stopAll() {
        stopping = true;
        pending -= queue.size();
        queue.clear();
        if (current != null) Native.cancel();
    }

    /** One line per conversion in FILMDRAW/LOG.TXT (time, result, priority, NEON) for tuning. */
    private static void log(String line) {
        if (!Diag.verbose()) return;   // FILMDRAW/DEBUG.TXT on the card turns LOG.TXT on
        try {
            java.io.File f = new java.io.File(Photos.appDir(), "LOG.TXT");
            f.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(line + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String cpuFeatures() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("Features")) { r.close(); return l.substring(l.indexOf(':') + 1).trim(); }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return "?";
    }

    // Nice +5: below the UI and the camera's own work, but not starved like BACKGROUND (+10),
    // which made a conversion take ~2 min with live view running. Native threads inherit it.
    private static final int PRIORITY = 5;

    private void loop() {
        Process.setThreadPriority(PRIORITY);
        String feats = cpuFeatures();
        log("# " + new java.util.Date() + "  nice=" + PRIORITY + "  neon=" + (feats.contains("neon") ? "yes" : "NO") + "  features: " + feats);
        for (;;) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            current = job;
            String err = null;
            long t0 = System.currentTimeMillis(), size = 0;
            String retried = "", wbNote = null;
            // 0.58: once (on the a7R II) a conversion into a new folder reported OK but left an empty file. The result is
            // checked: an empty or tiny file is deleted and converted once more; if it is still bad, the job fails.
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    File dir = job.out.getParentFile();
                    if (!dir.isDirectory() && !dir.mkdirs()) err = "cannot create " + dir;
                    else {
                        Native.setGrain(job.grain);
                        Native.setHalation(job.look.halation);
                        byte[] lut = looks.lut(job.look);
                        if (job.wbMode > 0 && job.look.wb > 0) {
                            // White balance: Film (0.64): the film's cast put back from what auto chose (FilmBalance)
                            FilmBalance.Result fb = FilmBalance.apply(lut, job.in, job.look.wb, job.wbMode == 1 ? job.look.wbShare / 100.0 : 0);
                            if (fb.lut != null) lut = fb.lut;
                            wbNote = fb.note;
                        }
                        err = Native.apply(job.in.getAbsolutePath(), job.out.getAbsolutePath(), lut, 95);
                    }
                } catch (Throwable t) {
                    err = t.toString();
                }
                if (err != null) break;
                size = job.out.length();
                if (size >= MIN_OUTPUT) break;
                retried += "  (written " + size + " bytes" + (attempt == 0 ? ", converting again)" : ")");
                if (!job.out.delete() && job.out.exists()) { err = "empty file written, cannot delete it"; break; }
                if (attempt == 1) err = "the converted file came out empty (" + size + " bytes)";
                else try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
            long ms = System.currentTimeMillis() - t0;
            String st;
            try { st = Native.stats(); } catch (Throwable t) { st = "?"; }
            log(job.in.getName() + "  " + job.look.tag + " grain " + job.grain
                    + (job.look.halation > 0 ? " halation " + job.look.halation : "") + "  " + ms + " ms  "
                    + (err == null ? "OK -> " + job.out.getParentFile().getName() + "/" + job.out.getName() + " " + size / 1024 + " KB" : "FAILED: " + err)
                    + retried + "  (queued after: " + (pending - 1) + ")" + (wbNote != null ? "\n    " + wbNote : "") + "\n    " + st);
            synchronized (this) {
                current = null;
                if (err == null) done++; else failed++;
                pending--;
                // A job cancelled by stopAll() stays saved, to be resumed next time.
                if (!stopping) { outstanding.remove(job); save(); }
            }
            if (!stopping) listener.onJobDone(job, err, ms);
            synchronized (this) {
                if (pending == 0) listener.onBusyChanged(false);
            }
        }
    }
}
