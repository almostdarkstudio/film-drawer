# Film Drawer

Film looks for Sony cameras that run PlayMemories Camera Apps. Shoot as normal; each JPEG is
converted in the background with the film look you picked. Your originals are never changed.

By Almost Dark Studio: https://almostdarkstudio.com

**Free and open source (MIT).** If you enjoy it, a donation is welcome: https://ko-fi.com/R6I02B7OBL

> Developed and tested on the **Sony a7R II**. One user has run it on the original **a7** (slower there). Other
> bodies that run PlayMemories apps (a6000, a6300, a6500, a7 II, a7S II, RX100 III-V and others) may work but have
> not been tested. Use at your own risk.

## What it does
- 40 looks in 8 groups: Kodak negative, Cinema, Fuji & Agfa negative, Fuji simulations, Slide,
  Instant, Specialty (Metropolis, Aerochrome-style infrared), Black & white. Film grain per look,
  sized to the photo and with its own grain in each colour layer;
  CineStill 800T is tuned against real scans, with the red-orange halation around lights; 50D has a gentler one.
  **Almost Dark**, in Cinema, is the studio's own low-light look: deep blacks, rich lamplight, clean lights.
- Shoot inside the app. A quick review shows each photo in its look, and the conversion runs in
  the background (about 20-40 s per photo at JPEG size M on an a7R II, CineStill 800T the longest). When it is done,
  "Developed" and the film's name show for a moment.
- **Film mode** (MENU > Film mode): load a roll of one film, 24 or 36 frames, and shoot it like film. The film, grain,
  base style and size are fixed for the roll, there is no review, and PLAY is off. When the roll is finished (or you
  rewind it), it is developed and the whole roll comes back at once.
- Mark your **favourites** (MENU > Favourites) and they become the first group.
- Browse any photo on the card, preview a look, and convert it. Or view your converted photos
  (the camera's own playback cannot show them) and delete the ones you don't want: PLAY.
- Converted files go to a folder of the day the photo was taken, `FILMDRAW\<date>\<TAG><number>.JPG` (for example
  `FILMDRAW\20261126\P4002097.JPG` = DSC02097 in Portra 400), and in Film mode to one folder per roll
  (`FILMDRAW\ROLL0001\`). EXIF is kept. Up to 0.57 the folder was the camera's (`FILMDRAW\100MSDCF\`): those stay
  where they are.
  (Up to 0.34 the folder was `FILMLUT`. The app still shows what is in it, and never moves it.)

## Installing
Film Drawer is installed the same way as any other PlayMemories app on these cameras, with
**Sony-PMCA-RE** (https://github.com/ma1co/Sony-PMCA-RE):

1. On the camera, set **USB Connection** to **MTP** (Setup menu).
2. Connect the camera to your computer and start Sony-PMCA-RE (`pmca-gui`, or
   `pmca-console install -f FilmDrawer-<version>.apk`).
3. In the GUI, open **Install app**, choose **Select an apk**, pick `FilmDrawer-<version>.apk`, and press
   **Install**. Keep the cable connected until it says the task completed.
4. On the camera: **Menu > Application > Application List > Film Drawer**.

Check the download first: `SHA256SUMS.txt` lists the APK's checksum.

## Controls
| | Shoot mode | Browse mode |
|---|---|---|
| Wheel / Up / Down | Change look (a card shows your last photo in that look). The wheel can change ISO instead (Controls > Wheel) | Change look (converted photos: change photo) |
| Left / Right | Jump to the previous / next look group | Previous / next photo |
| Front dial | Aperture (A/M/P), or shutter speed (Controls > Dials) | Change look (converted photos: change photo) |
| Rear dial | Shutter speed (S/M), else exposure compensation, or aperture (Controls > Dials) | Previous / next photo |
| Fn | Base style: Standard / Neutral / Camera settings | Switch between all photos and converted photos |
| C1 | JPEG size in the app: M / S / L, with your camera's megapixels | |
| EV dial (a7R II) | Exposure compensation follows the dial | |
| C2 | Grain: Auto (per look) / Off / Low / Medium / High | Same |
| OK | Show / hide help | Convert this photo (converted photos: show / hide help) |
| Trash | **Controls**: the list below, with Move focus point first | All photos: before / after. Converted photos: delete (asks first; only the converted copy, never the original) |
| PLAY | Your converted photos, newest first (Fn: all photos). Not in Film mode | Back to shooting (or half-press) |
| MENU | Menu: look, grain, base style, JPEG size, quick review, **Film mode**, **Controls**, **Favourites**, photos, about, **Exit application** | Same |

**Controls** (Trash while shooting, or MENU > Controls):
- **Move focus point**: the arrows move it, OK or half-press keeps it, Trash goes back to the earlier focus area.
- **Focus area**: Wide, Center or Flexible Spot. The box turns green when focus locks.
- **Focus**: AF-S / AF-A / AF-C / DMF / MF, those your camera has (the AF/MF-AEL button does not work in the app). Your
  own choice is put back when you leave. Lock-on AF and Eye AF are not available.
- **ISO**, **Dials** (which one is shutter speed) and **Wheel** (looks or ISO; with ISO, looks stay on Up / Down).
- **What buttons do**.

**Favourites** (MENU > Favourites): Left / Right for the group, Up / Down for the look, OK to mark or unmark it. Once
you have marked one, Favourites is the first group, so Left / Right reaches it while shooting; after your last favourite
the wheel carries on into Kodak. Each look also stays in its own group.

**Film mode** (MENU > Film mode): Film mode On, the Film, and Roll length (24 or 36). The roll starts with your next
shot, in the film chosen then; from that shot on the film, grain, base style and JPEG size stay as they were, and the top
bar shows the frame count (`CineStill 800T · 14/36 · M`). There is no quick review, PLAY and All photos are off, and the
photos convert out of sight. At the last frame the roll goes to the lab and this menu opens, to choose the next film or
turn Film mode off. Once all its frames are converted the roll is developed ("Developed CineStill 800T · 36 frames") and
is in PLAY, in its own folder. **Rewind & develop** (OK twice) finishes a roll early. Turning Film mode off with frames on
the roll asks whether to develop them now (OK) or keep the roll for later (Left / Right). A burst uses one frame per
photo, as a motor drive does. The camera's own playback still shows its original JPEGs.

**Deleting a converted photo** (PLAY, then Trash): the app asks first, with Cancel chosen. Up, then OK, deletes it. Trash
again, MENU, PLAY or a half-press cancels. Only the converted copy is deleted, and the app warns you if the original is
no longer on the card. The app never deletes originals: use the camera's own playback for those.

The camera's mode dial sets the exposure mode as usual (P/A/S/M). Everything on Fn, C1 and C2 is also in the menu, for
bodies without those buttons. To leave the app: MENU > Exit application (photos still
converting carry on next time).

## The looks
In menu order (your favourites, if you mark any, come first). The app opens on Portra 400 the first time, then on the look you used last.

| Group | Looks |
|---|---|
| Kodak negative | Portra 400, Portra 160, Portra 800, Gold 200, UltraMax 400, ColorPlus 200, Ektar 100 |
| Cinema | Almost Dark, Vision3 500T, Vision3 250D, CineStill 800T, CineStill 50D, Kodak 2383 Print, Fujifilm Eterna |
| Fuji & Agfa negative | Fuji 400H, Fuji Pro 160NS, Reala 100, Superia 400, Superia 1600, Fujicolor C200, Agfa Vista 200 |
| Fuji simulations | Classic Chrome, Classic Negative |
| Slide | Kodachrome 64, Ektachrome E100, Ektachrome 100VS, Provia 100F, Astia 100F, Velvia 50 |
| Instant | Polaroid 669, Polaroid SX-70, Fuji FP-100C |
| Specialty | Lomo Metropolis, Aerochrome |
| Black & white | Tri-X 400, Ilford HP5, T-Max 400, Delta 3200, Acros 100, Pan F Plus 50 |

## Your camera settings
While you shoot in Film Drawer, the app sets a neutral base so the looks come out consistently:
Creative Style Standard (or Neutral) with contrast and saturation 0, DRO off, and your chosen JPEG
size, focus mode, focus area and (for CineStill) sharpness. The looks are applied to the JPEG, so if your camera is set
to RAW only, the app shoots RAW+JPEG while it is open. It saves your own settings first and is designed to put
them back when you leave the app with MENU > Exit application, or the next time you open it if the
camera was switched off. Please check your settings the first time. Choose
"Camera settings" (Fn) to leave your Creative Style alone. RAW files are never affected.

## Known limits
- **"Live view failed" (a7):** after viewing photos, the live view sometimes did not come back on an original a7. 0.48
  changed how it starts, but that is not confirmed there yet. If it happens: half-press to try again, or MENU > Exit
  application and open the app again. Please report it with `FILMDRAW\HANG.TXT`.
- Lock-on (tracking) AF, Eye AF, the Zone and expanded focus areas, and the camera's own custom buttons and dial settings
  do not work in the app.
- RAW files already on the card without a JPEG cannot be converted.
- The looks work on the camera's JPEG, after its own white balance. CineStill 800T therefore goes less blue in daylight
  than real film.
- Previews (on the camera and in the photo file) do not show the grain; the photo itself does.
- If your camera keeps contrast, saturation and sharpness separately for each Creative Style, your own values for Standard
  and Neutral may be left at the app's (not confirmed). If you have changed those two styles, please check them.
- On an original a7, a 24 MP photo takes a minute or more to convert.

## Problems
- If the app freezes or crashes it writes `FILMDRAW\HANG.TXT` or `FILMDRAW\CRASH.TXT` on the card.
  Please include that file, your camera model and the app version (shown in the help) in a bug report.
- For detailed logs, create an empty file `FILMDRAW\DEBUG.TXT` on the card. The app then also writes
  `LOG.TXT` (conversion timings), `FOCUS.TXT`, `KEYS.TXT`, `TRACE.TXT` and `PARAMS.TXT`. Delete it to turn them off again.

## Removing
Camera: **Menu > Application > Application List > Application Management > Manage and Remove**.
Leave Film Drawer normally (MENU > Exit application) first, so your camera settings are back. Converted photos in
`FILMDRAW\` stay on the card.

## Acknowledgements
- Chris Gampat, The Phoblographer, "Exclusive: I Hacked a Sony a7 With AI to Do What Sony Took Away" (16 October 2026):
  https://www.thephoblographer.com/2026/10/16/exclusive-i-hacked-a-sony-a7-with-ai-to-do-what-sony-took-away/
  - the article that started this project.
- Sony-PMCA-RE by ma1co (https://github.com/ma1co/Sony-PMCA-RE), which makes installing apps possible, and
  OpenMemories-Framework for documenting the camera's Sony APIs.
- The Archive.org PlayMemories Camera Apps collection (https://archive.org/download/sony-playmemories-camera-apps).
- libjpeg-turbo; the RawTherapee Film Simulation Collection (Pat David, Pavlov Dmitry, Michael Ezra); EmulsionPresets.

## Licences
Film Drawer: MIT (`LICENSE`). Bundled libjpeg-turbo and the film looks have their own licences
(`NOTICE`, `LOOKS-LICENSE.txt`). This software is based in part on the work of the Independent JPEG
Group. Film and product names are trademarks of their owners, used only to describe the look each
LUT approximates; Film Drawer is not affiliated with or endorsed by them, or by Sony.
