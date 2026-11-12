#pragma once
#include "config/CemuConfig.h"

struct gameProfileIntegerOption_t
{
	bool isPresent = false;
	sint32 value;
};

class GameProfile
{
	friend class GameProfileWindow;

public:
	static const uint32 kThreadQuantumDefault = 45000;

	bool Load(uint64_t title_id);
	void Save(uint64_t title_id);
	void ResetOptional();
	void Reset();

	[[nodiscard]] uint64 GetTitleId() const { return m_title_id; }
	[[nodiscard]] bool IsLoaded() const { return m_is_loaded; }
	[[nodiscard]] bool IsDefaultProfile() const { return m_is_default; }
	[[nodiscard]] const std::optional<std::string>& GetGameName() const { return m_gameName; }

	[[nodiscard]] const std::optional<bool>& ShouldLoadSharedLibraries() const { return m_loadSharedLibraries; }
	void SetShouldLoadSharedLibraries(bool shouldLoadSharedLibraries) { m_loadSharedLibraries = shouldLoadSharedLibraries; }
	[[nodiscard]] bool StartWithGamepadView() const { return m_startWithPadView; }
	// the installed update is not mounted and the game runs as its base version
	[[nodiscard]] bool ShouldIgnoreUpdate() const { return m_ignoreUpdate; }

	[[nodiscard]] const std::optional<GraphicAPI>& GetGraphicsAPI() const { return m_graphics_api; }
	[[nodiscard]] const AccurateShaderMulOption& GetAccurateShaderMul() const { return m_accurateShaderMul; }
	void SetAccurateShaderMul(AccurateShaderMulOption accurateShaderMulOption) { m_accurateShaderMul = accurateShaderMulOption; }
#ifdef ENABLE_METAL
	[[nodiscard]] bool GetShaderFastMath() const { return m_shaderFastMath; }
	[[nodiscard]] MetalBufferCacheMode GetBufferCacheMode() const { return m_metalBufferCacheMode; }
	[[nodiscard]] PositionInvariance GetPositionInvariance() const { return m_positionInvariance; }
#endif
	[[nodiscard]] const std::optional<PrecompiledShaderOption>& GetPrecompiledShadersState() const { return m_precompiledShaders; }

	[[nodiscard]] uint32 GetThreadQuantum() const { return m_threadQuantum; }
	void SetThreadQuantum(uint32 threadQuantum){ m_threadQuantum = threadQuantum; }
	[[nodiscard]] const std::optional<CPUMode>& GetCPUMode() const { return m_cpuMode; }
	void SetCPUMode(CPUMode cpuMode) { m_cpuMode = cpuMode; }

	[[nodiscard]] bool IsAudioDisabled() const { return m_disableAudio; }

	[[nodiscard]] const std::array< std::optional<std::string>, 8>& GetControllerProfile() const { return m_controllerProfile; }
	// an empty profile name uses the global settings of that controller
	void SetControllerProfile(size_t index, std::optional<std::string> profile) { m_controllerProfile.at(index) = std::move(profile); }

#if BOOST_PLAT_ANDROID
  public:
	struct DriverSetting
	{
		DriverSettingMode mode = DriverSettingMode::Global;
		std::optional<std::string> customPath;
	};

	[[nodiscard]] DriverSetting GetDriverSetting() const
	{
		return m_driverSetting;
	}

	void SetDriverSetting(DriverSetting driverSetting)
	{
		m_driverSetting = driverSetting;
	}

	// percent of the GamePad image cut off on the second screen, split evenly between both sides
	static constexpr sint32 kPadCropMax = 40;
	[[nodiscard]] sint32 GetPadCropHorizontal() const { return m_padCropHorizontal; }
	[[nodiscard]] sint32 GetPadCropVertical() const { return m_padCropVertical; }
	// turns the crop off without losing it
	[[nodiscard]] bool IsPadCropEnabled() const { return m_padCropEnabled; }
	void SetPadCropEnabled(bool enabled) { m_padCropEnabled = enabled; }
	// hides the toolbar in the black bars while this game's GamePad image is cropped
	[[nodiscard]] bool IsPadCropToolbarHidden() const { return m_padCropHideToolbar; }
	void SetPadCropToolbarHidden(bool hidden) { m_padCropHideToolbar = hidden; }
	void SetPadCrop(sint32 horizontal, sint32 vertical)
	{
		m_padCropHorizontal = std::clamp(horizontal, 0, kPadCropMax);
		m_padCropVertical = std::clamp(vertical, 0, kPadCropMax);
	}

  private:
	DriverSetting m_driverSetting;
	sint32 m_padCropHorizontal = 0;
	sint32 m_padCropVertical = 0;
	bool m_padCropEnabled = false;
	bool m_padCropHideToolbar = false;

#endif

  private:
	uint64_t m_title_id = 0;
	bool m_is_loaded = false;
	bool m_is_default = true;

	std::optional<std::string> m_gameName{};

	// general settings
	std::optional<bool> m_loadSharedLibraries{}; // = true;
	bool m_startWithPadView = false;
	bool m_ignoreUpdate = false;

	// graphic settings
	std::optional<GraphicAPI> m_graphics_api{};
	AccurateShaderMulOption m_accurateShaderMul = AccurateShaderMulOption::True;
#ifdef ENABLE_METAL
	bool m_shaderFastMath = true;
	MetalBufferCacheMode m_metalBufferCacheMode = MetalBufferCacheMode::Auto;
	PositionInvariance m_positionInvariance = PositionInvariance::Auto;
#endif
	std::optional<PrecompiledShaderOption> m_precompiledShaders{};
	// cpu settings
	uint32 m_threadQuantum = kThreadQuantumDefault; // values: 20000 45000 60000 80000 100000
	std::optional<CPUMode> m_cpuMode{}; // = CPUModeOption::kSingleCoreRecompiler;
	// audio
	bool m_disableAudio = false;
	// controller settings
	std::array< std::optional<std::string>, 8> m_controllerProfile{};
};
extern std::unique_ptr<GameProfile> g_current_game_profile;

void gameProfile_load();
