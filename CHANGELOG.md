# Changelog

What changed in each Cemu Plus release. The release page of each version shows its section from this file.

## 0.3.3 - 2026-11-03

### Swap screens
- **Swap screens** in the setup wizard and in **Screen layout**, for devices where the second screen is found the other way around, like the Dual Screen add-on of the Retroid Pocket Mini. It puts the GamePad on the main screen and the TV image on the second screen
- The choice is remembered, including when it is changed from the quick menu
- Fix: the toolbar buttons work with swapped screens when the toolbar fills the black bar

### Toolbar over the game
- When the toolbar is drawn over the game instead of in a black bar, it has a hide button in its top right corner
- A hidden toolbar becomes a small bubble with the frame rate in the same corner. Tap the bubble to bring the toolbar back. The choice is remembered

## 0.3.2 - 2026-11-02

### Wii U style quick menu
- Big **Back to game**, **Settings** and **Exit game** buttons at the top, like the Wii U HOME Menu
- Sections in order of use: Screens, GamePad screen and toolbar, Frame generation, Controls
- New in the menu: open the settings, the toolbar switch, the GamePad image position (top, center or bottom) and hiding the toolbar for the running game while cropping
- Follows the toolbar theme (white or black), with a cyan outline on the item a controller is on. Focus starts on Back to game
- Toggles no longer close the menu, so several can be tried in a row
- Turning the second screen or Show GamePad on or off moves the toolbar along, the same as the screen layout settings

### GamePad screen crop
- New **GamePad screen crop** section in **Edit game profile**, saved per game. Cutting the borders of the GamePad image makes the middle of it bigger on second screens that aren't 16:9, like the AYN Thor bottom screen
- Live previews of the cropped GamePad image and of the second screen as it will look in game
- Left and right, and top and bottom sliders (0 to 40%), **Fill the screen** and **Reset**
- The same controls in the quick menu, applied to the running game right away
- **Hide the toolbar for this game** hides the toolbar in the black bars while the image is cropped
- Touches follow the cropped image, and the cut borders can't be touched

### Controls
- **Setup all inputs** also sets two hotkeys: Select + Start exits the game (after asking) and Select + X opens the quick menu. Both can be changed in Hotkeys

## 0.3.1 - 2026-11-02

- First time setup wizard in the Wii U setup style: games folder, controls, frame generation and toolbar. Every step can be skipped and opened again from Settings
- The frame generation toolbar buttons show **Needs setup** until Lossless.dll is selected, and offer to open the frame generation settings
- Shader compilation progress and the quick menu button are on by default in the toolbar
- Fix: the toolbar no longer shows on both screens after setting up Lossless Scaling from the in-game settings
- Fix: the GamePad on the second screen comes back after leaving the app

## 0.3.0 - 2026-11-02

- New **Screen layout** page in Settings for one or two screens, with live previews of where the TV image, the GamePad image and the toolbar go
- **Use second screen** toggle in General settings, on by default when a second screen is found
- White Wii U toolbar theme
- Game list in the Wii U Menu style by default, with a toggle back to the list
- **Enable motion** uses the device gyro for the GamePad even without a Device controller mapped, and is remembered
- GamePad volume slider in the toolbar header
- Quick menu button in the toolbar
- Fix: GamePad position Above and Left now place the GamePad first

## 0.2.0 - 2026-11-01

- The toolbar fills the black bar and gets touch buttons: frame generation on/off, frame generation multiplier, pause and settings
- AMD FSR 1 and Snapdragon GSR upscale filters, with a sharpness slider
- Game icon and name in the toolbar header
- The temperature tile shows the GPU temperature first and the CPU next to it
- New defaults: GamePad image at the top, toolbar in the bottom black bar with FPS and all buttons on
- Fix: hang or crash when the device sleeps while the game is paused

## 0.1.0 - 2026-11-01

- Lossless Scaling frame generation, using the `Lossless.dll` from your own copy of Lossless Scaling. Multiplier, target frame rate and motion estimation resolution can be changed in game
- GamePad on a second screen (merged from the upstream dual screen work), swapping screens and a custom data storage location
- GamePad screen alignment and overlay placement for second screens that aren't 16:9
- GamePad styled performance panel with GPU usage, temperature and battery stats
- Installs next to regular Cemu (app id `info.cemu.cemuplus`)
