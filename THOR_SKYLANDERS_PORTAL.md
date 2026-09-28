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

- **Portal ring:** the slots sit on a drawn portal whose ring takes the colours the game sets on
  the portal's lights, the way a real portal lights up. It flashes when figures go on or come off.
  It doesn't animate otherwise, so it doesn't slow the game down.
- **Teams:** the teams button shows your saved teams. **+ Save team** saves the figures currently
  on the portal. Tap a team to swap it onto the portal. Long-press a team, then tap it again, to
  delete it.
- **Slots:** Player 1, Player 2, Trap and Magic Item are always shown. **More** shows the other
  twelve, with a count of any that are in use.
- **Automatic slots:** traps always go to the Trap slot, and magic items and trophies to the Magic
  Item slot. Swap Force halves go to the next free slot, so both halves can be on the portal
  together. Everything else goes to the selected slot.
- **Names and elements:** each card shows the figure's real name, read from the figure file, and
  is coloured by its element. When the file name is different from the figure's name, the file name
  is shown underneath.
- **Main roster:** the grid shows one entry per character, using its latest normal Series:
  S4 > S3 > S2 > S1. Eon's Elite never counts as the main version, because its stats are much
  stronger than the normal releases. Earlier Series, Eon's Elite, Legendary, Dark, LightCore,
  Chase, Holiday and other special editions are variants, and appear under **Variants** or **All
  versions** in the filters. Traps, magic items and vehicles are never merged, because different
  traps share one figure id. Cards show the Series number. The rules are in `SkylanderVersions.kt`.
- **Only figures for the game you're playing:** the portal detects the running Skylanders game
  from its title and only shows figures that work in it. Characters are shown if they debuted in
  that game or an earlier one, including all their later Series. Traps, magic items and vehicles are
  shown if they came out in that game or earlier. The first row of the filters shows the detected
  game and lets you pick a different game, or **Any game**.
- **Favourites:** tap the star on a card to make it a favourite, and tap **★ Favourites** above the
  grid to see only your favourites. Favourites are per character, so they carry over between a
  character's Series and variants.
- **Details and versions:** long-press a card for its details panel. It shows the figure's art,
  element, game and Series, and the progress saved on it: nickname, gold, play time, hero level
  and when it was last placed. The character level isn't shown yet. The panel also lists every
  version of the character, with **Place** for installed versions and **Create** for the rest.
  Tap a version to see its details.
- **Sorting and the A-Z bar:** the filters include **Sort: A-Z / Element / Game / Recently used**.
  With A-Z, a letter bar down the right of the grid jumps to the first figure starting with a
  letter. You can tap it or drag along it.
- **Filters:** the filter button shows rows of chips for type (Traps, Vehicles, Giants, Swappers…),
  element and game. Only filters that match at least one of your figures are shown.
- **Theme:** black background with gold highlights, to suit the AMOLED bottom screen.

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
