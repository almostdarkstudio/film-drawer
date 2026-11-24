package dev.filmlut.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.didep.Gpelibrary;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Film Drawer.
 *
 * SHOOT (default): live view; half-press focuses, shutter takes the picture as the camera
 *   normally would; each new JPEG is queued and converted with the current look in the
 *   background, and a quick review of the look on it is shown. Wheel / up-down: change look
 *   (a card shows the last shot in that look). Left-right: jump between look groups.
 *   OK: help. PLAY: browse. MENU: menu (Exit application there; unfinished conversions resume next time).
 * BROWSE: photo with a preview of the look (or the saved result, if already converted).
 *   Left-right: photo. Wheel / up-down: look. OK: convert. Trash: before/after.
 *   PLAY or half-press: back to shooting. Fn: all photos / converted (FILMLUT/). MENU: menu.
 * MENU (both modes): settings, converted photos, help, about, Exit application.
 *
 * Originals are never modified; results go to FILMLUT/<folder>/<TAG><number>.JPG.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, JobQueue.Listener {
    // Sony scan codes (OpenMemories-Framework stubs, ScalarInput).
    private static final int K_UP = 103, K_DOWN = 108, K_LEFT = 105, K_RIGHT = 106, K_ENTER = 232;
    private static final int K_MENU = 514, K_SK2 = 513, K_DELETE = 595, K_PLAY = 207;
    private static final int K_S1 = 516, K_S1_2 = 517, K_S2 = 518;
    private static final int D1_CW = 525, D1_CCW = 526, D2_CW = 528, D2_CCW = 529;
    private static final int WHEEL_CW = 522, WHEEL_CCW = 523, D3_CW = 635, D3_CCW = 634, K_FN = 520;
    // a7R II, found with 0.24's input diagnostics: EV dial (position from ScalarInput.getKeyStatus:
    // 1/3-stop clicks, 0x200 = negative), and the AF/MF-AEL button. Names from the camera (INPUT.TXT):
    // ISV_KEY_EV_COMPENSATION = 620, ISV_KEY_AFMF = 533 (lever at AF/MF), ISV_KEY_AEL = 532 (lever at AEL).
    private static final int K_EV_DIAL = 620, K_AF_MF = 533, K_AEL = 532;

    /** In-camera base while shooting: a known starting point for the LUTs. "camera" = leave as is. */
    private static final String[] BASES = {"standard", "neutral", "camera"};
    private static final String[] BASE_NAMES = {"Standard", "Neutral", "Camera settings"};

    /** JPEG size while shooting in the app (RAW is always full size). Sony spells medium "midium". */
    private static final String[] SIZES = {"midium", "small", "large"};
    private static final String[] SIZE_NAMES = {"M 18MP", "S 11MP", "L 42MP"};
    private static final int K_C1 = 622;
    private int sizeIndex;

    /** Grain: Auto = the look's own default (looks.txt), otherwise a fixed strength. C2 cycles. */
    private static final String[] GRAIN_NAMES = {"Auto", "Off", "Low", "Medium", "High"};
    private static final int[] GRAIN_VALUES = {-1, 0, 25, 50, 80};
    private static final int K_C2 = 623;
    private int grainIndex;

    private int grainFor(Looks.Look lk) {
        int g = GRAIN_VALUES[grainIndex];
        return g < 0 ? lk.grain : g;
    }

    /** What a shutter press asked for: the look and grain on screen at that moment. */
    private static final class Shot {
        final Looks.Look look;
        final int grain;
        final long time;   // shutter press (ms), 0 = no press matched (e.g. extra burst frames)
        Shot(Looks.Look look, int grain, long time) { this.look = look; this.grain = grain; this.time = time; }
    }

    private static final int PREVIEW_MAX_W = 640, PREVIEW_MAX_H = 480;
    private static final int THUMB_W = 240, THUMB_H = 160;
    private static final int CARD_MS = 2500, HELP_MS = 4000, NOTE_MS = 3000;
    /** Quick review after a shot (menu): off, 2 s, 4 s. */
    private static final String[] REVIEW_NAMES = {"Off", "2 s", "4 s"};
    private static final int[] REVIEW_VALUES = {0, 2000, 4000};
    private int reviewIndex = 1;

    private final Handler ui = new Handler();
    private SharedPreferences prefs;
    private Looks looks;
    private JobQueue jobs;
    private volatile int lookIndex;   // read by the shot watcher thread
    private int baseIndex;

    // views
    private SurfaceView live;
    private ImageView photoView;
    private TextView topLeft, topRight, bottom, centre, exposure;
    private android.widget.LinearLayout card;     // look picker: last shot in the new look + name
    private ImageView cardImage;
    private TextView cardText, cardGroup, cardStrip;
    private static final int ACCENT = 0xFFFFB020;   // amber: the current group
    private boolean helpVisible;
    private String note;                          // short corner message ("Saved ..."), cleared after NOTE_MS
    private long noteUntil;
    private boolean reviewing;                    // quick review covering the live view
    private volatile File lastShot;               // newest JPEG on the card (look picker thumbnail)
    private volatile long lastShutterMs;
    private boolean s1Down;
    private int swallowUp;                        // key whose release is ignored (it only dismissed the review)

    // shoot mode
    private boolean browsing;
    private CameraEx camera;
    private boolean surfaceReady;
    private final Set<String> knownJpegs = new HashSet<String>();
    private final Map<String, Long> growing = new HashMap<String, Long>();   // new file -> last size (watcher thread only)
    private volatile long watchUntil;
    /** Look + grain chosen at each shutter press, oldest first; the next new JPEG takes the oldest one. */
    private final java.util.LinkedList<Shot> shotLooks = new java.util.LinkedList<Shot>();
    private volatile boolean knownLoaded;

    // browse mode
    private List<File> photos;
    private int photoIndex;
    private boolean showOriginal;
    private final Object previewLock = new Object();
    private String previewPath;      // photo whose original pixels are cached
    private int[] previewPixels;     // [w, h, argb...]
    private int previewGen;
    private Thread previewThread;
    private Runnable previewRequest;

    private long exitArmedUntil;
    private String appVersion = "?";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("filmlut", MODE_PRIVATE);
        try { appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        Diag.install(appVersion);
        buildViews();
        try {
            looks = new Looks(getAssets());
        } catch (Exception e) {
            centre.setText("Cannot load looks: " + e);
            return;
        }
        lookIndex = Math.max(0, Math.min(looks.all.size() - 1, prefs.getInt("look", 1)));
        baseIndex = Math.max(0, Math.min(BASES.length - 1, prefs.getInt("base", 0)));
        sizeIndex = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("size", 0)));
        grainIndex = Math.max(0, Math.min(GRAIN_NAMES.length - 1, prefs.getInt("grain", 0)));
        reviewIndex = Math.max(0, Math.min(REVIEW_NAMES.length - 1, prefs.getInt("review", 1)));
        if (Native.LOAD_ERROR != null) {
            centre.setText("Native library failed to load:\n" + Native.LOAD_ERROR);
            return;
        }
        // Conversions left unfinished last time (app closed while busy) carry on first.
        List<JobQueue.Job> resumed = JobQueue.restore(prefs, looks);
        jobs = new JobQueue(looks, this, prefs);
        for (JobQueue.Job j : resumed) jobs.add(j);
        if (!resumed.isEmpty()) setNote("Resuming " + resumed.size() + " unfinished conversion" + (resumed.size() > 1 ? "s" : ""));
        startPreviewThread();
        // Remember which photos already exist, so only new shots are queued.
        new Thread(new Runnable() {
            public void run() {
                Set<String> s = new HashSet<String>();
                List<File> all = Photos.allJpegs();
                for (File f : all) s.add(f.getAbsolutePath());
                synchronized (knownJpegs) { knownJpegs.addAll(s); }
                if (lastShot == null && !all.isEmpty()) lastShot = all.get(all.size() - 1);
                knownLoaded = true;
                Diag.trace("scan done: " + all.size() + " JPEGs, newest " + (lastShot == null ? "none" : lastShot.getName()));
            }
        }, "filmlut-scan").start();
        startShotWatcher();
        showHelp();
        ui.post(statusTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Tell the camera which app is in front (as PMCADemo's BaseActivity does).
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
        if (looks == null || jobs == null) return;
        if (browsing) enterBrowse(); else enterShoot();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Diag.trace("pause");
        Diag.dumpTrace("left the app (pause)");
        closeCamera();
        if (prefs != null) prefs.edit().putInt("look", lookIndex).putInt("base", baseIndex).putInt("size", sizeIndex).putInt("grain", grainIndex).putInt("review", reviewIndex).commit();
    }

    // ---------------------------------------------------------------- views

    private TextView label(int gravity, float size) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0x99000000);
        t.setPadding(10, 4, 10, 4);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        live = new SurfaceView(this);
        live.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        live.getHolder().addCallback(this);
        root.addView(live, new FrameLayout.LayoutParams(-1, -1));
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setBackgroundColor(Color.BLACK);
        photoView.setVisibility(View.GONE);
        root.addView(photoView, new FrameLayout.LayoutParams(-1, -1));
        // One slim bar at the top (look + settings left, conversion status right), exposure at the
        // bottom left, help above it only for a few seconds, messages in the centre only for errors.
        topLeft = label(Gravity.TOP | Gravity.LEFT, 17);
        topLeft.setSingleLine(true);
        topRight = label(Gravity.TOP | Gravity.RIGHT, 15);
        topRight.setSingleLine(true);
        topRight.setVisibility(View.GONE);
        bottom = label(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 14);
        bottom.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bottom.getLayoutParams();
        blp.bottomMargin = 40;
        bottom.setLayoutParams(blp);
        centre = label(Gravity.CENTER, 18);
        centre.setGravity(Gravity.CENTER);
        centre.setVisibility(View.GONE);
        exposure = label(Gravity.BOTTOM | Gravity.LEFT, 17);

        // Look picker card (shoot mode): the last shot through the newly chosen look.
        card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundColor(0xCC000000);
        card.setPadding(6, 6, 6, 6);
        // Group name on top (amber, with left/right hints), thumbnail, look name + "2 of 5",
        // then a strip of all groups with the current one highlighted.
        cardGroup = new TextView(this);
        cardGroup.setTextSize(17);
        cardGroup.setTextColor(ACCENT);
        cardGroup.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cardGroup.setGravity(Gravity.CENTER);
        cardGroup.setSingleLine(true);
        card.addView(cardGroup, new android.widget.LinearLayout.LayoutParams(THUMB_W, -2));
        cardImage = new ImageView(this);
        cardImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(THUMB_W, THUMB_H);
        ilp.topMargin = 4;
        card.addView(cardImage, ilp);
        cardText = new TextView(this);
        cardText.setTextSize(16);
        cardText.setTextColor(Color.WHITE);
        cardText.setGravity(Gravity.CENTER);
        card.addView(cardText, new android.widget.LinearLayout.LayoutParams(THUMB_W, -2));
        cardStrip = new TextView(this);
        cardStrip.setTextSize(11);
        cardStrip.setTextColor(0xFF8A8A8A);
        cardStrip.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(THUMB_W, -2);
        slp.topMargin = 4;
        card.addView(cardStrip, slp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        clp.bottomMargin = 40;
        clp.rightMargin = 8;
        card.setVisibility(View.GONE);

        root.addView(exposure);
        root.addView(topLeft);
        root.addView(topRight);
        root.addView(bottom);
        root.addView(card, clp);
        menuView = buildMenuView();
        root.addView(menuView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(centre);
        setContentView(root);
    }

    private final Runnable hideHelp = new Runnable() {
        public void run() { helpVisible = false; updateTexts(); }
    };

    /** Shows the key help for a few seconds (at start, on entering a mode, and on OK in shoot mode). */
    private void showHelp() {
        helpVisible = true;
        updateTexts();
        ui.removeCallbacks(hideHelp);
        ui.postDelayed(hideHelp, HELP_MS);
    }

    /** Short message in the top-right corner (instead of covering the frame). */
    private void setNote(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + NOTE_MS;
        if (topRight != null) updateStatus();
    }

    private final Runnable hideCard = new Runnable() {
        public void run() { card.setVisibility(View.GONE); cardImage.setImageBitmap(null); updateDepth(); }
    };

    private final Runnable endReview = new Runnable() {
        public void run() { hideReview(); }
    };

    private void hideReview() {
        ui.removeCallbacks(endReview);
        if (!reviewing) return;
        reviewing = false;
        Diag.trace("review hidden");
        if (!browsing) {
            photoView.setVisibility(View.GONE);
            photoView.setImageBitmap(null);
        }
        updateTexts();
        updateDepth();
    }

    /**
     * Overlay colour depth. Shoot mode normally uses RGBA4444 (as PMCADemo's camera screen, so the
     * live view shows through); while a photo is on screen (review, look card, browse) 8888, so it is
     * not posterised.
     */
    private void updateDepth() {
        // 0.14 also switched to 8888 for the review / look card while live view ran; the app then
        // stalled and crashed on the camera, so shoot mode always stays at 4444 (as up to 0.13).
        setColorDepth(browsing);
    }

    private Boolean depthHigh;

    private void setColorDepth(boolean high) {
        if (depthHigh != null && depthHigh == high) return;
        depthHigh = high;
        Diag.trace("colour depth " + (high ? "8888" : "4444"));
        try {
            Gpelibrary.changeFrameBufferPixel(high ? Gpelibrary.GS_FRAMEBUFFER_TYPE.ABGR8888
                    : Gpelibrary.GS_FRAMEBUFFER_TYPE.RGBA4444);
        } catch (Throwable ignored) {
            // Not fatal: only affects how the overlay blends with the live view.
        }
    }

    private final Runnable hideCentre = new Runnable() {
        public void run() { centre.setVisibility(View.GONE); }
    };

    private void flash(String msg, int millis) {
        centre.setText(msg);
        centre.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideCentre);
        if (millis > 0) ui.postDelayed(hideCentre, millis);
    }

    private Looks.Look look() { return looks.all.get(lookIndex); }

    private static final String[] BASE_SHORT = {"Std", "Neutral", "Cam"};
    private static final String[] SIZE_SHORT = {"M", "S", "L"};
    private static final String SEP = "  ·  ";

    private String grainShort() {
        return "Grain " + (grainIndex == 0 ? "Auto" : GRAIN_NAMES[grainIndex]);
    }

    private void updateTexts() {
        if (looks == null) return;
        if (browsing && showConverted) {
            Looks.Look lk = photos == null || photos.isEmpty() ? null : lookOf(photos.get(photoIndex));
            topLeft.setText("Converted" + (lk == null ? "" : SEP + lk.name));
        } else {
            topLeft.setText(look().name + SEP + grainShort()
                    + (browsing ? "" : SEP + SIZE_SHORT[sizeIndex] + SEP + BASE_SHORT[baseIndex]));
        }
        if (reviewing) {
            bottom.setVisibility(View.GONE);
            return;
        }
        String text;
        if (!browsing) {
            text = helpVisible
                    ? "Film Drawer " + appVersion + "  (free, MIT licence)\n"
                      + "Wheel/Up/Down: look   Left/Right: group   Fn: base   C1: size   C2: grain\n"
                      + "Front: aperture   Rear: shutter/EV   OK: help   PLAY: browse   MENU: menu"
                    : null;
        } else if (photos == null || photos.isEmpty()) {
            text = (showConverted ? "No converted photos yet    Fn: all photos" : "No JPEGs on the card    Fn: converted")
                    + "    PLAY: shoot    MENU: menu";
        } else if (showConverted) {
            File p = photos.get(photoIndex);
            text = p.getParentFile().getName() + "/" + p.getName() + "  " + (photoIndex + 1) + "/" + photos.size()
                    + (helpVisible ? "\nLeft/Right/Wheel: photo   Fn: all photos   MENU: menu   PLAY: shoot" : "");
        } else {
            File p = photos.get(photoIndex);
            boolean done = Photos.outputFor(p, look().tag).exists();
            text = p.getName() + "  " + (photoIndex + 1) + "/" + photos.size()
                    + (showOriginal ? "    ORIGINAL" : done ? "    showing the converted file" : "    preview")
                    + (helpVisible ? "\nLeft/Right: photo   Wheel: look   OK: convert   Trash: before/after   Fn: converted   PLAY: shoot" : "");
        }
        if (text == null) {
            bottom.setVisibility(View.GONE);
        } else {
            bottom.setText(text);
            bottom.setVisibility(View.VISIBLE);
        }
    }

    /** Top-right corner: conversion progress, else a recent note, else failures; hidden when idle. */
    private void updateStatus() {
        if (jobs == null) return;
        JobQueue.Job cur = jobs.current();
        String s = null;
        if (cur != null) {
            s = "Converting " + (Native.progress() / 10) + "%";
            int waiting = jobs.pending() - 1;
            if (waiting > 0) s += "  +" + waiting;
        }
        if (note != null && System.currentTimeMillis() < noteUntil) s = s == null ? note : note + SEP + s;
        else note = null;
        if (s == null && jobs.failed() > 0) s = jobs.failed() + " failed";
        if (s == null) {
            topRight.setVisibility(View.GONE);
        } else {
            topRight.setText(s);
            topRight.setVisibility(View.VISIBLE);
        }
    }

    /** Status and exposure, refreshed twice a second. */
    private final Runnable statusTick = new Runnable() {
        public void run() {
            updateStatus();
            updateExposureText();
            ui.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- shoot mode

    private void enterShoot() {
        Diag.trace("enter shoot");
        synchronized (previewLock) { convCache.clear(); prefetchTask = null; }   // free ~4 MB of browse cache
        boolean wasBrowsing = browsing;
        browsing = false;
        showOriginal = false;
        reviewing = false;
        photoView.setVisibility(View.GONE);
        photoView.setImageBitmap(null);
        updateDepth();                 // RGBA4444, as PMCADemo's camera screen: lets the live view show through
        openCamera();
        if (wasBrowsing) showHelp(); else updateTexts();
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            camera = CameraEx.open(0, null);
            if (Diag.verbose()) dumpCameraParameters();   // FILMLUT/DEBUG.TXT on the card turns it on
            restoreUserFocus();                            // left over from an abrupt exit
            restoreUserSharpness();
            applyBase();
            startLiveView();
            applyEvDial(false);                            // match the EV dial's position
        } catch (Throwable t) {
            camera = null;
            flash("Camera unavailable:\n" + t + "\nPLAY: browse photos", 0);
        }
    }

    private void startLiveView() {
        if (camera == null || !surfaceReady || browsing) return;
        try {
            camera.getNormalCamera().setPreviewDisplay(live.getHolder());
            camera.getNormalCamera().startPreview();
        } catch (Throwable t) {
            flash("Live view failed: " + t, 4000);
        }
    }

    // ---- focus mode (menu): AF / MF / DMF via Camera.Parameters "focus-mode" (manual, auto, dmf).
    private static final String[] FOCUS_MODES = {"auto", "manual", "dmf"};
    private static final String[] FOCUS_NAMES = {"AF", "MF", "DMF"};

    private String currentFocusMode() {
        try { return camera.getNormalCamera().getParameters().getFocusMode(); } catch (Throwable t) { return null; }
    }

    private String focusName() {
        if (camera == null) return "(shooting only)";
        String m = currentFocusMode();
        for (int i = 0; i < FOCUS_MODES.length; i++) if (FOCUS_MODES[i].equals(m)) return FOCUS_NAMES[i];
        return m == null ? "?" : m;
    }

    /** Steps the focus mode. Your own mode is saved first and put back when the camera closes. */
    /** AF/MF button: toggles AF <-> MF (your own mode is still restored on exit). */
    private void toggleAfMf() {
        if (camera == null) return;
        try {
            String cur = currentFocusMode();
            String target = "manual".equals(cur) ? "auto" : "manual";
            if (!prefs.contains("userFocusMode") && cur != null) prefs.edit().putString("userFocusMode", cur).commit();
            setFocusMode(target);
        } catch (Throwable t) {
            flash("Focus mode: " + t, 3000);
        }
        updateExposureText();
    }

    /** EV dial: the app's exposure compensation follows the dial's position (1/3-stop clicks). */
    private void applyEvDial(boolean announce) {
        if (camera == null) return;
        Integer st = Diag.keyStatusValue(K_EV_DIAL);
        if (st == null) return;
        int v = st & 0x1FF;
        if ((st & 0x200) != 0) v = -v;
        try {
            android.hardware.Camera.Parameters cur = camera.getNormalCamera().getParameters();
            v = Math.max(cur.getMinExposureCompensation(), Math.min(cur.getMaxExposureCompensation(), v));
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            p.setExposureCompensation(v);
            camera.getNormalCamera().setParameters(p);
            Diag.trace("EV dial " + st + " -> " + v);
        } catch (Throwable t) {
            Diag.trace("EV dial failed: " + t);
        }
        updateExposureText();
    }

    /** AEL button: exposure held while pressed (as the camera's default "AEL hold"). */
    private void setAeLock(boolean lock) {
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            p.set("ae-lock", lock ? "locked" : "unlocked");
            camera.getNormalCamera().setParameters(p);
            aeLocked = lock;
        } catch (Throwable t) {
            Diag.trace("AEL failed: " + t);
        }
        updateExposureText();
    }

    private boolean aeLocked;

    /**
     * Sets the focus mode, trying (1) a parameter set with only focus-mode (as for EV), then (2) the
     * full current parameters with focus-mode changed (some firmwares want that). Reads it back after
     * each. If neither takes, writes FILMLUT/CAMERAEX.TXT (Sony's camera methods) to find the right way.
     */
    private void setFocusMode(String target) {
        String how = null;
        try {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            p.setFocusMode(target);
            camera.getNormalCamera().setParameters(p);
            if (target.equals(currentFocusMode())) how = "partial";
        } catch (Throwable t) {
            Diag.trace("focus (partial) failed: " + t);
        }
        if (how == null) {
            try {
                android.hardware.Camera.Parameters p = camera.getNormalCamera().getParameters();
                p.setFocusMode(target);
                camera.getNormalCamera().setParameters(p);
                if (target.equals(currentFocusMode())) how = "full";
            } catch (Throwable t) {
                Diag.trace("focus (full) failed: " + t);
            }
        }
        Diag.trace("focus " + target + ": " + (how == null ? "refused" : "set via " + how + " parameters"));
        if (how == null) Diag.dumpCameraEx(camera);
        reportFocus(target);
    }

    /** After a focus change: read the mode back, so a refusal by the camera shows instead of a false "MF". */
    private void reportFocus(String wanted) {
        String got = currentFocusMode();
        String name = "manual".equals(got) ? "MF" : "dmf".equals(got) ? "DMF" : "auto".equals(got) ? "AF" : String.valueOf(got);
        Diag.trace("focus wanted " + wanted + ", camera reports " + got);
        if (wanted.equals(got)) setNote("Focus: " + name);
        else flash("Camera kept " + name + "\n(" + wanted + " refused)", 2500);
        updateExposureText();
    }

    private void cycleFocus(int dir) {
        if (camera == null) { setNote("Focus mode: change it while shooting"); return; }
        try {
            String cur = currentFocusMode();
            java.util.List<String> ok = camera.getNormalCamera().getParameters().getSupportedFocusModes();
            int i = 0;
            for (int k = 0; k < FOCUS_MODES.length; k++) if (FOCUS_MODES[k].equals(cur)) i = k;
            for (int n = 0; n < FOCUS_MODES.length; n++) {      // next supported mode
                i = ((i + dir) % FOCUS_MODES.length + FOCUS_MODES.length) % FOCUS_MODES.length;
                if (ok == null || ok.contains(FOCUS_MODES[i])) break;
            }
            if (!prefs.contains("userFocusMode") && cur != null) prefs.edit().putString("userFocusMode", cur).commit();
            setFocusMode(FOCUS_MODES[i]);
        } catch (Throwable t) {
            flash("Focus mode: " + t, 3000);
        }
    }

    private void restoreUserFocus() {
        String m = prefs.getString("userFocusMode", null);
        if (camera == null || m == null) return;
        try {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            p.setFocusMode(m);
            camera.getNormalCamera().setParameters(p);
            prefs.edit().remove("userFocusMode").commit();
        } catch (Throwable t) {
            flash("Could not restore your focus mode (" + m + "): " + t, 4000);
        }
    }

    private void closeCamera() {
        if (camera == null) return;
        if (aeLocked) setAeLock(false);                    // never leave exposure locked
        restoreUserFocus();
        restoreUserSharpness();
        restoreUserStyle();
        try {
            camera.release();
        } catch (Throwable ignored) {
        }
        camera = null;
    }

    // ---------------------------------------------------------------- in-camera base (Creative Style)

    /**
     * Applies the in-app shooting settings: base Creative Style (+ contrast/saturation 0, unless
     * "camera") and the JPEG size. The user's own values are saved to prefs FIRST and put back by
     * restoreUserStyle(); if a session ends abruptly, they stay in prefs and are restored the next
     * time the camera opens.
     */
    private void applyBase() {
        if (camera == null) return;
        if (prefs.getBoolean("userSaved", false)) restoreUserStyle();   // left over from an abrupt exit
        // Never overwrite saved originals with our own settings: if they could not be restored, stop here.
        if (prefs.getBoolean("userSaved", false)) return;
        String base = BASES[baseIndex];
        String size = SIZES[sizeIndex];
        boolean style = !"camera".equals(base);
        try {
            CameraEx.ParametersModifier cur = camera.createParametersModifier(camera.getNormalCamera().getParameters());
            List modes = cur.getSupportedColorModes();
            if (style && modes != null && !modes.contains(base)) {
                flash("Base '" + base + "' not supported here", 3000);
                style = false;
            }
            List sizes = cur.getSupportedImageSizes();
            boolean sized = sizes == null || sizes.contains(size);
            if (!sized) flash("JPEG size '" + size + "' not supported here", 3000);
            // DRO off with the base style: DRO Auto re-tones every scene differently, so the same
            // look would come out differently shot to shot.
            List dros = style ? cur.getSupportedDROModes() : null;
            boolean dro = style && (dros == null || dros.contains("off"));
            if (!style && !sized) return;
            prefs.edit().putBoolean("userSaved", true)
                    .putBoolean("userStyleSaved", style).putString("userMode", cur.getColorMode())
                    .putInt("userContrast", cur.getContrast()).putInt("userSaturation", cur.getSaturation())
                    .putBoolean("userSizeSaved", sized).putString("userImageSize", cur.getImageSize())
                    .putBoolean("userDroSaved", dro).putString("userDroMode", dro ? cur.getDROMode() : null)
                    .putInt("userDroLevel", dro ? cur.getDROLevel() : 0)
                    .commit();
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            if (style) {
                m.setColorMode(base);
                m.setContrast(0);
                m.setSaturation(0);
            }
            if (dro) m.setDROMode("off");
            if (sized) m.setImageSize(size);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            flash("Could not set shooting settings:\n" + t, 4000);
        }
        if (style) applyLookSharpness();
    }

    /**
     * Camera sharpness for the current look (looks.txt; e.g. CineStill 800T -3: film is softer than
     * digital, and less sharpening beats blurring afterwards). Separate from applyBase so a failure
     * here never affects the base style. The user's own sharpness is saved first (once per session)
     * and put back by restoreUserSharpness(). Only with a base style (not "Camera settings").
     */
    private void applyLookSharpness() {
        if (camera == null || "camera".equals(BASES[baseIndex]) || looks == null) return;
        try {
            if (!prefs.contains("userSharpness")) {
                CameraEx.ParametersModifier cur = camera.createParametersModifier(camera.getNormalCamera().getParameters());
                prefs.edit().putInt("userSharpness", cur.getSharpness()).commit();
            }
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setSharpness(look().sharpness);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            Diag.trace("sharpness not set: " + t);
        }
    }

    private boolean sharpPending;
    private final Runnable applySharpnessLater = new Runnable() {
        public void run() { sharpPending = false; if (!browsing) applyLookSharpness(); }
    };

    private void restoreUserSharpness() {
        if (camera == null || !prefs.contains("userSharpness")) return;
        try {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setSharpness(prefs.getInt("userSharpness", 0));
            camera.getNormalCamera().setParameters(p);
            prefs.edit().remove("userSharpness").commit();
        } catch (Throwable t) {
            flash("Could not restore your sharpness (" + prefs.getInt("userSharpness", 0) + "): " + t, 4000);
        }
    }

    private void restoreUserStyle() {
        if (camera == null || !prefs.getBoolean("userSaved", false)) return;
        try {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            // Older saves (0.6/0.7) have no flags: they only ever saved the style.
            if (prefs.getBoolean("userStyleSaved", true)) {
                String mode = prefs.getString("userMode", null);
                if (mode != null) m.setColorMode(mode);
                m.setContrast(prefs.getInt("userContrast", 0));
                m.setSaturation(prefs.getInt("userSaturation", 0));
            }
            String size = prefs.getString("userImageSize", null);
            if (prefs.getBoolean("userSizeSaved", false) && size != null) m.setImageSize(size);
            String droMode = prefs.getString("userDroMode", null);
            if (prefs.getBoolean("userDroSaved", false) && droMode != null) {
                m.setDROMode(droMode);
                if ("on".equals(droMode)) m.setDROLevel(prefs.getInt("userDroLevel", 1));
            }
            camera.getNormalCamera().setParameters(p);
            prefs.edit().putBoolean("userSaved", false).commit();
        } catch (Throwable t) {
            flash("Could not restore your camera settings:\n" + t
                    + "\nSaved: " + prefs.getString("userMode", "?") + " " + prefs.getInt("userContrast", 0)
                    + "/" + prefs.getInt("userSaturation", 0) + ", JPEG " + prefs.getString("userImageSize", "?")
                    + ", DRO " + prefs.getString("userDroMode", "?") + " " + prefs.getInt("userDroLevel", 0), 6000);
        }
    }

    private void cycleSize() { cycleSize(1); }

    private void cycleSize(int dir) {
        restoreUserStyle();
        sizeIndex = ((sizeIndex + dir) % SIZES.length + SIZES.length) % SIZES.length;
        applyBase();
        updateTexts();
        setNote("JPEG size: " + SIZE_NAMES[sizeIndex]);
    }

    /**
     * READ-ONLY diagnostic, once per launch: writes every camera parameter (Camera.Parameters.flatten)
     * and the method names of Sony's ParametersModifier to FILMLUT/PARAMS.TXT, to find out which
     * settings (e.g. Picture Profile) apps can reach on this body. Changes nothing on the camera.
     */
    private boolean paramsDumped;

    private void dumpCameraParameters() {
        if (paramsDumped || camera == null) return;
        paramsDumped = true;
        try {
            File out = new File(new File(Photos.sd(), "FILMLUT"), "PARAMS.TXT");
            out.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(out);
            w.write("Camera.Parameters (key=value), " + android.os.Build.MODEL + "\n");
            String flat = camera.getNormalCamera().getParameters().flatten();
            String[] kv = flat.split(";");
            java.util.Arrays.sort(kv);
            for (String s : kv) w.write(s + "\n");
            w.write("\nCameraEx.ParametersModifier methods:\n");
            java.lang.reflect.Method[] ms = CameraEx.ParametersModifier.class.getMethods();
            String[] names = new String[ms.length];
            for (int i = 0; i < ms.length; i++) names[i] = ms[i].getName();
            java.util.Arrays.sort(names);
            String last = null;
            for (String n : names) { if (!n.equals(last)) w.write(n + "\n"); last = n; }
            w.close();
        } catch (Throwable t) {
            flash("Parameter dump failed: " + t, 3000);
        }
    }

    // ---------------------------------------------------------------- exposure (as Sony's own apps do it)

    private String sceneMode = "";

    /** f-stop / shutter / ISO / EV read from the camera's parameters (cheap, twice a second). */
    private void updateExposureText() {
        if (browsing || reviewing || camera == null) { exposure.setVisibility(View.GONE); return; }
        try {
            android.hardware.Camera.Parameters p = camera.getNormalCamera().getParameters();
            sceneMode = p.get("scene-mode") == null ? "" : p.get("scene-mode");
            StringBuilder sb = new StringBuilder();
            String ap = p.get("aperture");                       // e.g. 710 = f/7.1
            if (ap != null) {
                int a = Integer.parseInt(ap.trim());
                sb.append("f/").append(a / 100).append('.').append((a % 100) / 10).append("   ");
            }
            String nu = p.get("shutter-speed-nume"), de = p.get("shutter-speed-deno");
            if (nu != null && de != null) {
                int n = Integer.parseInt(nu.trim()), d = Integer.parseInt(de.trim());
                sb.append(d <= 1 ? n + "\"" : n == 1 ? "1/" + d : n + "/" + d).append("   ");
            }
            String iso = p.get("iso-sensitivity");
            sb.append("ISO ").append(iso == null || "0".equals(iso) ? "AUTO" : iso).append("   ");
            int ev = p.getExposureCompensation();
            sb.append(ev == 0 ? "±0.0" : (ev > 0 ? "+" : "-") + Math.abs(ev) / 3 + "." + (Math.abs(ev) % 3 == 0 ? "0" : Math.abs(ev) % 3 == 1 ? "3" : "7"));
            sb.append("   ").append(modeName(sceneMode));
            String fm = p.getFocusMode();
            sb.append("   ").append("manual".equals(fm) ? "MF" : "dmf".equals(fm) ? "DMF" : "AF");
            if (aeLocked) sb.append("   AEL");
            exposure.setText(sb.toString());
            exposure.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            exposure.setVisibility(View.GONE);
        }
    }

    private static String modeName(String m) {
        if ("aperture-priority".equals(m)) return "A";
        if ("shutter-speed".equals(m)) return "S";
        if ("manual-exposure".equals(m)) return "M";
        if ("program-auto".equals(m)) return "P";
        return m;
    }

    private boolean apertureAdjustable() {
        return "aperture-priority".equals(sceneMode) || "manual-exposure".equals(sceneMode) || "program-auto".equals(sceneMode);
    }

    private boolean shutterAdjustable() {
        return "shutter-speed".equals(sceneMode) || "manual-exposure".equals(sceneMode);
    }

    /** Front dial: aperture. (Direction may need flipping once tried on the camera.) */
    private void frontDial(int dir) {
        if (camera == null || browsing) return;
        try {
            if (apertureAdjustable()) { if (dir > 0) camera.incrementAperture(); else camera.decrementAperture(); }
            else setNote("Aperture is set by the camera in " + modeName(sceneMode));
        } catch (Throwable t) { flash("Aperture: " + t, 2000); }
        updateExposureText();
    }

    /** Rear dial: shutter speed in S/M, otherwise exposure compensation (1/3 EV steps). */
    private void rearDial(int dir) {
        if (camera == null || browsing) return;
        try {
            if (shutterAdjustable()) {
                if (dir > 0) camera.incrementShutterSpeed(); else camera.decrementShutterSpeed();
            } else {
                android.hardware.Camera.Parameters cur = camera.getNormalCamera().getParameters();
                int ev = Math.max(cur.getMinExposureCompensation(), Math.min(cur.getMaxExposureCompensation(),
                        cur.getExposureCompensation() + dir));
                android.hardware.Camera.Parameters p = camera.createEmptyParameters();
                p.setExposureCompensation(ev);
                camera.getNormalCamera().setParameters(p);
            }
        } catch (Throwable t) { flash("Dial: " + t, 2000); }
        updateExposureText();
    }

    private void cycleBase() { cycleBase(1); }

    private void cycleBase(int dir) {
        restoreUserSharpness();
        restoreUserStyle();
        baseIndex = ((baseIndex + dir) % BASES.length + BASES.length) % BASES.length;
        applyBase();
        updateTexts();
        setNote("Base: " + BASE_NAMES[baseIndex]);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startLiveView();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    private void shutterPressed() {
        if (camera == null) return;
        if (sharpPending) { ui.removeCallbacks(applySharpnessLater); applySharpnessLater.run(); }   // look just changed
        try {
            synchronized (shotLooks) {
                long now = System.currentTimeMillis();
                lastShutterMs = now;
                shotLooks.add(new Shot(look(), grainFor(look()), now));   // what was on screen when the shutter went
                while (shotLooks.size() > 50) shotLooks.removeFirst();
            }
            camera.getNormalCamera().takePicture(null, null, null);   // the camera saves the file itself
            watchUntil = System.currentTimeMillis() + 60000;          // look for the new JPEG(s) for a minute
        } catch (Throwable t) {
            flash("Shutter failed: " + t, 4000);
        }
    }

    /**
     * Background thread (never the UI thread): for a minute after each shutter press, once a second,
     * reads the file NAMES of the newest DCIM folder (one directory read, no per-file stat), and
     * queues any new JPEG once its size has stopped changing.
     */
    private void startShotWatcher() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (;;) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    if (!knownLoaded || jobs == null || System.currentTimeMillis() > watchUntil) continue;
                    try { pollNewShots(); } catch (Throwable ignored) {}
                }
            }
        }, "filmlut-watch");
        t.setDaemon(true);
        t.start();
    }

    private void pollNewShots() {
        File folder = Photos.newestFolder();
        if (folder == null) return;
        for (String name : Photos.jpegNamesIn(folder)) {
            File f = new File(folder, name);
            String key = f.getAbsolutePath();
            synchronized (knownJpegs) { if (knownJpegs.contains(key)) continue; }
            long size = f.length();   // stat only files we have not seen before
            Long last = growing.get(key);
            if (size > 0 && last != null && last == size) {
                growing.remove(key);
                synchronized (knownJpegs) { knownJpegs.add(key); }
                Shot shot;
                synchronized (shotLooks) { shot = shotLooks.isEmpty() ? null : shotLooks.removeFirst(); }
                if (shot == null) {                                // e.g. burst: more files than presses
                    Looks.Look cur = looks.all.get(lookIndex);
                    shot = new Shot(cur, grainFor(cur), 0);
                }
                jobs.add(new JobQueue.Job(f, Photos.outputFor(f, shot.look.tag), shot.look, shot.grain));
                lastShot = f;
                // Quick review, unless another shot has been taken since (burst / shooting on).
                if (reviewIndex > 0 && shot.time != 0 && shot.time == lastShutterMs) requestReview(f, shot.look);
                watchUntil = Math.max(watchUntil, System.currentTimeMillis() + 10000);
            } else {
                growing.put(key, size);   // still being written (or first sighting): check again
            }
        }
    }

    // ---------------------------------------------------------------- browse mode

    private void enterBrowse() {
        Diag.trace("enter browse");
        closeCamera();
        boolean wasShooting = !browsing;
        browsing = true;
        reviewing = false;
        ui.removeCallbacks(endReview);
        hideCardNow();
        updateDepth();
        photoView.setVisibility(View.VISIBLE);
        photos = showConverted ? Photos.allOutputs() : Photos.allJpegs();
        if (photoIndex <= 0 || photoIndex >= photos.size()) photoIndex = Math.max(0, photos.size() - 1);
        if (wasShooting) showHelp(); else updateTexts();
        requestPreview(true);
    }

    /** Fn in browse: all photos <-> converted photos (which the camera's own playback cannot show). */
    private boolean showConverted;

    private void toggleConverted() {
        showConverted = !showConverted;
        Diag.trace("browse " + (showConverted ? "converted" : "all photos"));
        photos = showConverted ? Photos.allOutputs() : Photos.allJpegs();
        photoIndex = Math.max(0, photos.size() - 1);      // newest
        lastDir = -1;
        showOriginal = false;
        setNote(showConverted ? "Converted photos (" + photos.size() + ")" : "All photos");
        updateTexts();
        requestPreview(true);
    }

    /** Look of a converted photo, from its file tag (null if unknown, e.g. a look since removed). */
    private Looks.Look lookOf(File output) {
        return looks.byTag(Photos.tagOf(output));
    }

    private void startPreviewThread() {
        previewThread = new Thread(new Runnable() {
            public void run() {
                // Just below the UI thread (and above the conversions at +5), so renders never starve keys.
                try { android.os.Process.setThreadPriority(2); } catch (Throwable ignored) {}
                for (;;) {
                    Runnable r;
                    synchronized (previewLock) {
                        while (previewRequest == null) {
                            try { previewLock.wait(); } catch (InterruptedException e) { return; }
                        }
                        r = previewRequest;
                        previewRequest = null;
                    }
                    long t0 = System.currentTimeMillis();
                    try { r.run(); } catch (Throwable t) { Diag.trace("preview failed " + t); postFlash("Preview failed: " + t); }
                    Diag.trace("render " + (System.currentTimeMillis() - t0) + " ms");
                    // Idle: pre-load the neighbours of the converted photo on screen (a new request wins).
                    Runnable p;
                    synchronized (previewLock) {
                        p = previewRequest == null ? prefetchTask : null;
                        prefetchTask = null;
                    }
                    if (p != null) {
                        try { p.run(); } catch (Throwable t) { Diag.trace("prefetch failed " + t); }
                    }
                }
            }
        }, "filmlut-preview");
        previewThread.setDaemon(true);
        previewThread.start();
    }

    private void postFlash(final String s) {
        ui.post(new Runnable() { public void run() { flash(s, 3000); } });
    }

    /** Latest request wins, so scrolling quickly doesn't pile up decodes. */
    private void requestPreview(boolean newPhoto) {
        if (photos == null || photos.isEmpty()) { photoView.setImageBitmap(null); return; }
        final File photo = photos.get(photoIndex);
        final Looks.Look lk = look();
        final boolean original = showOriginal;
        // Converted view: the neighbours to pre-load next, the direction of travel first.
        final File ahead = showConverted ? neighbour(lastDir) : null;
        final File behind = showConverted ? neighbour(-lastDir) : null;
        boolean cached;
        synchronized (previewLock) { cached = showConverted && convCache.containsKey(photo.getAbsolutePath()); }
        final int gen;
        synchronized (previewLock) {
            gen = ++previewGen;
            prefetchTask = null;
            previewRequest = new Runnable() {
                public void run() {
                    renderPreview(photo, lk, original, gen);
                    if (ahead != null || behind != null) {
                        synchronized (previewLock) {
                            if (gen == previewGen) prefetchTask = new Runnable() {
                                public void run() { prefetch(ahead, gen); prefetch(behind, gen); }
                            };
                        }
                    }
                }
            };
            previewLock.notify();
        }
        if (newPhoto && !cached) flash("Loading...", 0);
    }

    // ---- converted view: small cache of decoded photos + neighbour pre-loading (preview thread)
    // Converted files have no embedded preview, so each is a full (1/8-scale) decode of ~8 MB.
    private static final int CONV_CACHE = 4;   // ~1.1 MB each at 640x427
    private final java.util.LinkedHashMap<String, int[]> convCache =
            new java.util.LinkedHashMap<String, int[]>(8, 0.75f, true) {
                protected boolean removeEldestEntry(java.util.Map.Entry<String, int[]> e) { return size() > CONV_CACHE; }
            };
    private Runnable prefetchTask;
    private int lastDir = -1;                  // converted view opens at the newest and usually goes back

    private File neighbour(int dir) {
        int i = photoIndex + dir;
        return photos != null && i >= 0 && i < photos.size() ? photos.get(i) : null;
    }

    /** Decoded pixels of a converted photo, from the cache or decoded now (and cached). */
    private int[] convPixels(File f) {
        String key = f.getAbsolutePath();
        synchronized (previewLock) {
            int[] px = convCache.get(key);
            if (px != null) return px;
        }
        int[] px = Native.preview(key, PREVIEW_MAX_W, PREVIEW_MAX_H);
        if (px != null) synchronized (previewLock) { convCache.put(key, px); }
        return px;
    }

    private void prefetch(File f, int gen) {
        if (f == null || stale(gen)) return;
        long t0 = System.currentTimeMillis();
        convPixels(f);
        Diag.trace("prefetched " + f.getName() + " " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** The look applied to a small decode: LUT, then halation (as the conversion; grain is left out,
     *  since at screen size the real grain averages away). Returns [w, h, argb...] or null. */
    private int[] withLook(File photo, int[] src, Looks.Look lk) {
        int w = src[0], h = src[1];
        int[] px = new int[2 + w * h];
        px[0] = w;
        px[1] = h;
        try {
            if (!Native.lutArgb(src, px, 2, w * h, looks.lut(lk))) { postFlash("LUT failed"); return null; }
        } catch (Exception e) { postFlash("LUT: " + e); return null; }
        if (lk.halation > 0) Native.previewHalation(photo.getAbsolutePath(), px, 2, w, h, lk.halation);
        return px;
    }

    /** [w, h, argb...] -> Bitmap, turned upright from the EXIF orientation of `oriented`. */
    private static Bitmap toBitmap(int[] px, File oriented) {
        int w = px[0], h = px[1];
        Bitmap bmp = Bitmap.createBitmap(px, 2, w, w, h, Bitmap.Config.ARGB_8888);
        int rot = 0;
        try {
            int o = new ExifInterface(oriented.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            rot = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
        } catch (Exception ignored) {
        }
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
            bmp.recycle();
            bmp = r;
        }
        return bmp;
    }

    private boolean stale(int gen) {
        synchronized (previewLock) { return gen != previewGen; }
    }

    private void renderPreview(File photo, Looks.Look lk, boolean original, final int gen) {
        Bitmap bmp;
        if (showConverted) {
            // Converted view: `photo` is a file from FILMLUT/; show it as it is (cached / pre-loaded).
            int[] px = convPixels(photo);
            if (px == null) { postFlash("Cannot read " + photo.getName()); return; }
            if (stale(gen)) return;
            postShow(toBitmap(px, photo), gen);
            return;
        }
        File out = Photos.outputFor(photo, lk.tag);
        if (!original && out.exists()) {
            // Already converted with this look: show the real result rather than a preview of it.
            int[] px = Native.preview(out.getAbsolutePath(), PREVIEW_MAX_W, PREVIEW_MAX_H);
            if (px == null) { postFlash("Cannot read " + out.getName()); return; }
            if (stale(gen)) return;
            bmp = toBitmap(px, out);
        } else {
            int[] src;
            synchronized (previewLock) {
                src = photo.getAbsolutePath().equals(previewPath) ? previewPixels : null;
            }
            if (src == null) {
                src = Native.preview(photo.getAbsolutePath(), PREVIEW_MAX_W, PREVIEW_MAX_H);
                if (src == null) { postFlash("Cannot read " + photo.getName()); return; }
                synchronized (previewLock) {
                    previewPath = photo.getAbsolutePath();
                    previewPixels = src;
                }
            }
            if (stale(gen)) return;
            int[] px = original ? src : withLook(photo, src, lk);
            if (px == null || stale(gen)) return;
            bmp = toBitmap(px, photo);
        }
        postShow(bmp, gen);
    }

    /** Shows a rendered browse image on the UI thread, unless a newer request or mode change superseded it. */
    private void postShow(final Bitmap show, final int gen) {
        ui.post(new Runnable() {
            public void run() {
                if (stale(gen) || !browsing) { show.recycle(); return; }
                photoView.setImageBitmap(show);
                ui.removeCallbacks(hideCentre);
                centre.setVisibility(View.GONE);
                updateTexts();
            }
        });
    }

    // ---------------------------------------------------------------- quick review + look card (shoot mode)

    /** Called by the shot watcher: show the new photo in its look for REVIEW_MS (any key dismisses). */
    private void requestReview(final File photo, final Looks.Look lk) {
        final long shutter = lastShutterMs;
        final int gen;
        synchronized (previewLock) {
            gen = ++previewGen;
            previewRequest = new Runnable() {
                public void run() {
                    int[] src = Native.preview(photo.getAbsolutePath(), PREVIEW_MAX_W, PREVIEW_MAX_H);
                    if (src == null || stale(gen)) return;
                    int[] px = withLook(photo, src, lk);
                    if (px == null || stale(gen)) return;
                    final Bitmap show = toBitmap(px, photo);
                    ui.post(new Runnable() {
                        public void run() {
                            // Not if the user has moved on: browsing, shooting again or half-pressing.
                            if (stale(gen) || browsing || menuOpen || camera == null || s1Down || lastShutterMs != shutter) {
                                show.recycle();
                                return;
                            }
                            hideCardNow();
                            Diag.trace("review shown " + photo.getName());
                            reviewing = true;
                            updateDepth();
                            photoView.setImageBitmap(show);
                            photoView.setVisibility(View.VISIBLE);
                            updateTexts();
                            updateExposureText();
                            setNote("Review" + SEP + lk.name);
                            ui.removeCallbacks(endReview);
                            ui.postDelayed(endReview, REVIEW_VALUES[reviewIndex]);
                        }
                    });
                }
            };
            previewLock.notify();
        }
    }

    private void hideCardNow() {
        ui.removeCallbacks(hideCard);
        if (card.getVisibility() == View.VISIBLE) hideCard.run();
    }

    // Small decode of the last shot, kept while choosing looks (preview thread only).
    private String thumbPath;
    private int[] thumbPixels;

    /** Look card: the newest photo through the current look, for CARD_MS after the last change. */
    private void showLookCard() {
        Looks.Look lk = look();
        int[] pos = looks.posInGroup(lookIndex);
        cardGroup.setText("‹  " + lk.group + "  ›");
        android.text.SpannableStringBuilder name = new android.text.SpannableStringBuilder(lk.name);
        name.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name.length(), 0);
        int at = name.length();
        name.append("\n" + pos[0] + " of " + pos[1]);
        name.setSpan(new android.text.style.ForegroundColorSpan(0xFFB0B0B0), at, name.length(), 0);
        name.setSpan(new android.text.style.RelativeSizeSpan(0.8f), at, name.length(), 0);
        cardText.setText(name);
        android.text.SpannableStringBuilder strip = new android.text.SpannableStringBuilder();
        for (Looks.Look head : looks.groupHeads()) {
            if (strip.length() > 0) strip.append("  ");
            int s = strip.length();
            strip.append(head.groupShort);
            if (head.group.equals(lk.group)) {
                strip.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), s, strip.length(), 0);
                strip.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, strip.length(), 0);
            }
        }
        cardStrip.setText(strip);
        boolean wasShown = card.getVisibility() == View.VISIBLE;
        card.setVisibility(View.VISIBLE);
        if (!wasShown) updateDepth();
        ui.removeCallbacks(hideCard);
        ui.postDelayed(hideCard, CARD_MS);
        final File photo = lastShot;
        Diag.trace("card shown, photo " + (photo == null ? "none (scan not finished?)" : photo.getName())
                + ", card " + card.getWidth() + "x" + card.getHeight() + (wasShown ? " (already up)" : ""));
        if (photo == null) { cardImage.setVisibility(View.GONE); return; }
        cardImage.setVisibility(View.VISIBLE);
        final Looks.Look want = lk;
        final int gen;
        synchronized (previewLock) {
            gen = ++previewGen;
            previewRequest = new Runnable() {
                public void run() {
                    int[] src = photo.getAbsolutePath().equals(thumbPath) ? thumbPixels : null;
                    if (src == null) {
                        src = Native.preview(photo.getAbsolutePath(), THUMB_W, THUMB_H);
                        if (src == null) { Diag.trace("card decode FAILED " + photo.getName()); return; }
                        thumbPath = photo.getAbsolutePath();
                        thumbPixels = src;
                    }
                    if (stale(gen)) return;
                    int[] px = withLook(photo, src, want);
                    if (px == null || stale(gen)) return;
                    final Bitmap show = toBitmap(px, photo);
                    ui.post(new Runnable() {
                        public void run() {
                            if (stale(gen) || browsing || card.getVisibility() != View.VISIBLE) {
                                Diag.trace("card image dropped (" + (stale(gen) ? "newer request" : browsing ? "browsing" : "card hidden") + ")");
                                show.recycle();
                                return;
                            }
                            cardImage.setImageBitmap(show);
                            Diag.trace("card image set " + show.getWidth() + "x" + show.getHeight());
                        }
                    });
                }
            };
            previewLock.notify();
        }
    }

    private void convertCurrent() {
        if (photos == null || photos.isEmpty()) return;
        File p = photos.get(photoIndex);
        File out = Photos.outputFor(p, look().tag);
        if (out.exists() && System.currentTimeMillis() > exitArmedUntil) {
            exitArmedUntil = System.currentTimeMillis() + 3000;   // reuse the "press again" timer
            flash("Already converted with " + look().name + ".\nPress OK again to redo.", 3000);
            return;
        }
        exitArmedUntil = 0;
        jobs.add(new JobQueue.Job(p, out, look(), grainFor(look())));
        setNote("Queued " + p.getName() + SEP + look().name);
    }

    // ---------------------------------------------------------------- menu (MENU key, as in Sony's own apps)

    private static final int M_LOOK = 0, M_GRAIN = 1, M_BASE = 2, M_SIZE = 3, M_FOCUS = 4, M_REVIEW = 5,
            M_CONVERTED = 6, M_HELP = 7, M_ABOUT = 8, M_EXIT = 9, M_COUNT = 10;
    private TextView menuView;
    private boolean menuOpen, aboutOpen;
    private int menuSel;
    private String menuFooter;

    private TextView buildMenuView() {
        TextView t = new TextView(this);
        t.setTextSize(18);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xF0181818);
        t.setPadding(22, 14, 22, 14);
        t.setLineSpacing(4, 1);
        t.setVisibility(View.GONE);
        return t;
    }

    private String menuLabel(int i) {
        switch (i) {
            case M_LOOK: return "Look";
            case M_GRAIN: return "Grain";
            case M_BASE: return "Base style";
            case M_SIZE: return "JPEG size";
            case M_FOCUS: return "Focus";
            case M_REVIEW: return "Quick review";
            case M_CONVERTED: return browsing && showConverted ? "All photos" : "Converted photos";
            case M_HELP: return "Help & controls";
            case M_ABOUT: return "About & licences";
            default: return "Exit application";
        }
    }

    private String menuValue(int i) {
        switch (i) {
            case M_LOOK: return look().name;
            case M_GRAIN: return grainIndex == 0 ? "Auto (" + look().grain + ")" : GRAIN_NAMES[grainIndex];
            case M_BASE: return BASE_NAMES[baseIndex];
            case M_SIZE: return SIZE_NAMES[sizeIndex];
            case M_FOCUS: return focusName();
            case M_REVIEW: return REVIEW_NAMES[reviewIndex];
            default: return null;
        }
    }

    private void renderMenu() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int s0 = sb.length();
        sb.append("Film Drawer ").append(appVersion);
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), s0, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s0, sb.length(), 0);
        for (int i = 0; i < M_COUNT; i++) {
            sb.append('\n');
            int s = sb.length();
            String v = menuValue(i);
            sb.append("  ").append(menuLabel(i));
            if (v != null) {
                for (int pad = menuLabel(i).length(); pad < 16; pad++) sb.append(' ');
                sb.append("‹ ").append(v).append(" ›");
            }
            sb.append("  ");
            if (i == menuSel) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(ACCENT), s, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Color.BLACK), s, sb.length(), 0);
            }
        }
        sb.append("\n");
        int f = sb.length();
        sb.append(menuFooter != null ? menuFooter : "Up/Down: choose   Left/Right/OK: change   MENU: close");
        sb.setSpan(new android.text.style.ForegroundColorSpan(menuFooter != null ? ACCENT : 0xFF9A9A9A), f, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), f, sb.length(), 0);
        menuView.setTypeface(android.graphics.Typeface.MONOSPACE);
        menuView.setText(sb);
    }

    private void openMenu() {
        Diag.trace("menu open");
        hideReview();
        hideCardNow();
        menuOpen = true;
        aboutOpen = false;
        menuFooter = null;
        exitArmedUntil = 0;
        renderMenu();
        menuView.setVisibility(View.VISIBLE);
    }

    private void closeMenu() {
        menuOpen = false;
        aboutOpen = false;
        menuView.setVisibility(View.GONE);
        updateTexts();
    }

    private void showAbout() {
        aboutOpen = true;
        menuView.setTypeface(android.graphics.Typeface.DEFAULT);
        menuView.setText("Film Drawer " + appVersion + "  by Almost Dark Studio\n"
                + "almostdarkstudio.com   Donate: ko-fi.com/R6I02B7OBL\n\n"
                + "Free software, MIT licence.\n"
                + looks.all.size() + " film looks. Licences and credits:\n"
                + "  RawTherapee Film Simulation Collection\n"
                + "    (Pat David, Pavlov Dmitry, Michael Ezra), CC BY-SA 4.0\n"
                + "  EmulsionPresets by peva3, MIT\n"
                + "  Aerochrome, Almost Dark: made for Film Drawer, MIT\n"
                + "This software is based in part on the work of the\n"
                + "Independent JPEG Group (libjpeg-turbo).\n"
                + "Film names are trademarks of their owners, used only to\n"
                + "describe each look. Not affiliated with Sony or any film maker.\n\n"
                + "Any button: back");
    }

    /** Left/right/OK on a value: step it. Returns false for action items. */
    private boolean menuChange(int item, int dir) {
        switch (item) {
            case M_LOOK:
                changeLook(dir);
                break;
            case M_GRAIN:
                grainIndex = ((grainIndex + dir) % GRAIN_NAMES.length + GRAIN_NAMES.length) % GRAIN_NAMES.length;
                updateTexts();
                if (browsing) requestPreview(false);
                break;
            case M_BASE:
                cycleBase(dir);
                break;
            case M_SIZE:
                cycleSize(dir);
                break;
            case M_FOCUS:
                cycleFocus(dir);
                break;
            case M_REVIEW:
                reviewIndex = ((reviewIndex + dir) % REVIEW_NAMES.length + REVIEW_NAMES.length) % REVIEW_NAMES.length;
                break;
            default:
                return false;
        }
        hideCardNow();   // the menu covers the look card; the value shows in the menu
        return true;
    }

    private void menuActivate(int item) {
        if (menuChange(item, 1)) { renderMenu(); return; }
        switch (item) {
            case M_CONVERTED:
                closeMenu();
                if (browsing) toggleConverted();
                else { showConverted = true; enterBrowse(); }
                return;
            case M_HELP:
                closeMenu();
                showHelp();
                return;
            case M_ABOUT:
                showAbout();
                return;
            case M_EXIT:
                if (jobs != null && jobs.pending() > 0 && System.currentTimeMillis() > exitArmedUntil) {
                    exitArmedUntil = System.currentTimeMillis() + 4000;
                    menuFooter = jobs.pending() + " still converting (they carry on next time). OK again to exit.";
                    renderMenu();
                    return;
                }
                if (jobs != null) jobs.stopAll();
                finish();
        }
    }

    /** All keys while the menu is open (key-down). */
    private void menuKey(int sc) {
        if (aboutOpen) { renderMenu(); aboutOpen = false; return; }
        switch (sc) {
            case K_UP: case WHEEL_CCW: case D1_CCW: case D2_CCW: case D3_CCW:
                menuSel = (menuSel + M_COUNT - 1) % M_COUNT; menuFooter = null; break;
            case K_DOWN: case WHEEL_CW: case D1_CW: case D2_CW: case D3_CW:
                menuSel = (menuSel + 1) % M_COUNT; menuFooter = null; break;
            case K_LEFT: menuChange(menuSel, -1); break;
            case K_RIGHT: menuChange(menuSel, 1); break;
            case K_ENTER: menuActivate(menuSel); return;
            case K_MENU: case K_SK2: case K_DELETE: case K_S1: case K_PLAY:
                closeMenu(); return;
            default:
                return;
        }
        if (menuOpen && !aboutOpen) renderMenu();
    }

    // ---------------------------------------------------------------- keys

    private void changeLook(int delta) {
        int n = looks.all.size();
        setLook(((lookIndex + delta) % n + n) % n);
    }

    private void setLook(int index) {
        lookIndex = index;
        Diag.trace("look " + look().id);
        hideReview();
        updateTexts();
        if (browsing) requestPreview(false);
        else {
            showLookCard();
            ui.removeCallbacks(applySharpnessLater);           // settle first: no camera call per wheel click
            ui.postDelayed(applySharpnessLater, 400);
            sharpPending = true;
        }
    }

    /** Left/right in shoot mode: first look of the next / previous group (Kodak, Cine, Fuji, ...). */
    private void changeGroup(int dir) {
        setLook(looks.groupJump(lookIndex, dir));
    }

    private void changePhoto(int delta) {
        if (photos == null || photos.isEmpty()) return;
        int i = Math.max(0, Math.min(photos.size() - 1, photoIndex + delta));
        if (i == photoIndex) return;
        lastDir = delta > 0 ? 1 : -1;
        photoIndex = i;
        showOriginal = false;
        updateTexts();
        requestPreview(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (looks == null || jobs == null) {
            int sc = event.getScanCode();
            if (sc == K_MENU || sc == K_DELETE || sc == K_SK2) { finish(); return true; }
            return super.onKeyDown(keyCode, event);
        }
        if (event.getRepeatCount() == 0) Diag.trace("key down " + event.getScanCode());
        if (reviewing && event.getRepeatCount() == 0) {
            // Any control ends the quick review. Buttons acted on at key-up (trash, MENU, OK, Fn, C1/C2)
            // only dismiss it: on Sony cameras trash during review means "delete", so never exit on it.
            hideReview();
            int sc = event.getScanCode();
            if (sc == K_MENU || sc == K_DELETE || sc == K_SK2 || sc == K_ENTER || sc == K_FN || sc == K_C1 || sc == K_C2) {
                swallowUp = sc;
                return true;
            }
        }
        if (menuOpen) {
            // The menu takes every key; its release is ignored (MENU would otherwise reopen it).
            int sc = event.getScanCode();
            boolean scroll = sc == K_UP || sc == K_DOWN || sc == WHEEL_CW || sc == WHEEL_CCW;
            if (event.getRepeatCount() == 0 || scroll) menuKey(sc);
            swallowUp = sc;
            return true;
        }
        boolean conv = browsing && showConverted;   // converted view: every scroll control changes photo
        switch (event.getScanCode()) {
            case K_UP: case WHEEL_CCW:
                if (conv) changePhoto(-1); else changeLook(-1);
                return true;
            case K_DOWN: case WHEEL_CW:
                if (conv) changePhoto(1); else changeLook(1);
                return true;
            case D1_CW: case D1_CCW:
                // Shooting: front dial = aperture. Browsing: change look (converted view: photo).
                if (conv) changePhoto(event.getScanCode() == D1_CW ? 1 : -1);
                else if (browsing) changeLook(event.getScanCode() == D1_CW ? 1 : -1);
                else frontDial(event.getScanCode() == D1_CW ? 1 : -1);
                return true;
            case D2_CW: case D2_CCW:
                // Shooting: rear dial = shutter (S/M) or EV (A/P). Browsing: change photo.
                if (browsing) changePhoto(event.getScanCode() == D2_CW ? 1 : -1);
                else rearDial(event.getScanCode() == D2_CW ? 1 : -1);
                return true;
            case D3_CW: case D3_CCW:
                if (conv) changePhoto(event.getScanCode() == D3_CW ? 1 : -1);
                else if (browsing) changeLook(event.getScanCode() == D3_CW ? 1 : -1);
                else rearDial(event.getScanCode() == D3_CW ? 1 : -1);
                return true;
            case K_LEFT:
                if (browsing) changePhoto(-1); else changeGroup(-1);
                return true;
            case K_RIGHT:
                if (browsing) changePhoto(1); else changeGroup(1);
                return true;
            case K_S1:
                s1Down = true;
                if (browsing) { enterShoot(); return true; }
                // Half-press autofocuses, except in MF (DMF: AF first, then the ring, as on the camera).
                if (camera != null && event.getRepeatCount() == 0 && !"manual".equals(currentFocusMode())) {
                    try { camera.getNormalCamera().autoFocus(null); } catch (Throwable ignored) {}
                }
                return true;
            case K_S1_2:
                return true;
            case K_EV_DIAL:
                if (!browsing) applyEvDial(true);
                return true;
            case K_AF_MF:
                if (!browsing && event.getRepeatCount() == 0) toggleAfMf();
                return true;
            case K_AEL:
                if (!browsing && event.getRepeatCount() == 0) setAeLock(true);
                return true;
            case K_S2:
                if (!browsing && event.getRepeatCount() == 0) shutterPressed();
                return true;
            case K_ENTER: case K_PLAY: case K_MENU: case K_DELETE: case K_SK2: case K_FN: case K_C1: case K_C2:
                return true;   // handled on key-up
            default:
                // Unknown control: show its code so the dial mapping can be corrected after a try.
                // 0.22: always shown (corner note), to learn the EV dial / AF-MF button codes on the a7R II.
                if (event.getRepeatCount() == 0 && Diag.verbose()) {
                    // Diagnostics (FILMLUT/DEBUG.TXT): code, the camera's own name and reported position.
                    int sc = event.getScanCode();
                    String name = Diag.keyName(sc), st = Diag.keyStatus(sc);
                    Diag.trace("unknown key " + sc + " " + name + " status " + st + " (keyCode " + keyCode + ")");
                    setNote(sc + " " + (name.length() > 0 ? name : "?") + "  " + st);
                    Diag.dumpInputClass();
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (looks == null || jobs == null) return super.onKeyUp(keyCode, event);
        if (swallowUp != 0 && event.getScanCode() == swallowUp) { swallowUp = 0; return true; }   // it ended a review
        switch (event.getScanCode()) {
            case K_S1:
                s1Down = false;
                if (!browsing && camera != null) {
                    if (!"manual".equals(currentFocusMode())) {
                        try { camera.getNormalCamera().cancelAutoFocus(); } catch (Throwable ignored) {}
                    }
                }
                return true;
            case K_S2:
                if (!browsing && camera != null) {
                    try { camera.cancelTakePicture(); } catch (Throwable ignored) {}
                }
                return true;
            case K_ENTER:
                if (browsing && showConverted) { if (helpVisible) hideHelp.run(); else showHelp(); }
                else if (browsing) convertCurrent();
                else if (helpVisible) hideHelp.run();
                else showHelp();
                return true;
            case K_FN:
                if (browsing) toggleConverted();                 // all photos <-> converted
                else if (camera != null) cycleBase();
                return true;
            case K_C1:
                if (!browsing && camera != null) cycleSize();
                return true;
            case K_C2:
                grainIndex = (grainIndex + 1) % GRAIN_NAMES.length;
                updateTexts();
                setNote("Grain: " + GRAIN_NAMES[grainIndex] + (grainIndex == 0 ? " (per look)" : ""));
                return true;
            case K_PLAY:
                if (browsing) enterShoot(); else enterBrowse();
                return true;
            case K_DELETE: case K_SK2:
                // Browse (all photos): before/after. Never exits: MENU > Exit application does.
                if (browsing && !showConverted) {
                    showOriginal = !showOriginal;
                    updateTexts();
                    requestPreview(false);
                }
                return true;
            case K_MENU:
                openMenu();
                return true;
            case K_AEL:
                if (aeLocked) setAeLock(false);                  // AEL hold: released
                return true;
            case K_EV_DIAL: case K_AF_MF:
                return true;
            case K_UP: case K_DOWN: case K_LEFT: case K_RIGHT: case K_S1_2:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // ---------------------------------------------------------------- queue callbacks (worker thread)

    public void onJobDone(final JobQueue.Job job, final String error, final long millis) {
        Diag.trace("job done " + job.in.getName() + " " + job.look.tag + " " + millis + " ms" + (error == null ? "" : " FAILED " + error));
        synchronized (previewLock) { convCache.remove(job.out.getAbsolutePath()); }   // a redo replaces the file
        ui.post(new Runnable() {
            public void run() {
                if (error == null) {
                    setNote("Saved " + job.out.getName() + "  " + (millis / 100) / 10.0 + " s");
                } else {
                    flash(job.in.getName() + " failed:\n" + error, 6000);
                }
                updateTexts();
            }
        });
    }

    public void onBusyChanged(boolean busy) {
        // Keep the camera awake while converting.
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", busy ? "APO/NO" : "APO/NORMAL");
        sendBroadcast(intent);
    }
}
