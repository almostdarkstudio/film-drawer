# Film Drawer for the Sony a7R II: developer notes

Working name until 0.30: Film LUT. The package id (`dev.filmlut.app`), source names and the `FILMLUT` card
folder keep it.

A camera app (Android 2.3 / PlayMemories platform) that applies film looks to JPEGs in the camera.
Originals are never modified; results go to `FILMLUT\<DCF folder>\<TAG><number>.JPG` (the card only
accepts 8.3 names).

## Apps
- `filmlut/` - **Film Drawer** (`dev.filmlut.app`).
  - Shoot mode (default): live view; shutter saves the photo as normal; each new JPEG is queued and
    converted in the background with the current look. Wheel / up-down: look. Left/right: jump
    between look groups (`## Group` lines in `luts/app-looks.txt`). OK: help. PLAY: browse. MENU: menu (exit is in it).
  - Screen (0.14): one top bar (look, grain, JPEG size, base), conversion status/notes top right,
    exposure bottom left; help shows for 4 s at start/mode change (OK toggles it in shoot mode).
  - Quick review (0.14): ~2-3 s after a shot (when the file is on the card) the photo is shown in its
    look for 2 s; skipped when another shot was taken or half-press is held; any key ends it (trash,
    MENU, OK, Fn, C1/C2 only dismiss it). Look card: changing look in shoot mode shows the newest
    photo in that look (bottom right, 2.5 s). Shoot mode stays at 4444 overlay depth: switching to
    8888 while live view ran (0.14) made the app stall and crash on the camera.
  - Unfinished conversions are kept in the app's private preferences (not the card) and resume at the
    next start (a job resumed 3 times without finishing is dropped).
  - Browse mode: preview of the look on any photo, or the converted file itself if it exists.
    Left/right: photo. Wheel / up-down: look. OK: convert. Trash: before/after. PLAY or half-press:
    back to shooting. MENU: menu. Previews include halation (same highlight map as the conversion,
    cached per photo) but not grain, which averages away at screen size.
  - Base (Fn in shoot mode): Standard (default) / Neutral / Camera settings. Sets Creative Style +
    contrast/saturation 0 so the LUTs always start from a known image. The user's own values are saved
    to prefs before any change and restored when leaving shoot mode (or on the next launch after an
    abrupt exit). Uses Sony's CameraEx.ParametersModifier, as Sony's own apps do (setColorMode).
  - JPEG size while shooting in the app (C1): M / S / L, megapixels read from the camera (default: the largest up to ~20 MP), via
    ParametersModifier.setImageSize ("midium"/"small"/"large"). Saved and restored with the base
    style; RAW stays full size. Smaller JPEGs convert much faster (decoder is the bottleneck).
  - Dials (0.7+): front = aperture; rear = shutter (S/M) or EV (A/P); wheel = look.
  - DRO is switched off with the base style (DRO Auto re-tones each scene), saved/restored likewise.
  - Grain (C2): Auto (per-look default in `luts/app-looks.txt`) / Off / Low 25 / Medium 50 / High 80.
    Grain after the LUT, strongest in the lower mids and mids, little in dense highlights (tones relative to the
    look's own black and white, 0.59), deterministic per pixel position (parallel == serial). Not shown in the
    browse preview.
  - 40 looks in 8 groups (`luts/app-looks.txt`, assembled by `luts/assemble.py`). Aerochrome is our own
    (`luts/aerochrome.py`: estimated infrared -> red, red -> green, green -> blue; `luts/original.txt`). Almost Dark is derived from
    the Vision3 500T look (`luts/almostdark.py`).
- `hello/` - test app (`dev.filmlut.hello`) used to bring up the toolchain and engine.

- Previews (0.16) decode the embedded MPF preview (a7R II: 1616x1080, ~1 MB) when it is at least as
  wide as needed: ~6x faster than DCT-scaling the full photo; the preview halation map is built from
  it too. Full conversions never use it (byte-identical to 0.15). Files without MPF fall back.
- Diagnostics (0.15, `Diag.java`): FILMLUT/CRASH.TXT (uncaught exceptions) and FILMLUT/HANG.TXT
  (UI thread stuck > 3 s: its stack + the last 80 app events); FILMLUT/TRACE.TXT (the event trace,
  written on leaving the app).

## Engine (`native/`)
- `filmlut.c`: streaming JPEG -> 3D LUT (tetrahedral, 33^3) -> JPEG. Keeps EXIF, 4:2:2, q95.
  Writes `*.TMP` then renames; refuses damaged/incomplete JPEGs; supports cancel and progress.
  - Parallel path (default): 1 decoder thread + 3 LUT+encoder threads. MCU rows are dealt round-robin;
    each encoder uses one restart interval per MCU row, writes to memory, and the rows are stitched
    in order (SOF height patched, RSTs renumbered). Decodes pixel-identical to the serial path.
    Uses ~output size (~15 MB) of native memory; falls back to the serial path if memory/threads fail.
  - Serial path (fallback): decoder, 2 LUT workers, 1 encoder.
  - Camera timing (0.7, serial): encoder busy ~45-56 s = bottleneck; decoder ~30-38 s; LUT ~24 s.
    The a7R II CPU has no NEON (VFPv4 only).
- libjpeg-turbo 2.1.5.1 (`vendor/`), built with ndk-build (`libjpeg.mk`). armeabi-v7a also gets the
  NEON intrinsics, enabled at run time only if `/proc/cpuinfo` lists `neon`.
- `test/`: PC harness (Zig, 32-bit). `powershell -File film-app\native\test\build-test.ps1`, then
  `film-app\build\test\filmlut_test.exe apply|cancel|expectfail|preview ...`.
  Verified: PC pipeline output is byte-identical to the camera's single-threaded output (DSC02097).

## Looks (`luts/`)
- Source: RawTherapee Film Simulation Collection 2015-09-20, CC BY-SA 4.0 (Pat David, Pavlov Dmitry,
  Michael Ezra). `looks.txt` picks 20 and gives each a 3-char tag; `convert.py` resamples to 33^3
  `.lut` files (FLUT format), writes `LICENSE.txt` and a preview sheet.

- Trial: `xmp2lut.py` bakes Lightroom presets from EmulsionPresets (MIT, `vendor/EmulsionPresets`
  @ 4312c97) into LUTs (`emulsion-trial.txt` -> `out-emulsion/`). This is an approximation of
  Lightroom's per-pixel adjustments; grain/clarity/dehaze/halation are ignored. Not yet in the app.

- Look review (`lookreport.py`: ColorChecker metrics; `looksheet.py`: contact sheets on test photos in
  `samples/test`, kept local). Outcome 0.11: per-look strength in `app-looks.txt` (blend toward the
  original, baked by `assemble.py`) for Portra 160/400/800, Superia 400, Kodachrome, Ektachrome VS,
  Astia, Polaroid 669; HP5 Plus 400 replaced by standard HP5 (`candidates-rt.txt`). Portra NC was no
  better than the standard Portra files; CineStill 800T kept (strong at night, warm in daylight).

- Batch 2 (0.13): Superia 1600 and Delta 3200 (RawTherapee); Kodak 2383 print (80%), Fuji Pro 160NS,
  Classic Chrome, Classic Negative, Polaroid SX-70 (EmulsionPresets). Rejected: Polaroid PX-70 (strong
  yellow cast), EmulsionPresets Delta 3200 (xmp2lut does not implement the Lightroom B&W mixer), the
  EmulsionPresets Superia 1600 (barely changes the image). Sheets in `luts/report/batch2/`.

- Halation (0.12): per-look default (4th number in `app-looks.txt`; CineStill 800T 65, 50D 30).
  Pre-pass decodes at 1/4, max-pools 2x2 into a highlight map (brightest channel 0.86-0.99), blurs
  it at two scales (tight + wide) and keeps only the OUTWARD spread (blurred minus source), so lit
  walls/skies are not tinted; applied after the LUT as a red-orange screen blend, before grain.
  Deterministic (parallel == serial). Costs ~20% extra on the PC, CineStill looks only.

## Build and install
    powershell -ExecutionPolicy Bypass -File film-app\build.ps1 filmlut
    powershell -ExecutionPolicy Bypass -File src\pmca.ps1 install-built filmlut -DryRun
    powershell -ExecutionPolicy Bypass -File src\pmca.ps1 install-built filmlut      # camera in MTP mode

Toolchain (per user, no admin): Temurin JDK 17, Android SDK (platform android-10, build-tools
35.0.1), NDK r15c (`%LOCALAPPDATA%\Android\Sdk\ndk-r15c`, the last with android-9 headers; the build
lowers its platform floor and checks every native symbol against Android 2.3), Zig for PC tests.
Sony classes are compiled against stand-ins in `filmlut/stubs/` (never packaged).

## Camera facts learned
ARMv7 rev 1, 4 cores, armeabi-v7a, 24 MB Java heap, card mounted 8.3-only (long names refused),
single-threaded C engine: 58.6 s for a 42 MP JPEG (before pipelining/NEON).

## Public release (0.18+)
    powershell -ExecutionPolicy Bypass -File film-app\make-release.ps1 [-DonationLink <url>]
Builds `release\FilmDrawer-<version>\`: the release-signed APK, user guide (`docs\USER-GUIDE.md`), LICENSE
(MIT), NOTICE (libjpeg-turbo, looks, trademarks), LOOKS-LICENSE, SHA256SUMS and a source zip made from an
explicit file list (never `samples\`, `luts\report\`, `luts\out*\`, `build\`, `keys\`). Nothing is uploaded.
- Release key: `%USERPROFILE%\.filmlut-release\` (release.jks + pass.txt), outside the project. Back it
  up; every update must be signed with it. `build.ps1 -Release` writes `build\filmlut-release.apk` and no
  install record, so `install-built` keeps installing the debug-signed build on the dev camera.
- Diagnostics in all builds: HANG.TXT / CRASH.TXT only on failure. LOG.TXT, TRACE.TXT, PARAMS.TXT and the
  unknown-key pop-up only when `FILMLUT\DEBUG.TXT` exists on the card.

## CineStill 800T revision (0.23, from a review of night shots DSC02152-02163)
- Grain (all looks): clumpy (two offset 2x2-cell grids + fine noise), weighted to shadows/low mids
  (rational bell, peak ~30% grey, 0.6 in black, 0.45 in highlights). Still deterministic per pixel.
- Halation: wide glow ~1.4% of width per pass (was 0.9%), weight 3.2; glow colour graded from amber
  near the light (soft source map) to red further out; lamp centres stay white (tinting clipped
  cores directly turned them khaki). 800T halation 80 (was 65), grain 75 (was 60).
- `luts/tweaks.txt`: hue-selective saturation baked by assemble.py; 800T yellow 0.48, green 0.62.
- Per-look camera sharpness (5th number in app-looks.txt, looks.txt field 7): 800T -3, 50D -2.
  Set via ParametersModifier.setSharpness while shooting (base style only), user's value saved and
  restored like the other settings; applied 0.4 s after a look change, or at once on shutter.

## 0.29 - 0.31 (from night and indoor shots on the camera)
- `tweaks.txt` / `assemble.py`: `toe_*` (deeper blacks, fitted to edited pairs), `keep_hue` (a look that caps
  red turned bright oranges khaki), `mid` (darker upper mids); the 800T yellow/green mute was halved.
- Halation: all-channel clipped cores go back to white; the glow fades on already bright pixels (no contours on
  lit walls) and large blown areas glow less than lamps.
- Almost Dark (`luts/almostdark.py`): own look derived from Vision3 500T.
- The AF/MF-AEL button does not reach the app as 533/532 on the a7R II; focus mode is in the menu.
- Icon: `icon/make-icon.py`.

## 0.32 (other cameras)
- Quality = RAW: `storage-fmt` raw is switched to rawjpeg while the app is open (pref `userStorageFmt`, put back in
  `closeCamera`). If refused, the app says so and queues nothing. Not yet tried on a camera.
- JPEG size labels come from `image-pixel-size-<l|m|s>-<aspect>`; letter only if missing or in APS-C crop.
- Engine checked on the PC at 12 sizes from 1920x1080 to 7952x5304 in 4:2:0, 4:2:2 and 4:4:4 (parallel == serial).
- Grain was a fixed size in pixels until 0.52; since 0.53 it follows the photo size (below).

## 0.33 (first report from another camera: ILCE-7, firmware 3.20, app 0.31)
- It ran and converted: M 10 MP in 26 s, L 24 MP in 58 s (4 CPUs, no NEON), about half the a7R II's speed per pixel.
  Its PARAMS.TXT from a later session showed the tester's own style (deep), DRO (auto) and size (L) at start.
- Adobe RGB (`color-space=expanded`, files `_DSCnnnn.JPG`, EXIF uncalibrated + interop R03) was treated as sRGB: colours
  came out about 15% less saturated, and the output was still marked Adobe RGB. Now a per-job LUT takes Adobe RGB in
  (`filmlut_lut_adobe`), and the output EXIF says sRGB. sRGB photos are byte-identical to 0.32.
- "Converted photos" skipped outputs of `_DSCnnnn` files (C8TC1527: not 5 digits).
- Menu: ISO, Dials (swap front / rear), Wheel (looks or ISO). The wheel's own codes (522/523) are unconfirmed on the a7.
- Opening the camera reads the parameters once instead of five times (HANG.TXT: 3 s in applyEvDial under load).
- Converted files have no embedded preview, so viewing them takes 3-6 s on the a7.

## 0.34
- Mode dial (ISV_KEY_MODE_DIAL 572) was ignored, so the exposure mode stayed as it was when the app opened. Now its
  status is mapped to scene-mode, with a camera reopen as fallback. The codes themselves have not been logged.
- The menu measures the screen and picks the largest text that fits (13 rows at size 18 did not).

## 0.35 - 0.36 (focus area, folder)
- `Focus.java`: boxes over the live view from `CameraEx.getFocusAreaInfos` (argument: 0 = 3:2, 1 = 16:9), focus result
  from `AutoFocusDoneListener`, area from `setFocusAreaMode`, spot from `setFocusPoint(x, y)`. The autofocus itself is
  the camera's. What the a7R II reported (units, box 0, spot size, refused Zone) is in the class comment.
- The spot is rounded to the camera's own grid (asked for -663, got -665), so a move is judged by the read-back.
- The menu scrolls (15 rows, 11 shown).
- Card folder FILMLUT -> FILMDRAW (8.3 names only); the old one is still read. The package name stays `dev.filmlut.app`,
  so updates install over earlier versions.

## 0.37
- Menu speed: camera values are read once when the menu opens (and after Focus / ISO change), not twice per key; the
  text size is fitted once; the exposure bar is not refreshed behind the menu.

## 0.38
- Browse opens on the newest photo. All photos are ordered by file number, so Adobe RGB (`_DSC`) ones are not last.
- The back wheel sends 635/634 (dial 3) on the a7, 522/523 on the a7R II; both are the wheel now. (a7, 0.34: the wheel
  moved the shutter instead.) The mode dial reports ISV_KEY_MODE_* there (540 = M), as assumed in 0.34.
- `Controls.java`: dials follow the camera's own set-up via `ScalarInput.getKeyLogicCode(key, mode, 1)`, if it answers
  with a known FUNC_* code; otherwise as before. What it answers is written to KEYS.TXT (with DEBUG.TXT).
- Eye AF: `executeAutoFocusStartTrigger` (TRIGGER_EYE_START) exists from interface version 16; not used.

## 0.39
- MENU > Controls holds focus, focus area, move focus point, ISO, dials and wheel; the button guide too; the main menu has 9 rows and no
  longer scrolls. Trash (shooting) opens Controls directly. The camera's values are read when Controls opens, so the main menu opens without a camera call.

## 0.40
- PLAY opens the converted photos (all photos if there are none yet); Fn or the menu goes to all photos.

## 0.41 (preview and thumbnail in converted photos)
- `extras_*` in filmlut.c: an 808-wide preview (look + halation, no grain) after the main picture, listed in an MPF
  APP2 segment as in the camera's own JPEGs, and an EXIF thumbnail in the look (the original's was kept before). Made
  from the source's embedded preview. The segment is named FDP until the preview is safely appended, then MPF.
- Checked on the PC: main picture's scan data byte-identical with and without it; parallel and serial; 3 libraries
  (libjpeg-turbo, PIL, ffmpeg); 1001x667 to 7952x5304 in 4:4:4 / 4:2:2 / 4:2:0; cut-off and corrupted inputs.
- CineStill 50D halation 30 -> 50 (at 30 it was barely visible).

## 0.42
- "Loading..." goes away when browse is left before the photo has loaded.
- KEYS.TXT (0.38, a7R II): getKeyLogicCode(key, mode, status) answers with nonsense (dials = EXPOSURE_MODE), so "As
  camera" never did anything there. Eye AF: executeAutoFocusStartTrigger is not on the a7R II.

## 0.43
- "As camera" removed (see Controls.java). Three 0.41 files written by an a7R II were byte-identical to the PC's.

## 0.44
- Looks reordered within their groups (the most wanted first, families together, gentle to strong); first start opens
  on Portra 400. The look is saved by id (`lookId`); a position saved by an earlier version is converted.
- "HP5 (standard)" is called Ilford HP5 (its source is "Ilford HP5 2"; "HP5 Plus 400" is another one).

## 0.45
- Trash in the converted view: delete the converted photo (asks first, Cancel preselected). `Photos.deleteOutput` only
  accepts the app's own outputs (FILMDRAW|FILMLUT/<folder>/<TAG>...JPG): originals are in DCIM and never deleted, since
  that would also leave the camera's image database out of step.
- Photos' delete guard was tested on the PC against a fake card (originals, RAWs, logs, other files and folders refused).
  Installed and checked on the a7R II.

## 0.46
- a7 tester (0.45): leaving the photos with a half-press, `startPreview` blocked about 4 s and threw "startPreview
  failed"; the app did not try again. Cause not known (a 24 MP decode was running until just before; S1 was pressed).
  Now: the preview decode is stopped when leaving the photos (`filmlut_preview_abort`), the live view is tried again
  (startPreview, then once with the camera released and opened, then only on half-press), and each failure and
  recovery is in HANG.TXT. A camera that failed while opening is released. The reopen does not restore the user's
  settings through the failed camera (openCamera does, as after an abrupt exit) and is split over several messages, so
  keys never wait 5 s (ANR). Not tried on a camera: nothing here runs unless the live view fails.
- Shooting help is a line shorter on the a7.

## 0.47
- `UserSettings`: the user's focus mode, sharpness, Creative Style, JPEG size, DRO and quality are forgotten only when
  the camera reports them again after putting them back. If not, each group is sent again on its own, then at every
  open / close, 6 times in all (then the app shows what to check). Until then the saved value is never overwritten with
  the app's. Not reported = trusted, as before. Failures are in HANG.TXT. No Android or Sony calls in it: tested on the
  PC with fake cameras (ignoring, refusing or not reporting each setting, dead for a while, saved by 0.46, 20000 random
  histories).
- Full card (PC, writes failing at 19 points of the file, parallel and serial, new and redo): never a broken or partial
  file, no .TMP left, the earlier version kept on a failed redo; when only the preview does not fit, the photo is
  complete without one. "write failed (card full?)". No change was needed.
- Builds: the classes go to d8 as one jar (since 0.46).
- Open: contrast, saturation and sharpness may belong to each Creative Style on the camera. Then the user's own values
  for Standard / Neutral would stay at the app's. To check on a camera.
- Installed and run on the a7R II. The per-style question is not answered yet.

## 0.48
- a7 tester (0.46 or 0.47): all the retries failed too, with the camera released and reopened. So something outlives the
  camera. The one thing the app did unlike PMCADemo (and unlike its own start): the live view's surface was kept while
  photos were shown, across two changes of the framebuffer's depth. Now the surface is dropped with the photos (INVISIBLE) and
  made anew. Also: the camera is opened when the shutter button is released (1 s at most), cancelAutoFocus is only
  called after autoFocus, and a retry makes a new surface too. Not proven: no cause is known for sure.
  Next ideas if it still fails: a lens not waking in time; keeping the camera open while browsing.
- Focus: AF-S / AF-A / AF-C (`af-mode`; the a7 has no af-a) besides DMF and MF. The app's choice is kept for the session (as
  the focus area); the user's is saved and put back (`G_AF`). Before, a focus mode chosen in the app was lost on viewing photos.
- Lock-on AF: the a7 reports no tracking-focus at all; the a7R II has startTrackingFocus(x, y), not tried.
- a7R II (Android 4.1; the a7 is 2.3): 6 live view starts of 6, 8-23 ms each. One 4 s freeze on PLAY 1.6 s after two shots:
  probably `Photos.allOutputs()` (a stat per file) on the UI thread while the camera saves RAWs. To move off it.
- KEYS.TXT (a7R II): no order of getKeyLogicCode's arguments gives the user's dial settings. "As camera" is dropped.

## 0.49
- The photo lists (PLAY, Fn) are read in the background, with "Loading..." until then; a list no longer wanted (left,
  switched) is dropped by `listGen`. The dates of converted photos are read once per file and at the start of the app.
- TRACE / HANG.TXT: how long the list and the change of colour depth took, to see what froze PLAY after two shots.
- a7R II: four RAW+JPEG shots in 6 s, then PLAY: nothing in HANG.TXT (it records 3 s and more). The cause of the freeze is not proven.

## 0.50
- Favourites: marked in MENU > Favourites only (no new buttons). `Looks.nav` = copies of the favourites (group "Favourites",
  same id / tag / LUT) + all; the wheel and left / right go through it. Saved by id (`favourites`, unknown ids are kept);
  `lookFav` = the look in use is the one in Favourites. `current` is what the shot watcher reads, not an index.

## 0.51
- Favourites after Controls in the menu (0.50, not published: under Look).
- 0.50 checked on the a7R II: marking, the group, reopening, converting. 0.51 itself was not installed before publishing.

## 0.52: CineStill 800T against real scans
- Compared with 64 openly licensed real 800T photos (kept private, nothing from them is in the app). `scan800t` in
  assemble.py: warm colours less saturated and less red, greens to teal, cooler shadows and mids, white 225 -> 245.
  Checked on 584 photos not used for tuning (colour statistics, distance in spreads of the real scans): by day 0.52
  camera, 0.49 before, 0.32 now; by night 0.86, 0.79, 0.68. It replaces the earlier colours.
- Halation (800T and 50D): discs (DISC_*): a small lamp in the dark gets a red-orange disc, as on film. Large bright areas
  get far less. The wide glow is at 0.60 of before. The same in previews.
- Known: a lamp with a big lens bloom gets little red (the bloom is bright in the JPEG). Daylight scenes are bluer on film
  than here: the camera has already balanced the white.
- Cross-check of warm surfaces in dark photos (hue, 0 = red; real 22 in shadow, 26 lit): 0.51 15 / 26, 0.52 18 / 26. Lit brick and
  wood given back a little saturation (`lit` in scan800t); more would overdo night oranges.
- Discs fade where the surroundings (sources left out) are bright, DISC_DARK..DISC_BRIGHT: without it a nearly white woven
  cloth went pink and white objects in daylight got red edges.
- Two photos converted on the a7R II: byte-identical to the PC. Red round blown-out windows: as in a real scan of the same kind of scene.

## 0.53: grain like film, and colours against real scans
- Reference scans (openly licensed, private, nothing from them is in the app): Openverse and Wikimedia Commons, 10 colour
  films and Tri-X / Delta 3200. Scores are on held-out photographers. Changed only where better on nearly every split:
  Portra 400, Portra 160, ColorPlus, UltraMax (warm colours too saturated), Velvia 50 (blues too weak). `mute` in assemble.py
  (keys warm, blue, leaf in tweaks.txt). Checked and kept: Ektar, Gold 200, C200, CineStill 50D, Provia 100F.
  Too few scans: Vision3 500T, Portra 800. Not checked: the other Fuji negatives, Reala, Astia, Kodachrome, Ektachromes.
- Grain: until 0.52 2-3 pixels whatever the photo size, nearly invisible at 42 MP. Now 1/1988 of the long side (4 pixels at
  42 MP), interpolated noise, strength 100 = sd ~18. Black and white looks (neutral LUT) have their own tone curve: full
  grain in mids and light tones. At 1024 pixels wide, residual in smooth areas (dark / mid / light), real and app:
  Delta 3200 4.5/8.2/8.3 and 4.5/7.5/7.0 at strength 80 (now 85; was 2.1/3.0/1.3), Portra 400 4.0/4.2/2.0 and 3.2/3.7/1.9.
  Defaults changed: 800T 50 (75), Tri-X, HP5 65 (55), T-Max 45 (40), Delta 85 (80), Superia 1600 60 (70), Almost Dark 45 (55).

## 0.54: the other Kodak and Fuji looks against real scans
- Same method as 0.53, twelve more films. Changed (held-out splits won, of 16): Fuji 400H (16; skin saturation 43, real 25),
  Superia 400 (16; 46, real 30), Ektachrome 100VS (15; warm colours softened less than the test asked, it is a vivid film),
  Portra 800 (14). Checked and kept: Pro 160NS (10), Kodachrome 64 (11), Astia 100F (4).
- Too few scans or photographers to decide: Reala 100, Ektachrome E100, Vision3 250D, Vision3 500T, Superia 1600.
- Only the colours of these four looks changed.
- Previews: area-weighted shrink (808 -> 640 is not a whole step, so whole-pixel boxes looked jagged) and smooth colour
  upsampling. Photos are byte-identical without their previews; the preview copy in the file also has smoother colour.

## 0.55: the character of the grain
- 0.53/0.54 added one noise to R, G and B alike: no colour variation, and grey-black dots on saturated colours. Now: a
  shared noise plus own noise per layer (two lattices each, half a cell off), each layer scaled by its OWN tone, so a layer
  with little dye has little grain. Larger, softer cells (1/1050 of the long side), a few stronger grains (GR_TAIL), less near
  black. Strength per look as before. Black and white looks: one layer. GR_* in filmlut.c.
- Measured in smooth areas at 1024 pixels wide, against real scans. Colour grain ONLY against scans saved 4:4:4, with ours
  reduced but not re-saved: 4:2:0 hides most of it. (The first 0.55 was tuned through a 4:2:0 re-save and came out three times
  too colourful and too fine, like digital noise; it was never released.) Colour against brightness variation: real 0.4-0.7,
  0.54 0.25-0.4, now 0.45-0.56. Neighbour correlation of the colour: real 0.63, now 0.54-0.67. Brightness neighbour
  correlation (Delta, mids) real 0.35, 0.54 0.20, now 0.35. Excess kurtosis real +0.3..0.6, 0.54 0..0.15, now 0.2..0.3.
- Cost: the grain step for colour is about 1.8x as slow as before on one core (PC, no vectorisation), black and white the same.
- CineStill 800T: camera sharpness -1 while shooting (was -3), a judgement, not measured. Checked with and without grain: grain does not blur (coarse and
  medium detail keep 96-100%), it only hides detail, and the new grain hides less medium and fine detail than 0.31-0.54.

## 0.56: preview made alongside the conversion
- Camera timings (FILMDRAW/LOG.TXT, 0.55, a7R II, 18 MP; the camera has no NEON): 800T 37-48 s, of which ~13 s is the
  glow pre-pass and 4-6 s the preview + thumbnail, both on one core with three idle; Gold/Portra/HP5 16-20 s, already
  balanced (decode ~13.5 s on one core, each of three workers ~14 s).
- The preview and thumbnail (extras_build) now run on their own thread during the conversion. The header's MPF
  placeholder is written when a preview is planned; the EXIF thumbnail is put in at the stitch (same length, in place).
  Output byte-identical to 0.55 (16 conversions: 6 looks, 10, 18 and 24 MP, parallel and serial).
- Glow rows (halo_row): column positions computed once per photo (halo_prepare), rows with no glow or core skipped, the
  glow colour only where there is glow. Same arithmetic in the same order: 39 outputs byte-identical to 0.55 (13 photos,
  both pipelines, on-screen preview). 10-70% less time in that stage, most on night shots with separate lamps.
- Rejected: the glow map from the embedded preview (about 40% less pre-pass, but ~10% glow difference). The look comes
  first; speed changes must leave the output byte-identical.

## 0.58: Film mode, Developed note, date and roll folders
- When a photo is converted the corner shows "Developed <film>" (the film name in edge-print orange, 0xFFFFAA28) for
  NOTE_MS, instead of "Saved <file> <s>". Notes now replace the progress while they show.
- Film mode (Roll.java): a roll of one look. The first frame loads it with the look, grain index, base and size of the
  moment; those are locked (filmLocked) until the roll is rewound or finished. Frames convert as usual, into
  FILMDRAW/ROLLnnnn, but are hidden (Photos.setHidden) from PLAY and All photos until every frame of the roll is done
  (Roll.develop, checked on each job done, each frame and at start). PLAY and All photos are off while Film mode is on.
  State is in the app's settings (film_*, roll_*), saved after each frame. At the last frame the Film mode menu opens.
  Off with frames on the roll asks: develop (OK) or keep paused (Left/Right). Rewind & develop needs OK twice.
- Outside Film mode, new conversions go to FILMDRAW/<YYYYMMDD> from the photo's EXIF DateTime (the app's clock is not
  set: 1970); without one, the DCF folder as before. Photos.findOutput looks a converted photo up by name in an index of
  the app's folders read once at start (Photos.warm) and kept current by outputChanged, so lookups do not stat folders.
- Once on the a7R II, a conversion into a just-created date folder reported OK but left a 0-byte file (cause not found;
  the next ones into the same folder were fine). JobQueue now makes the folder when a job is queued, checks each result
  (under 16 KB: deleted and converted once more, then failed), and LOG.TXT gives each result's folder and size.
- Film mode had a quick run-through on the a7R II before release; not yet run on another camera.

## 0.59: grain by tone, halation by brightness
From an outside review of the 800T conversions (DSC02273-76): halation and the tungsten shift convincing; the grain gave
it away (the same size and strength everywhere, flat sky and highlights included; even per-pixel noise rather than
clumps), the halos were too even across lamps of different brightness and a little pinky-red.
- Grain amplitude per tone (grain_tables, per look, at the start of each conversion): tone measured between what the look
  makes of black and of white, per channel. Colour: 0.42 in deep black, full 0.22-0.32, then cleaning up from just above
  mid grey: 0.55 at 0.55, 0.14 at 0.80, 0.04 at white. Before, a fixed curve on absolute values only fell to 0.45, and
  800T (white ~245) kept about two-thirds of its grain in skies and highlights. Black and white: 0.60 at 0.90, 0.25 at
  white (were 0.75 / 0.50).
- Second review of the first 0.59 (full grain up to t 0.45, scale 378): structure and halation better, but grain "a stop
  heavier than an 800T scan, like pushed two stops", and a lit facade at night as gritty as the sky and the street. The
  facade is ~100 of 255 (t 0.4): a midtone in numbers, a highlight to the eye in a night scene, so the fade now starts
  just above mid grey. Overall scale 378 -> 250 (all looks, black and white too: the larger grain shows more at screen
  size). The reviewer also saw the shadows of the dark building closer to black: its mean (~27) and 5th percentile
  (11-12) are the same as in 0.58; the clumps made it read darker. 800T's black is (6, 8, 14); a lifted blue-green floor
  would be a LUT change.
- The shared grain follows the pixel's luminance, each layer's own grain its own tone. Both per layer turned the shared
  grain into red-cyan noise in pale skies (red still in the mids, green and blue in the highlights).
- 800T, grain 50, DSC02276, std of the added grain (0.58 -> 0.59): dark building 4.5 -> 2.4, lit facade 6.0 -> 3.2,
  sky 4.6 -> 2.2; by output tone, lower mids 6.1 -> 3.6, 192-255 3.4 -> ~1.0. No mean shift (an earlier try with the
  clumps multiplying themselves brightened by 0.3).
- Structure: cells 1050 -> 920 per long side, per-pixel noise 30 -> 10, plus a clump grid 2.5 cells across that adds
  slow mottle (GR_COARSE) and raises or lowers the grain around it (GR_CLUMP). Tail 160 -> 70: with the clumps it made
  white specks in skies. Correlation at 4 pixels 0.33 -> 0.52. 820 cells looked too coarse at screen size.
- Halation: white-hot cores (clipped in all channels) also count over a ~2.5x wider radius (HOT_*), needing ~10 map
  pixels of core for a full ring: the nearest headlights and street lights get the widest rings, small and coloured
  lamps keep their disc. Coloured sources 0.45 -> 0.42. The `rest` buffer holds the map in between (no extra memory).
- Halo colour towards orange: far (1, .22-.24, .06-.12) -> (1, .28, .03), near the source amber as before. The blue
  share screened to pink over 800T's blue night. Tried (1, .32, .04): read as peach.
- Third review ("CineStill 800T, would not question it"; grain amount and tones right) left two refinements:
  - The nearest headlights had less red ring than distant tail lights. The surroundings test (DISC_DARK..DISC_BRIGHT)
    was the cause: a headlight lights the road and the car in front, so it kept 0.22-0.55 of its halo, tail lights
    0.8-1. Around a compact white-hot source (the HOT_* ring, kept in its own map) that test now takes away at most
    15% (HOT_KEEP). Large bright areas are still held back by the area test.
  - Shadows nearly black: 800T's floor lifted blue-green, (6, 8, 14) -> (8, 15, 21), fading out by the mids (grey in 127
    and above unchanged). `floor_*` in tweaks.txt, a step at the very end of apply_tweaks in assemble.py. The
    intermediate LUTs (luts/out) were not on the Mac this was done on, so cs800t.lut got the same function directly
    (assemble.floor on the 0.58 file); a full assemble run should give the same bytes.
- Fourth review: would accept the preset; grain "crept up a touch" in the sky and lower half. The raised floor moved
  tones up the grain curve (measured: sky +5%, facade +3%, lower half +1%, dark building -4%). Grain scale 250 -> 240.
- Parallel == serial (pixels) for 800T, HP5, Gold 200, Classic Neg. Not yet timed on a camera (one more interpolated
  grid and a luminance per pixel in grain_row).
- Kodak Gold 200 against the user's own lab scans (two Noritsu rolls: 33 and 37 untouched frames, many in low light, so
  grainier than the norm) and an outside review of a garden portrait (DSC09078). Measured at 1024 px on 10 a7R II ISO
  100 daylight photos:
  - Colour, `warm_tones` (new, assemble.py; skin_shift / warm_sat) and a warm floor, in tweaks.txt. Skin hue (Lab)
    56.7 -> 66.0 (scans 61-70), shaded skin 54.5 -> 63.9 (62-70); shadow b 0.5 -> 6.1 (7.1, 12.5); black (1, 6, 10)
    -> (12, 9, 2), darkest 2% L 2.4 -> 3.5 (4.7-6.1). Warm-tone saturation only +5%: the reviewer asked for more, but
    the scans were no more saturated in warm tones than the look already was (29-33, look 37). Gold had no tweaks
    before, so gold200.lut got the full apply_tweaks directly; a full assemble run gives the same bytes.
  - Grain, not changed yet: at default 35 Gold adds 1.7 in the mids at screen size, about half the lowest real estimate
    (~3.5); light tones keep ~70% of the mid grain in every split of the scans, the brightest ~35%, here ~40% and ~15%.
    The scans are grainier than the norm (mostly low light), and the reviewer wants Gold 200 grain fine in well-exposed
    daylight skin, so the default stays at 35. A gentler highlight fade for all colour looks is still open (re-check
    800T if so).
  - Second review: signed off ("reads clearly as Gold"). Saw grain a little stronger in the arms' skin: measured on
    DSC09078, luma grain on skin 1.57 -> 1.60, each channel within 0.03; the warmer, slightly more saturated skin likely
    shows the same grain more. Left as is.

