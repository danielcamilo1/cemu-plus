// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LsfgCommon.h"

namespace LSFG
{
	constexpr size_t MIP_LEVELS = 7;
	constexpr size_t ALPHA_STAGES = 4;
	constexpr size_t BETA_STAGES = 5;
	constexpr size_t BETA_OUTPUTS = 6;
	constexpr size_t GAMMA_STAGES = 5;
	constexpr size_t GAMMA_TEMPS = 3;
	constexpr size_t DELTA_STAGES = 10;
	constexpr size_t DELTA_TEMPS = 3;
	constexpr size_t DELTA_INSTANCES = 3;

	class Mipmaps
	{
	public:
		Mipmaps() = default;
		Mipmaps(Allocator& allocator, const Shaders& shaders, ImagePair& frames, float flowScale);

		void Dispatch(VkCommandBuffer cmd, uint64 frameCount);
		Image& Output(size_t level) { return m_outImages[level]; }

	private:
		ImagePair* m_frames{};
		Pass m_pass;
		std::array<VkDescriptorSet, 2> m_descriptorSets{};
		VkExtent2D m_flowExtent{};
		std::array<Image, MIP_LEVELS> m_outImages;
	};

	class AlphaPasses
	{
	public:
		AlphaPasses() = default;
		AlphaPasses(Allocator& allocator, const Shaders& shaders);

		const Pass& Get(size_t stage) const { return m_passes[stage]; }

	private:
		std::array<Pass, ALPHA_STAGES> m_passes;
	};

	class Alpha
	{
	public:
		Alpha() = default;
		Alpha(Allocator& allocator, const AlphaPasses& passes, Image& input);

		void PushBarriers(Barriers& barriers, uint64 frameCount, size_t stage);
		void DispatchStage(VkCommandBuffer cmd, uint64 frameCount, size_t stage);
		ImageHistory& Outputs() { return m_outImages; }

	private:
		const AlphaPasses* m_passes{};
		Image* m_input{};
		std::array<VkDescriptorSet, ALPHA_STAGES - 1> m_descriptorSets{};
		std::array<VkDescriptorSet, HISTORY_SLOTS> m_lastDescriptorSets{};
		Image m_temp1;
		Image m_temp2;
		ImagePair m_temp3;
		ImageHistory m_outImages;
	};

	class Beta
	{
	public:
		Beta() = default;
		Beta(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs);

		void Dispatch(VkCommandBuffer cmd, uint64 frameCount);
		Image& Output(size_t level) { return m_outImages[level]; }

	private:
		ImageHistory* m_inputs{};
		std::array<Pass, BETA_STAGES> m_passes;
		std::array<VkDescriptorSet, HISTORY_SLOTS> m_firstDescriptorSets{};
		std::array<VkDescriptorSet, BETA_STAGES - 1> m_descriptorSets{};
		ImagePair m_temp1;
		ImagePair m_temp2;
		std::array<Image, BETA_OUTPUTS> m_outImages;
	};

	class Gamma
	{
	public:
		Gamma() = default;
		Gamma(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs, Image& flowInput, Image* previous);

		void Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot);
		Image& Output() { return m_outImage; }

	private:
		struct Generation
		{
			std::array<VkDescriptorSet, HISTORY_SLOTS> firstDescriptorSets{};
			std::array<VkDescriptorSet, GAMMA_STAGES - 1> descriptorSets{};
		};

		ImageHistory* m_inputs{};
		Image* m_flowInput{};
		Image* m_previous{};
		std::array<Pass, GAMMA_STAGES> m_passes;
		std::array<Generation, GENERATION_SLOTS> m_generations{};
		std::array<Image, GAMMA_TEMPS> m_temp1;
		ImagePair m_temp2;
		Image m_outImage;
	};

	class Delta
	{
	public:
		Delta() = default;
		Delta(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs, Image& flowInput, Image* previousGamma, Image* previous1, Image* previous2);

		void Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot);
		Image& Output1() { return m_outImage1; }
		Image& Output2() { return m_outImage2; }

	private:
		struct Generation
		{
			std::array<VkDescriptorSet, HISTORY_SLOTS> firstDescriptorSets{};
			std::array<VkDescriptorSet, HISTORY_SLOTS> sixthDescriptorSets{};
			std::array<VkDescriptorSet, DELTA_STAGES - 2> descriptorSets{};
		};

		ImageHistory* m_inputs{};
		Image* m_flowInput{};
		Image* m_previousGamma{};
		Image* m_previous1{};
		Image* m_previous2{};
		std::array<Pass, DELTA_STAGES> m_passes;
		std::array<Generation, GENERATION_SLOTS> m_generations{};
		std::array<Image, DELTA_TEMPS> m_temp1;
		ImagePair m_temp2;
		Image m_outImage1;
		Image m_outImage2;
	};

	// writes the interpolated frame of a generation slot into the output image of that generation index
	class Generate
	{
	public:
		Generate() = default;
		Generate(Allocator& allocator, const Shaders& shaders, ImagePair& frames, Image& motion, Image& detail1, Image& detail2, std::array<Image, MAX_GENERATIONS>& outputs);

		void Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot);

	private:
		ImagePair* m_frames{};
		Image* m_motion{};
		Image* m_detail1{};
		Image* m_detail2{};
		std::array<Image, MAX_GENERATIONS>* m_outputs{};
		Pass m_pass;
		std::array<std::array<VkDescriptorSet, 2>, GENERATION_SLOTS> m_descriptorSets{};
	};

	class Chain
	{
	public:
		Chain(Allocator& allocator, const Shaders& shaders, VkExtent2D extent, float flowScale);
		Chain(const Chain&) = delete;
		Chain& operator=(const Chain&) = delete;

		// mipmaps, alpha and beta only depend on the two most recent frames and are shared by every generation
		void DispatchShared(VkCommandBuffer cmd, uint64 frameCount);
		void DispatchGeneration(VkCommandBuffer cmd, uint64 frameCount, size_t generationCount, size_t generation);

		Image& Input(uint64 frameCount) { return m_frames[frameCount % m_frames.size()]; }
		Image& Output(size_t generation) { return m_outputs[generation]; }

	private:
		ImagePair m_frames;
		std::array<Image, MAX_GENERATIONS> m_outputs;
		Mipmaps m_mipmaps;
		AlphaPasses m_alphaPasses;
		std::array<Alpha, MIP_LEVELS> m_alpha;
		Beta m_beta;
		std::array<Gamma, MIP_LEVELS> m_gamma;
		std::array<Delta, DELTA_INSTANCES> m_delta;
		Generate m_generate;
	};
}
