// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LsfgChain.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LosslessDll.h"

namespace LSFG
{
	using namespace LosslessDll::PerformanceShader;

	namespace
	{
		constexpr uint32 FIXED_DESCRIPTOR_SETS = 64;
		constexpr uint32 DESCRIPTOR_SETS_PER_SLOT = 112;
		constexpr size_t FIRST_DELTA_LEVEL = 4;

		VkExtent2D HalveExtent(VkExtent2D extent)
		{
			return {(extent.width + 1) >> 1, (extent.height + 1) >> 1};
		}
	}

	/* Mipmaps */

	Mipmaps::Mipmaps(Allocator& allocator, const Shaders& shaders, ImagePair& frames, float flowScale) : m_frames(&frames)
	{
		m_pass = Pass(allocator, shaders, MIPMAPS,
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {MIP_LEVELS, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

		const VkExtent2D inputExtent = frames[0].extent;
		m_flowExtent = {
			std::max(1u, static_cast<uint32>(static_cast<float>(inputExtent.width) * flowScale)),
			std::max(1u, static_cast<uint32>(static_cast<float>(inputExtent.height) * flowScale)),
		};
		for (size_t i = 0; i < MIP_LEVELS; ++i)
			m_outImages[i] = allocator.CreateImage({m_flowExtent.width >> i, m_flowExtent.height >> i}, FLOW_FORMAT);

		const std::vector<VkDescriptorSetLayout> layouts(m_descriptorSets.size(), m_pass.SetLayout());
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);

		const VkSampler sampler = allocator.GetSampler();
		const VkBuffer buffer = allocator.GetBuffer();
		for (size_t i = 0; i < m_descriptorSets.size(); ++i)
		{
			m_descriptorSets[i] = sets[i];
			DescriptorWriter(m_descriptorSets[i])
				.AddUniformBuffer(buffer, Allocator::BufferSize())
				.AddSampler(sampler)
				.AddSampledImage(frames[i])
				.AddStorageImages(m_outImages)
				.Build(allocator.GetDevice());
		}
	}

	void Mipmaps::Dispatch(VkCommandBuffer cmd, uint64 frameCount)
	{
		const size_t slot = frameCount % m_descriptorSets.size();
		Barriers(cmd).WriteToRead((*m_frames)[slot]).ReadToWriteAll(m_outImages).Build();
		m_pass.Bind(cmd, m_descriptorSets[slot]);
		vkCmdDispatch(cmd, GroupCount(m_flowExtent.width, 6), GroupCount(m_flowExtent.height, 6), 1);
	}

	/* Alpha */

	AlphaPasses::AlphaPasses(Allocator& allocator, const Shaders& shaders)
	{
		m_passes[0] = Pass(allocator, shaders, ALPHA[0],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[1] = Pass(allocator, shaders, ALPHA[1],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[2] = Pass(allocator, shaders, ALPHA[2],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[3] = Pass(allocator, shaders, ALPHA[3],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
	}

	Alpha::Alpha(Allocator& allocator, const AlphaPasses& passes, Image& input) : m_passes(&passes), m_input(&input)
	{
		const VkExtent2D halfExtent = HalveExtent(input.extent);
		const VkExtent2D quarterExtent = HalveExtent(halfExtent);

		m_temp1 = allocator.CreateImage(halfExtent);
		m_temp2 = allocator.CreateImage(halfExtent);
		for (size_t i = 0; i < m_temp3.size(); ++i)
		{
			m_temp3[i] = allocator.CreateImage(quarterExtent);
			for (size_t j = 0; j < HISTORY_SLOTS; ++j)
				m_outImages[j][i] = allocator.CreateImage(quarterExtent);
		}

		std::vector<VkDescriptorSetLayout> layouts;
		for (size_t i = 0; i < ALPHA_STAGES - 1; ++i)
			layouts.push_back(passes.Get(i).SetLayout());
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			layouts.push_back(passes.Get(3).SetLayout());
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);
		for (size_t i = 0; i < ALPHA_STAGES - 1; ++i)
			m_descriptorSets[i] = sets[i];
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			m_lastDescriptorSets[i] = sets[ALPHA_STAGES - 1 + i];

		const VkDevice device = allocator.GetDevice();
		const VkSampler sampler = allocator.GetSampler();
		DescriptorWriter(m_descriptorSets[0]).AddSampler(sampler).AddSampledImage(input).AddStorageImage(m_temp1).Build(device);
		DescriptorWriter(m_descriptorSets[1]).AddSampler(sampler).AddSampledImage(m_temp1).AddStorageImage(m_temp2).Build(device);
		DescriptorWriter(m_descriptorSets[2]).AddSampler(sampler).AddSampledImage(m_temp2).AddStorageImages(m_temp3).Build(device);
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			DescriptorWriter(m_lastDescriptorSets[i]).AddSampler(sampler).AddSampledImages(m_temp3).AddStorageImages(m_outImages[i]).Build(device);
	}

	void Alpha::PushBarriers(Barriers& barriers, uint64 frameCount, size_t stage)
	{
		switch (stage)
		{
		case 0:
			barriers.WriteToRead(*m_input).ReadToWrite(m_temp1);
			break;
		case 1:
			barriers.WriteToRead(m_temp1).ReadToWrite(m_temp2);
			break;
		case 2:
			barriers.WriteToRead(m_temp2).ReadToWriteAll(m_temp3);
			break;
		default:
			barriers.WriteToReadAll(m_temp3).ReadToWriteAll(m_outImages[frameCount % HISTORY_SLOTS]);
			break;
		}
	}

	void Alpha::DispatchStage(VkCommandBuffer cmd, uint64 frameCount, size_t stage)
	{
		const VkExtent2D extent = stage < 2 ? m_temp1.extent : m_temp3[0].extent;
		const VkDescriptorSet set = stage < ALPHA_STAGES - 1 ? m_descriptorSets[stage] : m_lastDescriptorSets[frameCount % HISTORY_SLOTS];
		m_passes->Get(stage).BindSet(cmd, set);
		vkCmdDispatch(cmd, GroupCount(extent.width, 3), GroupCount(extent.height, 3), 1);
	}

	/* Beta */

	Beta::Beta(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs) : m_inputs(&inputs)
	{
		m_passes[0] = Pass(allocator, shaders, BETA[0],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {6, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		for (size_t i = 1; i < BETA_STAGES - 1; ++i)
		{
			m_passes[i] = Pass(allocator, shaders, BETA[i],
				{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
				 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
				 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		}
		m_passes[4] = Pass(allocator, shaders, BETA[4],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

		const VkExtent2D extent = inputs[0][0].extent;
		for (size_t i = 0; i < m_temp1.size(); ++i)
		{
			m_temp1[i] = allocator.CreateImage(extent);
			m_temp2[i] = allocator.CreateImage(extent);
		}
		for (size_t i = 0; i < BETA_OUTPUTS; ++i)
			m_outImages[i] = allocator.CreateImage({extent.width >> i, extent.height >> i}, FLOW_FORMAT);

		std::vector<VkDescriptorSetLayout> layouts;
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			layouts.push_back(m_passes[0].SetLayout());
		for (size_t i = 1; i < BETA_STAGES; ++i)
			layouts.push_back(m_passes[i].SetLayout());
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			m_firstDescriptorSets[i] = sets[i];
		for (size_t i = 0; i < BETA_STAGES - 1; ++i)
			m_descriptorSets[i] = sets[HISTORY_SLOTS + i];

		const VkDevice device = allocator.GetDevice();
		const VkSampler sampler = allocator.GetSampler();
		const VkSampler borderSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_COMPARE_OP_NEVER, true);
		for (size_t i = 0; i < HISTORY_SLOTS; ++i)
		{
			DescriptorWriter(m_firstDescriptorSets[i])
				.AddSampler(borderSampler)
				.AddSampledImages(inputs[(i + 1) % HISTORY_SLOTS])
				.AddSampledImages(inputs[(i + 2) % HISTORY_SLOTS])
				.AddSampledImages(inputs[i % HISTORY_SLOTS])
				.AddStorageImages(m_temp1)
				.Build(device);
		}
		DescriptorWriter(m_descriptorSets[0]).AddSampler(sampler).AddSampledImages(m_temp1).AddStorageImages(m_temp2).Build(device);
		DescriptorWriter(m_descriptorSets[1]).AddSampler(sampler).AddSampledImages(m_temp2).AddStorageImages(m_temp1).Build(device);
		DescriptorWriter(m_descriptorSets[2]).AddSampler(sampler).AddSampledImages(m_temp1).AddStorageImages(m_temp2).Build(device);
		DescriptorWriter(m_descriptorSets[3])
			.AddUniformBuffer(allocator.GetBuffer(0.5f), Allocator::BufferSize())
			.AddSampler(sampler)
			.AddSampledImages(m_temp2)
			.AddStorageImages(m_outImages)
			.Build(device);
	}

	void Beta::Dispatch(VkCommandBuffer cmd, uint64 frameCount)
	{
		const VkExtent2D extent = m_temp1[0].extent;
		const uint32 groupsX = GroupCount(extent.width, 3);
		const uint32 groupsY = GroupCount(extent.height, 3);

		Barriers barriers(cmd);
		for (auto& slot : *m_inputs)
			barriers.WriteToReadAll(slot);
		barriers.ReadToWriteAll(m_temp1).Build();
		m_passes[0].Bind(cmd, m_firstDescriptorSets[frameCount % HISTORY_SLOTS]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp1).ReadToWriteAll(m_temp2).Build();
		m_passes[1].Bind(cmd, m_descriptorSets[0]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWriteAll(m_temp1).Build();
		m_passes[2].Bind(cmd, m_descriptorSets[1]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp1).ReadToWriteAll(m_temp2).Build();
		m_passes[3].Bind(cmd, m_descriptorSets[2]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWriteAll(m_outImages).Build();
		m_passes[4].Bind(cmd, m_descriptorSets[3]);
		vkCmdDispatch(cmd, GroupCount(extent.width, 5), GroupCount(extent.height, 5), 1);
	}

	/* Gamma */

	Gamma::Gamma(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs, Image& flowInput, Image* previous)
		: m_inputs(&inputs), m_flowInput(&flowInput), m_previous(previous)
	{
		m_passes[0] = Pass(allocator, shaders, GAMMA[0],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[1] = Pass(allocator, shaders, GAMMA[1],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[2] = Pass(allocator, shaders, GAMMA[2],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[3] = Pass(allocator, shaders, GAMMA[3],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[4] = Pass(allocator, shaders, GAMMA[4],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

		const VkExtent2D extent = inputs[0][0].extent;
		for (auto& image : m_temp1)
			image = allocator.CreateImage(extent);
		for (auto& image : m_temp2)
			image = allocator.CreateImage(extent);
		m_outImage = allocator.CreateImage(extent, MOTION_FORMAT);

		std::vector<VkDescriptorSetLayout> layouts;
		for (size_t slot = 0; slot < GENERATION_SLOTS; ++slot)
		{
			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				layouts.push_back(m_passes[0].SetLayout());
			for (size_t i = 1; i < GAMMA_STAGES; ++i)
				layouts.push_back(m_passes[i].SetLayout());
		}
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);

		const VkDevice device = allocator.GetDevice();
		const VkSampler sampler = allocator.GetSampler();
		const VkSampler borderSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_COMPARE_OP_NEVER, true);
		const VkSampler edgeSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_COMPARE_OP_ALWAYS, false);

		size_t next = 0;
		for (size_t slot = 0; slot < GENERATION_SLOTS; ++slot)
		{
			Generation& pass = m_generations[slot];
			const VkBuffer buffer = allocator.GetBuffer(SlotTimestamp(slot), previous == nullptr);

			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				pass.firstDescriptorSets[i] = sets[next++];
			for (size_t i = 0; i < GAMMA_STAGES - 1; ++i)
				pass.descriptorSets[i] = sets[next++];

			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			{
				DescriptorWriter(pass.firstDescriptorSets[i])
					.AddUniformBuffer(buffer, Allocator::BufferSize())
					.AddSampler(borderSampler)
					.AddSampler(edgeSampler)
					.AddSampledImages(inputs[(i + 2) % HISTORY_SLOTS])
					.AddSampledImages(inputs[i % HISTORY_SLOTS])
					.AddSampledImage(previous)
					.AddStorageImages(m_temp1)
					.Build(device);
			}
			DescriptorWriter(pass.descriptorSets[0]).AddSampler(sampler).AddSampledImages(m_temp1).AddStorageImages(m_temp2).Build(device);
			DescriptorWriter(pass.descriptorSets[1]).AddSampler(sampler).AddSampledImages(m_temp2).AddStorageImage(m_temp1[0]).AddStorageImage(m_temp1[1]).Build(device);
			DescriptorWriter(pass.descriptorSets[2]).AddSampler(sampler).AddSampledImage(m_temp1[0]).AddSampledImage(m_temp1[1]).AddStorageImages(m_temp2).Build(device);
			DescriptorWriter(pass.descriptorSets[3])
				.AddUniformBuffer(buffer, Allocator::BufferSize())
				.AddSampler(sampler)
				.AddSampler(edgeSampler)
				.AddSampledImages(m_temp2)
				.AddSampledImage(previous)
				.AddSampledImage(flowInput)
				.AddStorageImage(m_outImage)
				.Build(device);
		}
	}

	void Gamma::Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot)
	{
		const Generation& pass = m_generations[slot];
		const VkExtent2D extent = m_temp1[0].extent;
		const uint32 groupsX = GroupCount(extent.width, 3);
		const uint32 groupsY = GroupCount(extent.height, 3);

		const size_t history = frameCount % HISTORY_SLOTS;
		const size_t previousHistory = (frameCount + 2) % HISTORY_SLOTS;

		Barriers(cmd)
			.WriteToReadAll((*m_inputs)[previousHistory])
			.WriteToReadAll((*m_inputs)[history])
			.WriteToRead(m_previous)
			.ReadToWriteAll(m_temp1)
			.Build();
		m_passes[0].Bind(cmd, pass.firstDescriptorSets[history]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp1).ReadToWriteAll(m_temp2).Build();
		m_passes[1].Bind(cmd, pass.descriptorSets[0]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWrite(m_temp1[0]).ReadToWrite(m_temp1[1]).Build();
		m_passes[2].Bind(cmd, pass.descriptorSets[1]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToRead(m_temp1[0]).WriteToRead(m_temp1[1]).ReadToWriteAll(m_temp2).Build();
		m_passes[3].Bind(cmd, pass.descriptorSets[2]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd)
			.WriteToReadAll(m_temp2)
			.WriteToRead(m_previous)
			.WriteToRead(*m_flowInput)
			.ReadToWrite(m_outImage)
			.Build();
		m_passes[4].Bind(cmd, pass.descriptorSets[3]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);
	}

	/* Delta */

	Delta::Delta(Allocator& allocator, const Shaders& shaders, ImageHistory& inputs, Image& flowInput, Image* previousGamma, Image* previous1, Image* previous2)
		: m_inputs(&inputs), m_flowInput(&flowInput), m_previousGamma(previousGamma), m_previous1(previous1), m_previous2(previous2)
	{
		m_passes[0] = Pass(allocator, shaders, DELTA[0],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[1] = Pass(allocator, shaders, DELTA[1],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[2] = Pass(allocator, shaders, DELTA[2],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[3] = Pass(allocator, shaders, DELTA[3],
			{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[4] = Pass(allocator, shaders, DELTA[4],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		m_passes[5] = Pass(allocator, shaders, DELTA[5],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {6, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		for (size_t i = 6; i < DELTA_STAGES - 1; ++i)
		{
			m_passes[i] = Pass(allocator, shaders, DELTA[i],
				{{1, VK_DESCRIPTOR_TYPE_SAMPLER},
				 {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
				 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
		}
		m_passes[9] = Pass(allocator, shaders, DELTA[9],
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

		const VkExtent2D extent = inputs[0][0].extent;
		for (auto& image : m_temp1)
			image = allocator.CreateImage(extent);
		for (auto& image : m_temp2)
			image = allocator.CreateImage(extent);
		m_outImage1 = allocator.CreateImage(extent, MOTION_FORMAT);
		m_outImage2 = allocator.CreateImage(extent, MOTION_FORMAT);

		std::vector<VkDescriptorSetLayout> layouts;
		for (size_t slot = 0; slot < GENERATION_SLOTS; ++slot)
		{
			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				layouts.push_back(m_passes[0].SetLayout());
			for (size_t i = 1; i <= 4; ++i)
				layouts.push_back(m_passes[i].SetLayout());
			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				layouts.push_back(m_passes[5].SetLayout());
			for (size_t i = 6; i < DELTA_STAGES; ++i)
				layouts.push_back(m_passes[i].SetLayout());
		}
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);

		const VkDevice device = allocator.GetDevice();
		const VkSampler sampler = allocator.GetSampler();
		const VkSampler borderSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_COMPARE_OP_NEVER, true);
		const VkSampler edgeSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_COMPARE_OP_ALWAYS, false);

		size_t next = 0;
		for (size_t slot = 0; slot < GENERATION_SLOTS; ++slot)
		{
			Generation& pass = m_generations[slot];
			const VkBuffer buffer = allocator.GetBuffer(SlotTimestamp(slot), false, previousGamma == nullptr);

			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				pass.firstDescriptorSets[i] = sets[next++];
			for (size_t i = 0; i < 4; ++i)
				pass.descriptorSets[i] = sets[next++];
			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
				pass.sixthDescriptorSets[i] = sets[next++];
			for (size_t i = 4; i < DELTA_STAGES - 2; ++i)
				pass.descriptorSets[i] = sets[next++];

			for (size_t i = 0; i < HISTORY_SLOTS; ++i)
			{
				DescriptorWriter(pass.firstDescriptorSets[i])
					.AddUniformBuffer(buffer, Allocator::BufferSize())
					.AddSampler(borderSampler)
					.AddSampler(edgeSampler)
					.AddSampledImages(inputs[(i + 2) % HISTORY_SLOTS])
					.AddSampledImages(inputs[i % HISTORY_SLOTS])
					.AddSampledImage(previousGamma)
					.AddStorageImages(m_temp1)
					.Build(device);
				DescriptorWriter(pass.sixthDescriptorSets[i])
					.AddUniformBuffer(buffer, Allocator::BufferSize())
					.AddSampler(borderSampler)
					.AddSampler(edgeSampler)
					.AddSampledImages(inputs[(i + 2) % HISTORY_SLOTS])
					.AddSampledImages(inputs[i % HISTORY_SLOTS])
					.AddSampledImage(previousGamma)
					.AddSampledImage(previous1)
					.AddStorageImage(m_temp2[0])
					.Build(device);
			}
			DescriptorWriter(pass.descriptorSets[0]).AddSampler(sampler).AddSampledImages(m_temp1).AddStorageImages(m_temp2).Build(device);
			DescriptorWriter(pass.descriptorSets[1]).AddSampler(sampler).AddSampledImages(m_temp2).AddStorageImage(m_temp1[0]).AddStorageImage(m_temp1[1]).Build(device);
			DescriptorWriter(pass.descriptorSets[2]).AddSampler(sampler).AddSampledImage(m_temp1[0]).AddSampledImage(m_temp1[1]).AddStorageImages(m_temp2).Build(device);
			DescriptorWriter(pass.descriptorSets[3])
				.AddUniformBuffer(buffer, Allocator::BufferSize())
				.AddSampler(sampler)
				.AddSampler(edgeSampler)
				.AddSampledImages(m_temp2)
				.AddSampledImage(previousGamma)
				.AddSampledImage(flowInput)
				.AddStorageImage(m_outImage1)
				.Build(device);
			DescriptorWriter(pass.descriptorSets[4]).AddSampler(sampler).AddSampledImage(m_temp2[0]).AddStorageImage(m_temp1[0]).Build(device);
			DescriptorWriter(pass.descriptorSets[5]).AddSampler(sampler).AddSampledImage(m_temp1[0]).AddStorageImage(m_temp2[0]).Build(device);
			DescriptorWriter(pass.descriptorSets[6]).AddSampler(sampler).AddSampledImage(m_temp2[0]).AddStorageImage(m_temp1[0]).Build(device);
			DescriptorWriter(pass.descriptorSets[7])
				.AddUniformBuffer(buffer, Allocator::BufferSize())
				.AddSampler(sampler)
				.AddSampler(edgeSampler)
				.AddSampledImage(m_temp1[0])
				.AddSampledImage(previous2)
				.AddStorageImage(m_outImage2)
				.Build(device);
		}
	}

	void Delta::Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot)
	{
		const Generation& pass = m_generations[slot];
		const VkExtent2D extent = m_temp1[0].extent;
		const uint32 groupsX = GroupCount(extent.width, 3);
		const uint32 groupsY = GroupCount(extent.height, 3);

		const size_t history = frameCount % HISTORY_SLOTS;
		const size_t previousHistory = (frameCount + 2) % HISTORY_SLOTS;

		Barriers(cmd)
			.WriteToReadAll((*m_inputs)[previousHistory])
			.WriteToReadAll((*m_inputs)[history])
			.WriteToRead(m_previousGamma)
			.ReadToWriteAll(m_temp1)
			.Build();
		m_passes[0].Bind(cmd, pass.firstDescriptorSets[history]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp1).ReadToWriteAll(m_temp2).Build();
		m_passes[1].Bind(cmd, pass.descriptorSets[0]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWriteAll(m_temp1).Build();
		m_passes[2].Bind(cmd, pass.descriptorSets[1]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp1).ReadToWriteAll(m_temp2).Build();
		m_passes[3].Bind(cmd, pass.descriptorSets[2]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd)
			.WriteToReadAll(m_temp2)
			.WriteToRead(m_previousGamma)
			.WriteToRead(*m_flowInput)
			.ReadToWrite(m_outImage1)
			.Build();
		m_passes[4].Bind(cmd, pass.descriptorSets[3]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd)
			.WriteToReadAll((*m_inputs)[previousHistory])
			.WriteToReadAll((*m_inputs)[history])
			.WriteToRead(m_previousGamma)
			.WriteToRead(m_previous1)
			.ReadToWriteAll(m_temp2)
			.Build();
		m_passes[5].Bind(cmd, pass.sixthDescriptorSets[history]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWrite(m_temp1[0]).ReadToWrite(m_temp1[1]).Build();
		m_passes[6].Bind(cmd, pass.descriptorSets[4]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToRead(m_temp1[0]).WriteToRead(m_temp1[1]).ReadToWriteAll(m_temp2).Build();
		m_passes[7].Bind(cmd, pass.descriptorSets[5]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd).WriteToReadAll(m_temp2).ReadToWrite(m_temp1[0]).ReadToWrite(m_temp1[1]).Build();
		m_passes[8].Bind(cmd, pass.descriptorSets[6]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);

		Barriers(cmd)
			.WriteToRead(m_temp1[0])
			.WriteToRead(m_temp1[1])
			.WriteToRead(m_previous2)
			.ReadToWrite(m_outImage2)
			.Build();
		m_passes[9].Bind(cmd, pass.descriptorSets[7]);
		vkCmdDispatch(cmd, groupsX, groupsY, 1);
	}

	/* Generate */

	Generate::Generate(Allocator& allocator, const Shaders& shaders, ImagePair& frames, Image& motion, Image& detail1, Image& detail2, std::array<Image, MAX_GENERATIONS>& outputs)
		: m_frames(&frames), m_motion(&motion), m_detail1(&detail1), m_detail2(&detail2), m_outputs(&outputs)
	{
		m_pass = Pass(allocator, shaders, GENERATE,
			{{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
			 {2, VK_DESCRIPTOR_TYPE_SAMPLER},
			 {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
			 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

		const std::vector<VkDescriptorSetLayout> layouts(GENERATION_SLOTS * 2, m_pass.SetLayout());
		const std::vector<VkDescriptorSet> sets = allocator.AllocateSets(layouts);

		const VkDevice device = allocator.GetDevice();
		const VkSampler sampler = allocator.GetSampler();
		const VkSampler edgeSampler = allocator.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_COMPARE_OP_ALWAYS, false);

		size_t next = 0;
		for (size_t slot = 0; slot < GENERATION_SLOTS; ++slot)
		{
			const VkBuffer buffer = allocator.GetBuffer(SlotTimestamp(slot));
			const Image& output = outputs[SlotGeneration(slot)];
			for (size_t i = 0; i < 2; ++i)
			{
				m_descriptorSets[slot][i] = sets[next++];
				DescriptorWriter(m_descriptorSets[slot][i])
					.AddUniformBuffer(buffer, Allocator::BufferSize())
					.AddSampler(sampler)
					.AddSampler(edgeSampler)
					.AddSampledImage(frames[1 - i])
					.AddSampledImage(frames[i])
					.AddSampledImage(motion)
					.AddSampledImage(detail1)
					.AddSampledImage(detail2)
					.AddStorageImage(output)
					.Build(device);
			}
		}
	}

	void Generate::Dispatch(VkCommandBuffer cmd, uint64 frameCount, size_t slot)
	{
		Image& output = (*m_outputs)[SlotGeneration(slot)];

		// the output was last read by a blit to the swapchain, its previous contents can be discarded
		VkImageMemoryBarrier outputBarrier{};
		outputBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		outputBarrier.srcAccessMask = 0;
		outputBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
		outputBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		outputBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
		outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		outputBarrier.image = output.image;
		outputBarrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &outputBarrier);
		output.layout = VK_IMAGE_LAYOUT_GENERAL;

		Barriers(cmd)
			.WriteToReadAll(*m_frames)
			.WriteToRead(*m_motion)
			.WriteToRead(*m_detail1)
			.WriteToRead(*m_detail2)
			.Build();

		m_pass.Bind(cmd, m_descriptorSets[slot][frameCount % 2]);
		vkCmdDispatch(cmd, GroupCount(output.extent.width, 4), GroupCount(output.extent.height, 4), 1);
	}

	/* Chain */

	Chain::Chain(Allocator& allocator, const Shaders& shaders, VkExtent2D extent, float flowScale)
	{
		allocator.CreateDescriptorPool(FIXED_DESCRIPTOR_SETS + DESCRIPTOR_SETS_PER_SLOT * static_cast<uint32>(GENERATION_SLOTS));

		for (auto& image : m_frames)
			image = allocator.CreateImage(extent);
		for (auto& image : m_outputs)
			image = allocator.CreateImage(extent);

		m_mipmaps = Mipmaps(allocator, shaders, m_frames, flowScale);

		m_alphaPasses = AlphaPasses(allocator, shaders);
		for (size_t i = 0; i < MIP_LEVELS; ++i)
			m_alpha[i] = Alpha(allocator, m_alphaPasses, m_mipmaps.Output(i));

		m_beta = Beta(allocator, shaders, m_alpha[0].Outputs());

		for (size_t i = 0; i < MIP_LEVELS; ++i)
		{
			const size_t level = MIP_LEVELS - 1 - i;
			m_gamma[i] = Gamma(allocator, shaders, m_alpha[level].Outputs(), m_beta.Output(std::min(level, BETA_OUTPUTS - 1)), i == 0 ? nullptr : &m_gamma[i - 1].Output());

			if (i < FIRST_DELTA_LEVEL)
				continue;

			const size_t index = i - FIRST_DELTA_LEVEL;
			m_delta[index] = Delta(allocator, shaders, m_alpha[level].Outputs(), m_beta.Output(level),
				i == FIRST_DELTA_LEVEL ? nullptr : &m_gamma[i - 1].Output(),
				i == FIRST_DELTA_LEVEL ? nullptr : &m_delta[index - 1].Output1(),
				i == FIRST_DELTA_LEVEL ? nullptr : &m_delta[index - 1].Output2());
		}

		m_generate = Generate(allocator, shaders, m_frames, m_gamma[MIP_LEVELS - 1].Output(), m_delta[DELTA_INSTANCES - 1].Output1(), m_delta[DELTA_INSTANCES - 1].Output2(), m_outputs);
	}

	void Chain::DispatchShared(VkCommandBuffer cmd, uint64 frameCount)
	{
		m_mipmaps.Dispatch(cmd, frameCount);

		for (size_t stage = 0; stage < ALPHA_STAGES; ++stage)
		{
			Barriers barriers(cmd);
			for (auto& level : m_alpha)
				level.PushBarriers(barriers, frameCount, stage);
			barriers.Build();

			m_alphaPasses.Get(stage).BindPipeline(cmd);
			for (auto& level : m_alpha)
				level.DispatchStage(cmd, frameCount, stage);
		}

		m_beta.Dispatch(cmd, frameCount);
	}

	void Chain::DispatchGeneration(VkCommandBuffer cmd, uint64 frameCount, size_t generationCount, size_t generation)
	{
		const size_t slot = GenerationSlot(generationCount, generation);
		for (size_t i = 0; i < MIP_LEVELS; ++i)
		{
			m_gamma[i].Dispatch(cmd, frameCount, slot);
			if (i >= FIRST_DELTA_LEVEL)
				m_delta[i - FIRST_DELTA_LEVEL].Dispatch(cmd, frameCount, slot);
		}
		m_generate.Dispatch(cmd, frameCount, slot);
	}
}
