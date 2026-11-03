# Changelog

What changed in each Cemu Plus release. The release page of each version shows its section from this file.

## 0.3.4 - 2026-11-03

### Game settings on the second screen
- With the second screen in use, picking a game in the library shows its settings on the second screen, where they can be changed with touch. Tap a game once to pick it and again to start it, or tap **Start** on the second screen. With a controller, the game the cursor is on is shown and A starts it
- The game settings (**Edit game profile**) are redone in the style of the Wii U Menu: the game's icon with its play time, last played date, region and version, switches on white panels and one tap choices for CPU mode, thread quantum and custom driver
- The GamePad screen crop section has the same look
- The game settings start with the ones used most: GamePad screen crop first, then the custom driver under **Graphics**, and the CPU, shader and library settings last under **Advanced**

### Fixes
- The app opens faster. Graphic packs were loaded twice at startup, and with a custom data folder every save was synced with it before the app could show anything. That sync now runs in the background, and games wait for it before they start
- Exiting a game no longer hangs the app. The way the app closed could get stuck while the emulator was still running, and with a custom data folder all saves were copied again on the main thread. Now only the saves that changed are copied, in the background
- Shader caches are written to storage right away, so closing a game does not lose them

## 0.3.3 - 2026-11-03

### Swap screens
- **Swap screens** in the setup wizard and in **Screen layout**, for devices where the second screen is found the other way around, like the Dual Screen add-on of the Retroid Pocket Mini. It puts the GamePad on the main screen and the TV image on the second screen
- While setting up the screens, the setup wizard shows on the second screen itself whether it gets the TV or the GamePad image, so it is easy to see if swapping is needed
- The choice is remembered, including when it is changed from the quick menu
- Fix: the toolbar buttons work with swapped screens when the toolbar fills the black bar

### Toolbar
- The toolbar goes with the GamePad image by default, so swapping the screens moves it along. **Toolbar goes on** in the setup wizard and in **Screen layout** puts it on the TV screen instead
- The setup wizard's toolbar step shows the layout preview on the second screen itself, with the toolbar and the game image, to judge it on the real screen and its shape
- The setup wizard and the settings say when the black bars are too thin for the toolbar, so it floats over the game, and how to minimize it

### Toolbar over the game
- When the toolbar is drawn over the game instead of in a black bar, it has a hide key in its top right corner
- A hidden toolbar becomes a small bubble with the frame rate. Tap the bubble to bring the toolbar back. The choice is remembered
- **Minimized toolbar position** picks the corner of the bubble: top left, top, top right, bottom left, bottom or bottom right

### Controls
- The Android back button opens the quick menu with one press during the game, also the back button of handhelds with built-in controls

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
