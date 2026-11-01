#include "Cafe/HW/Latte/Core/LatteOverlay.h"
#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LattePerformanceMonitor.h"
#include "Cafe/HW/Latte/Renderer/Renderer.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/FrameGenVk.h"
#include "Cafe/Account/Account.h"
#include "config/CemuConfig.h"
#include "config/ActiveSettings.h"
#include "WindowSystem.h"

#include <imgui.h>
#include "resource/IconsFontAwesome5.h"
#include "imgui/imgui_extension.h"

#include "input/InputManager.h"
#include "util/SystemInfo/SystemInfo.h"

#include <cinttypes>

struct OverlayStats
{
	OverlayStats() {};

	int processor_count = 1;
	ProcessorTime processor_time_cemu;
	std::vector<ProcessorTime> processor_times;

	double fps{};
	double presented_fps{}; // real + generated frames shown on the TV
	double frame_gen_ratio{}; // presented frames per real frame
	bool frame_gen_active{};
	uint32 draw_calls_per_frame{};
	uint32 fast_draw_calls_per_frame{};
	float cpu_usage{}; // cemu cpu usage in %
	std::vector<float> cpu_per_core; // global cpu usage in % per core
	uint32 ram_usage{}; // ram usage in MB

	int vramUsage{}, vramTotal{}; // vram usage in mb
} g_state{};

// device sensors read from sysfs. Which files exist and are readable depends on the device, missing values are negative
struct DeviceStats
{
	float gpu_usage = -1.0f; // in %
	sint32 gpu_freq_mhz = -1;
	sint32 cpu_freq_mhz = -1; // fastest core
	float cpu_temp = -1.0f; // in °C
	float gpu_temp = -1.0f;
} g_device{};

// battery state is pushed by the frontend (Android BatteryManager)
std::atomic<sint32> g_battery_level{-1}; // in %
std::atomic<sint32> g_battery_temp_decidegrees{-1};
std::atomic_bool g_battery_charging{false};

void LatteOverlay_setBatteryStatus(sint32 level, sint32 temperatureDecidegrees, bool charging)
{
	g_battery_level = level;
	g_battery_temp_decidegrees = temperatureDecidegrees;
	g_battery_charging = charging;
}

// panel buttons drawn in the last frame, hit tested by the frontend from its touch thread
struct OverlayButton
{
	OverlayAction action;
	ImVec2 min, max;
};
std::mutex g_buttons_mutex;
std::vector<OverlayButton> g_buttons[2]; // TV, GamePad
std::atomic<sint32> g_pressed_action{(sint32)OverlayAction::kNone};
std::atomic_bool g_title_paused{false};

static void LatteOverlay_publishButtons(bool padView, std::vector<OverlayButton>&& buttons)
{
	std::scoped_lock lock(g_buttons_mutex);
	g_buttons[padView ? 1 : 0] = std::move(buttons);
}

OverlayAction LatteOverlay_getActionAt(sint32 x, sint32 y, bool padView)
{
	std::scoped_lock lock(g_buttons_mutex);
	for (const auto& button : g_buttons[padView ? 1 : 0])
	{
		if ((float)x >= button.min.x && (float)x < button.max.x && (float)y >= button.min.y && (float)y < button.max.y)
			return button.action;
	}
	return OverlayAction::kNone;
}

void LatteOverlay_setPressedAction(OverlayAction action)
{
	g_pressed_action = (sint32)action;
}

void LatteOverlay_setPaused(bool paused)
{
	g_title_paused = paused;
}

static float LatteOverlay_getBatteryTemperature()
{
	const sint32 temp = g_battery_temp_decidegrees.load();
	return temp > 0 ? (float)temp / 10.0f : -1.0f;
}

static bool ReadSysfsValues(const std::string& path, sint64* values, sint32 count)
{
	std::ifstream file(path);
	if (!file)
		return false;
	for (sint32 i = 0; i < count; ++i)
	{
		if (!(file >> values[i]))
			return false;
	}
	return true;
}

// frequencies are reported in Hz, kHz or MHz depending on the driver
static sint32 FrequencyToMHz(sint64 value)
{
	if (value >= 10000000)
		return (sint32)(value / 1000000);
	if (value >= 10000)
		return (sint32)(value / 1000);
	return (sint32)value;
}

static float QueryGpuUsage()
{
	sint64 values[2];
	// Adreno
	if (ReadSysfsValues("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", values, 1))
		return (float)values[0];
	if (ReadSysfsValues("/sys/class/kgsl/kgsl-3d0/gpubusy", values, 2))
		return values[1] > 0 ? 100.0f * (float)values[0] / (float)values[1] : 0.0f;
	// Mali and MediaTek
	for (const char* path : { "/sys/kernel/gpu/gpu_busy", "/sys/module/ged/parameters/gpu_loading", "/sys/class/misc/mali0/device/utilization" })
	{
		if (ReadSysfsValues(path, values, 1))
			return (float)values[0];
	}
	return -1.0f;
}

static sint32 QueryGpuFrequency()
{
	sint64 value;
	for (const char* path : { "/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "/sys/kernel/gpu/gpu_clock", "/sys/class/misc/mali0/device/cur_freq" })
	{
		if (ReadSysfsValues(path, &value, 1) && value > 0)
			return FrequencyToMHz(value);
	}
	return -1;
}

static sint32 QueryCpuFrequency()
{
	sint32 maxFreq = -1;
	for (sint32 i = 0; i < g_state.processor_count; ++i)
	{
		sint64 value;
		if (ReadSysfsValues(fmt::format("/sys/devices/system/cpu/cpu{}/cpufreq/scaling_cur_freq", i), &value, 1))
			maxFreq = std::max(maxFreq, FrequencyToMHz(value));
	}
	return maxFreq;
}

static void QueryTemperatures(float& cpuTemp, float& gpuTemp)
{
	// sort the thermal zones into CPU and GPU sensors once
	static std::vector<std::string> s_cpuZones, s_gpuZones;
	static bool s_scanned = false;
	if (!s_scanned)
	{
		s_scanned = true;
		std::error_code ec;
		for (const auto& entry : fs::directory_iterator("/sys/class/thermal", ec))
		{
			const auto name = entry.path().filename().string();
			if (!name.starts_with("thermal_zone"))
				continue;
			std::ifstream typeFile(entry.path() / "type");
			std::string type;
			if (!(typeFile >> type))
				continue;
			std::transform(type.begin(), type.end(), type.begin(), ::tolower);
			const auto tempPath = (entry.path() / "temp").string();
			if (type.find("gpu") != std::string::npos)
				s_gpuZones.emplace_back(tempPath);
			else if (type.find("cpu") != std::string::npos || type.find("soc") != std::string::npos || type.starts_with("tsens"))
				s_cpuZones.emplace_back(tempPath);
		}
	}

	auto hottest = [](const std::vector<std::string>& zones)
	{
		float result = -1.0f;
		for (const auto& path : zones)
		{
			sint64 value;
			if (!ReadSysfsValues(path, &value, 1))
				continue;
			// most drivers report millidegrees
			const float celsius = std::abs(value) >= 1000 ? (float)value / 1000.0f : (float)value;
			if (celsius > 0.0f && celsius < 150.0f)
				result = std::max(result, celsius);
		}
		return result;
	};
	cpuTemp = hottest(s_cpuZones);
	gpuTemp = hottest(s_gpuZones);
}

std::atomic<uint64> g_presented_real_frames{0};
std::atomic<uint64> g_presented_generated_frames{0};

void LatteOverlay_notifyFramePresented(bool generated)
{
	(generated ? g_presented_generated_frames : g_presented_real_frames).fetch_add(1, std::memory_order_relaxed);
}

extern std::atomic_int g_compiled_shaders_total;
extern std::atomic_int g_compiled_shaders_async;

std::atomic_int g_compiling_pipelines;
std::atomic_int g_compiling_pipelines_async;
std::atomic_uint64_t g_compiling_pipelines_syncTimeSum;

extern std::mutex g_friend_notification_mutex;
extern std::vector< std::pair<std::string, int> > g_friend_notifications;

std::mutex g_notification_mutex;
std::vector< std::pair<std::string, int> > g_notifications;

void LatteOverlay_pushNotification(const std::string& text, sint32 duration)
{
	std::unique_lock lock(g_notification_mutex);
	g_notifications.emplace_back(text, duration);
}

struct ShaderCompileStatus
{
	sint32 shaders = 0;
	sint32 shadersAsync = 0;
	sint32 pipelines = 0;
	sint32 pipelinesAsync = 0;
};

// collects the shaders and pipelines compiled recently. The counts reset 2.5 seconds after the last compilation
static ShaderCompileStatus LatteOverlay_updateShaderCompileStatus()
{
	static ShaderCompileStatus s_status;
	static std::chrono::steady_clock::time_point s_lastShaderTick = tick_cached();
	static std::chrono::steady_clock::time_point s_lastPipelineTick = tick_cached();
	const auto now = tick_cached();

	const int shaders = g_compiled_shaders_total.exchange(0);
	s_status.shaders += shaders;
	s_status.shadersAsync += g_compiled_shaders_async.exchange(0);
	if (shaders > 0)
		s_lastShaderTick = now;
	if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_lastShaderTick).count() >= 2500)
	{
		s_status.shaders = 0;
		s_status.shadersAsync = 0;
	}

	const int pipelines = g_compiling_pipelines.exchange(0);
	s_status.pipelines += pipelines;
	s_status.pipelinesAsync += g_compiling_pipelines_async.exchange(0);
	if (pipelines > 0)
		s_lastPipelineTick = now;
	if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_lastPipelineTick).count() >= 2500)
	{
		s_status.pipelines = 0;
		s_status.pipelinesAsync = 0;
	}
	return s_status;
}

// the shader compilation messages can be moved from the notifications into the GamePad panel
static bool LatteOverlay_isShaderStatusInPanel()
{
	const auto& overlay = GetConfig().overlay;
	return overlay.shader_compiling && overlay.style == OverlayStyle::kGamePad && overlay.position != ScreenPosition::kDisabled;
}

struct OverlayList
{
	std::wstring text;
	float pos_x = 0;
	float pos_y = 0;
	float width;

	OverlayList(std::wstring text, float width)
		: text(std::move(text)), width(width) {}
};

const auto kPopupFlags = ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav;

const float kBackgroundAlpha = 0.65f;
const char* const kDegree = "\xC2\xB0";

static void LatteOverlay_renderDebugInfo()
{
	// general debug info
	ImGui::Text("--- Debug info ---");
	ImGui::Text("IndexUploadPerFrame: %dKB", (performanceMonitor.stats.indexDataUploadPerFrame+1023)/1024);
	// backend specific info
	g_renderer->AppendOverlayDebugInfo();
}

void LatteOverlay_renderOverlay(ImVec2& position, ImVec2& pivot, sint32 direction, float fontSize, bool pad, bool horizontal)
{
	auto& config = GetConfig();

	const auto font = ImGui_GetFont(fontSize);
	ImGui::PushFont(font);

	const ImVec4 color = ImGui::ColorConvertU32ToFloat4(config.overlay.text_color);
	ImGui::PushStyleColor(ImGuiCol_Text, color);
	// stats overlay
	if (config.overlay.fps || config.overlay.drawcalls || config.overlay.cpu_usage || config.overlay.cpu_per_core_usage || config.overlay.ram_usage || config.overlay.vram_usage || config.overlay.gpu_usage || config.overlay.temperature || config.overlay.battery || config.overlay.debug)
	{
		ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
		ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
		if (ImGui::Begin("Stats overlay", nullptr, kPopupFlags))
		{
			// in horizontal mode (wide, short black bars) the stats are placed next to each other
			bool firstItem = true;
			auto beginItem = [&]()
			{
				if (horizontal && !firstItem)
				{
					ImGui::SameLine();
					ImGui::TextUnformatted("|");
					ImGui::SameLine();
				}
				firstItem = false;
			};

			if (config.overlay.fps)
			{
				beginItem();
				if (g_state.frame_gen_active)
				{
					ImGui::Text("FPS: %.2lf (FG x%.1lf)", g_state.presented_fps, g_state.frame_gen_ratio);
					beginItem();
					ImGui::Text("Base FPS: %.2lf", g_state.fps);
				}
				else
					ImGui::Text("FPS: %.2lf", g_state.fps);
			}

			if (config.overlay.drawcalls)
			{
				beginItem();
				ImGui::Text("Draws/f: %d (fast: %d)", g_state.draw_calls_per_frame, g_state.fast_draw_calls_per_frame);
			}

			if (config.overlay.cpu_usage)
			{
				beginItem();
				ImGui::Text("CPU: %.2lf%%", g_state.cpu_usage);
			}

			if (config.overlay.cpu_per_core_usage)
			{
				for (sint32 i = 0; i < g_state.processor_count; ++i)
				{
					beginItem();
					ImGui::Text("CPU #%d: %.2lf%%", i + 1, g_state.cpu_per_core[i]);
				}
			}

			if (config.overlay.ram_usage)
			{
				beginItem();
				ImGui::Text("RAM: %dMB", g_state.ram_usage);
			}

			if(config.overlay.vram_usage && g_state.vramUsage != -1 && g_state.vramTotal != -1)
			{
				beginItem();
				ImGui::Text("VRAM: %dMB / %dMB", g_state.vramUsage, g_state.vramTotal);
			}

			if (config.overlay.gpu_usage)
			{
				beginItem();
				if (g_device.gpu_usage < 0.0f)
					ImGui::TextUnformatted("GPU: N/A");
				else if (g_device.gpu_freq_mhz > 0)
					ImGui::Text("GPU: %.0f%% (%dMHz)", g_device.gpu_usage, g_device.gpu_freq_mhz);
				else
					ImGui::Text("GPU: %.0f%%", g_device.gpu_usage);
			}

			if (config.overlay.temperature)
			{
				beginItem();
				std::string text = "Temp:";
				if (g_device.cpu_temp >= 0.0f)
					text += fmt::format(" CPU {:.0f}{}C", g_device.cpu_temp, kDegree);
				if (g_device.gpu_temp >= 0.0f)
					text += fmt::format(" GPU {:.0f}{}C", g_device.gpu_temp, kDegree);
				if (LatteOverlay_getBatteryTemperature() >= 0.0f)
					text += fmt::format(" Battery {:.0f}{}C", LatteOverlay_getBatteryTemperature(), kDegree);
				if (text.size() == 5)
					text += " N/A";
				ImGui::TextUnformatted(text.c_str());
			}

			if (config.overlay.battery)
			{
				beginItem();
				const sint32 level = g_battery_level.load();
				if (level < 0)
					ImGui::TextUnformatted("Battery: N/A");
				else
					ImGui::Text("Battery: %d%%%s", level, g_battery_charging ? " (charging)" : "");
			}

			if (config.overlay.debug)
				LatteOverlay_renderDebugInfo();

			position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
		}
		ImGui::End();
	}

	ImGui::PopStyleColor();
	ImGui::PopFont();
}

// GamePad panel style, a piano black shell with a glossy reflection and matte stat tiles like the black Wii U GamePad
const ImU32 kPanelBody = IM_COL32(10, 10, 12, 238);
const ImU32 kPanelRim = IM_COL32(58, 58, 64, 255);
const ImU32 kPanelTile = IM_COL32(24, 24, 28, 240);
const ImU32 kPanelTileBorder = IM_COL32(46, 46, 52, 255);
const ImU32 kPanelLabel = IM_COL32(142, 142, 152, 255);
const ImU32 kPanelGrille = IM_COL32(52, 52, 58, 255);
const ImU32 kPanelAccent = IM_COL32(0, 162, 217, 255); // Wii U blue
const ImU32 kPanelGood = IM_COL32(76, 217, 100, 255);
const ImU32 kPanelWarning = IM_COL32(255, 176, 32, 255);
const ImU32 kPanelCritical = IM_COL32(255, 77, 77, 255);

static ImU32 LatteOverlay_usageColor(float fraction)
{
	if (fraction >= 0.9f)
		return kPanelCritical;
	if (fraction >= 0.7f)
		return kPanelWarning;
	return kPanelAccent;
}

static ImU32 LatteOverlay_temperatureColor(float celsius)
{
	if (celsius >= 75.0f)
		return kPanelCritical;
	if (celsius >= 55.0f)
		return kPanelWarning;
	return kPanelAccent;
}

struct OverlayTile
{
	const char* icon = "";
	std::string label;
	std::string detail; // top right, next to the label
	std::string value;
	std::string suffix; // smaller text after the value
	float meter = -1.0f; // 0 - 1, no meter if negative
	ImU32 meterColor = kPanelAccent;
	ImU32 iconColor = kPanelAccent;
	bool coreBars = false; // shows the usage of every core instead of a value
	OverlayAction action = OverlayAction::kNone; // drawn as a touch button
	bool on = false; // lit button
};

static std::vector<OverlayTile> LatteOverlay_buildTiles()
{
	const auto& config = GetConfig();
	std::vector<OverlayTile> tiles;

	if (config.overlay.fps)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_TACHOMETER_ALT;
		tile.label = "FPS";
		if (g_state.frame_gen_active)
		{
			tile.value = fmt::format("{:.0f}", g_state.presented_fps);
			tile.suffix = fmt::format("base {:.0f}", g_state.fps);
			tile.detail = fmt::format("FG x{:.1f}", g_state.frame_gen_ratio);
		}
		else
		{
			tile.value = fmt::format("{:.0f}", g_state.fps);
			if (g_state.fps > 0.0)
				tile.detail = fmt::format("{:.1f} ms", 1000.0 / g_state.fps);
		}
	}

	if (config.overlay.drawcalls)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_CUBES;
		tile.label = "DRAWS";
		tile.value = fmt::format("{}", g_state.draw_calls_per_frame);
		tile.detail = fmt::format("fast {}", g_state.fast_draw_calls_per_frame);
	}

	if (config.overlay.cpu_usage)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_MICROCHIP;
		tile.label = "CPU";
		tile.value = fmt::format("{:.0f}%", g_state.cpu_usage);
		tile.meter = std::clamp(g_state.cpu_usage / 100.0f, 0.0f, 1.0f);
		tile.meterColor = LatteOverlay_usageColor(tile.meter);
		if (g_device.cpu_freq_mhz > 0)
			tile.detail = fmt::format("{:.2f} GHz", (float)g_device.cpu_freq_mhz / 1000.0f);
	}

	if (config.overlay.cpu_per_core_usage && g_state.processor_count > 0)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_LAYER_GROUP;
		tile.label = "CORES";
		tile.detail = fmt::format("{}", g_state.processor_count);
		tile.coreBars = true;
	}

	if (config.overlay.gpu_usage)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_DESKTOP;
		tile.label = "GPU";
		if (g_device.gpu_usage >= 0.0f)
		{
			tile.value = fmt::format("{:.0f}%", g_device.gpu_usage);
			tile.meter = std::clamp(g_device.gpu_usage / 100.0f, 0.0f, 1.0f);
			tile.meterColor = LatteOverlay_usageColor(tile.meter);
		}
		else
			tile.value = "N/A";
		if (g_device.gpu_freq_mhz > 0)
			tile.detail = fmt::format("{} MHz", g_device.gpu_freq_mhz);
	}

	if (config.overlay.ram_usage)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_MEMORY;
		tile.label = "RAM";
		tile.value = fmt::format("{}", g_state.ram_usage);
		tile.suffix = "MB";
	}

	if (config.overlay.vram_usage && g_state.vramUsage != -1 && g_state.vramTotal > 0)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_MEMORY;
		tile.label = "VRAM";
		tile.value = fmt::format("{}", g_state.vramUsage);
		tile.suffix = "MB";
		tile.detail = fmt::format("of {}", g_state.vramTotal);
		tile.meter = std::clamp((float)g_state.vramUsage / (float)g_state.vramTotal, 0.0f, 1.0f);
		tile.meterColor = LatteOverlay_usageColor(tile.meter);
	}

	if (config.overlay.temperature)
	{
		auto& tile = tiles.emplace_back();
		tile.icon = (const char*)ICON_FA_THERMOMETER_HALF;
		tile.label = "TEMP";
		const float batteryTemp = LatteOverlay_getBatteryTemperature();
		// the CPU sensor is the most useful one, devices that don't expose it still report the battery temperature
		float temp = g_device.cpu_temp;
		const char* source = "CPU";
		if (temp < 0.0f)
		{
			temp = batteryTemp;
			source = "BAT";
		}
		if (temp >= 0.0f)
		{
			tile.value = fmt::format("{:.0f}{}", temp, kDegree);
			tile.suffix = source;
			tile.meter = std::clamp((temp - 25.0f) / 70.0f, 0.0f, 1.0f);
			tile.meterColor = LatteOverlay_temperatureColor(temp);
			tile.iconColor = tile.meterColor;
		}
		else
			tile.value = "N/A";
		if (g_device.gpu_temp >= 0.0f)
			tile.detail = fmt::format("GPU {:.0f}{}", g_device.gpu_temp, kDegree);
		else if (g_device.cpu_temp >= 0.0f && batteryTemp >= 0.0f)
			tile.detail = fmt::format("BAT {:.0f}{}", batteryTemp, kDegree);
	}

	if (config.overlay.battery)
	{
		auto& tile = tiles.emplace_back();
		tile.label = "BATTERY";
		const sint32 level = g_battery_level.load();
		const bool charging = g_battery_charging.load();
		if (charging)
			tile.icon = (const char*)ICON_FA_BOLT;
		else if (level > 85)
			tile.icon = (const char*)ICON_FA_BATTERY_FULL;
		else if (level > 60)
			tile.icon = (const char*)ICON_FA_BATTERY_THREE_QUARTERS;
		else if (level > 35)
			tile.icon = (const char*)ICON_FA_BATTERY_HALF;
		else if (level > 10)
			tile.icon = (const char*)ICON_FA_BATTERY_QUARTER;
		else
			tile.icon = (const char*)ICON_FA_BATTERY_EMPTY;
		if (level >= 0)
		{
			tile.value = fmt::format("{}%", level);
			tile.meter = std::clamp((float)level / 100.0f, 0.0f, 1.0f);
			tile.meterColor = level <= 15 ? kPanelCritical : (level <= 30 ? kPanelWarning : kPanelGood);
			tile.iconColor = charging ? kPanelWarning : tile.meterColor;
			if (charging)
				tile.suffix = "charging";
		}
		else
			tile.value = "N/A";
		if (LatteOverlay_getBatteryTemperature() >= 0.0f)
			tile.detail = fmt::format("{:.0f}{}", LatteOverlay_getBatteryTemperature(), kDegree);
	}

	// touch buttons go after the stats
	const bool frameGenEnabled = config.frame_gen.enabled.GetValue();
	if (config.overlay.action_frame_gen)
	{
		auto& tile = tiles.emplace_back();
		tile.action = OverlayAction::kFrameGen;
		tile.icon = (const char*)ICON_FA_MAGIC;
		tile.on = frameGenEnabled;
		tile.label = frameGenEnabled ? "FRAME GEN ON" : "FRAME GEN OFF";
		if (frameGenEnabled)
		{
			switch (FrameGenVk::GetRuntimeStatus())
			{
			case FrameGenRuntimeStatus::UnsupportedDevice: tile.detail = "Unsupported GPU"; break;
			case FrameGenRuntimeStatus::DllUnavailable: tile.detail = "Lossless.dll missing"; break;
			case FrameGenRuntimeStatus::InitFailed: tile.detail = "Failed to start"; break;
			default: break;
			}
		}
	}

	if (config.overlay.action_frame_gen_mode)
	{
		auto& tile = tiles.emplace_back();
		tile.action = OverlayAction::kFrameGenMode;
		tile.icon = (const char*)ICON_FA_LAYER_GROUP;
		const sint32 targetRate = config.frame_gen.target_rate.GetValue();
		if (targetRate > 0)
			tile.label = fmt::format("FG {} FPS", targetRate);
		else
			tile.label = fmt::format("FG x{}", config.frame_gen.multiplier.GetValue());
		tile.on = frameGenEnabled;
		if (targetRate > 0 && g_state.frame_gen_active)
			tile.detail = fmt::format("now x{:.1f}", g_state.frame_gen_ratio);
	}

	if (config.overlay.action_settings)
	{
		auto& tile = tiles.emplace_back();
		tile.action = OverlayAction::kSettings;
		tile.icon = (const char*)ICON_FA_COG;
		tile.label = "SETTINGS";
	}

	if (config.overlay.action_pause)
	{
		auto& tile = tiles.emplace_back();
		tile.action = OverlayAction::kPause;
		const bool paused = g_title_paused.load();
		tile.icon = paused ? (const char*)ICON_FA_PLAY : (const char*)ICON_FA_PAUSE;
		tile.label = paused ? "RESUME" : "PAUSE";
		tile.on = paused;
	}

	return tiles;
}

struct PanelLayout
{
	float f{}; // base font size, everything else is relative to it
	sint32 cols{}, rows{};
	float tileW{}, tileH{}, gap{}, pad{};
	float headerH{}; // header row with the power LED, holds the shader status
	float statusH{}; // separate shader status row when there is no header
	ImVec2 size{};
};

static void LatteOverlay_updatePanelSize(PanelLayout& l)
{
	const float gridH = l.rows > 0 ? (float)l.rows * l.tileH + (float)(l.rows - 1) * l.gap : 0.0f;
	l.size.x = 2.0f * l.pad + (float)l.cols * l.tileW + (float)(l.cols - 1) * l.gap;
	l.size.y = 2.0f * l.pad + l.headerH + gridH + l.statusH;
}

static PanelLayout LatteOverlay_measurePanel(float f, sint32 tileCount, sint32 cols, bool header, bool statusRow)
{
	PanelLayout l;
	l.f = f;
	l.cols = cols;
	l.rows = (tileCount + cols - 1) / cols;
	l.tileW = f * 6.4f;
	l.tileH = f * 3.6f;
	l.gap = f * 0.45f;
	l.pad = f * 0.7f;
	l.headerH = header ? f * 1.5f : 0.0f;
	l.statusH = statusRow ? f * 1.2f + (l.rows > 0 ? l.gap : 0.0f) : 0.0f;
	LatteOverlay_updatePanelSize(l);
	return l;
}

// draws the shader compilation status between minX and maxX, vertically centered on centerY. Returns false if nothing is compiling
static bool LatteOverlay_drawShaderStatus(ImDrawList* dl, ImFont* font, float fontSize, float minX, float maxX, float centerY, bool alignRight)
{
	const auto status = LatteOverlay_updateShaderCompileStatus();
	if (status.shaders <= 0 && status.pipelines <= 0)
		return false;

	const bool showAsync = GetConfig().async_compile;
	std::string text = "Compiling";
	if (status.shaders > 0)
	{
		text += fmt::format(" {} shader{}", status.shaders, status.shaders > 1 ? "s" : "");
		if (showAsync && status.shadersAsync > 0)
			text += fmt::format(" ({} async)", status.shadersAsync);
	}
	if (status.pipelines > 0)
	{
		if (status.shaders > 0)
			text += " \xC2\xB7";
		text += fmt::format(" {} pipeline{}", status.pipelines, status.pipelines > 1 ? "s" : "");
		if (showAsync && status.pipelinesAsync > 0)
			text += fmt::format(" ({} async)", status.pipelinesAsync);
	}

	const float radius = fontSize * 0.36f;
	const float spinnerW = radius * 2.0f + fontSize * 0.4f;
	const float textW = font->CalcTextSizeA(fontSize, FLT_MAX, 0.0f, text.c_str()).x;
	const float x = alignRight ? std::max(minX, maxX - spinnerW - textW) : minX;

	// spinning arc
	const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(tick_cached().time_since_epoch()).count();
	const float angle = (float)(ms % 100000) * 0.006f;
	const ImVec2 center{ x + radius, centerY };
	dl->AddCircle(center, radius, IM_COL32(255, 255, 255, 30), 20, fontSize * 0.12f);
	dl->PathArcTo(center, radius, angle, angle + 4.2f, 20);
	dl->PathStroke(kPanelAccent, 0, fontSize * 0.12f);

	dl->PushClipRect({ minX, centerY - fontSize }, { maxX, centerY + fontSize }, true);
	dl->AddText(font, fontSize, { x + spinnerW, centerY - fontSize * 0.5f }, kPanelLabel, text.c_str());
	dl->PopClipRect();
	return true;
}

// a raised key like the buttons of the GamePad: big icon with a caption, an LED when it is switched on
static void LatteOverlay_drawButton(ImDrawList* dl, const OverlayTile& tile, ImVec2 tmin, ImVec2 tmax, float f, ImFont* smallFont, float smallSize, ImFont* valueFont, float valueSize)
{
	const float rounding = f * 0.35f;
	const float ip = f * 0.4f;
	const bool pressed = g_pressed_action.load() == (sint32)tile.action;
	dl->AddRectFilled(tmin, tmax, pressed ? IM_COL32(14, 14, 18, 245) : IM_COL32(34, 34, 40, 245), rounding);
	if (!pressed)
		dl->AddRectFilled({ tmin.x + 1.0f, tmin.y + 1.0f }, { tmax.x - 1.0f, (tmin.y + tmax.y) * 0.5f }, IM_COL32(255, 255, 255, 9), rounding, ImDrawFlags_RoundCornersTop);
	dl->AddRect(tmin, tmax, pressed ? kPanelAccent : kPanelTileBorder, rounding, 0, pressed ? std::max(1.5f, f * 0.08f) : 1.0f);
	dl->PushClipRect(tmin, tmax, true);

	if (tile.on)
	{
		const float r = f * 0.13f;
		const ImVec2 led{ tmax.x - ip - r, tmin.y + ip + r };
		dl->AddCircleFilled(led, r * 2.2f, IM_COL32(76, 217, 100, 40));
		dl->AddCircleFilled(led, r, kPanelGood);
	}

	const float tileW = tmax.x - tmin.x;
	const float tileH = tmax.y - tmin.y;
	const float iconW = valueFont->CalcTextSizeA(valueSize, FLT_MAX, 0.0f, tile.icon).x;
	const float labelW = smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, tile.label.c_str()).x;
	const float lineGap = f * 0.15f;
	float blockH = valueSize + lineGap + smallSize;
	const bool showDetail = !tile.detail.empty() && blockH + lineGap + smallSize <= tileH - 2.0f * ip;
	if (showDetail)
		blockH += lineGap + smallSize;
	float y = tmin.y + std::max(ip * 0.5f, (tileH - blockH) * 0.5f);
	const ImU32 iconColor = tile.on ? kPanelAccent : IM_COL32(222, 222, 230, 255);
	dl->AddText(valueFont, valueSize, { tmin.x + (tileW - iconW) * 0.5f, y }, iconColor, tile.icon);
	y += valueSize + lineGap;
	dl->AddText(smallFont, smallSize, { tmin.x + std::max(ip, (tileW - labelW) * 0.5f), y }, tile.on ? IM_COL32(222, 222, 230, 255) : kPanelLabel, tile.label.c_str());
	if (showDetail)
	{
		y += smallSize + lineGap;
		const float detailW = smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, tile.detail.c_str()).x;
		dl->AddText(smallFont, smallSize, { tmin.x + std::max(ip, (tileW - detailW) * 0.5f), y }, kPanelWarning, tile.detail.c_str());
	}
	dl->PopClipRect();
}

static void LatteOverlay_drawTile(ImDrawList* dl, const OverlayTile& tile, ImVec2 tmin, ImVec2 tmax, float f, ImFont* smallFont, float smallSize, ImFont* valueFont, float valueSize, ImU32 valueColor)
{
	if (tile.action != OverlayAction::kNone)
	{
		LatteOverlay_drawButton(dl, tile, tmin, tmax, f, smallFont, smallSize, valueFont, valueSize);
		return;
	}
	const float rounding = f * 0.35f;
	const float ip = f * 0.4f;
	dl->AddRectFilled(tmin, tmax, kPanelTile, rounding);
	dl->AddRect(tmin, tmax, kPanelTileBorder, rounding, 0, 1.0f);
	dl->PushClipRect(tmin, tmax, true);

	// label row: icon, name and an optional detail on the right
	const float labelY = tmin.y + ip;
	float x = tmin.x + ip;
	if (*tile.icon)
	{
		dl->AddText(smallFont, smallSize, { x, labelY }, tile.iconColor, tile.icon);
		x += smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, tile.icon).x + f * 0.25f;
	}
	dl->AddText(smallFont, smallSize, { x, labelY }, kPanelLabel, tile.label.c_str());
	x += smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, tile.label.c_str()).x;
	if (!tile.detail.empty())
	{
		const float detailW = smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, tile.detail.c_str()).x;
		const float detailX = tmax.x - ip - detailW;
		if (detailX >= x + f * 0.4f)
			dl->AddText(smallFont, smallSize, { detailX, labelY }, kPanelLabel, tile.detail.c_str());
	}

	const float valueY = labelY + smallSize + f * 0.1f;
	const float meterH = f * 0.2f;
	if (tile.coreBars)
	{
		// one small vertical bar per core
		const sint32 count = g_state.processor_count;
		const float areaTop = valueY + f * 0.1f;
		const float areaBottom = tmax.y - ip;
		const float areaW = tmax.x - tmin.x - 2.0f * ip;
		const float barGap = std::max(1.0f, f * 0.12f);
		const float barW = (areaW - barGap * (float)(count - 1)) / (float)count;
		for (sint32 i = 0; i < count; ++i)
		{
			const float bx = tmin.x + ip + (float)i * (barW + barGap);
			const float usage = std::clamp(g_state.cpu_per_core[i] / 100.0f, 0.0f, 1.0f);
			dl->AddRectFilled({ bx, areaTop }, { bx + barW, areaBottom }, IM_COL32(255, 255, 255, 18), barW * 0.25f);
			if (usage > 0.0f)
				dl->AddRectFilled({ bx, areaBottom - (areaBottom - areaTop) * usage }, { bx + barW, areaBottom }, LatteOverlay_usageColor(usage), barW * 0.25f);
		}
	}
	else
	{
		// tiles stretched to fill a black bar get taller, keep the value in the middle of the free space
		const float spaceBottom = tile.meter >= 0.0f ? tmax.y - ip - meterH - f * 0.1f : tmax.y - ip;
		const float y = std::max(valueY, valueY + (spaceBottom - valueY - valueSize) * 0.5f);
		dl->AddText(valueFont, valueSize, { tmin.x + ip, y }, valueColor, tile.value.c_str());
		if (!tile.suffix.empty())
		{
			const float valueW = valueFont->CalcTextSizeA(valueSize, FLT_MAX, 0.0f, tile.value.c_str()).x;
			dl->AddText(smallFont, smallSize, { tmin.x + ip + valueW + f * 0.25f, y + valueSize - smallSize - f * 0.15f }, kPanelLabel, tile.suffix.c_str());
		}
		if (tile.meter >= 0.0f)
		{
			const ImVec2 m0{ tmin.x + ip, tmax.y - ip - meterH };
			const ImVec2 m1{ tmax.x - ip, tmax.y - ip };
			dl->AddRectFilled(m0, m1, IM_COL32(255, 255, 255, 18), meterH * 0.5f);
			const float fillX = m0.x + (m1.x - m0.x) * tile.meter;
			if (fillX - m0.x >= meterH)
				dl->AddRectFilled(m0, { fillX, m1.y }, tile.meterColor, meterH * 0.5f);
		}
	}
	dl->PopClipRect();
}

static void LatteOverlay_drawPanel(const PanelLayout& l, ImVec2 pmin, const std::vector<OverlayTile>& tiles, bool showStatus, ImFont* smallFont, float smallSize, ImFont* valueFont, float valueSize, std::vector<OverlayButton>& buttons)
{
	ImDrawList* dl = ImGui::GetForegroundDrawList();
	const float f = l.f;
	const ImVec2 pmax{ pmin.x + l.size.x, pmin.y + l.size.y };
	const float rounding = f * 0.9f;
	const float rim = std::max(1.5f, f * 0.08f);
	const float innerRounding = std::max(0.0f, rounding - rim);
	const ImU32 valueColor = GetConfig().overlay.text_color;

	// soft drop shadow
	dl->AddRectFilled({ pmin.x, pmin.y + f * 0.15f }, { pmax.x, pmax.y + f * 0.3f }, IM_COL32(0, 0, 0, 80), rounding);
	// piano black shell
	dl->AddRectFilled(pmin, pmax, kPanelBody, rounding);
	// glossy reflection on the upper part of the shell, fading out towards the middle
	for (sint32 i = 0; i < 4; ++i)
	{
		const float h = l.size.y * (0.5f - 0.11f * (float)i);
		dl->AddRectFilled({ pmin.x + rim, pmin.y + rim }, { pmax.x - rim, pmin.y + h }, IM_COL32(255, 255, 255, 5), innerRounding, ImDrawFlags_RoundCornersTop);
	}
	// rim with a thin highlight on the inside
	dl->AddRect(pmin, pmax, kPanelRim, rounding, 0, rim);
	dl->AddRect({ pmin.x + rim, pmin.y + rim }, { pmax.x - rim, pmax.y - rim }, IM_COL32(255, 255, 255, 16), innerRounding, 0, 1.0f);

	const float x0 = pmin.x + l.pad;
	const float x1 = pmax.x - l.pad;
	float y = pmin.y + l.pad;

	if (l.headerH > 0.0f)
	{
		const float cy = y + f * 0.5f;
		// power LED
		const float r = f * 0.17f;
		const ImVec2 led{ x0 + r * 2.0f, cy };
		dl->AddCircleFilled(led, r * 2.6f, IM_COL32(0, 162, 217, 35));
		dl->AddCircleFilled(led, r * 1.6f, IM_COL32(0, 162, 217, 70));
		dl->AddCircleFilled(led, r, kPanelAccent);
		dl->AddCircleFilled({ led.x - r * 0.3f, led.y - r * 0.3f }, r * 0.35f, IM_COL32(255, 255, 255, 170));
		const char* title = "CEMU PLUS";
		const float titleX = led.x + r * 3.0f;
		dl->AddText(smallFont, smallSize, { titleX, cy - smallSize * 0.5f }, kPanelLabel, title);
		const float titleEnd = titleX + smallFont->CalcTextSizeA(smallSize, FLT_MAX, 0.0f, title).x + f;

		// the shader status takes the right side of the header while compiling, the speaker grille is shown otherwise
		if (!showStatus || !LatteOverlay_drawShaderStatus(dl, smallFont, smallSize, titleEnd, x1, cy, true))
		{
			const float dot = f * 0.07f;
			const float spacing = f * 0.26f;
			for (sint32 row = 0; row < 2; ++row)
			{
				for (sint32 col = 0; col < 7; ++col)
				{
					const ImVec2 c{ x1 - dot - (float)col * spacing, cy + ((float)row - 0.5f) * spacing };
					if (c.x - dot > titleEnd)
						dl->AddCircleFilled(c, dot, kPanelGrille);
				}
			}
		}
		y += l.headerH;
	}

	const sint32 count = (sint32)tiles.size();
	for (sint32 i = 0; i < count; ++i)
	{
		const sint32 row = i / l.cols;
		const sint32 col = i % l.cols;
		// the tiles of a last row that isn't full get wider so the row still spans the whole panel
		const sint32 inRow = row == l.rows - 1 ? count - row * l.cols : l.cols;
		const float tileW = inRow == l.cols ? l.tileW : (x1 - x0 - (float)(inRow - 1) * l.gap) / (float)inRow;
		const ImVec2 tmin{ x0 + (float)col * (tileW + l.gap), y + (float)row * (l.tileH + l.gap) };
		const ImVec2 tmax{ tmin.x + tileW, tmin.y + l.tileH };
		LatteOverlay_drawTile(dl, tiles[i], tmin, tmax, f, smallFont, smallSize, valueFont, valueSize, valueColor);
		if (tiles[i].action != OverlayAction::kNone)
			buttons.push_back({ tiles[i].action, tmin, tmax });
	}

	if (l.statusH > 0.0f)
	{
		// recessed status strip at the bottom of the panel
		const float stripH = f * 1.2f;
		const ImVec2 s0{ x0, pmax.y - l.pad - stripH };
		const ImVec2 s1{ x1, pmax.y - l.pad };
		dl->AddRectFilled(s0, s1, kPanelTile, f * 0.35f);
		dl->AddRect(s0, s1, kPanelTileBorder, f * 0.35f, 0, 1.0f);
		const float cy = (s0.y + s1.y) * 0.5f;
		if (!LatteOverlay_drawShaderStatus(dl, smallFont, smallSize, s0.x + f * 0.4f, s1.x - f * 0.4f, cy, false))
		{
			dl->AddCircleFilled({ s0.x + f * 0.4f + f * 0.15f, cy }, f * 0.12f, kPanelGood);
			dl->AddText(smallFont, smallSize, { s0.x + f * 0.4f + f * 0.55f, cy - smallSize * 0.5f }, kPanelLabel, "Shaders idle");
		}
	}
}

enum class OverlayBar
{
	kNone,
	kLetterbox, // bars above and below the image
	kPillarbox, // bars left and right of the image
};

// renders the stats as a GamePad styled panel. When a black bar was found the panel fills all of it, otherwise a
// compact panel is drawn over gameplay. Returns true if the panel was placed in the black bar
static bool LatteOverlay_renderPanel(ImVec2& position, const ImVec2& pivot, sint32 direction, float fontSize, const ImVec2& areaMin, const ImVec2& areaSize, OverlayBar bar, std::vector<OverlayButton>& buttons)
{
	const auto tiles = LatteOverlay_buildTiles();
	const bool statusEnabled = LatteOverlay_isShaderStatusInPanel();
	const sint32 count = (sint32)tiles.size();
	if (count == 0 && !statusEnabled)
		return false;

	PanelLayout layout;
	ImVec2 pmin;
	bool inBar = false;
	if (bar != OverlayBar::kNone)
	{
		const float margin = fontSize * 0.5f;
		const ImVec2 avail{ areaSize.x - 2.0f * margin, areaSize.y - 2.0f * margin };
		const float minScale = std::max(6.0f, std::floor(fontSize * 0.6f));
		const float maxScale = std::floor(fontSize * 2.2f);
		// the panel size grows linearly with the scale, so the biggest scale that fits can be computed for every
		// column count. Prefer a header, then the arrangement with the biggest text and the fewest empty slots
		for (sint32 header = 1; header >= 0 && !inBar; --header)
		{
			float bestScale = 0.0f;
			sint32 bestEmpty = 0;
			for (sint32 cols = 1; cols <= std::max(count, 1); ++cols)
			{
				const auto unit = LatteOverlay_measurePanel(1.0f, count, cols, header != 0, statusEnabled && header == 0);
				const float scale = std::min(maxScale, std::floor(std::min(avail.x / unit.size.x, avail.y / unit.size.y)));
				const sint32 empty = unit.rows * cols - count;
				if (scale < minScale)
					continue;
				if (scale > bestScale * 1.05f || (scale >= bestScale * 0.95f && empty < bestEmpty))
				{
					bestScale = std::max(scale, bestScale);
					bestEmpty = empty;
					layout = LatteOverlay_measurePanel(scale, count, cols, header != 0, statusEnabled && header == 0);
					inBar = true;
				}
			}
		}
		if (inBar)
		{
			// stretch the tiles so the panel fills the whole bar, whatever the number of tiles
			layout.tileW = (avail.x - 2.0f * layout.pad - (float)(layout.cols - 1) * layout.gap) / (float)layout.cols;
			if (layout.rows > 0)
				layout.tileH = (avail.y - 2.0f * layout.pad - layout.headerH - layout.statusH - (float)(layout.rows - 1) * layout.gap) / (float)layout.rows;
			LatteOverlay_updatePanelSize(layout);
			pmin = { areaMin.x + margin, areaMin.y + margin };
		}
	}
	if (!inBar)
	{
		// compact panel over gameplay
		const sint32 cols = count <= 3 ? std::max(count, 1) : std::min((count + 1) / 2, 4);
		layout = LatteOverlay_measurePanel(std::floor(fontSize), count, cols, false, statusEnabled);
		pmin = { position.x - layout.size.x * pivot.x, position.y - layout.size.y * pivot.y };
	}

	// fonts are created on demand, skip this frame if they aren't ready yet. Taller tiles get bigger text
	const float growth = std::clamp(layout.tileH / (layout.f * 3.6f), 1.0f, 1.6f);
	const float smallSize = std::floor(layout.f * 0.75f * std::min(growth, 1.25f));
	const float valueSize = std::floor(layout.f * 1.5f * growth);
	ImFont* smallFont = ImGui_GetFont(smallSize);
	ImFont* valueFont = ImGui_GetFont(valueSize);
	if (smallFont && valueFont)
		LatteOverlay_drawPanel(layout, pmin, tiles, statusEnabled, smallFont, smallSize, valueFont, valueSize, buttons);

	if (!inBar)
		position.y += (layout.size.y + 10.0f) * (float)direction;
	return inBar;
}

static void LatteOverlay_renderDebugWindow(ImVec2& position, const ImVec2& pivot, sint32 direction, float fontSize)
{
	const auto font = ImGui_GetFont(fontSize);
	if (!font)
		return;
	ImGui::PushFont(font);
	ImGui::PushStyleColor(ImGuiCol_Text, ImGui::ColorConvertU32ToFloat4(GetConfig().overlay.text_color));
	ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
	ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
	if (ImGui::Begin("Debug overlay", nullptr, kPopupFlags))
	{
		LatteOverlay_renderDebugInfo();
		position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
	}
	ImGui::End();
	ImGui::PopStyleColor();
	ImGui::PopFont();
}

void LatteOverlay_RenderNotifications(ImVec2& position, ImVec2& pivot, sint32 direction, float fontSize, bool pad)
{
	auto& config = GetConfig();

	const auto font = ImGui_GetFont(fontSize);
	ImGui::PushFont(font);

	const ImVec4 color = ImGui::ColorConvertU32ToFloat4(config.notification.text_color);
	ImGui::PushStyleColor(ImGuiCol_Text, color);

	// selected controller profiles in the beginning
	if (config.notification.controller_profiles)
	{
		static bool s_init_overlay = false;
		if (!s_init_overlay)
		{
			static std::chrono::steady_clock::time_point s_started = tick_cached();

			const auto now = tick_cached();
			if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_started).count() <= 5000)
			{
				// active account
				ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
				ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
				if (ImGui::Begin("Active account", nullptr, kPopupFlags))
				{
					ImGui::TextUnformatted((const char*)ICON_FA_USER);
					ImGui::SameLine();

					static std::string s_mii_name;
					if (s_mii_name.empty())
					{
						auto tmp_view = Account::GetAccount(ActiveSettings::GetPersistentId()).GetMiiName();
						std::wstring tmp{ tmp_view };
						s_mii_name = boost::nowide::narrow(tmp);
					}
					ImGui::TextUnformatted(s_mii_name.c_str());

					position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
				}
				ImGui::End();
				
				// controller
				std::vector<std::pair<int, std::string>> profiles;
				auto& input_manager = InputManager::instance();
				for (int i = 0; i < InputManager::kMaxController; ++i)
				{
					const auto controller = input_manager.get_controller(i);
					if (!controller)
						continue;

					const auto& profile_name = controller->get_profile_name();
					if (profile_name.empty())
						continue;

					profiles.emplace_back(i, profile_name);
				}

				if (!profiles.empty())
				{
					ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
					ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
					if (ImGui::Begin("Controller profile names", nullptr, kPopupFlags))
					{
						auto it = profiles.cbegin();
						ImGui::TextUnformatted((const char*)ICON_FA_GAMEPAD);
						ImGui::SameLine();
						ImGui::Text("Player %d: %s", it->first + 1, it->second.c_str());

						for (++it; it != profiles.cend(); ++it)
						{
							ImGui::Separator();
							ImGui::TextUnformatted((const char*)ICON_FA_GAMEPAD);
							ImGui::SameLine();
							ImGui::Text("Player %d: %s", it->first + 1, it->second.c_str());
						}

						position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
					}
					ImGui::End();
				}
				else
					s_init_overlay = true;
			}
			else
				s_init_overlay = true;
		}
	}

	if (config.notification.friends)
	{
		static std::vector< std::pair<std::string, std::chrono::steady_clock::time_point> > s_friend_list;

		std::unique_lock lock(g_friend_notification_mutex);
		if (!g_friend_notifications.empty())
		{
			const auto tick = tick_cached();

			for (const auto& entry : g_friend_notifications)
			{
				s_friend_list.emplace_back(entry.first, tick + std::chrono::milliseconds(entry.second));
			}

			g_friend_notifications.clear();
		}

		if (!s_friend_list.empty())
		{
			ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
			ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
			if (ImGui::Begin("Friends overlay", nullptr, kPopupFlags))
			{
				const auto tick = tick_cached();
				for (auto it = s_friend_list.cbegin(); it != s_friend_list.cend();)
				{
					ImGui::TextUnformatted(it->first.c_str(), it->first.c_str() + it->first.size());
					if (tick >= it->second)
						it = s_friend_list.erase(it);
					else
						++it;
				}

				position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
			}
			ImGui::End();
		}
	}

	// low battery warning
	if (config.notification.controller_battery)
	{
		std::vector<int> batteries;
		auto& input_manager = InputManager::instance();
		for (int i = 0; i < InputManager::kMaxController; ++i)
		{
			const auto controller = input_manager.get_controller(i);
			if (!controller)
				continue;

			if (controller->is_battery_low())
				batteries.emplace_back(i);
		}

		if (!batteries.empty())
		{
			static std::chrono::steady_clock::time_point s_last_tick = tick_cached();
			static bool s_blink_state = false;
			const auto now = tick_cached();

			if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_last_tick).count() >= 750)
			{
				s_blink_state = !s_blink_state;
				s_last_tick = now;
			}

			ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
			ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
			if (ImGui::Begin("Low battery overlay", nullptr, kPopupFlags))
			{
				auto it = batteries.cbegin();
				ImGui::TextUnformatted((const char*)(s_blink_state ? ICON_FA_BATTERY_EMPTY : ICON_FA_BATTERY_QUARTER));
				ImGui::SameLine();
				ImGui::Text("Player %d", *it + 1);

				for (++it; it != batteries.cend(); ++it)
				{
					ImGui::Separator();
					ImGui::TextUnformatted((const char*)(s_blink_state ? ICON_FA_BATTERY_EMPTY : ICON_FA_BATTERY_QUARTER));
					ImGui::SameLine();
					ImGui::Text("Player %d", *it + 1);
				}

				position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
			}
			ImGui::End();
		}
	}

	const auto compileStatus = LatteOverlay_updateShaderCompileStatus();
	if (config.notification.shader_compiling && !LatteOverlay_isShaderStatusInPanel())
	{
		const auto now = tick_cached();
		if (compileStatus.shaders > 0)
		{
			ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
			ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
			if (ImGui::Begin("Compiling shaders overlay", nullptr, kPopupFlags))
			{
				ImRotateStart();
				ImGui::TextUnformatted((const char*)ICON_FA_SPINNER);

				const auto ticks = std::chrono::time_point_cast<std::chrono::milliseconds>(now);
				ImRotateEnd(0.001f * ticks.time_since_epoch().count());
				ImGui::SameLine();

				if (compileStatus.shadersAsync > 0 && GetConfig().async_compile) // the latter condition is to never show async count when async isn't enabled. Since it can be confusing to the user
				{
					if(compileStatus.shaders > 1)
						ImGui::Text("Compiled %d new shaders... (%d async)", compileStatus.shaders, compileStatus.shadersAsync);
					else
						ImGui::Text("Compiled %d new shader... (%d async)", compileStatus.shaders, compileStatus.shadersAsync);
				}
				else
				{
					if (compileStatus.shaders > 1)
						ImGui::Text("Compiled %d new shaders...", compileStatus.shaders);
					else
						ImGui::Text("Compiled %d new shader...", compileStatus.shaders);
				}

				position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
			}
			ImGui::End();
		}

		if (compileStatus.pipelines > 0)
		{
			ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
			ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
			if (ImGui::Begin("Compiling pipeline overlay", nullptr, kPopupFlags))
			{
				ImRotateStart();
				ImGui::TextUnformatted((const char*)ICON_FA_SPINNER);

				const auto ticks = std::chrono::time_point_cast<std::chrono::milliseconds>(now);
				ImRotateEnd(0.001f * ticks.time_since_epoch().count());
				ImGui::SameLine();

#ifdef CEMU_DEBUG_ASSERT
				uint64 totalTime = g_compiling_pipelines_syncTimeSum / 1000000ull;
				if (compileStatus.pipelinesAsync > 0)
				{
					if (compileStatus.pipelines > 1)
						ImGui::Text("Compiled %d new pipelines... (%d async) TotalSync: %" PRIu64 "ms", compileStatus.pipelines, compileStatus.pipelinesAsync, totalTime);
					else
						ImGui::Text("Compiled %d new pipeline... (%d async) TotalSync: %" PRIu64 "ms", compileStatus.pipelines, compileStatus.pipelinesAsync, totalTime);
				}
				else
				{
					if (compileStatus.pipelines > 1)
						ImGui::Text("Compiled %d new pipelines... TotalSync: %" PRIu64 "ms", compileStatus.pipelines, totalTime);
					else
						ImGui::Text("Compiled %d new pipeline... TotalSync: %" PRIu64 "ms", compileStatus.pipelines, totalTime);
				}
#else
				if (compileStatus.pipelinesAsync > 0)
				{
					if (compileStatus.pipelines > 1)
						ImGui::Text("Compiled %d new pipelines... (%d async)", compileStatus.pipelines, compileStatus.pipelinesAsync);
					else
						ImGui::Text("Compiled %d new pipeline... (%d async)", compileStatus.pipelines, compileStatus.pipelinesAsync);
				}
				else
				{
					if (compileStatus.pipelines > 1)
						ImGui::Text("Compiled %d new pipelines...", compileStatus.pipelines);
					else
						ImGui::Text("Compiled %d new pipeline...", compileStatus.pipelines);
				}
#endif
				position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
			}
			ImGui::End();
		}
	}

	// misc notifications
	static std::vector< std::pair<std::string, std::chrono::steady_clock::time_point> > s_misc_notifications;

	std::unique_lock misc_lock(g_notification_mutex);
	if (!g_notifications.empty())
	{
		const auto tick = tick_cached();

		for (const auto& entry : g_notifications)
		{
			s_misc_notifications.emplace_back(entry.first, tick + std::chrono::milliseconds(entry.second));
		}

		g_notifications.clear();
	}
	misc_lock.unlock();

	if (!s_misc_notifications.empty())
	{
		ImGui::SetNextWindowPos(position, ImGuiCond_Always, pivot);
		ImGui::SetNextWindowBgAlpha(kBackgroundAlpha);
		if (ImGui::Begin("Misc notifications", nullptr, kPopupFlags))
		{
			const auto tick = tick_cached();
			for (auto it = s_misc_notifications.cbegin(); it != s_misc_notifications.cend();)
			{
				ImGui::TextUnformatted(it->first.c_str(), it->first.c_str() + it->first.size());
				if (tick >= it->second)
					it = s_misc_notifications.erase(it);
				else
					++it;
			}

			position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
		}
		ImGui::End();
	}
	ImGui::PopStyleColor();
	ImGui::PopFont();
}

void LatteOverlay_translateScreenPosition(ScreenPosition pos, const Vector2f& window_size, ImVec2& position, ImVec2& pivot, sint32& direction)
{
	switch (pos)
	{
	case ScreenPosition::kTopLeft:
		position = { 10, 10 };
		pivot = { 0, 0 };
		direction = 1;
		break;
	case ScreenPosition::kTopCenter:
		position = { window_size.x / 2.0f, 10 };
		pivot = { 0.5f, 0 };
		direction = 1;
		break;
	case ScreenPosition::kTopRight:
		position = { window_size.x - 10, 10 };
		pivot = { 1, 0 };
		direction = 1;
		break;
	case ScreenPosition::kBottomLeft:
		position = { 10, window_size.y - 10 };
		pivot = { 0, 1 };
		direction = -1;
		break;
	case ScreenPosition::kBottomCenter:
		position = { window_size.x / 2.0f, window_size.y - 10 };
		pivot = { 0.5f, 1 };
		direction = -1;
		break;
	case ScreenPosition::kBottomRight:
		position = { window_size.x - 10, window_size.y - 10 };
		pivot = { 1, 1 };
		direction = -1;
		break;
	default:
		UNREACHABLE;
	}
}

// the stats overlay can be limited to the TV or the GamePad screen. If the GamePad screen isn't shown it always goes to the TV screen
static bool LatteOverlay_isStatsScreen(bool pad_view)
{
	switch (GetConfig().overlay.screen)
	{
	case OverlayScreen::kTV:
		return !pad_view;
	case OverlayScreen::kPad:
		return pad_view || !WindowSystem::IsPadWindowOpen();
	default:
		return true;
	}
}

static sint32 LatteOverlay_countStatLines()
{
	const auto& config = GetConfig();
	sint32 lines = 0;
	if (config.overlay.fps)
		lines += g_state.frame_gen_active ? 2 : 1;
	if (config.overlay.drawcalls)
		lines++;
	if (config.overlay.cpu_usage)
		lines++;
	if (config.overlay.cpu_per_core_usage)
		lines += g_state.processor_count;
	if (config.overlay.ram_usage)
		lines++;
	if (config.overlay.vram_usage)
		lines++;
	if (config.overlay.gpu_usage)
		lines++;
	if (config.overlay.temperature)
		lines++;
	if (config.overlay.battery)
		lines++;
	return lines;
}

// finds the empty space left around the game image (letterbox or pillarbox bars) to place the stats overlay in.
// Keeps the full screen area if no bar is big enough for at least one line of text
static void LatteOverlay_getBlackBarArea(bool pad_view, ScreenPosition position, float fontSize, ImVec2& areaMin, ImVec2& areaSize, bool& horizontal, OverlayBar& bar)
{
	sint32 imageX, imageY, imageWidth, imageHeight, fullWidth, fullHeight;
	LatteRenderTarget_getScreenImageArea(&imageX, &imageY, &imageWidth, &imageHeight, &fullWidth, &fullHeight, pad_view);

	const float minSize = fontSize * 1.5f + 20.0f;
	const float topBar = (float)imageY;
	const float bottomBar = (float)(fullHeight - imageY - imageHeight);
	const float leftBar = (float)imageX;
	const float rightBar = (float)(fullWidth - imageX - imageWidth);

	const bool prefersTop = position == ScreenPosition::kTopLeft || position == ScreenPosition::kTopCenter || position == ScreenPosition::kTopRight;
	const bool prefersLeft = position == ScreenPosition::kTopLeft || position == ScreenPosition::kBottomLeft;
	const bool prefersRight = position == ScreenPosition::kTopRight || position == ScreenPosition::kBottomRight;

	if (std::max(topBar, bottomBar) >= minSize)
	{
		// letterbox: use the bar on the side of the selected position when it fits, otherwise the bigger one
		const float preferred = prefersTop ? topBar : bottomBar;
		const bool useTop = preferred >= minSize ? prefersTop : topBar > bottomBar;
		const float barHeight = useTop ? topBar : bottomBar;
		areaMin = { 0.0f, useTop ? 0.0f : (float)(imageY + imageHeight) };
		areaSize = { (float)fullWidth, barHeight };
		// stack the stats like over gameplay if they fit, otherwise put them in a row
		const float stackedHeight = (float)LatteOverlay_countStatLines() * (fontSize + 4.0f) + 36.0f;
		horizontal = stackedHeight > barHeight;
		bar = OverlayBar::kLetterbox;
	}
	else if (std::max(leftBar, rightBar) >= minSize)
	{
		// pillarbox
		bool useLeft = leftBar > rightBar;
		if (prefersLeft && leftBar >= minSize)
			useLeft = true;
		else if (prefersRight && rightBar >= minSize)
			useLeft = false;
		areaMin = { useLeft ? 0.0f : (float)(imageX + imageWidth), 0.0f };
		areaSize = { useLeft ? leftBar : rightBar, (float)fullHeight };
		bar = OverlayBar::kPillarbox;
	}
}

void LatteOverlay_render(bool pad_view)
{
	const auto& config = GetConfig();
	std::vector<OverlayButton> buttons;
	LatteOverlay_publishButtons(pad_view, {});
	if(config.overlay.position == ScreenPosition::kDisabled && config.notification.position == ScreenPosition::kDisabled)
		return;

	sint32 w = 0, h = 0;
	if (pad_view && WindowSystem::IsPadWindowOpen())
		WindowSystem::GetPadWindowPhysSize(w, h);
	else
		WindowSystem::GetWindowPhysSize(w, h);

	if (w == 0 || h == 0)
		return;

	const Vector2f window_size{ (float)w,(float)h };

	float fontDPIScale = !pad_view ? WindowSystem::GetWindowDPIScale() : WindowSystem::GetPadDPIScale();

	float overlayFontSize = 14.0f * (float)config.overlay.text_scale / 100.0f * fontDPIScale;

	// test if fonts are already precached
	if (!ImGui_GetFont(overlayFontSize))
		return;

	float notificationsFontSize = 14.0f * (float)config.notification.text_scale / 100.0f * fontDPIScale;
	
	if (!ImGui_GetFont(notificationsFontSize))
		return;

	ImVec2 position{}, pivot{};
	sint32 direction{};

	const bool showStats = config.overlay.position != ScreenPosition::kDisabled && LatteOverlay_isStatsScreen(pad_view);
	// the notifications avoid the black bar used by the stats
	ImVec2 notificationAreaMin{ 0.0f, 0.0f };
	ImVec2 notificationAreaSize{ window_size.x, window_size.y };
	bool statsInBar = false;
	if (showStats)
	{
		ImVec2 areaMin{ 0.0f, 0.0f };
		ImVec2 areaSize{ window_size.x, window_size.y };
		bool horizontal = false;
		OverlayBar bar = OverlayBar::kNone;
		if (config.overlay.placement == OverlayPlacement::kBlackBars)
			LatteOverlay_getBlackBarArea(pad_view, config.overlay.position, overlayFontSize, areaMin, areaSize, horizontal, bar);

		LatteOverlay_translateScreenPosition(config.overlay.position, { areaSize.x, areaSize.y }, position, pivot, direction);
		position.x += areaMin.x;
		position.y += areaMin.y;
		if (config.overlay.style == OverlayStyle::kGamePad)
		{
			statsInBar = LatteOverlay_renderPanel(position, pivot, direction, overlayFontSize, areaMin, areaSize, bar, buttons);
			LatteOverlay_publishButtons(pad_view, std::move(buttons));
			if (config.overlay.debug)
			{
				if (statsInBar)
					LatteOverlay_translateScreenPosition(config.overlay.position, window_size, position, pivot, direction);
				LatteOverlay_renderDebugWindow(position, pivot, direction, overlayFontSize);
			}
		}
		else
		{
			LatteOverlay_renderOverlay(position, pivot, direction, overlayFontSize, pad_view, horizontal);
			statsInBar = bar != OverlayBar::kNone;
		}

		if (statsInBar)
		{
			if (bar == OverlayBar::kLetterbox)
			{
				notificationAreaSize.y -= areaSize.y;
				if (areaMin.y == 0.0f)
					notificationAreaMin.y = areaSize.y;
			}
			else
			{
				notificationAreaSize.x -= areaSize.x;
				if (areaMin.x == 0.0f)
					notificationAreaMin.x = areaSize.x;
			}
		}
	}

	if (config.notification.position != ScreenPosition::kDisabled)
	{
		if (!showStats || statsInBar || config.overlay.position != config.notification.position)
		{
			LatteOverlay_translateScreenPosition(config.notification.position, { notificationAreaSize.x, notificationAreaSize.y }, position, pivot, direction);
			position.x += notificationAreaMin.x;
			position.y += notificationAreaMin.y;
		}

		LatteOverlay_RenderNotifications(position, pivot, direction, notificationsFontSize, pad_view);
	}
}

void LatteOverlay_init()
{
	g_state.processor_count = GetProcessorCount();

	g_state.processor_times.resize(g_state.processor_count);
	g_state.cpu_per_core.resize(g_state.processor_count);
}

static void UpdateStats_CemuCpu()
{
	ProcessorTime now;
	QueryProcTime(now);
	
	double cpu = ProcessorTime::Compare(g_state.processor_time_cemu, now);
	cpu /= g_state.processor_count;
	
	g_state.cpu_usage = cpu * 100;
	g_state.processor_time_cemu = now;
}

static void UpdateStats_CpuPerCore()
{
	std::vector<ProcessorTime> now(g_state.processor_count);
	QueryCoreTimes(g_state.processor_count, now);

	for (int32_t i = 0; i < g_state.processor_count; ++i)
	{
		double cpu = ProcessorTime::Compare(g_state.processor_times[i], now[i]);

		g_state.cpu_per_core[i] = cpu * 100;
		g_state.processor_times[i] = now[i];
	}
}

void UpdateStats_PresentedFrames()
{
	static std::chrono::steady_clock::time_point s_lastUpdate = std::chrono::steady_clock::now();
	static uint64 s_lastReal = 0;
	static uint64 s_lastGenerated = 0;

	const auto now = std::chrono::steady_clock::now();
	const uint64 real = g_presented_real_frames.load(std::memory_order_relaxed);
	const uint64 generated = g_presented_generated_frames.load(std::memory_order_relaxed);
	const double elapsed = std::chrono::duration<double>(now - s_lastUpdate).count();
	const uint64 realDelta = real - s_lastReal;
	const uint64 generatedDelta = generated - s_lastGenerated;
	s_lastUpdate = now;
	s_lastReal = real;
	s_lastGenerated = generated;

	g_state.frame_gen_active = generatedDelta > 0 || FrameGenVk::GetRuntimeStatus() == FrameGenRuntimeStatus::Active;
	g_state.presented_fps = elapsed > 0.0 ? (double)(realDelta + generatedDelta) / elapsed : 0.0;
	g_state.frame_gen_ratio = realDelta > 0 ? (double)(realDelta + generatedDelta) / (double)realDelta : 1.0;
}

void LatteOverlay_updateStats(double fps, sint32 drawcalls, sint32 fastDrawcalls)
{
	if (GetConfig().overlay.position == ScreenPosition::kDisabled)
		return;

	g_state.fps = fps;
	UpdateStats_PresentedFrames();
	g_state.draw_calls_per_frame = drawcalls;
	g_state.fast_draw_calls_per_frame = fastDrawcalls;
	UpdateStats_CemuCpu();
	UpdateStats_CpuPerCore();

	// update ram
	g_state.ram_usage = (QueryRamUsage() / 1000) / 1000;

	// update vram
	g_renderer->GetVRAMInfo(g_state.vramUsage, g_state.vramTotal);

	// device sensors
	const auto& overlay = GetConfig().overlay;
	if (overlay.cpu_usage)
		g_device.cpu_freq_mhz = QueryCpuFrequency();
	if (overlay.gpu_usage)
	{
		g_device.gpu_usage = QueryGpuUsage();
		g_device.gpu_freq_mhz = QueryGpuFrequency();
	}
	if (overlay.temperature)
		QueryTemperatures(g_device.cpu_temp, g_device.gpu_temp);
}
