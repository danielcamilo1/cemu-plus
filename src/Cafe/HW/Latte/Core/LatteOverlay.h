#pragma once

void LatteOverlay_init();
void LatteOverlay_render(bool pad_view);
void LatteOverlay_updateStats(double fps, sint32 drawcalls, sint32 fastDrawcalls);
// called for every image presented to the TV, generated is true for frames created by frame generation
void LatteOverlay_notifyFramePresented(bool generated);

void LatteOverlay_pushNotification(const std::string& text, sint32 duration);
// battery state reported by the frontend, level in percent and temperature in tenths of a degree Celsius (negative if unknown)
void LatteOverlay_setBatteryStatus(sint32 level, sint32 temperatureDecidegrees, bool charging);
// touch buttons of the GamePad panel, the values match NativeEmulation.OverlayAction on Android
enum class OverlayAction : sint32
{
	kNone = 0,
	kFrameGen = 1,
	kSettings = 2,
	kPause = 3,
	kFrameGenMode = 4,
};
// returns the panel button at the given position (in window pixels) of the TV or GamePad screen, as drawn in the last frame
OverlayAction LatteOverlay_getActionAt(sint32 x, sint32 y, bool padView);
// highlights a button while it is held down
void LatteOverlay_setPressedAction(OverlayAction action);
// shows the pause button as a resume button
void LatteOverlay_setPaused(bool paused);
