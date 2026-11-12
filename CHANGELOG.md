# Changelog

What changed in each Cemu Plus release. The release page of each version shows its section from this file.

## 0.4.1 - 2026-11-12

### Game updates
- **Use update** in the settings of a game, under Update, turns its installed update off, and the game runs as its base version. It's only shown for games with an update, and applies the next time the game starts. The game card shows the version the game runs as
- Games whose update doesn't work in Cemu have it off by default, with a note under the switch saying it's recommended off. **Why?** opens the reason

### Fixes
- ZombiU no longer stays on a black screen after the Ubisoft logo. Its update (v32) doesn't work in Cemu, so it's off by default and ZombiU runs as its base version

## 0.4.0 - 2026-11-08

### Motion sensitivity
- **Motion sensitivity X axis** and **Motion sensitivity Y axis** set how fast turning left and right, and tilting up and down go, from 25% to 500%. They are next to the motion inversion on each controller's page in **Input settings** and in the quick menu under Controls, and work for the motion of the device and of controllers
- They scale the turning speed the game gets, which is what aiming with motion uses. A tilt that is held still settles at the real angle of the device

### Updated to the latest Cemu
Cemu Plus now includes everything the main Cemu project added since April 2026:
- **Wii U CPU on ARM:** fixes in the ARM64 recompiler that runs every game, for single-precision float stores (now faster too), division by zero, carry flags and a few wrong instructions. The default NaN mode of ARM is turned off to match the Wii U
- **Performance:** smaller Vulkan and GPU optimizations, like checking only the render state that changed, caching shader lookups and using the fast draw path more often
- **Rendering:** better texture readback (e.g. Color Splash and Xenoblade Chronicles X), textures are refreshed when a game invalidates their memory, mip and slice handling, offsets of some texture sampling modes and many shader corrections
- **Videos:** the colors of in-game videos are right on ARM devices, and video decoding handles interrupted streams better
- **Audio:** more accurate sound mixing and sampling
- **Compatibility:** more Wii U system functions, like the dynamic library notifications, alarm queues, `exit()` and date conversions, and more of the software keyboard
- **Stability:** more state is reset when a game closes, so starting another game afterwards is more reliable, and fixes for crashes when a game exits or the app closes while a game boots
- **Online:** reworked network code
- **Graphic packs:** patches load faster and their code caves get the full size

### Fixes
- Games that point a texture at memory that doesn't exist no longer crash the emulator. The texture is cleared instead. Sonic Lost World crashed this way when Start was pressed on the title screen
- Games in a folder picked with the file picker are read more reliably. After one failed read, every later read of that file came back empty

### Logs
- The emulator log is also written to logcat, so it can be read with adb while a game runs

## 0.3.6 - 2026-11-03

### Input settings, redone
- Each controller has all its settings on one page, the ones used most first: what it emulates, the controller in use with **Set up all inputs**, its buttons and sticks, motion, rumble, profiles, and the stick and trigger sensitivity at the end under **Advanced**. The **Controller settings** dialog and the separate **Device settings** screen are gone
- The controller in use is picked from the connected ones right on the page, and **Set up all inputs** binds that one directly
- **Invert motion X axis** and **Invert motion Y axis** are next to **Use motion** on each controller's page
- The touch screen, sensors and vibration of the device can be moved to a controller from its page, and the vibration strength of the device is set there too
- The inputs are shown as a grid of tiles that say **Not set** when nothing is bound
- The input settings list the controllers in use, with **Add a controller** for the next free one, then **Hotkeys** and **On-screen controls**

### Controller profiles per game
- A controller can be saved as a named profile, and loaded or deleted again, from its page
- The game settings start with a new **Controls** section. Each controller uses your global controls until you tap **Customize for this game**, which gives the game its own copy of them and opens it to change. **Edit controls** opens it again later and **Use global controls** goes back. Profiles saved in the input settings can be picked there too. Changes made while that game runs are saved to its controls
- The game settings show the most used settings first: **Controls**, **GamePad screen crop** and **Graphics**, with **Thread quantum** last under **Advanced**

### Wii U style settings
- Every settings screen has the white and silver look of the Wii U settings, with panels, round switches and a cyan highlight, like the game settings on the second screen
- The settings home has an icon for each section
- **Overlay settings** are now **Toolbar settings**, the name used everywhere else

### Sliders with a gamepad
- Up and down on the D-pad move past a slider to the next setting instead of changing it
- Press A on a slider to change it with left and right, A again keeps the value and B puts the old one back. Left and right also change it right away

### Game library with a controller
- A guide in the corner of the library shows what the buttons do once a controller is used: **A** starts the game, **Y** opens its game settings, **X** the settings of Cemu and **Start** its options. It works in the grid and in the list
- With the second screen on, **Y** moves the controller to the game settings shown there, so they can be changed without touching the screen. **B** goes back to the games
- In the list, the second screen shows the game the controller is on, not only the one that was tapped

### Motion controls
- **Invert motion X axis** and **Invert motion Y axis** flip turning left and right, and tilting up and down. They work for the motion of the device and of controllers, and can be found in **Input settings** and in the quick menu under Controls

### Quick menu
- A new **Input** section opens the input settings, or the settings of each controller in use, without leaving the game

### Fixes
- Star Fox Zero no longer crashes when shots hit the ships in the tutorial. The game uses 64-bit values that the emulator handled with ARM64 instructions that need a stricter memory alignment than the Wii U does

### Crash reports
- **Share log file** and **Open Cemu folder** use the data folder Cemu is using. With a custom data folder they showed the default folder, so the shared log could be from an old session
- When the emulator crashes, the crash details (signal, native backtrace, the game and the Wii U CPU state) are written to log.txt before the app closes, and to logcat. Before, the app could close before they were written
- Android also writes its own crash report (tombstone) for emulator crashes now

## 0.3.5 - 2026-11-03

### Fixes
- With **Swap screens** on, a game starts with the GamePad image on its screen. Before, both screens showed the TV image until Swap screens was turned off and on again
- Swapping the screens keeps the toolbar where you put it. It moved to the top when the GamePad image was centered on its screen

### GamePad screen crop
- The crop is off for every game until it is turned on in that game's settings. Before, the switch showed as on for all games even when nothing was cropped
- The game settings and the quick menu say when Swap screens is on, because the crop is only used while the GamePad is on the second screen

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
