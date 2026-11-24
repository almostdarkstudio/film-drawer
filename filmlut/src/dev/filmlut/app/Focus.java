package dev.filmlut.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Handler;
import android.view.View;

import com.sony.scalar.hardware.CameraEx;

import java.util.ArrayList;
import java.util.List;

/**
 * Focus area: shows it over the live view (green when focus locks), changes it, and moves the flexible spot.
 *
 * Sony's calls are undocumented. What an a7R II reports (FOCUS.TXT, 0.35): boxes in -1000..1000 over the
 * picture; box 0 of each area is the frame around all of them; flexible spot has its range as box 1 and only
 * its SIZE as box 2 (the position is getFocusPoint, which the camera rounds to its own grid); a lock in a
 * single area is reported with the box numbers of the wide area. Zone ("local") is listed but refused.
 * UI thread only. Every camera call is guarded: without support the app behaves as before this existed.
 */
final class Focus {
    /** True in trial builds: what the camera reports goes to FILMDRAW/FOCUS.TXT. Otherwise only with DEBUG.TXT. */
    private static final boolean TRIAL = false;

    private static final String FLEX = "flex-spot";
    private static final String[] AREAS = {"multi", "center", FLEX};
    private static final String[] AREA_NAMES = {"Wide", "Center", "Flexible Spot"};

    final BoxView view;
    private final SharedPreferences prefs;
    private final Handler ui;
    private CameraEx cam;
    private CameraEx.FocusAreaInfos[] infos;       // last answer of getFocusAreaInfos
    private String area;                           // the camera's current focus area
    private String appArea;                        // chosen in the app this session (null = not touched)
    private int[] appXy;                           // where the app moved the spot
    private int[] spot;                            // where the camera says it is
    private boolean dumped;

    Focus(Context ctx, SharedPreferences prefs, Handler ui) {
        this.prefs = prefs;
        this.ui = ui;
        view = new BoxView(ctx);
    }

    // ---------------------------------------------------------------- camera opened / closed

    private final Runnable refreshLater = new Runnable() {
        public void run() { refresh(); }
    };

    /** After the camera opened: puts back what an abrupt exit left, then this session's own choice. */
    void open(CameraEx c) {
        cam = c;
        view.clear();
        if (prefs.contains("userFocusArea")) restoreUser();
        if (appArea != null) {
            saveUser();
            setArea(appArea);
            if (appXy != null) setXy(appXy[0], appXy[1]);
        }
        try {
            cam.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                public void onDone(final int status, final int[] areas, CameraEx x) {
                    ui.post(new Runnable() { public void run() { focusResult(status, areas); } });
                }
            });
        } catch (Throwable t) { log("no focus result: " + t); }
        try {
            cam.setFocusAreaListener(new CameraEx.FocusAreaListener() {
                public void onChanged(CameraEx.FocusAreaInfos i, CameraEx x) { ui.post(refreshLater); }
            });
        } catch (Throwable t) { log("no focus area events: " + t); }
        ui.removeCallbacks(refreshLater);
        ui.postDelayed(refreshLater, 700);          // after the live view has started
    }

    /** Before the camera is released: the user's own focus area and spot go back. */
    void close() {
        ui.removeCallbacks(refreshLater);
        if (cam == null) return;
        try { cam.setAutoFocusDoneListener(null); } catch (Throwable ignored) {}
        try { cam.setFocusAreaListener(null); } catch (Throwable ignored) {}
        if (prefs.contains("userFocusArea")) restoreUser();
        cam = null;
        view.clear();
        flush.run();
    }

    /** The camera failed and is released without close(): the user's focus area stays saved for the next open(). */
    void drop() {
        ui.removeCallbacks(refreshLater);
        if (cam == null) return;
        cam = null;
        view.clear();
        flush.run();
    }

    private CameraEx.ParametersModifier read() {
        return cam.createParametersModifier(cam.getNormalCamera().getParameters());
    }

    /** Remembers the user's own setting, once, before the app's first change. */
    private void saveUser() {
        if (prefs.contains("userFocusArea")) return;
        try {
            CameraEx.ParametersModifier m = read();
            SharedPreferences.Editor e = prefs.edit().putString("userFocusArea", m.getFocusAreaMode());
            int[] xy = xy(m);
            if (xy != null) e.putInt("userFocusX", xy[0]).putInt("userFocusY", xy[1]);
            e.commit();
        } catch (Throwable t) { log("save failed: " + t); }
    }

    private void restoreUser() {
        String a = prefs.getString("userFocusArea", null);
        boolean hasXy = prefs.contains("userFocusX");
        int x = prefs.getInt("userFocusX", 0), y = prefs.getInt("userFocusY", 0);
        if (hasXy && (FLEX.equals(a) || FLEX.equals(appArea))) {   // the spot can only be set in flexible spot
            setArea(FLEX);
            setXy(x, y);
        }
        boolean ok = a == null || setArea(a);
        log("restored " + a + (hasXy ? ", spot " + x + "," + y : "") + (ok ? "" : " REFUSED"));
        if (ok) prefs.edit().remove("userFocusArea").remove("userFocusIndex").remove("userFocusX").remove("userFocusY").commit();
    }

    // ---------------------------------------------------------------- camera calls (true = it took)

    private boolean setArea(String a) {
        try {
            Camera.Parameters p = cam.createEmptyParameters();
            cam.createParametersModifier(p).setFocusAreaMode(a);
            cam.getNormalCamera().setParameters(p);
            String got = read().getFocusAreaMode();
            log("area " + a + " -> " + got);
            return a.equals(got);
        } catch (Throwable t) { log("area " + a + " failed: " + t); return false; }
    }

    /** Moves the spot; returns where the camera put it (it rounds), or null if it did not say. */
    private int[] setXy(int x, int y) {
        try {
            Camera.Parameters p = cam.createEmptyParameters();
            cam.createParametersModifier(p).setFocusPoint(x, y);
            cam.getNormalCamera().setParameters(p);
            int[] got = xy(read());
            log("spot " + x + "," + y + " -> " + (got == null ? "?" : got[0] + "," + got[1]));
            if (got != null) spot = got;
            return got;
        } catch (Throwable t) { log("spot " + x + "," + y + " failed: " + t); return null; }
    }

    private static int[] xy(CameraEx.ParametersModifier m) {
        try {
            android.util.Pair p = m.getFocusPoint();
            return p == null ? null : new int[]{((Number) p.first).intValue(), ((Number) p.second).intValue()};
        } catch (Throwable t) { return null; }
    }

    // ---------------------------------------------------------------- boxes

    /** Reads the focus area and its boxes, and shows them. */
    void refresh() {
        if (cam == null) return;
        boolean wide = false, mf = false;
        try {
            Camera.Parameters p = cam.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m = cam.createParametersModifier(p);
            area = m.getFocusAreaMode();
            spot = xy(m);
            wide = "16:9".equals(p.get("image-aspect"));
            mf = "manual".equals(p.getFocusMode());
        } catch (Throwable t) { log("read failed: " + t); }
        try { infos = cam.getFocusAreaInfos(wide ? 1 : 0); } catch (Throwable t) { infos = null; log("no boxes: " + t); }
        dump();
        view.aspect = wide ? 16f / 9 : 1.5f;
        showBoxes(mf);
    }

    private void showBoxes(boolean mf) {
        List<Rect> boxes = new ArrayList<Rect>();
        if (FLEX.equals(area)) {
            Rect r = flexBox(2);
            if (r != null && spot != null) {
                int w = r.width() / 2, h = r.height() / 2;
                boxes.add(new Rect(spot[0] - w, spot[1] - h, spot[0] + w, spot[1] + h));
            }
        } else {
            for (CameraEx.FocusAreaRectInfo r : rects(area)) boxes.add(r.rect);
        }
        boolean wide = "multi".equals(area) || "wide".equals(area);
        // Wide: which boxes locked (their position in the list + 1 is their number). Otherwise: one box.
        view.show(mf || !wide && boxes.size() != 1 ? null : boxes, wide);
    }

    /** Flexible spot: 1 = the range it can move in, 2 = its size. */
    private Rect flexBox(int index) {
        for (CameraEx.FocusAreaRectInfo r : rects(FLEX)) if (r.index == index) return r.rect;
        return null;
    }

    private static List<CameraEx.FocusAreaRectInfo> rectsOf(CameraEx.FocusAreaInfos i) {
        List<CameraEx.FocusAreaRectInfo> out = new ArrayList<CameraEx.FocusAreaRectInfo>();
        if (i != null && i.rectInfos != null)
            for (CameraEx.FocusAreaRectInfo r : i.rectInfos)
                if (r != null && r.index > 0 && r.rect != null && !r.rect.isEmpty()) out.add(r);   // 0 = the frame around all
        return out;
    }

    private List<CameraEx.FocusAreaRectInfo> rects(String mode) {
        if (infos != null && mode != null)
            for (CameraEx.FocusAreaInfos i : infos) if (i != null && mode.equals(i.focusAreaMode)) return rectsOf(i);
        return new ArrayList<CameraEx.FocusAreaRectInfo>();
    }

    private void focusResult(int status, int[] areas) {
        StringBuilder sb = new StringBuilder("focus result " + status + " areas");
        if (areas != null) for (int a : areas) sb.append(' ').append(a);
        log(sb.toString());
        // AutoFocusDoneListener: 0 clear, 1 locked, 2 not in focus, 3 working, 4 continuous
        view.result(status == 1 || status == 4 ? areas == null ? new int[0] : areas : null, status == 2);
    }

    void clearResult() { view.result(null, false); }

    // ---------------------------------------------------------------- menu

    String areaName() {
        if (area == null) return "-";
        for (int i = 0; i < AREAS.length; i++) if (AREAS[i].equals(area)) return AREA_NAMES[i];
        return area;
    }

    /** Next / previous focus area the camera offers. Returns a note to show. */
    String cycleArea(int dir) {
        if (cam == null) return "Focus area: change it while shooting";
        List ok = null;
        try { ok = read().getSupportedFocusAreaModes(); } catch (Throwable ignored) {}
        int at = 0;
        for (int i = 0; i < AREAS.length; i++) if (AREAS[i].equals(area)) at = i;
        saveUser();
        for (int n = 0; n < AREAS.length; n++) {
            at = (at + dir + AREAS.length) % AREAS.length;
            if (ok != null && !ok.contains(AREAS[at])) continue;
            if (setArea(AREAS[at])) { appArea = AREAS[at]; appXy = null; break; }
        }
        refresh();
        return "Focus area: " + areaName();
    }

    // ---------------------------------------------------------------- moving the spot

    private static final int STEP_X = 60, STEP_Y = 80;     // of 2000: about 1/33 of the width, 1/25 of the height
    private String backArea;                               // where Trash (cancel) goes back to
    private int[] backXy;

    /** Switches to flexible spot if needed. Returns null if moving can start, else why not. */
    String startMove() {
        if (cam == null) return "Focus point: move it while shooting";
        refresh();
        backArea = area;
        backXy = spot;
        saveUser();
        if (!FLEX.equals(area)) {
            if (!setArea(FLEX)) return "Flexible Spot is not available here";
            appArea = FLEX;
            refresh();
        }
        if (spot == null) { cancelMove(); return "The focus point cannot be moved here"; }
        log("move start at " + spot[0] + "," + spot[1] + " from " + backArea);
        view.moving(true);
        return null;
    }

    /** Keeps the new position. */
    void endMove() {
        log("move done");
        view.moving(false);
    }

    /** Puts back the focus area and the position from before moving. */
    void cancelMove() {
        log("move cancelled");
        view.moving(false);
        if (cam == null) return;
        if (backXy != null && appXy != null) { setXy(backXy[0], backXy[1]); appXy = backXy; }
        if (backArea != null && !backArea.equals(area) && setArea(backArea)) appArea = backArea;
        refresh();
    }

    /** One step. Returns a note if the camera did not move it, else null. */
    String move(int dx, int dy) {
        if (cam == null || spot == null) return null;
        int x = spot[0] + dx * STEP_X, y = spot[1] + dy * STEP_Y;
        Rect range = flexBox(1), size = flexBox(2);
        if (range != null && size != null) {               // the whole box stays inside the range
            x = Math.max(range.left + size.width() / 2, Math.min(range.right - size.width() / 2, x));
            y = Math.max(range.top + size.height() / 2, Math.min(range.bottom - size.height() / 2, y));
        }
        if (x == spot[0] && y == spot[1]) return null;     // at the edge
        int[] from = spot, got = setXy(x, y);
        if (got == null) return "The focus point did not move";
        appXy = got;
        showBoxes(false);
        return got[0] == from[0] && got[1] == from[1] && Math.abs(x - from[0]) + Math.abs(y - from[1]) > 30 ? "The focus point did not move" : null;
    }

    // ---------------------------------------------------------------- diagnostics

    private final StringBuilder pending = new StringBuilder();

    private void log(String s) {
        Diag.trace("focus: " + s);
        if (!TRIAL && !Diag.verbose()) return;
        synchronized (pending) { pending.append(s).append('\n'); }
        ui.removeCallbacks(flush);
        ui.postDelayed(flush, 1500);
    }

    private final Runnable flush = new Runnable() {
        public void run() {
            new Thread(new Runnable() {
                public void run() {
                    String s;
                    synchronized (pending) { s = pending.toString(); pending.setLength(0); }
                    if (s.length() > 0) Diag.write("FOCUS.TXT", s);
                }
            }, "filmlut-focuslog").start();
        }
    };

    /** Once per launch: everything the camera says about focus areas. */
    private void dump() {
        if (dumped || cam == null || (!TRIAL && !Diag.verbose())) return;
        dumped = true;
        StringBuilder sb = new StringBuilder("focus areas, " + android.os.Build.MODEL + "\n");
        try {
            CameraEx.ParametersModifier m = read();
            int[] p = xy(m);
            sb.append("mode ").append(m.getFocusAreaMode()).append(" of ").append(m.getSupportedFocusAreaModes())
              .append(" index ").append(m.getFocusPointIndex()).append(" point ").append(p == null ? "?" : p[0] + "," + p[1]).append('\n');
        } catch (Throwable t) { sb.append("parameters: ").append(t).append('\n'); }
        for (int arg = 0; arg < 2; arg++) {
            try {
                CameraEx.FocusAreaInfos[] a = cam.getFocusAreaInfos(arg);
                sb.append("getFocusAreaInfos(").append(arg).append("): ").append(a == null ? "null" : a.length + " modes").append('\n');
                if (a != null) for (CameraEx.FocusAreaInfos i : a) {
                    if (i == null) continue;
                    sb.append("  ").append(i.focusAreaMode).append(':');
                    if (i.rectInfos != null) for (CameraEx.FocusAreaRectInfo r : i.rectInfos)
                        if (r != null) sb.append(' ').append(r.index).append(r.enable ? "+" : "-").append(r.rect == null ? "null" : r.rect.flattenToString().replace(' ', ','));
                    sb.append('\n');
                }
            } catch (Throwable t) { sb.append("getFocusAreaInfos(").append(arg).append("): ").append(t).append('\n'); }
        }
        sb.append("screen ").append(view.getWidth()).append('x').append(view.getHeight());
        log(sb.toString());
    }

    // ---------------------------------------------------------------- overlay

    /** Draws the boxes over the live view (which is letterboxed to the picture's shape). */
    static final class BoxView extends View {
        private static final int GREEN = 0xFF30D060, GREY = 0xFFD0D0D0, AMBER = 0xFFFFB020, DARK = 0xFF101010;
        private final Paint paint = new Paint();
        private List<Rect> rects;
        private boolean wide, moving, failed;
        private int[] focused;                     // null = not in focus; otherwise the wide boxes that locked
        float aspect = 1.5f;

        BoxView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        void clear() { rects = null; focused = null; failed = false; moving = false; invalidate(); }

        void show(List<Rect> r, boolean wide) {
            rects = r;
            this.wide = wide;
            invalidate();
        }

        void result(int[] f, boolean failed) { focused = f; this.failed = failed; invalidate(); }

        void moving(boolean m) { moving = m; focused = null; invalidate(); }

        /** -1000..1000 over the picture, which is letterboxed on the screen. */
        private RectF toScreen(Rect r) {
            float w = getWidth(), h = w / aspect;
            if (h > getHeight()) { h = getHeight(); w = h * aspect; }
            float x0 = (getWidth() - w) / 2, y0 = (getHeight() - h) / 2;
            return new RectF(x0 + (r.left + 1000) / 2000f * w, y0 + (r.top + 1000) / 2000f * h,
                    x0 + (r.right + 1000) / 2000f * w, y0 + (r.bottom + 1000) / 2000f * h);
        }

        private void box(Canvas c, RectF r, int color) {
            paint.setColor(DARK);
            paint.setStrokeWidth(4);
            c.drawRect(r, paint);
            paint.setColor(color);
            paint.setStrokeWidth(2);
            c.drawRect(r, paint);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (rects == null) return;
            if (!wide) {
                for (Rect r : rects) box(c, toScreen(r), focused != null ? GREEN : moving ? AMBER : failed ? 0xFF909090 : GREY);
            } else if (focused != null) {
                for (int f : focused) if (f >= 1 && f <= rects.size()) box(c, toScreen(rects.get(f - 1)), GREEN);
            }
        }
    }
}
