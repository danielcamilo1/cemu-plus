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
void LatteOverlay_renderOverlay(ImVec2& position, ImVec2& pivot, sint32 direction, float fontSize, bool pad, bool horizontal)
{
	auto& config = GetConfig();

	const auto font = ImGui_GetFont(fontSize);
	ImGui::PushFont(font);

	const ImVec4 color = ImGui::ColorConvertU32ToFloat4(config.overlay.text_color);
	ImGui::PushStyleColor(ImGuiCol_Text, color);
	// stats overlay
	if (config.overlay.fps || config.overlay.drawcalls || config.overlay.cpu_usage || config.overlay.cpu_per_core_usage || config.overlay.ram_usage)
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

			if (config.overlay.debug)
			{
				// general debug info
				ImGui::Text("--- Debug info ---");
				ImGui::Text("IndexUploadPerFrame: %dKB", (performanceMonitor.stats.indexDataUploadPerFrame+1023)/1024);
				// backend specific info
				g_renderer->AppendOverlayDebugInfo();
			}

			position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
		}
		ImGui::End();
	}

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

	if (config.notification.shader_compiling)
	{
		static int32_t s_shader_count = 0;
		static int32_t s_shader_count_async = 0;
		if (s_shader_count > 0 || g_compiled_shaders_total > 0)
		{
			const int tmp = g_compiled_shaders_total.exchange(0);
			const int tmpAsync = g_compiled_shaders_async.exchange(0);
			s_shader_count += tmp;
			s_shader_count_async += tmpAsync;

			static std::chrono::steady_clock::time_point s_last_tick = tick_cached();
			const auto now = tick_cached();

			if (tmp > 0)
				s_last_tick = now;

			if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_last_tick).count() >= 2500)
			{
				s_shader_count = 0;
				s_shader_count_async = 0;
			}

			if (s_shader_count > 0)
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

					if (s_shader_count_async > 0 && GetConfig().async_compile) // the latter condition is to never show async count when async isn't enabled. Since it can be confusing to the user
					{
						if(s_shader_count > 1)
							ImGui::Text("Compiled %d new shaders... (%d async)", s_shader_count, s_shader_count_async);
						else
							ImGui::Text("Compiled %d new shader... (%d async)", s_shader_count, s_shader_count_async);
					}
					else
					{
						if (s_shader_count > 1)
							ImGui::Text("Compiled %d new shaders...", s_shader_count);
						else
							ImGui::Text("Compiled %d new shader...", s_shader_count);
					}

					position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
				}
				ImGui::End();
			}
		}
		
		static int32_t s_pipeline_count = 0;
		static int32_t s_pipeline_count_async = 0;
		if (s_pipeline_count > 0 || g_compiling_pipelines > 0)
		{
			const int tmp = g_compiling_pipelines.exchange(0);
			const int tmpAsync = g_compiling_pipelines_async.exchange(0);
			s_pipeline_count += tmp;
			s_pipeline_count_async += tmpAsync;

			static std::chrono::steady_clock::time_point s_last_tick = tick_cached();
			const auto now = tick_cached();

			if (tmp > 0)
				s_last_tick = now;

			if (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_last_tick).count() >= 2500)
			{
				s_pipeline_count = 0;
				s_pipeline_count_async = 0;
			}

			if (s_pipeline_count > 0)
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
					if (s_pipeline_count_async > 0)
					{
						if (s_pipeline_count > 1)
							ImGui::Text("Compiled %d new pipelines... (%d async) TotalSync: %" PRIu64 "ms", s_pipeline_count, s_pipeline_count_async, totalTime);
						else
							ImGui::Text("Compiled %d new pipeline... (%d async) TotalSync: %" PRIu64 "ms", s_pipeline_count, s_pipeline_count_async, totalTime);
					}
					else
					{
						if (s_pipeline_count > 1)
							ImGui::Text("Compiled %d new pipelines... TotalSync: %" PRIu64 "ms", s_pipeline_count, totalTime);
						else
							ImGui::Text("Compiled %d new pipeline... TotalSync: %" PRIu64 "ms", s_pipeline_count, totalTime);
					}
#else
					if (s_pipeline_count_async > 0)
					{
						if (s_pipeline_count > 1)
							ImGui::Text("Compiled %d new pipelines... (%d async)", s_pipeline_count, s_pipeline_count_async);
						else
							ImGui::Text("Compiled %d new pipeline... (%d async)", s_pipeline_count, s_pipeline_count_async);
					}
					else
					{
						if (s_pipeline_count > 1)
							ImGui::Text("Compiled %d new pipelines...", s_pipeline_count);
						else
							ImGui::Text("Compiled %d new pipeline...", s_pipeline_count);
					}
#endif
					position.y += (ImGui::GetWindowSize().y + 10.0f) * direction;
				}
				ImGui::End();
			}
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
	return lines;
}

// finds the empty space left around the game image (letterbox or pillarbox bars) to place the stats overlay in.
// Keeps the full screen area if no bar is big enough for at least one line of text
static void LatteOverlay_getBlackBarArea(bool pad_view, ScreenPosition position, float fontSize, ImVec2& areaMin, ImVec2& areaSize, bool& horizontal)
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
	}
}

void LatteOverlay_render(bool pad_view)
{
	const auto& config = GetConfig();
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
	if (showStats)
	{
		ImVec2 areaMin{ 0.0f, 0.0f };
		ImVec2 areaSize{ window_size.x, window_size.y };
		bool horizontal = false;
		if (config.overlay.placement == OverlayPlacement::kBlackBars)
			LatteOverlay_getBlackBarArea(pad_view, config.overlay.position, overlayFontSize, areaMin, areaSize, horizontal);

		LatteOverlay_translateScreenPosition(config.overlay.position, { areaSize.x, areaSize.y }, position, pivot, direction);
		position.x += areaMin.x;
		position.y += areaMin.y;
		LatteOverlay_renderOverlay(position, pivot, direction, overlayFontSize, pad_view, horizontal);
	}
	

	if (config.notification.position != ScreenPosition::kDisabled)
	{
		if(!showStats || config.overlay.position != config.notification.position)
			LatteOverlay_translateScreenPosition(config.notification.position, window_size, position, pivot, direction);

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
}
