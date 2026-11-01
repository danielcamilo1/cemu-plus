# Cemu Plus (Wii U Emulator for Android)

[![Build](https://github.com/danielcamilo1/cemu-plus/actions/workflows/android_release.yml/badge.svg)](https://github.com/danielcamilo1/cemu-plus/actions/workflows/android_release.yml)
[![Latest release](https://img.shields.io/github/v/release/danielcamilo1/cemu-plus?include_prereleases)](https://github.com/danielcamilo1/cemu-plus/releases)
[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/danielcamilo)

Cemu Plus is a fork of the [Cemu Android port](https://github.com/SapphireRhodonite/Cemu) that adds
**Lossless Scaling frame generation**, inspired by how [Eden Duo](https://github.com/igawa6/eden-duo) does it.
You supply your own `Lossless.dll` and Cemu Plus generates in-between frames on your phone's GPU, with full
control from the in-game menu.

The Android port itself is still early and experimental. Stability, performance and features are not
guaranteed, and some functionality may be missing compared to the desktop version of Cemu.

## Features

- **Frame generation** with the Lossless Scaling shaders (2x, 3x and 4x), running directly in Cemu's Vulkan renderer
- **Target frame rate mode** that raises or lowers the multiplier on its own to reach 60, 90 or 120 FPS, and
  rolls back any step that slows the game down
- **Adjustable motion estimation resolution** (auto, 50%, 75%, 100%) to trade quality for GPU time
- **In-game quick menu controls**: toggle frame generation and change every option while playing
- **Improved FPS overlay**: while frame generation is active it shows the displayed frame rate, the base
  frame rate of the game and the effective multiplier, for example `FPS: 60.00 (FG x2.0)` / `Base FPS: 30.00`
- **Dual screen support** from the upstream dual screen branch: show the Wii U GamePad on a second display
  (for example the bottom screen of the AYN Thor), swap the screens and rotate the external screen, all from the
  in-game menu
- **Dual screen layout options** for devices whose second screen has a different aspect ratio than the
  Wii U GamePad (for example the AYN Thor): align the GamePad image to the top, center or bottom of its screen
  (Settings → Graphics settings → GamePad screen alignment), and show the performance overlay on both screens,
  only the TV or only the GamePad screen, either over gameplay or inside the black bars (Settings → Overlay settings)
- **GamePad panel overlay**: a performance panel styled after the black Wii U GamePad (piano black shell, power LED,
  speaker grille) with a tile per stat and usage meters. In the black bars it scales up and spreads out to use the
  free space, for example the area around the GamePad image on the AYN Thor's bottom screen. Each stat has its own
  toggle: FPS, draw calls, CPU usage and clock, GPU usage and clock, CPU/GPU/battery temperature, battery level and
  RAM. Shader compilation progress can also be shown inside the panel instead of as a notification, and
  notifications move out of the black bar so nothing overlaps (Settings → Overlay settings → Style). In a black bar the
  panel always fills the whole bar, however many stats are turned on
- **Panel buttons**: optional touch buttons in the GamePad panel to turn frame generation on and off, cycle the
  frame generation multiplier (x2, x3, x4), pause and resume the game, and open the emulator settings (which pauses
  the game until you leave them)
- Installs next to the regular Cemu Android app (package `info.cemu.cemuplus`)

## Download

Grab the latest APK from the [releases page](https://github.com/danielcamilo1/cemu-plus/releases).

## Setting up frame generation

1. Copy `Lossless.dll` from your own, legally purchased copy of
   [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) to your device. On Windows
   it is in the Lossless Scaling installation folder (for Steam: right click the game → Manage → Browse local files).
2. Open **Settings → Graphics settings → Frame generation (Lossless Scaling)** and tap **Select Lossless.dll**.
   Cemu Plus checks that the DLL contains the frame generation shaders and copies it into its data folder.
   The DLL is only read, never executed.
3. Enable frame generation on that screen, or from the in-game menu (back button / menu hotkey) while playing.

Tips:

- Frame generation forces VSync (FIFO presentation) while enabled, because the display is what spaces the
  generated frames out. Pick a multiplier or target frame rate that your screen can actually show.
- A 30 FPS game on a 60 Hz screen works best at 2x; on a 120 Hz screen try 4x or the 120 FPS target.
- If the GPU is the bottleneck, lower the motion estimation resolution first.
- Frame generation applies to the TV screen. It needs a GPU driver with Vulkan memory model, float16 and
  null descriptor support. The settings screen and the in-game menu show the current status.

## Building

The APK is built by GitHub Actions ([workflow](.github/workflows/android_release.yml)). Pushing a tag named
`v<version>` publishes a release; tags that contain a `-` (for example `v0.1.0-beta.1`) are published as
prereleases. To build locally, see [BUILD.md](BUILD.md) and run `./gradlew assembleRelease` in `src/android`.

## Support

Cemu Plus is free and open source, and it will stay that way. If frame generation made your games feel
smoother and you feel like saying thanks, you can buy me a coffee. It is genuinely appreciated and it keeps
the updates coming ☕

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/danielcamilo)

## Credits

This is an unofficial fork. It is not affiliated with, endorsed by or supported by the Cemu team,
Lossless Scaling or any of the projects below.

- [Cemu](https://github.com/cemu-project/Cemu), the Wii U emulator
- [SSimco](https://github.com/SSimco/Cemu) and [SapphireRhodonite](https://github.com/SapphireRhodonite/Cemu) for the Android port
- [Eden Duo](https://github.com/igawa6/eden-duo) / [Eden](https://git.eden-emu.dev/eden-emu/eden), whose
  Lossless Scaling integration this frame generation code is ported from
- [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk), the original Vulkan implementation of the Lossless Scaling frame generation pipeline
- [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) by THS. The frame generation shaders
  are not distributed with Cemu Plus; you need your own copy of the application.

## License

Cemu is licensed under [Mozilla Public License 2.0](/LICENSE.txt). Exempt from this are all files in the
dependencies directory for which the licenses of the original code apply as well as some individual files in
the src folder, as specified in those file headers respectively.

The frame generation code in `src/Cafe/HW/Latte/Renderer/Vulkan/FrameGen` is ported from Eden and lsfg-vk and
is licensed under the GNU General Public License v3.0 or later, as stated in those file headers. Because the APK
combines it with the rest of Cemu, binary releases of Cemu Plus are distributed under the terms of the GPL-3.0
(which MPL-2.0 permits as a secondary license).
