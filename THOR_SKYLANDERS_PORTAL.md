# Skylanders portal on a second screen (AYN Thor)

This branch builds on `android-port-dual` and adds a touch Skylanders portal that runs on the
handheld's second display (the Thor's bottom screen) while the game runs on the main one.

It uses Cemu's own application id, `info.cemu.cemu`, so frontends that recognise emulators by
package name, such as Cocoon, treat it as Cemu. The launcher name is "Cemu Portal". Because the id is
shared, it replaces a regular Cemu install, and the two can't be installed together. The official
APK is signed with a different key, so uninstall regular Cemu first (after backing up its data),
then install this build.

## Using it

1. In Cemu's settings, enable **Emulated USB Devices → Emulate Skylander Portal**.
2. Add figures. The quickest way is **Settings → Emulated USB Devices → Create all Skylanders
   figures**, which creates the main roster (see below). **Create all variants too** also adds
   every earlier Series and special edition. You can also create single figures from the in-game
   menu, or copy `.sky`/`.bin` dumps into `<Cemu data>/emulatedUSBDevices/skylanders/`.
3. Start a Skylanders game. As soon as the game starts talking to the portal (usually by the title
   screen), the portal opens on the second display. Other games leave the second display alone, so
   **External PAD screen** keeps working for them.
4. Select a slot at the top, then tap a figure to put it on that slot. If the slot already has a
   figure, the old one is removed and the new one goes on half a second later, because some games
   miss an instant swap. Tap a glowing figure to take it off.

### Portal screen

The portal screen has three pages. Swipe left and right, or tap the page names at the top:

- **Portal:** a drawn Portal of Power, with a ring of stone bricks and rune marks around a glowing
  centre, and the figures on the portal standing in it. The glow takes the colour the game sets on
  the portal's lights, and flashes when figures go on or come off. Below it are the slots and your
  saved teams.
- **Collection:** every figure that works in the game you're playing, with **Sort** (tap to switch
  between A-Z, element, game and recently used) and **Filters** (Main roster / Variants / All
  versions, type and element), and an A-Z bar down the side when sorted A-Z.
- **Recent:** the figures you last used in this game, then your favourites. Recently used figures
  are saved per game straight away, so they're still there next time you play. Only the last 30 per
  game are kept.

A short tip appears when the portal opens, then fades out. The arrow at the top right shows which
slot tapped figures go to; tap it to go back to the portal page. The gear button opens **Portal
settings**:

- **Portal glow:** turn the glowing, animated centre off for an unlit portal with no animation.
- **Show portal:** hide the drawn portal to leave more room for the slots and teams.
- **Reload figures and card art.**

Other details:

- **Only figures for the game you're playing:** the portal detects the running Skylanders game from
  its title and only shows figures that work in it. Characters show if they debuted in that game or
  an earlier one, including all their later Series. Traps, magic items and vehicles show if they
  came out in that game or earlier. If the game can't be detected, everything is shown.
- **Main roster:** one entry per character, using its latest normal Series: S4 > S3 > S2 > S1.
  Eon's Elite never counts as the main version, because its stats are much stronger than the
  normal releases. Earlier Series and special editions are variants, and appear under the
  **Variants** or **All versions** filter.
- **Favourites:** tap the star on a card. Favourites are per character.
- **Details and versions:** long-press a card for its details, saved progress (nickname, gold,
  play time, hero level, last placed) and every version of the character, with **Place** or
  **Create**.
- **Slots:** Player 1, Player 2, Trap and Magic Item; **More** shows the other twelve. Traps and
  magic items go to their own slots, and Swap Force halves go to the next free slot.

The in-game side menu has a **Skylanders portal on external screen** toggle. It is on by default.
Turn it off to give the second display back to the GamePad (**External PAD screen**). The regular
**Emulated USB Devices** dialog still works either way, and both views share the same state.

### Card art

Art for about 700 figures and variants is bundled in `app/src/main/assets/skylanders_art/` (8 MB
of 256 px WebP images, taken from skylanderscharacterlist.com). Each file is named after the
figure's id and variant in 4-digit hex, as read from the figure file: `0010_0000.webp` is Spyro.
Both Cemu's and Dolphin's variant codes are covered, because they differ for some figures. The
scripts that built the set, and a manifest of which figure each file is, live outside the repo in
`SkylandersArt/` on the dev machine.

To use your own pictures, add images to an `art` folder next to the figures. They win over the
bundled art. Each image can be named after the figure file, the figure's name, its id and variant
(`0010_0000.png`) or its base figure's name, so one `Spyro.png` is used for every Spyro file:

```
<Cemu data>/emulatedUSBDevices/skylanders/Spyro.sky
<Cemu data>/emulatedUSBDevices/skylanders/art/Spyro.png   (png, jpg, jpeg or webp)
```

Figures with no art at all get a card in their element's colour, showing their initials.

**Settings → Emulated USB Devices** can import images for you, either from a `.zip` or as
individual images. Folders inside the zip are ignored. **Missing card art** lists the image names
still needed for figures with no bundled art, one per figure, and one image covers every variant of that figure. Image names are
not case-sensitive.

The element, game and type of each figure come from the Skylanders figure list in Dolphin
(GPL-2.0-or-later). See `SkylanderCatalog.kt`.

## How it works

- `SkylanderPortalPresentation` is an `android.app.Presentation` on the display that
  `rememberPadDisplay` finds, which is the same display the existing PAD presentation uses. Its window is
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, so touches on the portal never take focus from
  `EmulationActivity`. Controller input keeps going to the game and the activity is not paused.
  Because of this, the portal screen uses no dialogs or text fields.
- The presentation uses the activity as its lifecycle, `ViewModelStore` and saved-state owner, so
  `SkylanderPortalScreen` shares the same `EmulatedUSBDevicesViewModel` as the in-game dialog.
- The presentation is only shown while emulation is initialized and is dismissed when emulation
  ends, so the JNI calls in `NativeEmulatedUSBDevices` only run while a game is running.
- `EmulatedUSBDevicesViewModel` now remembers the file path loaded into each Skylander slot, which
  is used to highlight loaded figures, and has `placeSkylanderFigure`, which does the delayed swap.

## Status on the device

The APK builds on GitHub Actions (`.github/workflows/thor_portal_apk.yml`; download the
`cemu-portal-apk` artifact from the run).

- [x] The branch builds.
- [x] The portal appears on the Thor's bottom screen while a game runs.
- [x] Placing figures from the portal works.
- [x] Swapping works: tap a different figure while a slot is occupied.
- [x] Controller input still reaches the game while you touch the portal, and emulation does not
      stutter or pause.
- [x] Quitting the game closes the portal.
