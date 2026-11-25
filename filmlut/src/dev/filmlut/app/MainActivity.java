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
 *   OK: help. PLAY: converted photos. MENU: menu (Exit application there; unfinished conversions resume next time).
 * BROWSE: photo with a preview of the look (or the saved result, if already converted).
 *   Left-right: photo. Wheel / up-down: look. OK: convert. Trash: before/after (converted photos: delete).
 *   PLAY or half-press: back to shooting. Fn: all photos / converted (FILMDRAW/). MENU: menu.
 * MENU (both modes): settings, converted photos, help, about, Exit application.
 *
 * Originals are never modified; results go to FILMDRAW/<folder>/<TAG><number>.JPG.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, JobQueue.Listener {
    // Sony scan codes (OpenMemories-Framework stubs, ScalarInput).
    private static final int K_UP = 103, K_DOWN = 108, K_LEFT = 105, K_RIGHT = 106, K_ENTER = 232;
    private static final int K_MENU = 514, K_SK2 = 513, K_DELETE = 595, K_PLAY = 207;
    private static final int K_S1 = 516, K_S1_2 = 517, K_S2 = 518;
    private static final int D1_CW = 525, D1_CCW = 526, D2_CW = 528, D2_CCW = 529;
    // The back wheel: 522/523 on the a7R II, 635/634 ("dial 3") on the a7. Both are the wheel here.
    private static final int WHEEL_CW = 522, WHEEL_CCW = 523, D3_CW = 635, D3_CCW = 634, K_FN = 520;
    // a7R II, found with 0.24's input diagnostics: EV dial (position from ScalarInput.getKeyStatus:
    // 1/3-stop clicks, 0x200 = negative), and the AF/MF-AEL button. Names from the camera (INPUT.TXT):
    // ISV_KEY_EV_COMPENSATION = 620, ISV_KEY_AFMF = 533 (lever at AF/MF), ISV_KEY_AEL = 532 (lever at AEL).
    private static final int K_EV_DIAL = 620, K_AF_MF = 533, K_AEL = 532;
    private static final int K_MODE_DIAL = 572;   // ISV_KEY_MODE_DIAL; its status should be an ISV_KEY_MODE_* code

    /** In-camera base while shooting: a known starting point for the LUTs. "camera" = leave as is. */
    private static final String[] BASES = {"standard", "neutral", "camera"};
    private static final String[] BASE_NAMES = {"Standard", "Neutral", "Camera settings"};

    /** JPEG size while shooting in the app (RAW is always full size). Sony spells medium "midium". */
    private static final String[] SIZES = {"midium", "small", "large"};
    private static final String[] SIZE_KEYS = {"m", "s", "l"};
    /** Megapixels per size, read from this camera (readImageSizes); 0 = unknown, then not shown. */
    private final float[] sizeMp = new float[SIZES.length];
    /** No size chosen yet with C1 / the menu: use the largest up to AUTO_MAX_MP (a7R II: M). */
    private boolean sizeAuto;
    private static final float AUTO_MAX_MP = 20.5f;
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
    private Focus focus;                          // focus area: boxes, choice, moving the spot
    private boolean movingSpot;                   // arrows move the focus point (Trash, or the menu)
    private SharedPreferences prefs;
    private Looks looks;
    private JobQueue jobs;
    private int lookIndex;                        // in looks.nav
    private volatile Looks.Look current;          // that look (read by the shot watcher thread)
    private final java.util.Set<String> favourites = new java.util.LinkedHashSet<String>();   // look ids
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
    private boolean afStarted;                    // autoFocus() called and not yet cancelled
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
        user = new UserSettings(prefs);
        try { appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        Diag.install(appVersion);
        buildViews();
        try {
            looks = new Looks(getAssets());
        } catch (Exception e) {
            centre.setText("Cannot load looks: " + e);
            return;
        }
        for (String id : prefs.getString("favourites", "").split(",")) if (id.length() > 0) favourites.add(id);
        looks.setFavourites(favourites);
        lookIndex = savedLook();
        current = looks.nav.get(lookIndex);
        baseIndex = Math.max(0, Math.min(BASES.length - 1, prefs.getInt("base", 0)));
        sizeIndex = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("size", 0)));
        sizeAuto = !prefs.contains("size");
        dialIndex = prefs.getInt("dials", 0) == 1 ? 1 : 0;       // 0.38-0.42 also had 2: the same as 0 now
        wheelIndex = prefs.getInt("wheel", 0) == 1 ? 1 : 0;
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
                try { Photos.allOutputs(); } catch (Throwable ignored) {}      // so PLAY is quick later: the dates are kept
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
        ui.removeCallbacks(modeDialSettled);
        s1Down = false;
        Diag.trace("pause");
        if (deleteFile != null) closeDelete();
        Diag.dumpTrace("left the app (pause)");
        closeCamera();
        if (prefs != null) prefs.edit().putString("lookId", looks == null ? null : look().id).putBoolean("lookFav", looks != null && look().isFavourite()).remove("look").putInt("base", baseIndex).putInt("grain", grainIndex).putInt("review", reviewIndex)
                .putInt("dials", dialIndex).putInt("wheel", wheelIndex).commit();
        if (prefs != null && !sizeAuto) prefs.edit().putInt("size", sizeIndex).commit();
    }

    /**
     * The look in use last time, saved by id. Up to 0.43 it was saved as a position in the list, whose order
     * changed in 0.44: OLD_ORDER turns that into the same look. First start: Portra 400.
     */
    private int savedLook() {
        String id = prefs.getString("lookId", null);
        if (id == null) {
            int old = prefs.getInt("look", -1);
            id = old >= 0 && old < OLD_ORDER.length ? OLD_ORDER[old] : "portra400";
        }
        return looks.navIndex(looks.byId(id) == null ? "portra400" : id, prefs.getBoolean("lookFav", false));
    }

    private static final String[] OLD_ORDER = ("gold200 ultramax colorplus portra160 portra400 portra800 ektar100 vision250d vision500t almostdark cs50d cs800t k2383 eterna fujic200 superia400 superia1600 reala100 fuji400h vista200 pro160ns classicchrome classicneg kchrome64 ekta100vs e100 velvia50 provia100f astia100f pola669 sx70 fp100c metropolis aerochrome trix400 hp5std tmax400 acros100 delta3200 panf50").split(" ");

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
        focus = new Focus(this, prefs, ui);
        root.addView(focus.view, new FrameLayout.LayoutParams(-1, -1));   // under the photo, menu and texts
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
        long t0 = System.currentTimeMillis();
        try {
            Gpelibrary.changeFrameBufferPixel(high ? Gpelibrary.GS_FRAMEBUFFER_TYPE.ABGR8888
                    : Gpelibrary.GS_FRAMEBUFFER_TYPE.RGBA4444);
        } catch (Throwable ignored) {
            // Not fatal: only affects how the overlay blends with the live view.
        }
        Diag.trace("colour depth " + (high ? "8888" : "4444") + ", " + (System.currentTimeMillis() - t0) + " ms");
    }

    private final Runnable hideCentre = new Runnable() {
        public void run() { centre.setVisibility(View.GONE); }
    };

    private boolean loading;                      // the centre message is "Loading..."

    private void showLoading() {
        flash("Loading...", 0);
        loading = true;
    }

    /** Takes "Loading..." away (any other message stays for its time). */
    private void hideLoading() {
        if (!loading) return;
        loading = false;
        ui.removeCallbacks(hideCentre);
        centre.setVisibility(View.GONE);
    }

    private void flash(String msg, int millis) {
        loading = false;
        centre.setText(msg);
        centre.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideCentre);
        if (millis > 0) ui.postDelayed(hideCentre, millis);
    }

    private Looks.Look look() { return current; }

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
            topLeft.setText((browsing && look().isFavourite() ? "Fav" + SEP : "") + look().name + SEP + grainShort()
                    + (browsing ? "" : SEP + SIZE_SHORT[sizeIndex] + SEP + BASE_SHORT[baseIndex]));
        }
        if (reviewing) {
            bottom.setVisibility(View.GONE);
            return;
        }
        if (movingSpot) { showMoveHelp(); return; }
        String text;
        if (!browsing) {
            text = helpVisible
                    ? "Film Drawer " + appVersion + "  (free, MIT licence)\n"
                      + (wheelIsIso() ? "Up/Down: look   Wheel: ISO" : "Wheel/Up/Down: look") + "   Left/Right: group   Fn: base   C1: size   C2: grain\n"
                      + (dialIndex == 1 ? "Front: shutter/EV   Rear: aperture" : "Front: aperture   Rear: shutter/EV") + "   Trash: controls   PLAY: photos   MENU"
                    : null;
        } else if (listing) {
            text = helpVisible ? "PLAY: shoot" : null;
        } else if (photos == null || photos.isEmpty()) {
            text = (showConverted ? "No converted photos yet    Fn: all photos" : "No JPEGs on the card    Fn: converted")
                    + "    PLAY: shoot    MENU: menu";
        } else if (showConverted) {
            File p = photos.get(photoIndex);
            text = p.getParentFile().getName() + "/" + p.getName() + "  " + (photoIndex + 1) + "/" + photos.size()
                    + (helpVisible ? "\nLeft/Right/Wheel: photo   Trash: delete   Fn: all photos   PLAY: shoot" : "");
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
            if (!menuOpen) updateExposureText();           // reads every camera parameter: not behind the menu
            ui.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- shoot mode

    private void enterShoot() {
        Diag.trace("enter shoot");
        synchronized (previewLock) { convCache.clear(); prefetchTask = null; previewRequest = null; previewGen++; }   // free ~4 MB of browse cache
        Native.previewAbort();         // a 24 MP decode takes seconds on an a7, just as the live view starts
        hideLoading();                 // left before the photo appeared: it never will now
        listGen++;                     // a list still being read is not wanted any more
        listing = false;
        clearLiveFailure();
        boolean wasBrowsing = browsing;
        browsing = false;
        showOriginal = false;
        reviewing = false;
        photoView.setVisibility(View.GONE);
        photoView.setImageBitmap(null);
        updateDepth();                 // RGBA4444, as PMCADemo's camera screen: lets the live view show through
        live.setVisibility(View.VISIBLE);      // a new surface (enterBrowse dropped the old one), as when the app starts
        ui.removeCallbacks(openWaiting);
        if (s1Down) ui.postDelayed(openWaiting, 1000);     // left with a half-press: opened when it is released (or now, if held)
        else openCamera();
        checkLiveSoon();
        if (wasBrowsing) showHelp(); else updateTexts();
    }

    private final Runnable openWaiting = new Runnable() {
        public void run() {
            if (browsing || camera != null) return;
            openCamera();
            updateTexts();
        }
    };

    // Normally surfaceCreated (or openCamera, if it comes later) starts the live view. If neither did: start it anyway.
    private boolean liveStarted;
    private long liveAttempt, openedAt;           // when it was last tried, and when the camera was opened

    private void checkLiveSoon() {
        ui.removeCallbacks(checkLive);
        ui.postDelayed(checkLive, 2500);
    }

    private final Runnable checkLive = new Runnable() {
        public void run() {
            if (browsing || camera == null || liveStarted || liveTries > 0 || System.currentTimeMillis() - liveAttempt < 2500) return;
            boolean valid = live.getHolder().getSurface() != null && live.getHolder().getSurface().isValid();
            Diag.report("Live view not started, surface " + (surfaceReady ? "ready" : "not ready") + (valid ? ", valid" : ", not valid"));
            surfaceReady = valid;
            startLiveView();
        }
    };

    private android.hardware.Camera.Parameters opening;   // read once while opening: each read is slow (~0.1-0.5 s)

    private android.hardware.Camera.Parameters params() {
        return opening != null ? opening : camera.getNormalCamera().getParameters();
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            camera = CameraEx.open(0, null);
            liveStarted = false;
            afStarted = false;
            openedAt = System.currentTimeMillis();
            if (Diag.verbose()) dumpCameraParameters();   // FILMDRAW/DEBUG.TXT on the card turns it on
            restoreUser("open", UserSettings.G_FOCUS, UserSettings.G_AF, UserSettings.G_SHARPNESS, UserSettings.G_STYLE, UserSettings.G_SIZE, UserSettings.G_DRO);   // left over from an abrupt exit
            opening = camera.getNormalCamera().getParameters();
            applyStorageFormat();                          // before the size: there must be a JPEG
            applyModeDial(false);                          // turned while browsing, say
            readImageSizes();
            applyBase();
            applyAppFocus();
            if (liveTries == 0) startLiveView(); else ui.post(startLiveLater);
            focus.open(camera);
            Controls.dump(camera);
            applyEvDial(false);                            // match the EV dial's position
        } catch (Throwable t) {
            CameraEx c = camera;
            camera = null;
            focus.drop();
            if (c != null) try { c.release(); } catch (Throwable ignored) {}      // else it cannot be opened again
            liveFailed("Camera unavailable", t);
        } finally {
            opening = null;
        }
    }

    private void startLiveView() {
        if (camera == null || !surfaceReady || browsing || liveStarted) return;
        liveAttempt = System.currentTimeMillis();
        try {
            camera.getNormalCamera().setPreviewDisplay(live.getHolder());
            camera.getNormalCamera().startPreview();
            liveStarted = true;
            Diag.trace("live view started, " + (System.currentTimeMillis() - liveAttempt) + " ms");
            if (liveTries > 0) {
                Diag.report("Live view started on try " + (liveTries + 1));
                hideCentre.run();
                ui.removeCallbacks(hideCentre);
                setNote("Live view is back");
            }
            liveTries = 0;
        } catch (Throwable t) {
            liveFailed("Live view failed", t);
        }
    }

    // On a tester's a7 (0.45) startPreview sometimes gave up after 4 s when leaving the photos. Tried again: first
    // startPreview alone, then once with the camera released and opened; after that only when the user asks (half-press).
    private static final int LIVE_RETRIES = 2;
    private int liveTries;                        // failed tries in a row; 0 = live view is fine
    private long liveWaitUntil;                   // how long a retry waits for the shutter button

    private void liveFailed(String what, Throwable t) {
        liveTries++;
        Diag.report(what + " (try " + liveTries + (s1Down ? ", half-pressed" : "") + (jobs != null && jobs.pending() > 0 ? ", converting" : "") + (surfaceReady ? "" : ", no surface") + ", " + Runtime.getRuntime().freeMemory() / 1024 + "/" + android.os.Debug.getNativeHeapAllocatedSize() / 1024 + " KB): " + t);
        if (liveTries <= LIVE_RETRIES) retryLiveSoon();
        else {
            ui.removeCallbacks(retryLive);
            flash(what + "\n" + t + "\nHalf-press: try again\nor MENU > Exit, and open the app again", 0);
        }
    }

    private void retryLiveSoon() {
        flash("Starting the live view again...", 0);
        liveWaitUntil = System.currentTimeMillis() + 3000;
        ui.removeCallbacks(retryLive);
        ui.removeCallbacks(reopenLive);
        ui.postDelayed(retryLive, 700);
    }

    // In steps (release, then open, then start: posted), so keys are handled in between: startPreview alone can block
    // for 4 s, and Android closes an app that leaves a key waiting for 5.
    private final Runnable retryLive = new Runnable() {
        public void run() {
            if (browsing) return;
            if (s1Down && System.currentTimeMillis() < liveWaitUntil) { ui.postDelayed(this, 300); return; }
            Diag.trace("live view: trying again");
            if (camera != null && surfaceReady && liveTries == 1) { startLiveView(); return; }
            // Not closeCamera(): the user's settings are not put back through a camera that does not answer properly.
            // They stay saved, and opening it puts them back first, as after an abrupt exit.
            if (camera != null) {
                aeLocked = false;
                endMoveSpot();
                focus.drop();
                try { camera.release(); } catch (Throwable ignored) {}
                camera = null;
                liveStarted = false;
            }
            live.setVisibility(View.INVISIBLE);                        // and a new surface
            ui.postDelayed(reopenLive, 600);
        }
    };

    private final Runnable reopenLive = new Runnable() {
        public void run() {
            if (browsing || camera != null) return;
            live.setVisibility(View.VISIBLE);
            openCamera();                         // surfaceCreated starts it, or the start is posted there
            updateTexts();
            checkReopened();
        }
    };

    /** As checkLive, for a retry: the message must never stay without a result. */
    private void checkReopened() {
        ui.removeCallbacks(reopenCheck);
        ui.postDelayed(reopenCheck, 3000);
    }

    private final Runnable reopenCheck = new Runnable() {
        public void run() {
            if (browsing || camera == null || liveStarted || liveTries == 0 || liveTries > LIVE_RETRIES
                    || System.currentTimeMillis() - liveAttempt < 3000) return;
            surfaceReady = live.getHolder().getSurface() != null && live.getHolder().getSurface().isValid();
            if (surfaceReady) startLiveView(); else liveFailed("Live view failed", new RuntimeException("no surface"));
        }
    };

    private final Runnable startLiveLater = new Runnable() {
        public void run() { if (liveAttempt < openedAt) startLiveView(); }    // unless surfaceCreated has tried already
    };

    /** The "starting again" / "failed" message goes when the user moves on. */
    private void clearLiveFailure() {
        if (liveTries == 0) return;
        liveTries = 0;
        ui.removeCallbacks(hideCentre);
        hideCentre.run();
    }

    // ---- focus mode (menu): AF-S / AF-A / AF-C / DMF / MF, as on the camera. Two camera settings: "focus-mode" (manual,
    // auto, dmf) and, for auto, "af-mode" (af-s, af-a, af-c; the a7 has no af-a).
    private String currentFocusMode() {
        try { return camera.getNormalCamera().getParameters().getFocusMode(); } catch (Throwable t) { return null; }
    }

    private String currentAfMode() {
        try { return camera.getNormalCamera().getParameters().get("af-mode"); } catch (Throwable t) { return null; }
    }

    private static String focusName(String m, String af) {
        if ("manual".equals(m)) return "MF";
        if ("dmf".equals(m)) return "DMF";
        if (!"auto".equals(m)) return m == null ? "?" : m;
        return "af-s".equals(af) ? "AF-S" : "af-c".equals(af) ? "AF-C" : "af-a".equals(af) ? "AF-A" : "AF";
    }

    private String appFocusMode, appAfMode;       // chosen in the app this session (null = the camera's own): kept after the photos

    private void applyAppFocus() {
        if (appFocusMode == null && appAfMode == null) return;
        try {
            user.saveFocus(settings(params()));
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            if (appFocusMode != null) p.setFocusMode(appFocusMode);
            if (appAfMode != null) camera.createParametersModifier(p).setAutoFocusMode(appAfMode);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            Diag.trace("focus mode not set again: " + t);
        }
    }

    /** Steps the focus mode. Your own mode is saved first and put back when the camera closes. */
    /** AF/MF button: toggles AF <-> MF (your own mode is still restored on exit). */
    private void toggleAfMf() {
        if (camera == null) return;
        try {
            String cur = currentFocusMode();
            String target = "manual".equals(cur) ? "auto" : "manual";
            user.saveFocus(settings(camera.getNormalCamera().getParameters()));
            setFocusMode(target);
            appFocusMode = target;
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
            android.hardware.Camera.Parameters cur = params();
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
     * each. If neither takes, writes FILMDRAW/CAMERAEX.TXT (Sony's camera methods) to find the right way.
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
        String name = focusName(got, currentAfMode());
        Diag.trace("focus wanted " + wanted + ", camera reports " + got);
        focus.refresh();                                   // no boxes in MF
        if (wanted.equals(got)) setNote("Focus: " + name);
        else flash("Camera kept " + name + "\n(" + wanted + " refused)", 2500);
        updateExposureText();
    }

    private void cycleFocus(int dir) {
        if (camera == null) { setNote("Focus mode: change it while shooting"); return; }
        try {
            android.hardware.Camera.Parameters p = camera.getNormalCamera().getParameters();
            List<String> ok = p.getSupportedFocusModes();
            List afs = null;
            try { afs = camera.createParametersModifier(p).getSupportedAutoFocusModes(); } catch (Throwable ignored) {}
            List<String[]> all = new java.util.ArrayList<String[]>();              // {focus-mode, af-mode}, this camera's
            if (ok == null || ok.contains("auto")) {
                for (String a : new String[]{"af-s", "af-a", "af-c"}) if (afs != null && afs.contains(a)) all.add(new String[]{"auto", a});
                if (all.isEmpty()) all.add(new String[]{"auto", null});
            }
            for (String f : new String[]{"dmf", "manual"}) if (ok == null || ok.contains(f)) all.add(new String[]{f, null});
            String cur = p.getFocusMode(), af = p.get("af-mode");
            int i = 0;
            for (int k = 0; k < all.size(); k++)
                if (all.get(k)[0].equals(cur) && (all.get(k)[1] == null || all.get(k)[1].equals(af))) i = k;
            String[] to = all.get(((i + dir) % all.size() + all.size()) % all.size());
            user.saveFocus(settings(p));
            appFocusMode = to[0];
            if (to[1] != null) appAfMode = to[1];
            if (!to[0].equals(cur)) setFocusMode(to[0]);
            if (to[1] != null && !to[1].equals(af)) {
                android.hardware.Camera.Parameters q = camera.createEmptyParameters();
                camera.createParametersModifier(q).setAutoFocusMode(to[1]);
                camera.getNormalCamera().setParameters(q);
            }
            p = camera.getNormalCamera().getParameters();
            String got = focusName(p.getFocusMode(), p.get("af-mode")), wanted = focusName(to[0], to[1] != null ? to[1] : p.get("af-mode"));
            Diag.trace("focus wanted " + wanted + ", camera has " + got);
            if (got.equals(wanted)) setNote("Focus: " + got);
            else flash("Camera kept " + got + "\n(" + wanted + " refused)", 2500);
            focus.refresh();
            updateExposureText();
        } catch (Throwable t) {
            flash("Focus mode: " + t, 3000);
        }
    }

    // ---- the user's own settings (UserSettings): the camera side

    private UserSettings user;

    private final UserSettings.Cam userCam = new UserSettings.Cam() {
        public Map<String, String> read() {
            return settings(camera.getNormalCamera().getParameters());
        }

        public void set(Map<String, String> v) {
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            for (Map.Entry<String, String> e : v.entrySet()) {
                String k = e.getKey(), s = e.getValue();
                if (k.equals(UserSettings.FOCUS)) p.setFocusMode(s);
                else if (k.equals(UserSettings.AF)) m.setAutoFocusMode(s);
                else if (k.equals(UserSettings.SHARPNESS)) m.setSharpness(Integer.parseInt(s));
                else if (k.equals(UserSettings.MODE)) m.setColorMode(s);
                else if (k.equals(UserSettings.CONTRAST)) m.setContrast(Integer.parseInt(s));
                else if (k.equals(UserSettings.SATURATION)) m.setSaturation(Integer.parseInt(s));
                else if (k.equals(UserSettings.SIZE)) m.setImageSize(s);
                else if (k.equals(UserSettings.DRO)) m.setDROMode(s);
                else if (k.equals(UserSettings.DRO_LEVEL)) m.setDROLevel(Integer.parseInt(s));
                else if (k.equals(UserSettings.FORMAT)) m.setPictureStorageFormat(s);
            }
            camera.getNormalCamera().setParameters(p);
        }
    };

    /** The settings the app touches, as this camera reports them (null = it does not). */
    private Map<String, String> settings(android.hardware.Camera.Parameters p) {
        CameraEx.ParametersModifier m = camera.createParametersModifier(p);
        Map<String, String> v = new HashMap<String, String>();
        try { v.put(UserSettings.FOCUS, p.getFocusMode()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.AF, m.getAutoFocusMode()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.SHARPNESS, "" + m.getSharpness()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.MODE, m.getColorMode()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.CONTRAST, "" + m.getContrast()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.SATURATION, "" + m.getSaturation()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.SIZE, m.getImageSize()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.DRO, m.getDROMode()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.DRO_LEVEL, "" + m.getDROLevel()); } catch (Throwable ignored) {}
        try { v.put(UserSettings.FORMAT, m.getPictureStorageFormat()); } catch (Throwable ignored) {}
        return v;
    }

    /** Puts the user's own settings back; what the camera does not take stays saved, for the next time. */
    private void restoreUser(String when, int... groups) {
        if (camera == null) return;
        String left = user.restore(userCam, groups);
        if (left == null) return;
        Diag.report("Settings not put back (" + when + (user.gaveUp() ? ", given up" : "") + "): " + left);
        if (user.gaveUp()) flash("Please check your camera settings.\nNot put back: " + left, 8000);
        else setNote("Some camera settings not back yet");
    }

    private void closeCamera() {
        ui.removeCallbacks(retryLive);
        ui.removeCallbacks(reopenLive);
        ui.removeCallbacks(startLiveLater);
        ui.removeCallbacks(openWaiting);
        ui.removeCallbacks(checkLive);
        ui.removeCallbacks(reopenCheck);
        liveStarted = false;
        if (camera == null) return;
        if (aeLocked) setAeLock(false);                    // never leave exposure locked
        endMoveSpot();
        focus.close();
        // The quality on its own, last: with RAW the camera may not report a JPEG size to check.
        restoreUser("close", UserSettings.G_FOCUS, UserSettings.G_AF, UserSettings.G_SHARPNESS, UserSettings.G_STYLE, UserSettings.G_SIZE, UserSettings.G_DRO);
        restoreUser("close", UserSettings.G_FORMAT);
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
     * restoreUser(); if a session ends abruptly, they stay in prefs and are restored the next
     * time the camera opens. Values still waiting to be put back are never overwritten (saveBase).
     */
    private void applyBase() {
        if (camera == null) return;
        String base = BASES[baseIndex];
        String size = SIZES[sizeIndex];
        boolean style = !"camera".equals(base);
        try {
            android.hardware.Camera.Parameters now = params();
            CameraEx.ParametersModifier cur = camera.createParametersModifier(now);
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
            // Never change what cannot be saved first.
            Map<String, String> mine = settings(now);
            if (mine.get(UserSettings.MODE) == null || mine.get(UserSettings.CONTRAST) == null || mine.get(UserSettings.SATURATION) == null) style = false;
            if (mine.get(UserSettings.SIZE) == null) sized = false;
            if (mine.get(UserSettings.DRO) == null) dro = false;
            if (!style && !sized && !dro) return;
            user.saveBase(mine, style, sized, dro);
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
     * and put back by restoreUser(). Only with a base style (not "Camera settings").
     */
    private void applyLookSharpness() {
        if (camera == null || "camera".equals(BASES[baseIndex]) || looks == null) return;
        try {
            if (!prefs.contains("userSharpness")) {
                CameraEx.ParametersModifier cur = camera.createParametersModifier(params());
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

    // ---------------------------------------------------------------- RAW only -> RAW+JPEG

    private boolean rawOnly;       // the camera saves no JPEG, so there is nothing to convert

    /**
     * The looks are applied to the JPEG, so with Quality = RAW nothing would ever be converted. Then the
     * app shoots RAW+JPEG (the RAW is saved just the same) and puts RAW back when it closes the camera.
     * JPEG and RAW+JPEG are left alone. Says so if the camera refuses.
     */
    private void applyStorageFormat() {
        rawOnly = false;
        if (camera == null) return;
        try {
            CameraEx.ParametersModifier cur = camera.createParametersModifier(params());
            String fmt = cur.getPictureStorageFormat();
            // Left over from an abrupt exit, but changed by the user since: forget it.
            if ("jpeg".equals(fmt)) prefs.edit().remove("userStorageFmt").commit();
            if (!"raw".equals(fmt)) return;
            rawOnly = true;
            List ok = cur.getSupportedPictureStorageFormats();
            if (ok == null || ok.contains("rawjpeg")) {
                prefs.edit().putString("userStorageFmt", fmt).commit();
                android.hardware.Camera.Parameters p = camera.createEmptyParameters();
                camera.createParametersModifier(p).setPictureStorageFormat("rawjpeg");
                camera.getNormalCamera().setParameters(p);
                opening = camera.getNormalCamera().getParameters();
                cur = camera.createParametersModifier(opening);
                rawOnly = "raw".equals(cur.getPictureStorageFormat());
            }
        } catch (Throwable t) {
            Diag.trace("storage format: " + t);
        }
        Diag.trace("quality RAW -> " + (rawOnly ? "refused" : "RAW+JPEG"));
        if (rawOnly) warnRawOnly();
        else setNote("RAW+JPEG in the app (yours: RAW)");
    }

    private void warnRawOnly() {
        flash("Quality is RAW: no JPEG to convert\nSet RAW & JPEG in the camera menu", 4000);
    }

    // ---------------------------------------------------------------- JPEG sizes of this camera

    /**
     * Megapixels of L / M / S from the camera ("image-pixel-size-m-3-2" = "5168x3448"), for the aspect
     * ratio in use. Not shown in APS-C crop, where these may not apply.
     */
    private void readImageSizes() {
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters p = params();
            String aspect = p.get("image-aspect"), crop = p.get("apsc-mode");
            String suffix = "-" + (aspect == null ? "3:2" : aspect).replace(':', '-');
            boolean full = crop == null || "off".equals(crop);
            for (int i = 0; i < SIZES.length; i++) sizeMp[i] = full ? megapixels(p.get("image-pixel-size-" + SIZE_KEYS[i] + suffix)) : 0;
        } catch (Throwable t) {
            Diag.trace("image sizes: " + t);
        }
        if (!sizeAuto) return;
        int best = -1;
        for (int i = 0; i < SIZES.length; i++)
            if (sizeMp[i] > 0 && sizeMp[i] <= AUTO_MAX_MP && (best < 0 || sizeMp[i] > sizeMp[best])) best = i;
        if (best >= 0) sizeIndex = best;
    }

    /** "5168x3448" -> 17.8; 0 if missing or not understood. */
    private static float megapixels(String wh) {
        if (wh == null) return 0;
        String[] s = wh.trim().split("x");
        if (s.length != 2) return 0;
        try { return Integer.parseInt(s[0]) * (float) Integer.parseInt(s[1]) / 1e6f; } catch (NumberFormatException e) { return 0; }
    }

    private String sizeName() {
        return SIZE_SHORT[sizeIndex] + (sizeMp[sizeIndex] > 0 ? " " + Math.round(sizeMp[sizeIndex]) + "MP" : "");
    }

    private void cycleSize() { cycleSize(1); }

    private void cycleSize(int dir) {
        restoreUser("size", UserSettings.G_STYLE, UserSettings.G_SIZE, UserSettings.G_DRO);
        sizeIndex = ((sizeIndex + dir) % SIZES.length + SIZES.length) % SIZES.length;
        sizeAuto = false;
        applyBase();
        updateTexts();
        setNote("JPEG size: " + sizeName());
    }

    /**
     * READ-ONLY diagnostic, once per launch: writes every camera parameter (Camera.Parameters.flatten)
     * and the method names of Sony's ParametersModifier to FILMDRAW/PARAMS.TXT, to find out which
     * settings (e.g. Picture Profile) apps can reach on this body. Changes nothing on the camera.
     */
    private boolean paramsDumped;

    private void dumpCameraParameters() {
        if (paramsDumped || camera == null) return;
        paramsDumped = true;
        try {
            File out = new File(Photos.appDir(), "PARAMS.TXT");
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
            sb.append("   ").append(focusName(fm, p.get("af-mode")));
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

    /** Exposure mode for a mode-dial position (ISV_KEY_MODE_* / ISV_KEY_EXPOSURE_MODE_*), or null. */
    private static String sceneFor(int code) {
        switch (code) {
            case 537: case 628: return "program-auto";
            case 539: case 629: return "aperture-priority";
            case 538: case 630: return "shutter-speed";
            case 540: case 631: return "manual-exposure";
            default: return null;
        }
    }

    /**
     * The mode dial. While an app is open the camera leaves it to the app: without this, turning A -> M kept
     * A until the app was reopened. Sets the exposure mode for the dial's position; if that position is
     * unknown or refused, the camera is reopened instead (fallback), as leaving and reopening the app does.
     */
    private void applyModeDial(boolean fallback) {
        if (camera == null || browsing) return;
        Integer st = Diag.keyStatusValue(K_MODE_DIAL);
        String want = st == null ? null : sceneFor(st), was = null, got = null;
        try {
            was = got = params().get("scene-mode");
            if (want != null && !want.equals(was)) {
                android.hardware.Camera.Parameters p = camera.createEmptyParameters();
                p.setSceneMode(want);
                camera.getNormalCamera().setParameters(p);
                got = camera.getNormalCamera().getParameters().get("scene-mode");
            }
        } catch (Throwable t) {
            Diag.trace("mode dial: " + t);
        }
        Diag.trace("mode dial " + st + " -> " + want + " (was " + was + ", now " + got + ")");
        if (fallback && (want == null || !want.equals(got))) {
            closeCamera();
            openCamera();
            hideReview();
        }
        updateExposureText();
        if (fallback) setNote("Mode " + modeName(sceneMode));
    }

    private final Runnable modeDialSettled = new Runnable() {
        public void run() { applyModeDial(true); }
    };

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
                stepEv(dir);
                return;
            }
        } catch (Throwable t) { flash("Dial: " + t, 2000); }
        updateExposureText();
    }

    /** Exposure compensation by a third of a stop. */
    private void stepEv(int dir) {
        if (camera == null || browsing) return;
        try {
                android.hardware.Camera.Parameters cur = camera.getNormalCamera().getParameters();
                int ev = Math.max(cur.getMinExposureCompensation(), Math.min(cur.getMaxExposureCompensation(),
                        cur.getExposureCompensation() + dir));
                android.hardware.Camera.Parameters p = camera.createEmptyParameters();
                p.setExposureCompensation(ev);
                camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) { flash("Dial: " + t, 2000); }
        updateExposureText();
    }

    // ---- control options (menu), for other dial set-ups and bodies with a broken dial
    private static final String[] DIAL_NAMES = {"Front f/, rear S", "Front S, rear f/"};
    private static final String[] WHEEL_NAMES = {"Looks", "ISO"};
    private int dialIndex, wheelIndex;

    /** A dial turned while shooting: front (0) or rear (1). */
    private void shootDial(int which, int dir) {
        if (which == dialIndex) frontDial(dir);
        else rearDial(dir);                    // shutter speed; EV where the camera sets the shutter
    }

    /** True if the wheel changes ISO while shooting (looks are then on Up/Down only). */
    private boolean wheelIsIso() { return wheelIndex == 1; }

    private static boolean isWheel(int sc) { return sc == WHEEL_CW || sc == WHEEL_CCW || sc == D3_CW || sc == D3_CCW; }

    /** Next / previous ISO the camera offers (0 = Auto, listed first). */
    private void stepIso(int dir) {
        if (camera == null || browsing) { setNote("ISO: change it while shooting"); return; }
        try {
            android.hardware.Camera.Parameters cur = camera.getNormalCamera().getParameters();
            String list = cur.get("iso-sensitivity-values"), now = cur.get("iso-sensitivity");
            if (list == null || now == null) { setNote("ISO cannot be changed here"); return; }
            String[] v = list.split(",");
            int at = 0;
            for (int i = 0; i < v.length; i++) if (v[i].trim().equals(now.trim())) at = i;
            int to = Math.max(0, Math.min(v.length - 1, at + dir));
            if (to == at) return;
            android.hardware.Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setISOSensitivity(Integer.parseInt(v[to].trim()));
            camera.getNormalCamera().setParameters(p);
            Diag.trace("ISO " + now + " -> " + v[to]);
        } catch (Throwable t) { flash("ISO: " + t, 2000); }
        updateExposureText();
    }

    private String menuFocus = "-", menuIso = "-";

    /**
     * The menu's camera values, with ONE parameter read: when the menu opens and after one of them changes.
     * (Up to 0.36 every menu key read all parameters twice, which made the menu slow.)
     */
    private void readMenuValues() {
        menuFocus = browsing ? "(shooting only)" : "-";
        menuIso = "-";
        if (camera == null || browsing) return;
        try {
            android.hardware.Camera.Parameters p = camera.getNormalCamera().getParameters();
            menuFocus = focusName(p.getFocusMode(), p.get("af-mode"));
            String iso = p.get("iso-sensitivity");
            if (iso != null) menuIso = "0".equals(iso.trim()) ? "Auto" : iso.trim();
        } catch (Throwable t) {
            Diag.trace("menu values: " + t);
        }
    }

    private void cycleBase() { cycleBase(1); }

    private void cycleBase(int dir) {
        restoreUser("base style", UserSettings.G_SHARPNESS, UserSettings.G_STYLE, UserSettings.G_SIZE, UserSettings.G_DRO);
        baseIndex = ((baseIndex + dir) % BASES.length + BASES.length) % BASES.length;
        applyBase();
        updateTexts();
        setNote("Base: " + BASE_NAMES[baseIndex]);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        liveStarted = false;                       // not on this surface
        startLiveView();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        liveStarted = false;
    }

    private void shutterPressed() {
        if (camera == null || liveTries > 0) return;
        if (sharpPending) { ui.removeCallbacks(applySharpnessLater); applySharpnessLater.run(); }   // look just changed
        try {
            if (rawOnly) {                                  // no JPEG will come: never queue a look for it
                camera.getNormalCamera().takePicture(null, null, null);
                warnRawOnly();
                return;
            }
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
                    Looks.Look cur = current;
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
        clearLiveFailure();
        closeCamera();
        live.setVisibility(View.INVISIBLE);        // drops the surface: the live view gets a new one, as when the app starts
        boolean wasShooting = !browsing;
        browsing = true;
        reviewing = false;
        ui.removeCallbacks(endReview);
        hideCardNow();
        updateDepth();
        photoView.setVisibility(View.VISIBLE);
        // Opened from shooting: the newest photo. Coming back to the app while browsing: stay on the same one.
        loadPhotos(wasShooting, wasShooting, false);
        if (wasShooting) showHelp(); else updateTexts();
    }

    private int listGen;
    private boolean listing;                      // the list of photos is being read: photos is null

    /**
     * Reads the list of photos in the background (reading the card can take seconds while the camera is saving), then
     * shows one: the newest, or the same as before. allIfNone: all photos if nothing has been converted yet.
     */
    private void loadPhotos(final boolean newest, final boolean allIfNone, final boolean say) {
        final int gen = ++listGen;
        final boolean want = showConverted;
        listing = true;
        photos = null;
        photoView.setImageBitmap(null);
        synchronized (previewLock) { previewRequest = null; prefetchTask = null; previewGen++; }
        showLoading();
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean c = want;
                List<File> found;
                long t0 = System.currentTimeMillis();
                try {
                    found = c ? Photos.allOutputs() : Photos.allJpegs();
                    if (c && allIfNone && found.isEmpty()) {
                        c = false;
                        found = Photos.allJpegs();
                    }
                } catch (Throwable e) {
                    Diag.trace("list failed " + e);
                    found = new java.util.ArrayList<File>();
                }
                Diag.trace("list of " + found.size() + " read, " + (System.currentTimeMillis() - t0) + " ms");
                final boolean conv = c;
                final List<File> list = found;
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != listGen || !browsing) return;
                        listing = false;
                        showConverted = conv;
                        photos = list;
                        if (newest || photoIndex < 0 || photoIndex >= photos.size()) {
                            photoIndex = Math.max(0, photos.size() - 1);
                            lastDir = -1;
                            showOriginal = false;
                        }
                        if (say) setNote(conv ? "Converted photos (" + photos.size() + ")" : "All photos");
                        updateTexts();
                        requestPreview(true);
                    }
                });
            }
        }, "filmlut-list");
        t.setDaemon(true);
        t.start();
    }

    /** Fn in browse: all photos <-> converted photos (which the camera's own playback cannot show). */
    private boolean showConverted;

    private void toggleConverted() {
        showConverted = !showConverted;
        Diag.trace("browse " + (showConverted ? "converted" : "all photos"));
        loadPhotos(true, false, true);                    // newest
        updateTexts();
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
        if (listing) return;                               // it comes when the list is read
        if (photos == null || photos.isEmpty()) { photoView.setImageBitmap(null); hideLoading(); return; }
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
        if (newPhoto && !cached) showLoading();
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

    private String adobePath, adobeLook;       // last answers (preview thread only)
    private boolean adobeIs;
    private byte[] adobeBytes;

    /** The look's LUT for this photo: Adobe RGB photos need their own (as the conversion does). */
    private byte[] lutFor(File photo, Looks.Look lk) throws Exception {
        String path = photo.getAbsolutePath();
        if (!path.equals(adobePath)) { adobeIs = Native.isAdobe(path); adobePath = path; }
        byte[] lut = looks.lut(lk);
        if (!adobeIs) return lut;
        if (!lk.id.equals(adobeLook)) { adobeBytes = Native.adobeLut(lut); adobeLook = lk.id; }
        return adobeBytes != null ? adobeBytes : lut;
    }

    /** The look applied to a small decode: LUT, then halation (as the conversion; grain is left out
     *  for speed). Returns [w, h, argb...] or null. */
    private int[] withLook(File photo, int[] src, Looks.Look lk) {
        int w = src[0], h = src[1];
        int[] px = new int[2 + w * h];
        px[0] = w;
        px[1] = h;
        try {
            if (!Native.lutArgb(src, px, 2, w * h, lutFor(photo, lk))) { postFlash("LUT failed"); return null; }
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
            // Converted view: `photo` is a file from FILMDRAW/; show it as it is (cached / pre-loaded).
            int[] px = convPixels(photo);
            if (stale(gen)) return;                        // e.g. deleted meanwhile
            if (px == null) {
                ui.post(new Runnable() { public void run() { if (!stale(gen) && browsing) photoView.setImageBitmap(null); } });   // not the one before
                postFlash("Cannot read " + photo.getName());
                return;
            }
            postShow(toBitmap(px, photo), gen);
            return;
        }
        File out = Photos.outputFor(photo, lk.tag);
        if (!original && out.exists()) {
            // Already converted with this look: show the real result rather than a preview of it.
            int[] px = Native.preview(out.getAbsolutePath(), PREVIEW_MAX_W, PREVIEW_MAX_H);
            if (stale(gen)) return;
            if (px == null) { postFlash("Cannot read " + out.getName()); return; }
            bmp = toBitmap(px, out);
        } else {
            int[] src;
            synchronized (previewLock) {
                src = photo.getAbsolutePath().equals(previewPath) ? previewPixels : null;
            }
            if (src == null) {
                src = Native.preview(photo.getAbsolutePath(), PREVIEW_MAX_W, PREVIEW_MAX_H);
                if (src == null) { if (!stale(gen)) postFlash("Cannot read " + photo.getName()); return; }
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
                loading = false;
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
        if (menuOpen) return;                              // covered by the menu: do not render a preview for it
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

    private static final int M_LOOK = 0, M_GRAIN = 1, M_BASE = 2, M_SIZE = 3, M_FOCUS = 4, M_AREA = 5, M_POINT = 6, M_ISO = 7,
            M_DIALS = 8, M_WHEEL = 9, M_REVIEW = 10, M_CONVERTED = 11, M_HELP = 12, M_ABOUT = 13, M_EXIT = 14, M_CONTROLS = 15,
            M_FAV = 16;
    /** The menu, and the Controls menu inside it. */
    private static final int[] MENU_MAIN = {M_LOOK, M_GRAIN, M_BASE, M_SIZE, M_REVIEW, M_CONTROLS, M_FAV, M_CONVERTED, M_ABOUT, M_EXIT};
    private static final int[] MENU_CONTROLS = {M_POINT, M_AREA, M_FOCUS, M_ISO, M_DIALS, M_WHEEL, M_HELP};
    private int[] menuRows = MENU_MAIN;
    private int menuMainSel;                      // row to come back to from Controls

    private boolean menuDirect;                   // Controls opened with Trash: going back closes the menu
    private long menuDirectAt;

    /** Trash while shooting: straight to Controls, with the focus point first. */
    private void openControlsDirect() {
        openMenu();                               // main menu first: fits the text size (once) and resets the rest
        openControls();
        menuDirect = true;
        menuDirectAt = System.currentTimeMillis();
        renderMenu();
    }

    private void openControls() {
        menuMainSel = menuSel;
        menuRows = MENU_CONTROLS;
        menuSel = 0;
        menuFooter = null;
        readMenuValues();
        renderMenu();
    }

    private void closeControls() {
        if (menuDirect) { closeMenu(); return; }
        menuRows = MENU_MAIN;
        menuSel = menuMainSel;
        menuFooter = null;
        renderMenu();
    }
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
            case M_AREA: return "Focus area";
            case M_POINT: return "Move focus point";
            case M_ISO: return "ISO";
            case M_DIALS: return "Dials";
            case M_WHEEL: return "Wheel";
            case M_REVIEW: return "Quick review";
            case M_CONVERTED: return !browsing || showConverted ? "All photos" : "Converted photos";
            case M_CONTROLS: return "Controls";
            case M_FAV: return "Favourites";
            case M_HELP: return "What buttons do";
            case M_ABOUT: return "About & licences";
            default: return "Exit application";
        }
    }

    private String menuValue(int i) {
        switch (i) {
            case M_LOOK: return look().name;
            case M_GRAIN: return grainIndex == 0 ? "Auto (" + look().grain + ")" : GRAIN_NAMES[grainIndex];
            case M_BASE: return BASE_NAMES[baseIndex];
            case M_SIZE: return sizeName();
            case M_CONTROLS: return "";
            case M_FAV: return "";
            case M_FOCUS: return menuFocus;
            case M_AREA: return browsing ? "-" : focus.areaName();
            case M_ISO: return menuIso;
            case M_DIALS: return DIAL_NAMES[dialIndex];
            case M_WHEEL: return WHEEL_NAMES[wheelIndex];
            case M_REVIEW: return REVIEW_NAMES[reviewIndex];
            default: return null;
        }
    }

    // ---- MENU > Favourites: one group at a time, OK marks. Never changes the look in use.

    private boolean favOpen;
    private int favGroup, favSel;
    private long favClosed;

    private int favCount() { return looks.nav.size() - looks.all.size(); }

    /** Opens on the look in use. */
    private void openFavourites() {
        favOpen = true;
        List<Looks.Look> heads = looks.ownGroupHeads();
        for (favGroup = heads.size() - 1; favGroup > 0; favGroup--) {
            favSel = favRow(heads.get(favGroup));
            if (favSel >= 0) break;
        }
        if (favGroup == 0) favSel = Math.max(0, favRow(heads.get(0)));
        renderFavourites();
    }

    private int favRow(Looks.Look head) {
        List<Looks.Look> g = looks.inGroup(head);
        for (int i = 0; i < g.size(); i++) if (g.get(i).id.equals(look().id)) return i;
        return -1;
    }

    private void renderFavourites() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        List<Looks.Look> heads = looks.ownGroupHeads();
        sb.append("Film Drawer");
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
        sb.append("  \u203a  Favourites     ").append("" + favCount()).append(" marked\n   \u2039 ").append(heads.get(favGroup).group)
                .append(" \u203a   " + (favGroup + 1) + "/" + heads.size());
        List<Looks.Look> g = looks.inGroup(heads.get(favGroup));
        for (int i = 0; i < g.size(); i++) {
            sb.append('\n');
            int s = sb.length();
            sb.append(favourites.contains(g.get(i).id) ? "  [x] " : "  [ ] ").append(g.get(i).name);
            for (int pad = g.get(i).name.length(); pad < 18; pad++) sb.append(' ');
            if (i == favSel) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(ACCENT), s, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Color.BLACK), s, sb.length(), 0);
            }
        }
        sb.append("\n");
        int f = sb.length();
        sb.append("Up/Down: look   Left/Right: group   OK: mark   MENU: back");
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF9A9A9A), f, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), f, sb.length(), 0);
        if (menuSize > 0) menuTextSize(menuSize);
        menuView.setText(sb);
    }

    private void favouriteKey(int sc) {
        List<Looks.Look> heads = looks.ownGroupHeads();
        List<Looks.Look> g = looks.inGroup(heads.get(favGroup));
        switch (sc) {
            case K_UP: case WHEEL_CCW: case D1_CCW: case D2_CCW: case D3_CCW: favSel = (favSel + g.size() - 1) % g.size(); break;
            case K_DOWN: case WHEEL_CW: case D1_CW: case D2_CW: case D3_CW: favSel = (favSel + 1) % g.size(); break;
            case K_LEFT: favGroup = (favGroup + heads.size() - 1) % heads.size(); favSel = 0; break;
            case K_RIGHT: favGroup = (favGroup + 1) % heads.size(); favSel = 0; break;
            case K_ENTER: toggleFavourite(g.get(favSel).id); break;
            case K_MENU: case K_SK2: case K_DELETE:
                favOpen = false;
                favClosed = System.currentTimeMillis();
                renderMenu();
                return;
            case K_S1: case K_PLAY: closeMenu(); return;
            default: return;
        }
        renderFavourites();
    }

    private void toggleFavourite(String id) {
        if (!favourites.remove(id)) favourites.add(id);
        StringBuilder sb = new StringBuilder();
        for (String f : favourites) sb.append(sb.length() > 0 ? "," : "").append(f);
        prefs.edit().putString("favourites", sb.toString()).apply();
        Diag.trace("favourites " + sb);
        // Stays on the same look: in Favourites if it was there and still is, else in its own group.
        Looks.Look was = look();
        looks.setFavourites(favourites);
        lookIndex = looks.navIndex(was.id, was.isFavourite());
        current = looks.nav.get(lookIndex);
    }

    private void renderMenu() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int s0 = sb.length();
        sb.append("Film Drawer ").append(appVersion);
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), s0, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s0, sb.length(), 0);
        if (menuRows == MENU_CONTROLS) sb.append("  \u203a  Controls");
        for (int row = 0; row < menuRows.length; row++) {
            int i = menuRows[row];
            sb.append('\n');
            int s = sb.length();
            String v = menuValue(i);
            sb.append("  ").append(menuLabel(i));
            if (v != null) {
                for (int pad = menuLabel(i).length(); pad < 16; pad++) sb.append(' ');
                if (v.length() == 0) sb.append("  ").append(i == M_FAV && favCount() > 0 ? favCount() + " marked " : "").append("›"); else sb.append("‹ ").append(v).append(" ›");
            }
            sb.append("  ");
            if (row == menuSel) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(ACCENT), s, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Color.BLACK), s, sb.length(), 0);
            }
        }
        sb.append("\n");
        int f = sb.length();
        sb.append(menuFooter != null ? menuFooter : "Up/Down: choose   Left/Right/OK: change   MENU: "
                + (menuRows == MENU_CONTROLS && !menuDirect ? "back" : "close"));
        sb.setSpan(new android.text.style.ForegroundColorSpan(menuFooter != null ? ACCENT : 0xFF9A9A9A), f, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), f, sb.length(), 0);
        if (menuView.getTypeface() != android.graphics.Typeface.MONOSPACE) menuView.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (menuSize > 0) menuTextSize(menuSize);          // before the text: one layout, not two
        menuView.setText(sb);
        if (menuSize > 0) return;
        // Once: the largest text that fits this screen, measured on the camera itself, with the main menu (the
        // longer one, always shown first); for the width, room is kept for the widest row ("Move focus point" aside: 16 + a long look name).
        View root = (View) menuView.getParent();
        int maxW = root.getWidth(), maxH = root.getHeight();
        if (maxH <= 0) return;                             // not laid out yet
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 46; i++) wide.append('M');
        for (int size = 18; size >= 10; size--) {
            menuTextSize(size);
            menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float w = menuView.getPaint().measureText(wide.toString()) + menuView.getPaddingLeft() + menuView.getPaddingRight();
            menuSize = size;
            if (menuView.getMeasuredHeight() <= maxH - 8 && w <= maxW) break;
        }
    }

    private int menuSize, menuSizeNow;                     // fitted once; 0 = not yet

    private void menuTextSize(int size) {
        if (size == menuSizeNow) return;
        menuSizeNow = size;
        menuView.setTextSize(size);
    }

    private void openMenu() {
        Diag.trace("menu open");
        menuDirect = false;
        hideReview();
        hideCardNow();
        menuOpen = true;
        aboutOpen = false;
        favOpen = false;
        menuFooter = null;
        exitArmedUntil = 0;
        if (menuRows != MENU_MAIN) { menuRows = MENU_MAIN; menuSel = menuMainSel; }
        renderMenu();
        menuView.setVisibility(View.VISIBLE);
    }

    // ---- deleting a converted photo (Trash, converted view; uses the menu's panel). Originals are never deleted.
    private File deleteFile;                      // non-null: the question is on screen
    private boolean deleteYes;
    private String deleteOriginal;
    private long deleteAt;                        // Trash may send two codes per press: opened / closed just now

    private void askDelete() {
        if (photos == null || photos.isEmpty() || System.currentTimeMillis() - deleteAt < 600) return;
        if (loading) { setNote("Still loading"); return; }                // only what can be seen
        File f = photos.get(photoIndex);
        if (jobs.writes(f)) { setNote("Still converting"); return; }
        deleteFile = f;
        deleteOriginal = Photos.originalOf(f);
        deleteYes = false;
        deleteAt = System.currentTimeMillis();
        menuOpen = true;
        aboutOpen = false;
        renderDelete();
        menuView.setVisibility(View.VISIBLE);
    }

    private void renderDelete() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append("Delete this converted photo?");
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
        sb.append("\n" + deleteFile.getParentFile().getName() + "/" + deleteFile.getName() + "\n"
                + (deleteOriginal != null ? "The original stays (" + deleteOriginal + ")"
                                          : "Its original is NOT on the card: this is the only copy here") + "\n");
        for (int i = 0; i < 2; i++) {
            sb.append('\n');
            int s = sb.length();
            sb.append(i == 0 ? "   Delete   " : "   Cancel   ");
            if ((i == 0) == deleteYes) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(ACCENT), s, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Color.BLACK), s, sb.length(), 0);
            }
        }
        menuView.setTypeface(android.graphics.Typeface.DEFAULT);
        menuTextSize(18);
        menuView.setText(sb);
    }

    private void closeDelete() {
        deleteFile = null;
        deleteAt = System.currentTimeMillis();
        closeMenu();
    }

    /** All keys while the question is on screen (key-down). Cancel is chosen first; only OK on Delete deletes. */
    private void deleteKey(int sc) {
        switch (sc) {
            case K_UP: case K_LEFT: case WHEEL_CCW: case D1_CCW: case D2_CCW: case D3_CCW: deleteYes = true; renderDelete(); return;
            case K_DOWN: case K_RIGHT: case WHEEL_CW: case D1_CW: case D2_CW: case D3_CW: deleteYes = false; renderDelete(); return;
            case K_ENTER:
                File f = deleteFile;
                boolean yes = deleteYes;
                closeDelete();
                if (yes) deleteNow(f);
                return;
            case K_DELETE: case K_SK2:
                if (System.currentTimeMillis() - deleteAt < 400) return;
                closeDelete();
                return;
            case K_MENU: case K_S1: case K_PLAY:
                closeDelete();
        }
    }

    private void deleteNow(File f) {
        int i = photos.indexOf(f);
        if (i < 0) return;
        if (jobs.writes(f)) { setNote("Still converting"); return; }
        boolean ok = Photos.deleteOutput(f);
        Diag.trace("delete " + f.getName() + (ok ? " ok" : " FAILED"));
        if (!ok) { flash("Could not delete " + f.getName(), 3000); return; }
        synchronized (previewLock) { convCache.remove(f.getAbsolutePath()); prefetchTask = null; }
        photos.remove(i);
        // The next photo takes its place; at the newest end, the one before.
        photoIndex = Math.max(0, Math.min(i, photos.size() - 1));
        setNote("Deleted " + f.getName());
        updateTexts();
        requestPreview(true);
    }

    private void closeMenu() {
        menuOpen = false;
        aboutOpen = false;
        favOpen = false;
        menuView.setVisibility(View.GONE);
        updateTexts();
        updateExposureText();
    }

    /** Controls > What buttons do: a page that stays until a button is pressed. */
    private void showButtons() {
        aboutOpen = true;
        boolean iso = wheelIsIso();
        String dials = dialIndex == 1 ? "Front dial:  shutter / EV     Rear dial:  aperture"
                : "Front dial:  aperture     Rear dial:  shutter / EV";
        menuView.setTypeface(android.graphics.Typeface.DEFAULT);
        menuTextSize(16);
        menuView.setText("SHOOTING\n"
                + (iso ? "Up / Down:  look     Wheel:  ISO\n" : "Wheel, Up / Down:  look\n")
                + "Left / Right:  next group of looks\n"
                + dials + "\n"
                + "Fn:  base style     C1:  JPEG size     C2:  grain\n"
                + "Trash:  controls (focus point first)     OK:  short help\n"
                + "PLAY:  your converted photos\n\n"
                + "YOUR PHOTOS\n"
                + "Left / Right:  photo     Wheel, Up / Down:  look\n"
                + "OK:  convert     Trash:  before / after (converted: delete)\n"
                + "Fn:  all photos / converted     PLAY:  shoot\n\n"
                + "Any button: back");
    }

    private void showAbout() {
        aboutOpen = true;
        menuView.setTypeface(android.graphics.Typeface.DEFAULT);
        menuTextSize(18);
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
                readMenuValues();
                break;
            case M_AREA:
                setNote(focus.cycleArea(dir));
                break;
            case M_ISO:
                stepIso(dir);
                readMenuValues();
                break;
            case M_DIALS:
                dialIndex = (dialIndex + dir + DIAL_NAMES.length) % DIAL_NAMES.length;
                break;
            case M_WHEEL:
                wheelIndex = (wheelIndex + dir + WHEEL_NAMES.length) % WHEEL_NAMES.length;
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
            case M_CONTROLS:
                openControls();
                return;
            case M_FAV:
                openFavourites();
                return;
            case M_CONVERTED:
                closeMenu();
                if (browsing) toggleConverted();
                else { showConverted = false; enterBrowse(); }   // PLAY opens the converted ones
                return;
            case M_POINT:
                closeMenu();
                startMoveSpot();
                return;
            case M_HELP:
                showButtons();
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
        if (favOpen) { favouriteKey(sc); return; }
        switch (sc) {
            case K_UP: case WHEEL_CCW: case D1_CCW: case D2_CCW: case D3_CCW:
                menuSel = (menuSel + menuRows.length - 1) % menuRows.length; menuFooter = null; break;
            case K_DOWN: case WHEEL_CW: case D1_CW: case D2_CW: case D3_CW:
                menuSel = (menuSel + 1) % menuRows.length; menuFooter = null; break;
            case K_LEFT: menuChange(menuRows[menuSel], -1); break;
            case K_RIGHT:
                if (menuRows[menuSel] == M_CONTROLS) { openControls(); return; }
                if (menuRows[menuSel] == M_FAV) { openFavourites(); return; }
                menuChange(menuRows[menuSel], 1);
                break;
            case K_ENTER: menuActivate(menuRows[menuSel]); return;
            case K_MENU: case K_SK2: case K_DELETE:
                // Trash may send two codes per press: the second must not close what the first opened.
                if (menuDirect && sc != K_MENU && System.currentTimeMillis() - menuDirectAt < 400) return;
                if (sc != K_MENU && System.currentTimeMillis() - favClosed < 400) return;
                if (menuRows == MENU_CONTROLS) { closeControls(); return; }
                closeMenu();
                return;
            case K_S1: case K_PLAY:
                closeMenu(); return;
            default:
                return;
        }
        if (menuOpen && !aboutOpen) renderMenu();
    }

    // ---------------------------------------------------------------- keys

    private void startMoveSpot() {
        if (camera == null || browsing) { setNote("Focus point: move it while shooting"); return; }
        hideCardNow();
        String why = focus.startMove();
        if (why != null) { flash(why, 2500); return; }
        movingSpot = true;
        setNote(focus.areaName());
        showMoveHelp();
    }

    private void showMoveHelp() {
        bottom.setText("Arrows: move the focus point\nOK or half-press: done   Trash: back to before");
        bottom.setVisibility(View.VISIBLE);
    }

    private void endMoveSpot() {
        if (!movingSpot) return;
        movingSpot = false;
        moveEnded = System.currentTimeMillis();
        focus.endMove();
        updateTexts();
    }

    private long moveEnded;        // Trash may send two codes per press: the second must not start again

    private void cancelMoveSpot() {
        movingSpot = false;
        focus.cancelMove();
        moveEnded = System.currentTimeMillis();
        setNote("Focus area: " + focus.areaName());
        updateTexts();
    }

    /** Keys while the focus point is being moved. False = act on the key as usual (the shutter also ends moving). */
    private boolean moveKey(int sc) {
        String problem = null;
        switch (sc) {
            case K_UP: problem = focus.move(0, -1); break;
            case K_DOWN: problem = focus.move(0, 1); break;
            case K_LEFT: problem = focus.move(-1, 0); break;
            case K_RIGHT: problem = focus.move(1, 0); break;
            case K_S1: case K_S2: endMoveSpot(); return false;
            case K_ENTER: case K_MENU: case K_PLAY: case K_FN: endMoveSpot(); return true;
            case K_DELETE: case K_SK2: cancelMoveSpot(); return true;
            case K_S1_2: case K_EV_DIAL: case K_AEL: case D1_CW: case D1_CCW: case D2_CW: case D2_CCW:
                return false;                               // exposure stays adjustable
            default: break;
        }
        if (problem != null) setNote(problem);
        return true;
    }

    private void changeLook(int delta) {
        int n = looks.nav.size();
        setLook(((lookIndex + delta) % n + n) % n);
    }

    private void setLook(int index) {
        lookIndex = index;
        current = looks.nav.get(index);
        Diag.trace("look " + look().id + (look().isFavourite() ? " (favourite)" : ""));
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
        if (event.getScanCode() == K_MODE_DIAL || sceneFor(event.getScanCode()) != null) {
            ui.removeCallbacks(modeDialSettled);               // once the dial has settled (menu open or not)
            ui.postDelayed(modeDialSettled, 400);
            return true;
        }
        if (menuOpen) {
            // The menu takes every key; its release is ignored (MENU would otherwise reopen it).
            int sc = event.getScanCode();
            boolean scroll = sc == K_UP || sc == K_DOWN || isWheel(sc);
            if (deleteFile != null) { if (event.getRepeatCount() == 0) deleteKey(sc); }
            else if (event.getRepeatCount() == 0 || scroll) menuKey(sc);
            swallowUp = sc;
            return true;
        }
        if (movingSpot && moveKey(event.getScanCode())) {
            swallowUp = event.getScanCode();
            return true;
        }
        boolean conv = browsing && showConverted;   // converted view: every scroll control changes photo
        if (!browsing && isWheel(event.getScanCode()) && wheelIsIso()) {
            stepIso(event.getScanCode() == WHEEL_CW || event.getScanCode() == D3_CW ? 1 : -1);   // looks are still on Up/Down
            return true;
        }
        switch (event.getScanCode()) {
            case K_UP: case WHEEL_CCW: case D3_CCW:
                if (conv) changePhoto(-1); else changeLook(-1);
                return true;
            case K_DOWN: case WHEEL_CW: case D3_CW:
                if (conv) changePhoto(1); else changeLook(1);
                return true;
            case D1_CW: case D1_CCW:
                // Shooting: front dial = aperture. Browsing: change look (converted view: photo).
                if (conv) changePhoto(event.getScanCode() == D1_CW ? 1 : -1);
                else if (browsing) changeLook(event.getScanCode() == D1_CW ? 1 : -1);
                else shootDial(0, event.getScanCode() == D1_CW ? 1 : -1);
                return true;
            case D2_CW: case D2_CCW:
                // Shooting: rear dial = shutter (S/M) or EV (A/P). Browsing: change photo.
                if (browsing) changePhoto(event.getScanCode() == D2_CW ? 1 : -1);
                else shootDial(1, event.getScanCode() == D2_CW ? 1 : -1);
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
                if (liveTries > 0) {                               // no live view: try again once released
                    if (liveTries > LIVE_RETRIES && event.getRepeatCount() == 0) {
                        liveTries = LIVE_RETRIES;          // one more: released and opened
                        retryLiveSoon();
                    }
                    return true;
                }
                // Half-press autofocuses, except in MF (DMF: AF first, then the ring, as on the camera).
                if (camera != null && event.getRepeatCount() == 0 && !"manual".equals(currentFocusMode())) {
                    try { camera.getNormalCamera().autoFocus(null); afStarted = true; } catch (Throwable ignored) {}
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
                    // Diagnostics (FILMDRAW/DEBUG.TXT): code, the camera's own name and reported position.
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
        if (event.getScanCode() == K_S1) {
            s1Down = false;                                        // also when the release is swallowed below
            if (!browsing && camera == null && liveTries == 0) { ui.removeCallbacks(openWaiting); openWaiting.run(); }
        }
        if (swallowUp != 0 && event.getScanCode() == swallowUp) { swallowUp = 0; return true; }   // it ended a review
        switch (event.getScanCode()) {
            case K_S1:
                s1Down = false;
                focus.clearResult();
                if (!browsing && camera != null && afStarted) {
                    try { camera.getNormalCamera().cancelAutoFocus(); } catch (Throwable ignored) {}
                }
                afStarted = false;
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
                if (browsing) enterShoot();
                else { showConverted = true; enterBrowse(); }     // the camera's own playback cannot show these
                return true;
            case K_DELETE: case K_SK2:
                // Browse: before/after (all photos) or delete (converted). Never exits: MENU > Exit application does.
                if (browsing && showConverted) askDelete();
                else if (browsing) {
                    showOriginal = !showOriginal;
                    updateTexts();
                    requestPreview(false);
                } else if (camera != null && System.currentTimeMillis() - moveEnded > 600) openControlsDirect();
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
        Photos.outputChanged(job.out);
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
