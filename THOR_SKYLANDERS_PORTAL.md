# Skylanders portal on a second screen (AYN Thor)

This branch builds on `android-port-dual` and adds a touch Skylanders portal that runs on the
handheld's second display (the Thor's bottom screen) while the game runs on the main one.

It installs as a separate app: `applicationId` is `info.cemu.cemu.portal` and the launcher name is
"Cemu Portal", so it can sit next to another Cemu Android build.

## Using it

1. In Cemu's settings, enable **Emulated USB Devices → Emulate Skylander Portal**.
2. Create figures once, either from **Emulated USB Devices → Skylanders → Create** in the in-game
   menu, or by copying `.sky`/`.bin` dumps into `<Cemu data>/emulatedUSBDevices/skylanders/`.
3. Start a Skylanders game. When emulation is running and a second display is present, the portal
   opens on it automatically.
4. Pick a slot at the top, then tap a figure to put it on that slot. If the slot already has a
   figure, the old one is removed and the new one goes on half a second later, because some games
   miss an instant swap. Tap a highlighted figure (it shows its slot number) to take it off.

The in-game side menu has a **Skylanders portal on external screen** toggle. It is on by default.
Turn it off to give the second display back to the GamePad (**External PAD screen**). The regular
**Emulated USB Devices** dialog still works either way, and both views share the same state.

Slots are generic, as in Cemu's own dialog. For Trap Team, put the trap on any free slot. For Swap
Force, put the top and bottom halves on two slots.

### Card art

No artwork is bundled. To show your own pictures, add an image named after the figure file to an
`art` folder next to the figures:

```
<Cemu data>/emulatedUSBDevices/skylanders/Spyro.sky
<Cemu data>/emulatedUSBDevices/skylanders/art/Spyro.png   (png, jpg, jpeg or webp)
```

Figures without an image get a coloured card with their initials.

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

## Still to verify on the device

This change has not been compiled or run yet. Check on the Thor:

- [ ] The unmodified `android-port-dual` branch builds, and then this branch builds.
- [ ] The portal appears on the bottom screen once the game has finished loading.
- [ ] Placing a figure makes it appear in game, and removing it makes it disappear.
- [ ] Swapping works: tap a different figure while a slot is occupied.
- [ ] Controller input still reaches the game while you touch the portal, and emulation does not
      stutter or pause.
- [ ] Quitting the game closes the portal.
