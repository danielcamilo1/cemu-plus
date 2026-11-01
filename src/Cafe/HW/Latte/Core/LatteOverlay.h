#pragma once

void LatteOverlay_init();
void LatteOverlay_render(bool pad_view);
void LatteOverlay_updateStats(double fps, sint32 drawcalls, sint32 fastDrawcalls);
// called for every image presented to the TV, generated is true for frames created by frame generation
void LatteOverlay_notifyFramePresented(bool generated);

void LatteOverlay_pushNotification(const std::string& text, sint32 duration);
// battery state reported by the frontend, level in percent and temperature in tenths of a degree Celsius (negative if unknown)
void LatteOverlay_setBatteryStatus(sint32 level, sint32 temperatureDecidegrees, bool charging);