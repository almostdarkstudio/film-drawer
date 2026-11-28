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
  - 41 looks in 8 groups (`luts/app-looks.txt`, assembled by `luts/assemble.py`). Aerochrome is our own
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
- Vision3 500T against the user's own ECN-2 roll (36 frames, Noritsu; 30 batch-rotated in Photoshop by the lab before
  delivery, the 6 untouched agree) and two outside reviews. Halation stays off: Vision3 keeps its remjet.
  - Blacks: fully crushed at (0, 0, 3) and cool; the scans keep lifted, slightly warm blacks (darkest 2% L 6.7-10). Floor
    -> (21, 15, 14), floor_p 5 so it stays in the blacks (p 3 also paled daylight skin, chroma 26.7 -> 25.4).
  - Grain default 45 -> 35. The scans are ~3x grainier than the look (lab scans run grainy), but 500T is fine-grained
    for its speed and the review saw a trial at 55 as coarser than 800T, "backwards". At 35 it is 19-25% under 800T at
    50 on the same night frame (sky, tower, dark building).
  - Dusk sky dull grey-blue (Lab b -19, 800T -25): `blue_sat` 1.25 (new in warm_tones, blues only) -> -23. Daylight
    skin a touch drained: warm_sat 1.10, skin chroma 26.7 -> 28.7. Gold's LUT is unchanged by the new parameter.
  - Almost Dark (derived from 500T, our own look) not changed.
- Black and white grain: the colour scale cut (378 -> 240) had taken Delta 3200, Tri-X and HP5 38-48% under 0.58 at
  1024 px, and 0.58 matched real scans (measured here the same way: 0.58 Delta at 85 4.5 / 7.5 / 7.5 dark / mid / light,
  the 0.53 real scans 4.5 / 8.2 / 8.3). Black and white now has its own scale (GR_MONO_SCALE 390): within -9..+5% of
  0.58 at every tone, with the new softer, clumped structure. The first 0.59's lower densest highlights for black and
  white (0.60 / 0.25 at 0.9 / 1, no evidence) are back to the measured 0.75 / 0.50.
- Not acted on: the user's Kodak HP4 cine roll (18 selects, Noritsu, edited in RawTherapee) loses grain in light tones
  (0.59x mid, brightest 0.35x), where the Tri-X / Delta curve keeps it (0.97x, 0.74x). A different film and edited
  frames; worth a look with untouched black and white scans.
- Blind outside review of Tri-X 400 (default grain 65, garden portrait DSC09078): read as "Tri-X pushed a stop or two,
  or Delta 3200". Gave away: clipped highlights, grain all the way down into the blacks, grain "fine and even, like
  noise". Changes:
  - Highlights: Tri-X and HP5 (the same curve) squeezed the sunlit fur (206-238 in the camera's JPEG, almost nothing
    clipped) into 232-250. `shoulder` (new, assemble.py, applied before floor) 0.5 for both: fur -> 222-249, at 250 or
    above 11.7% -> 6.9%, mid grey 147 -> 146. Delta 3200 and Pan F crowd less; T-Max and Acros not at all. Both LUTs had
    no tweaks, so a full assemble run gives the same bytes.
  - Black and white grain curve: 0.10 at black (was 0.40), 0.28 at 0.05: the deepest blacks about half as grainy (Tri-X
    2.26 -> 1.18 at 1024 px).
  - Bigger, clumpier black and white grain: GR_CELLS_MONO 700 (colour 920), GR_COARSE_MONO 45, GR_CLUMP_MONO 620;
    correlation two pixels apart at 1024 px 0.01 -> 0.20. GR_MONO_SCALE 390 -> 345 keeps 0.58's measured level: Delta,
    Tri-X, HP5 mids and light tones within -2..+5%.
- Second blind review of Tri-X: blacks, grain structure and highlight texture fixed ("a clear step closer"); grain now
  heaviest and "sparkly, gritty" in near-white fur. Black and white grain curve made a bell: full 0.42-0.65, 0.62 at
  0.80, 0.35 at 0.90, 0.12 at white (was 0.75 / 0.50 at 0.9 / 1). At 1024 px, brightest tones 0.82x -> 0.46-0.49x the
  mid grain, light greys 0.91-0.93x; mids unchanged (Delta, Tri-X, HP5). The user's HP4 roll: 0.59x / 0.35x.
  The review read it as "Tri-X at 1600 or Delta 3200". The user wants the black and white looks at box speed: defaults
  Tri-X 65 -> 45 and HP5 65 -> 45 (two stops of push is roughly 1.4-1.5x the grain), T-Max 400 45 -> 30 (much finer
  than Tri-X, RMS granularity ~10 vs 17). Delta 3200 stays 85 (fast at box speed; matched to real Delta scans), Acros 25
  and Pan F 10 unchanged.
- Third review of Tri-X: grain fixed (peaks in the mids, clean blacks, no grit in the whites); the right-hand toy's face
  "close to flat white", suspected clipped in the source. It is not clipped (camera JPEG 226-244, no channel at 254) but
  out of focus at f/2.5: a third of the left toy's fine detail in the source (0.75 vs 2.18), which no curve can add.
  The top of the Tri-X / HP5 curve still met white like a wall (241-251): `shoulder2` 0.7 from 0.85 of white, inputs
  207 / 223 / 239 -> 220 / 231 / 246 (were 222 / 238 / 249); mid grey and the image median unchanged.
- CineStill 50D in daylight (10 garden photos, EV 12-14, halation 50): the 0.59 wide ring for white-hot sources and the
  lit-surroundings exemption put orange-brown glows on black trousers next to white piping and sunlit toys (red added
  +40% on 6 of 10 frames, locally +50..72 levels). A JPEG cannot tell a lamp from sunlit white cloth; the exposure can.
  photo_ev() (new, EXIF ExposureTime / FNumber / ISO) gates both: in full up to EV 8 (HOT_EV_FULL), none from EV 11
  (HOT_EV_NONE); night streets here are EV 5-6. No EXIF: in full. Night 800T byte-identical; daylight 50D back to 0.58
  within 3 levels (the remainder is the warmer tint). 0.58 itself already put a pink glow on the trousers there: the
  disc and glow, unchanged here.
- filmlut_is_adobe now reads EXIF through app1_load(), shared with photo_ev().
- Almost Dark (our own look, from Vision3 500T) takes 500T's fixes, keeping its deeper blacks for punch (the user):
  grain 45 -> 35 like 500T; a small warm floor (7, 4, 0), black (7, 7, 10) -> (14, 11, 10), darkest L about 3.3
  against 500T's 5.0; mids unchanged; no warm_sat / blue_sat (it is already the punchier of the two). It had no tweaks
  before, so almostdark.lut got apply_tweaks directly.
- Outside review of 50D in the garden: grain, colour, sharpness right for 50D (the close-up "at the cold end": its fur
  is neutral in both the camera JPEG and the look, b +2.9 / +3.9; the cold read is pale-blue toys in open shade with the
  camera's auto white balance; not changed). Halation still "too wide and patchy" in daylight even at 0.58's level: in
  daylight 50D shows "a thin red rim on the brightest specular edges". The same exposure weight now also shrinks the
  wide glow (DAY_WIDE 0.25) and the disc (DAY_DISC 0.20) by day; the tight rim stays. Red added on the 10 garden frames:
  about a quarter of 0.58's; area lifted more than 8 levels 1.8-8.8% -> 0.2-2.5%. Night byte-identical.
- Idea, not done: real film has a fixed balance (a daylight stock goes cool in shade, warm under tungsten); the app keeps
  the camera's auto white balance. A per-look fixed white balance while shooting would be closer to film.
  (0.62: done, opt-in: Controls > White balance: Film; see below.)
- Portra 400 against 37 openly licensed Portra 400 scans (Wikimedia Commons, 24 photographers, 2048 px; one digital
  "Portra filter" imitation left out) and an outside review, which named it Portra 400 blind. On the 10 garden photos:
  - Shadows: deeper and slightly green (darkest 2% L 1.1, a -1.4) where the scans are lifted and warm (2.7, a +1.6,
    b +4.6). A first lift to (12, 9, 6) matched the numbers but turned dark fabric "milky navy-grey"; floor (6, 4, 0)
    -> black (9, 7, 3): dark fabric L 1.9 -> 3.1, "blacks have weight again ... still a gentle lift".
  - Skin "a little pink-magenta in the cheeks": skin_shift 4, hue 60.5 -> 65.9 (warm peach).
  - Greens: foliage chroma 32.5, scans 21.3 (Gold's scans showed the same); "closer to Ektar or Gold than Portra".
    `green_sat` (new in warm_tones, yellow-green to green) 0.70 -> 27.7. A sunlit garden is more vivid than the scans'
    mixed scenes, so not pushed to 21.
  - Portra 400 already had warm / blue / leaf (mute), which run before the new steps: portra400.lut got only the new
    steps, which is what a full assemble run gives. Portra 160 and 800 not changed yet.
- Portra 160 and 800 follow Portra 400 (skin_shift 4, warm floor, green_sat). 160: green_sat 0.68, floor as 400, the
  softest of the three. 800, after two outside reviews ("reads well as Portra 800"): punchier and warmer by design
  (green_sat 0.74, warm golden skin kept); a bigger warm-brown toe, floor (21, 13, 4) p 3 -> black (23, 16, 7), deepest
  hedge / grass L 5.6 -> 7.4 / 5.0 -> 6.9; default grain 50 -> 60. At 50 it already added 800T's grain per tone, but 42%
  of the sunlit garden frame was crushed shade, where grain cannot show. At 100% on the same frame: shade / mid 3.70 /
  3.37 against Vision3 500T's 2.32 / 2.10, fading to 0.76 on the brightest. Both had warm / blue / leaf tweaks before
  the new steps: their LUTs got only the new steps, as for Portra 400.
- Fujicolor C200 against 27 openly licensed C200 scans (Wikimedia Commons, 17 photographers, 1920 px) and two outside
  reviews. Foliage sat at Lab hue 114, like the Kodak looks (Portra scans 115, the user's Gold scans 100); C200 scans
  132, "that vivid, cool emerald is the Fuji 200 signature". The YCbCr steps cannot reach yellow-green foliage without
  moving yellows (side by side on that wheel), so `green_hue` (new, assemble.py) rotates in Lab, hue ~105-165 only:
  18 -> foliage 134, strong yellows 95 -> 93. Skin, shadows and balance follow the review, not the Commons scans (which
  measured warmer skin, 64 against 57, and slightly magenta shadows): skin left at C200's own (56.4, cooler, slight pink),
  a small lift keeping a green-cyan toe (shadow a -4.9, darkest L 0.9 -> 2.4), `balance` (new: a mid-tone colour
  balance, black and white untouched) -4 / 0 / +3: highlights b 3.8 -> 3.0. Grain 35 kept (Gold's level). fujic200 had
  no tweaks, so its LUT got apply_tweaks directly; every LUT changed earlier is byte-identical with the new steps.
- Fuji negatives: Superia 400, Superia 1600, 400H and Pro 160NS all put foliage at Kodak-like hues (113-123; C200
  scans 132). Pro 160NS done, after three outside reviews ("reads as Pro 160NS now ... clearly different from both your
  Fuji 200 and your Portra presets"): the first trial (Fuji greens only) read "closer to Fuji 200 than a professional
  portrait stock", more saturated than Portra 400. Now Fuji's hue muted to sage (green_sat 0.62; foliage chroma 35.5 ->
  21), all chroma down (`sat`, new in warm_tones, 0.80; median 12.5 -> 8.1, Portra 400 8.7), skin cooler and less
  saturated (hue 56.8 -> 50.6, chroma 27.8 -> 18.2), cool mids (balance -3 / 0 / +4), an airy cool lift reaching into the
  lower tones (black (14, 18, 22); dark fabric on the wide shot L 9.4, "charcoal, not black"). Grain 25 -> 20 (50D's).
  Superia 400 / 1600 and 400H: trials with Fuji greens (and a cool lift for 400H) not committed; the review's numbered
  reads could not be matched to them with confidence.
- Superia 1600: Fuji greens (green_hue 8, foliage hue 123 -> 129) and a little blue out of its lifted shadows (floor_b
  -4; its LUT already lifts black to cyan (16, 25, 25)): dark navy fabric read "cool magenta-purple" in the review; now
  a green-cyan toe (dark fabric hue 210 -> 169, L unchanged). Grain 60 kept.
- Superia 400 (outside review: "reads as Superia 400 now ... clearly separate from your Fuji 200"): Fuji greens
  (green_hue 12, foliage 118 -> 130); Superia's green-cyan toe, lifted into the lower tones (floor 2 / 12 / 9, p 2.5:
  darkest 15% L 2.2 -> 5.6, a -2.1 -> -6.6; C200 3.1, -2.9); skin a touch cooler (skin_shift -3, warm_sat 0.92: hue 61.5
  -> 59.6, chroma 22.7 -> 20.4); grain 40 -> 55 (at 100%: shade 3.27, C200 2.35, Portra 800 3.70). Its warm / blue /
  leaf tweaks run before the new steps: superia400.lut got only the new steps.
- Fuji 400H after three outside reviews ("reads as 400H ... clearly lighter and mintier than your 160NS"). The first
  trial (greens and a cool lift) was "too dense and too saturated", with a purple-magenta toe on navy fabric. Now: mid-tones
  lifted (balance +32 / +35 / +34 on all channels: median L over the 10 garden photos 23 -> 33, Pro 160NS 31), whites kept
  off the wall (shoulder 0.35 from 0.6: toys at 250 or above 8.7% -> 7.3%, fine detail up), minty greens (green_hue 26,
  green_sat 0.65: foliage hue 146, chroma 30.5 -> 22.6, lighter), paler skin (sat 0.76, warm_sat 0.90: chroma 20.9 ->
  15.6), a soft teal toe with little red (floor 3 / 21 / 20, p 2: dark fabric L 4 -> 10, hue 185). Its warm / blue / leaf
  tweaks run before the new steps: fuji400h.lut got only the new steps. All Fuji negatives now have Fuji's greens.
- New look: Infrared (INF), black-and-white infrared in the spirit of Kodak HIE / Rollei Infrared behind a deep red
  filter, our own (luts/infrared.py, MIT, listed in original.txt). Infrared is estimated from visible colour as in
  aerochrome.py (vegetation strong, blue sky / water weak, other surfaces about their red); output is neutral, so the app
  treats it as black and white (lut->mono: its grain curve and scale). Foliage glows white (Wood effect), skies go nearly
  black, skin pale; HIE contrast with a soft top. Default grain 50 (35 from the indoor review, see below). Build: `python -I luts/infrared.py
  luts/out-original/infrared.lut` before assemble.py (out-original was not on the Mac where it was made: the asset was
  written directly by the same script).
  Outside review of the first version ("a good first pass", read as Kodak HIE): foliage clipped to flat cut-outs,
  shaded leaves black, cheeks blown, "closer to a threshold". Now vegetation is keyed on how green a colour is for its
  brightness (shaded leaves count), leaves roll off (1 - exp(-4.5 g)), a milder S-curve and a shoulder. On DSC09076:
  sunlit leaves at 250 or above 100% -> 17%, texture 1.0 -> 2.1; shaded leaves 64 -> 89; bright skin clipped 51% -> 3%.
- Glow for black and white looks (bloom_build, glow_build in filmlut.c): HIE had no anti-halation layer, so glowing
  foliage bled light around it ("a soft, dreamy glow ... the biggest difference"). The glow comes from what the LOOK makes
  bright: the 1/8-scale map (average-pooled) is run through the LUT, output 0.62-0.92 -> glow, blurred at two radii
  (width / 200 and / 50), added as a white screen by halo_row (halo_t.neutral), fading on already bright pixels as the
  halation does, so the foliage keeps its texture. Only for black and white looks with halation on: colour looks go
  through halo_build as before (800T night, 50D day, Tri-X and the 800T screen preview byte-identical). The screen preview
  of a black and white look (every pixel grey after the LUT) builds the same glow from the preview (preview_bloom).
  Infrared: halation 50; adds 6 levels on average, ~50 at the brightest edges. Stays grey; parallel == serial.
- Second outside review of Infrared: "a very convincing IR preset", but "wavy, contour-like outlines around the leaf
  clusters, almost like topographic lines" in the brightest foliage. Two causes, both fixed:
  1. The LUT (main cause). It switched between a leaf formula and a non-leaf one with a vegetation mask. A blurred edge
     between a pale out-of-focus blob (163,163,139 -> 162) and a dark leaf (32,53,0 -> 142) is a mix of the two, which
     the mask counted as "not quite leaf": 70/30 came out at 150, darker than both ends, a dark line round every blob.
     Now the leaf term is ADDED to the base (from green above red and blue, past a 0.03 dead zone; steep, 1 - exp(-22 e);
     smaller on already bright surfaces), with a soft knee from 0.7 to white at 1.2. The same blend now rises 158 -> 182.
     On 150,000 neighbouring-pixel pairs 4-40 px apart (quarter-size garden photos), worst dips below both ends (p99)
     13.6 / 15.6 levels -> 4.7 / 9.9. Sunlit leaves 195-204 -> 228-237 (the same spread: texture kept), deep-shade leaves
     87 -> 108, skin 245 -> 232, sky and neutrals unchanged.
  2. The glow blend. halo_row faded the glow by each pixel's own brightness (150 -> 250); with the white glow, which
     covers whole areas of foliage, that tone curve flattened to a slope of 0.2 between 165 and 200 at strength 50 (81
     input levels in 150-230 -> 35 outputs) and folded back at 100. The white bloom now fades per AREA, in its map
     (bloom_map: brightness blurred at the tight radius, faded up to 85% from 0.59 to 0.98), and halo_row adds it as a
     plain screen. The screen preview uses the same bloom_map. Colour halation unchanged (800T and Portra 400 with
     halation 60 byte-identical); parallel == serial pixels.
  Third review ("close to finished"): the shaded hedge had slipped back to black, and the right-hand face was near
  clipping. The hedge is around 10 in the camera's JPEG (6,14,1; 7,19,7), and 62% of it fell inside the leaf term's
  0.03 dead zone: now 0.012 (near-black noise such as 3,5,2 still 0). Hedge median 0 -> 30, p75 31 -> 68; those
  colours 24 / 43 -> 66 / 79. (A dead zone scaled by brightness lifted the hedge as well but brought the dips back,
  p99 4.7 -> 13; the constant 0.012 gives 5.6.) Skin: base from 0.92 x red (was 0.96), a little more shoulder: face
  p90 237 -> 230, at 245 or above 0.7% -> 0.3%; white unchanged. Pale blobs a little brighter (182 -> 198). The faint
  rings left at the top are out-of-focus highlight discs in the source.
- Quick review after a shot: off by default (it added little) and moved from the menu into MENU > Controls (still Off /
  2 s / 4 s). Saved under a new key ("review2"): the app saved "review" on every pause, so nearly everyone had the old
  default 2 s stored, and keeping it would have left the review on.
- Aerochrome (luts/aerochrome.py; asset written directly by the script, as before), from an outside review that read it as
  Aerochrome but "a hue rotation rather than a channel remap". It already was the remap (IR -> R, red -> G, green -> B);
  the tells came from the IR estimate:
  - Shade-lit white fur and pale stripes went vivid cyan: slightly bluish, they counted as sky (b - r), which cut their
    IR. Sky is now blue for its brightness ((b - r) / (b + 0.04), 0.18 -> 0.42): white fur 185,213,225 -> 237,183,222
    (pale pink-lavender, was 135,183,255); blue sky unchanged (110,150,210 -> 40,98,223).
  - Foliage "flat and uniform, like a mask", most leaves at full red: the leaf term is now added and graded (as in
    infrared.py), smaller on bright surfaces, with a soft knee to white at 1.3: sunlit 255,172,191, mid 226,67,96, dark
    195,16,40, deep shade 127,8,20.
  - Skin lemon-yellow: on non-foliage, non-sky colours, the yellow is halved (blue lifted halfway to the red / green
    average): 220,170,140 -> 237,222,202 (was 255,222,174).
  - An orange acer went yellow-olive: saturated warm colours (r - b 0.35 -> 0.65) get extra IR and a little less green:
    200,110,40 -> 255,143,104 (was 232,200,104). Dark fabric stays black.
  Second review: skin and hair "almost monochrome, a grey-yellow", mouths and teeth greenish-yellow. Of the yellow
  (red / green average above blue) on non-foliage, non-sky colours, 35% now goes into blue and 25% off green: skin
  220,170,140 -> 237,208,194, lips 190,110,105 -> 206,166,137, teeth and whites barely move.
- Glow for colour infrared (Aerochrome, default 50; the review preferred the glow mock-up "with two tweaks"). A look is
  colour infrared when mid-green foliage (70,115,45) comes out strongly red (lut->irc, from the LUT's content like
  mono; only aerochrome.lut, no other look within 60 levels). It gets bloom_build, not halation:
  - weighted to foliage, "where the IR is": a cell glows only where red stands 0.06-0.25 above green and blue, so white
    toys and pale skin do not (the mock-up's bloom round a white toy "reads as haze");
  - pink (1, 0.45, 0.55), about the average foliage colour;
  - blacks kept (a slide film): the glow fades out below 80 by the brightest channel, which only steepens the curve
    (slope >= 1 - a), so no banding. Dark pixels (< 40) move 0.2-1 level on average (the mock-up lifted them to
    navy-grey).
  The screen preview needs the look to choose the glow: filmlut_preview_halation / Native.previewHalation now take
  the look's LUT (MainActivity.withLook passes the one it just applied). Halation (800T, Portra 400) and Infrared
  byte-identical to before; parallel == serial pixels.
- withLook: lutFor() throws, so it is called inside the try again (the first 0.59 did not compile).

### 0.59 grain: accurate rather than heavy (decision, after review on Windows)
The aim from 0.59 on is accurate grain: grain that behaves like film by tone, not 0.58's mostly uniform overlay, and
nothing that reads as digital noise. The defaults stay as set here; 0.58's heavier levels are not restored on purpose.
Checked on the 8 own photos (grainm.py measure, 1024 px), 0.59 / 0.58 at each look's default setting:
- Colour mids 0.63-0.87, darks 0.39-0.91, light tones 0.25-0.60. Fine and slide films lose least in the mids (Ektar,
  E100, Kodachrome, 50D, Velvia 0.81-0.87); looks with a lifted black lose most in the darks (2383, SX-70, Polaroid
  669, Metropolis, 800T 0.39-0.48), as their darks sit at the look's own black, where the curve is 0.42.
- Black and white near 0.58 in the mids (Tri-X 0.96, Delta 3200 0.89), light tones 0.70-0.76.
- No look got heavier, none stands out. Getting 0.58's mid level back would need about 1.5x the setting (UltraMax ~68).
- UltraMax's two usable real scans measure far noisier than even 0.58 (scanner noise): no guide to the level.
- Running time (PC, 32-bit plain C, median of 5): 800T, Portra 400, HP5 and Aerochrome as 0.58 within noise; Infrared's
  glow adds ~150 ms (~40%), to be timed on the camera. A full assemble.py run here gives all 41 LUTs byte for byte.


### Infrared indoors (review of INF02279, ISO 6400, on the camera)
"Fix the highlight grain and it's a convincing indoor IR frame." The pale wall, a white scarf and a bottle had fine speckle
that read as sensor noise. The camera's JPEG is smooth there: it was our grain. mono_weight keeps full grain up to t 0.65,
and Infrared makes much of a frame light grey. Infrared (lut->ir: mono, foliage 70,115,45 -> >150, blue sky -> <60; only
infrared.lut) now has ir_weight: full in the mids, 0.45 at 0.68, 0.15 at 0.82, 0.03 at white. Default grain 50 -> 35
("closer to pushed 3200"). Other looks byte-identical; parallel == serial pixels.
The glass "glowing like a light source" holds yellow juice, which the look turns white (as IR film does). A tighter glow
(wide radius halved, weight 0.9 -> 0.6) changed little round it and would weaken the foliage glow: kept as it was.
Not yet checked on the earlier garden frames (not on this PC).

## 0.60: halation as one model (800T and 50D)
Two outside reviews of 0.59's 800T on 25 night frames (`samples/run-800t-set`, DSC02166-02178 and 12 others): colour and
grain right, but the halation "looks like an effect laid on top": flat red discs with an edge and a dark gap round
street lamps, all the same size; the brightest source (a floodlight, DSC02174) with no red; a tube glowing at one end
(DSC02173); orange-lit shirt stripes and a hand glowing like neon while the windows behind did not (DSC01831). The
house frame (DSC02166) was the one that worked.
- Cause: halation had become a stack of fixes since 0.23 (outward-only glow, discs, area and surroundings damping, a fade
  on every pixel from 150 up, a wide ring for white-hot lamps). It followed edges and contrast, not light: sources were
  picked by the brightest channel (orange stripes counted), the fade removed the red over a lamp's own bloom, and the
  surroundings test held back the brightest lamps.
- Now (halo_build, halo_row): source = luminance 0.75-0.95 (max-pooled to 1/8); all-channel clipped cores count 3x.
  Three blurs (map width / 300, / 100, / 35), each growing as (light gathered)^0.4 (C 0.52 / 0.20 / 0.045), summed,
  1 - exp. Colour amber where the tight part dominates, red further out (tint as 0.59). halo_row: no glow inside blown
  areas (output luminance 205-240, tubes stay white), a screen, and a pull of 0.33 towards the glow colour at the same
  brightness (half that at white): a screen alone over a floodlight's bright bloom went pinkish-white.
- Tried on the way: linear growth (the church windows flooded the top half red); saturating each blur (flat discs
  again); a stronger colour pull 0.5 (the garage brick looked lit by an orange lamp: "pull it back by a third"); the
  colour pull fully off on bright pixels (the floodlight lost its rim).
- Daylight (50D, 15 frames from the card, `samples/run-50d-day`, EV 9.6-14): the first version added ten times 0.59's red
  (sunlit whites and sky are near-white sources too). The whole glow now falls to 0.10 by day and the wide part further,
  steeply from EV 8 to 11 (day^2). Red added (mean / 99th percentile): 0.59 0.1-0.5 / 3-10, 0.60 -1.1-0.6 / 1.5-2.5 on
  the sunlit frames; the overcast garden DSC02105 (EV 9.6) 2.6 / 17.5 (0.59 0.3 / 7.5), a faint warmth on a white cushion.
  Night (EV <= 8) is unaffected by the daylight gate.
- Grain colour (GR_CHROMA 50 -> 25) tried for the campfire's purple wall ("coloured speckle"): at 100% hardly different;
  most of that speckle is the camera's own ISO noise. Kept at 50.
- Byte-identical to the approved prototype on 7 frames (800T and 50D, night and day); Infrared and Aerochrome unchanged;
  parallel == serial pixels; preview, cancel and bad-file tests pass. PC timing 800T 463 -> 505 ms (pow per map cell).

## 0.61: Fuji 400H shadows (test set, outside review)
- The test set (`samples/testset`, 34 of the user's frames by situation, `luts/testset.py`) compared 0.58 and 0.60 for
  every reworked look. Only 400H moved a lot (about +25 levels everywhere: the 0.59 rework). An outside review of that
  sheet kept 0.60's skies (paler, cyan rather than lavender), skin, greens and high-key, but found night shadows tinted
  teal rather than dark (car park, stairs, house, restaurant; the dusk tower) and tungsten skin grey-sallow.
- Cause: the 0.59 teal floor (+21 green, +20 blue at black, p 2). Now `band` (new in assemble.py, after the floor): a
  tint in the lower mids only, none at black, peaking at a quarter of the look's luminance range, gone at white;
  400H's mint goes there (green 7, blue 6), with a near-neutral charcoal floor (12/14/14, p 3: black (8,26,23) ->
  (17,19,17)) and warm_sat 0.90 -> 1.0.
- Five versions: B and C (weaker floors) fixed the teal, C was the reviewer's pick but crushed the stairs' shadows (400H
  shadows go milky, not inky); D and E lift the black. E taken after a full-size check: green tint in the darkest 30%
  of the night frames 0.60 ~15 -> E 1.8-4.2 (D 1.6-4.1), shadow spread unchanged (E 1.1-6.1, D 1.2-6.4), darkest 1%
  ~4 levels above D. Only `fuji400h.lut` changes; daylight frames essentially unchanged.

## 0.62: Ektachrome E100 as slide film (test set, outside reviews)
- E100 had never been tuned (the EmulsionPresets bake at 55%; too few real scans to match). On the test set it read as
  "camera with more contrast and bluer skies": a print-film amount of contrast (+8), skin warmer than the camera's.
- New tweaks in assemble.py: `slide` (a per-channel reversal S-curve between the look's black and white, steepest at
  `slide_pivot` just below mid so shadows fall off sooner; hard toe and shoulder) and `red_pure` (pure reds and
  crimsons only, angle 55-122, skin at 125-140 untouched). E100: slide 0.20, pivot 0.48, a slightly cool toe (4/3/0,
  p 3), reds x1.08, blue as before. Greys: input 32 -> 13-18 (was 19), 223 -> 238 (was 235); black and white unchanged.
- On the way: slide 0.3-0.5 with blue x1.10-1.15 and reds x1.15-1.25 (A, B) went to "Velvia ... electric blue", skin
  clipping and crushing, tungsten scenes dark and red. At full size the reviewer chose the gentlest of three as "the
  safest single profile across every lighting group"; this is that colour with a slightly firmer curve.
- Not in the look: under tungsten E100 should go amber (it is daylight balanced), but the camera's auto white balance has
  already neutralised those scenes. A per-look fixed white balance while shooting is still only an idea (see above).
- Then, from real frames shot with White balance: Film (DSC02292, a domestic lamp at 5300 K): "saturated warm grading
  rather than slide film under tungsten". Greys were neutral per channel; the look raised chroma in every hue by ~25%,
  which multiplies a lamp's cast. Oranges and yellows only back to the camera's (warm_sat 0.78, run before red_pure):
  skin chroma 39 -> 48 -> 38, amber 67 -> 84 -> 66, yellow 80 -> 99 -> 77; reds 105 and sky 77 kept.
- Only `e100.lut` changes.

## 0.62: White balance: Film (opt-in)
- Real film has a fixed balance (a daylight stock goes amber under tungsten, 800T blue in daylight); the app kept the
  camera's auto white balance, which has already neutralised such scenes before the look is applied (outside review of
  E100: "tungsten frames barely change"). Parked since 0.54 as an idea.
- MENU > Controls > White balance: Camera (default) / Film / Film (full). It sets `whitebalance=color-temp` and
  `color-temperture-white-balance` (Sony's spelling; 2500-9900 K on the a7R II and a7, PARAMS.TXT) to the look's kelvin
  while shooting: `wb=<full>:<film>` in app-looks.txt, the 8th field of looks.txt (0 = the camera's). Full: 3200 K for
  800T, 500T, Almost Dark; 5500 K for 50D, 250D, Kodachrome, E100, Ektachrome VS, Provia, Astia, Velvia. Negatives keep
  auto (lab scans are colour-corrected, which auto resembles), and instant, B&W and creative looks.
- Film (the middle step) is centred on the tuning: the looks were tuned on photos where auto had chosen ~3900 K (800T's
  night streets) and ~4900 K (daylight), read from the 34 test-set RAWs (`luts/wbsim.py`). About 65% of the way from
  there to the film's own (3450 K, 5300 K) keeps the tuned look close on average while each scene moves with its own
  light; an outside review suggested 60-70% "as Frontier / Noritsu scans partly corrected the cast". Those kelvin are on
  the estimated scale (camera colour matrix, CCT) until a RAW shot with the setting ties it to the camera's own.
- Simulation (wbsim.py): each RAW decoded with the as-shot multipliers and with those for the target kelvin; the 3x3
  between the two (linear sRGB) applied to the camera JPEG. Auto chose: daylight 4550-5275 K, indoor bulbs 2700-2925 K,
  night lights 2625-4250 K, dusk 5075-6950 K. 800T night at full 3200 K: house R-B +15 -> -2, stairs 0 -> -19 (reviewer:
  "the cool-ambient, warm-practicals split ... exactly what tungsten film does", stairs "at the strong end"); at 3900 K
  +12 and -5. E100 food at 5500 K went red-orange in the simulation (the shift is applied after the camera's saturation
  there; on the camera it comes first): to be checked on a real frame.
- Like sharpness: the user's own mode and temperature are saved first (UserSettings group G_WB, prefs userWb /
  userWbTemp) and put back on close, on open after an abrupt exit, and when the look has no balance or the option is
  turned off. Not changed if it cannot be read first, or the camera has no color-temp mode. Applied with the look's
  sharpness (after the 400 ms settle), on the base settings, and before the shutter if a look change is pending.
- On the a7R II (0.62 debug, DEBUG.TXT): TRACE.TXT "white balance 5300 K: camera has color-temp 5300" on every look
  change; after MENU > Exit the camera's own white balance was back to Auto. Calibration (`samples/wb-cal`): 800T at
  Film 3450 K read 3275 K from its RAW (DSC02290/91), E100 at 5300 K read 5165 K (DSC02292): the estimate runs ~150 K
  below the camera's scale, so the tuning photos' auto values were ~4050 K (night) and ~5050 K (daylight) on it, and
  65% of the way gives 3500 K / 5350 K: within 50 K of 3450 / 5300, kept. E100 under a domestic lamp on the camera came
  out amber-gold with believable skin, not the simulation's red-orange (the camera balances before its own saturation).
- Opt-in for now. Film may become the default once the full sheets are reviewed (and the a7 is checked).

## 0.63: E100's top end, and a white balance diagnostic
- E100 (outside review, after the white balance question was settled: a 2700 K bulb against 5300 K is the light, not
  the look): "on a slide, overexposed areas go towards clear film base ... the deep orange should sit in the midtones
  and then desaturate towards white at the very top". It already rolled off (saturation 0.89 -> 0.42 up the brightest
  channel in DSC02292) but ended cream-yellow (250,237,145). New tweak `white_roll` (assemble.py): above output
  luminance 200, each colour moves towards neutral at its own brightest channel, up to 0.7 at 240 (smoothstep). Now
  (250,245,213), saturation 0.15; the orange mids (brightest channel 220-240) unchanged. Only saturated colours roll
  off (saturation 0.15 -> 0.40): ungated it also took the cream out of a golden-hour sky (DSC00788, 38% of pixels
  moved > 3 levels, (244,241,228) -> (244,243,236)); gated, 1-2% of bright daylight frames move (a crisp's yellow
  highlight goes paler). Only `e100.lut` changes.
- Diagnostic for the lab-scan idea (debug only, DEBUG.TXT): each shutter press logs the camera's white-balance
  parameters to TRACE.TXT at the press and 1.5 s after, to learn whether the camera reports the temperature its auto
  white balance chose. If it does, White balance: Film can shoot at the film's balance and correct each frame by a share
  of that (65% for negative and cine stocks, ~15% for slides, which labs matched rather than corrected); if not, the
  cast is estimated from the photo (`luts/scancorr.py`: plain grey-world reads blue skies as a cast, needs better).
  Target for either: per-frame kelvin 65% (in mired) from the film's balance to the frame's auto value, simulated from
  the RAWs (`white-balance-*-camera-full-lab.jpg`): 800T daylight skin R-B +26 / -22 full / +10 lab, dusk -32 / -135 /
  -70, night unchanged.
- Battery level, bottom right while shooting (the user: the app is full screen, so the camera's own icon is hidden):
  Android's sticky ACTION_BATTERY_CHANGED (level / scale), read every 30 s with the exposure text; amber at 15% and below;
  hidden if the camera reports no level. With DEBUG.TXT each new reading goes to TRACE.TXT. Works on the a7R II
  (64% -> 62% over 90 s of live view).
- The camera keeps colour temperature in 100 K steps (TRACE: 3450 K set, 3400 K reported): the tungsten Film value is now
  3500 K, the calibrated 65% value.
- Camera check (TRACE, White balance: Camera, Auto): the camera reports whitebalance=auto with the stored manual value
  (3600 K) at the shutter and after, not what auto chose: the lab-scan correction has to estimate the cast from the photo.
  And just after opening it ignored the app's 5300 K ("camera has auto 3600" at 2 s, taken 11 s later at a look change):
  applyLookWb now reads back and retries every 0.75 s, up to 8 times (then a report).
  Confirmed with RAWs DSC02303-08 (samples/wb-cal): auto chose 2950-4225 K while the camera reported 3600 K throughout.

## 0.64: White balance: Film from the JPEG's own record (lab scan)
- The camera does not report its auto white balance to the app (TRACE: stored 3600 K while auto chose 2950-4225 K),
  and estimating the cast from the photo (luts/wbest.py: grey pixels on the black-body locus, an exposure prior) was
  only ~39 mired median against the truth. But every a7R II JPEG records it: Sony maker note 0x9408, deciphered (b ->
  i where i^3 mod 249 = b, as ExifTool), uint16 R, G, B at offset 698 = the RAW's as-shot multipliers (34 / 34 test
  frames, exact). So the camera's auto white balance is the lab scanner.
- `FilmBalance.java` (pure Java, PC-tested via luts/FilmBalanceTest.java): reads those levels (a7R II only, checked
  plausible; else the camera's balance is kept), finds what auto chose on the locus through the camera's XYZ matrix
  (DNG ColorMatrix2, the same 150 K scale offset), and the final balance in mired: film K + share x (auto - film).
  The 3x3 (linear sRGB) between the two is folded into a per-photo copy of the look's LUT: ~3 ms on the PC, so no extra
  native work. Conversions (JobQueue, mode saved with the job) and previews (lutFor) use it; LOG.TXT notes auto and final K.
- looks: `wb=<K>:<lab %>`: 800T, 500T, Almost Dark 3200:65; 50D, 250D 5500:65; slides 5500:15 (an outside review:
  slides were matched, not corrected). Film = that share, Film (full) = 0.
- Against the RAW-built lab target (luts/wbsim.py at per-frame kelvin), 800T: warmth within ~2 in every situation
  (daylight skin target +10, app +8; sky -100 / -103; night +1 / +1), mean |difference| 1.4-2.9 levels, dusk 6.
- The camera's white balance is no longer set (0.62-0.63 code removed; a value saved then is still put back once).
  Works on photos taken outside the app too, if they were shot on Auto.

## 0.65: White balance: Film is the default
- Outside review of the 0.64 sheets (Camera / Film / Film (full), 34 frames, 800T, E100, 50D): "the three columns now do
  distinct, sensible things, and the defaults are in the right place"; 50D "right", 800T's lab-scan daylight "looks
  like a real scan of 800T used outdoors", full "a striking look ... worth keeping as an option"; E100 at full size
  (DSC02292): the curtain rolls off to (250,245,212), saturation 0.15, folds kept. The user: "as close to film as we
  can get it".
- Film is the default: saved as "wbMode2" (default 1), so everyone starts on Film once (0.62-0.64's "wbMode" is dropped).
  Only the 11 looks with a balance change, only on the a7R II; the 800T night look holds on average (R-B +3 -> +1).
- README garage example re-rendered with Film: auto had chosen 3150 K, the film is 3200 K: |diff| 1.7 levels.

## 0.66: E100 holds its shadows
- Outside review of the 0.64/0.65 sheets: white balance finished on all three stocks; E100's daylight frames still too
  contrasty (hedge and wall shadows almost pure black, hard patio highlights, deep saturated skies). E100 is punchy but
  holds more shadow detail.
- Two steps on the test set (current / B / C): B (slide 0.13, floor 5, blue 0.88) was hard to see at full size; C taken
  (the user: "don't have to correct too hard"): slide 0.20 -> 0.08, an even floor of 9 (p4), blue_sat 0.80. Grey
  31 -> 24 (was 15), 63 -> 46 (39), 127 and 255 unchanged; a sky blue (89,140,217) -> (79,142,213) (was 61,147,236).
- Only `e100.lut` changes.
