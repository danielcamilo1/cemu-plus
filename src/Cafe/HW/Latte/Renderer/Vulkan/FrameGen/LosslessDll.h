// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

// Loader for the frame generation shaders shipped inside a user supplied copy of Lossless.dll
// (Lossless Scaling). The DLL is never executed, only its RCDATA resources are read.

#pragma once

#include <array>
#include <map>
#include <vector>

namespace LosslessDll
{
	enum class Status : sint32
	{
		Ok = 0,
		NotInstalled = 1,
		UnreadableFile = 2,
		NotPortableExecutable = 3,
		MissingShaders = 4,
		TranslationFailed = 5,
	};

	using ShaderModules = std::map<uint32, std::vector<uint32>>;

	// resource ids of the "performance" shader set
	namespace PerformanceShader
	{
		constexpr uint32 MIPMAPS = 255;
		constexpr uint32 GENERATE = 256;
		constexpr std::array<uint32, 4> ALPHA{290, 291, 292, 293};
		constexpr std::array<uint32, 5> BETA{298, 299, 300, 301, 302};
		constexpr std::array<uint32, 5> GAMMA{280, 282, 283, 284, 285};
		constexpr std::array<uint32, 10> DELTA{280, 286, 287, 288, 289, 281, 294, 295, 296, 297};

		// the SPIR-V variant of each shader is stored at this offset from the base id
		constexpr uint32 NATIVE_FP16_OFFSET = 49;
	}

	fs::path GetInstalledPath();

	Status Validate(const fs::path& path);
	Status GetInstalledStatus();

	Status LoadShaderModules(ShaderModules& modulesOut);

	bool RemoveInstalled();

	// bumped whenever the installed DLL changes so that running renderers reload the shaders
	uint32 GetChangeCounter();
	void NotifyChanged();
}
