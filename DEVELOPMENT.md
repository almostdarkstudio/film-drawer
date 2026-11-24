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
    Luminance grain after the LUT, midtone-weighted, deterministic per pixel position (parallel ==
    serial). Not shown in the browse preview.
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
- Grain is a fixed size in pixels (tuned at 18 MP), so it looks coarser in small photos and finer in large ones.

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
