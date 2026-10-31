// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"

#include <array>
#include <deque>
#include <initializer_list>
#include <map>
#include <utility>
#include <vector>

class VKRMemoryManager;

namespace LSFG
{
	constexpr VkFormat DEFAULT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
	constexpr VkFormat FLOW_FORMAT = VK_FORMAT_R8_UNORM;
	constexpr VkFormat MOTION_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

	constexpr size_t HISTORY_SLOTS = 3;
	constexpr size_t MAX_GENERATIONS = 3;
	constexpr size_t GENERATION_SLOTS = MAX_GENERATIONS * (MAX_GENERATIONS + 1) / 2;

	// every (generation count, generation index) pair gets its own slot so that the per slot timestamps can be baked
	// into the descriptor sets
	constexpr size_t GenerationSlot(size_t generationCount, size_t generation)
	{
		return (generationCount - 1) * generationCount / 2 + generation;
	}

	constexpr float Timestamp(size_t generation, size_t generationCount)
	{
		return static_cast<float>(generation + 1) / static_cast<float>(generationCount + 1);
	}

	constexpr size_t SlotCount(size_t slot)
	{
		size_t count = 1;
		while (GenerationSlot(count + 1, 0) <= slot)
			++count;
		return count;
	}

	constexpr size_t SlotGeneration(size_t slot)
	{
		return slot - GenerationSlot(SlotCount(slot), 0);
	}

	constexpr float SlotTimestamp(size_t slot)
	{
		return Timestamp(SlotGeneration(slot), SlotCount(slot));
	}

	// handles are owned by the Allocator that created them
	struct Image
	{
		VkImage image{VK_NULL_HANDLE};
		VkImageView view{VK_NULL_HANDLE};
		VkExtent2D extent{};
		VkFormat format{VK_FORMAT_UNDEFINED};
		VkImageLayout layout{VK_IMAGE_LAYOUT_UNDEFINED};
	};

	using ImagePair = std::array<Image, 2>;
	using ImageHistory = std::array<ImagePair, HISTORY_SLOTS>;

	class Shaders
	{
	public:
		Shaders(VkDevice device);
		~Shaders();
		Shaders(const Shaders&) = delete;
		Shaders& operator=(const Shaders&) = delete;

		bool IsValid() const { return m_valid; }
		VkShaderModule Get(uint32 shaderId) const;

	private:
		VkDevice m_device;
		std::map<uint32, VkShaderModule> m_modules;
		bool m_valid{false};
	};

	// creates and owns every Vulkan object used by one frame generation chain
	class Allocator
	{
	public:
		Allocator(VkDevice device, VKRMemoryManager* memoryManager, float flowScale);
		~Allocator();
		Allocator(const Allocator&) = delete;
		Allocator& operator=(const Allocator&) = delete;

		VkDevice GetDevice() const { return m_device; }
		bool HasFailed() const { return m_failed; }

		Image CreateImage(VkExtent2D extent, VkFormat format = DEFAULT_FORMAT);

		VkSampler GetSampler(VkSamplerAddressMode addressMode = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VkCompareOp compareOp = VK_COMPARE_OP_NEVER, bool whiteBorder = false);
		VkBuffer GetBuffer(float timestamp = 0.0f, bool firstIter = false, bool firstIterS = false);
		static VkDeviceSize BufferSize();

		VkDescriptorSetLayout CreateSetLayout(const std::vector<VkDescriptorType>& types);
		VkPipelineLayout CreatePipelineLayout(VkDescriptorSetLayout setLayout);
		VkPipeline CreateComputePipeline(VkPipelineLayout layout, VkShaderModule shader);

		void CreateDescriptorPool(uint32 maxSets);
		std::vector<VkDescriptorSet> AllocateSets(const std::vector<VkDescriptorSetLayout>& layouts);

	private:
		VkDevice m_device;
		VKRMemoryManager* m_memoryManager;
		float m_flowScale;
		bool m_failed{false};

		VkDescriptorPool m_descriptorPool{VK_NULL_HANDLE};
		std::map<uint64, VkSampler> m_samplers;
		std::map<uint64, VkBuffer> m_buffers;
		std::vector<VkDeviceMemory> m_memory;
		std::vector<VkImage> m_images;
		std::vector<VkImageView> m_views;
		std::vector<VkDescriptorSetLayout> m_setLayouts;
		std::vector<VkPipelineLayout> m_pipelineLayouts;
		std::vector<VkPipeline> m_pipelines;
	};

	class Barriers
	{
	public:
		explicit Barriers(VkCommandBuffer cmd) : m_cmd(cmd) {}

		Barriers& WriteToRead(Image& image);
		Barriers& ReadToWrite(Image& image);
		Barriers& WriteToRead(Image* image);
		Barriers& ReadToWrite(Image* image);

		template<typename Range>
		Barriers& WriteToReadAll(Range& images)
		{
			for (auto& image : images)
				WriteToRead(image);
			return *this;
		}

		template<typename Range>
		Barriers& ReadToWriteAll(Range& images)
		{
			for (auto& image : images)
				ReadToWrite(image);
			return *this;
		}

		void Build();

	private:
		Barriers& Push(Image& image, VkAccessFlags srcAccess, VkAccessFlags dstAccess);

		VkCommandBuffer m_cmd;
		std::vector<VkImageMemoryBarrier> m_barriers;
	};

	// writes consecutive bindings starting at 0, matching the binding renumbering done when loading the shaders
	class DescriptorWriter
	{
	public:
		explicit DescriptorWriter(VkDescriptorSet set) : m_set(set) {}

		DescriptorWriter& AddSampler(VkSampler sampler);
		DescriptorWriter& AddSampledImage(const Image& image);
		DescriptorWriter& AddSampledImage(const Image* image);
		DescriptorWriter& AddStorageImage(const Image& image);
		DescriptorWriter& AddUniformBuffer(VkBuffer buffer, VkDeviceSize size);

		template<typename Range>
		DescriptorWriter& AddSampledImages(const Range& images)
		{
			for (const auto& image : images)
				AddSampledImage(image);
			return *this;
		}

		template<typename Range>
		DescriptorWriter& AddStorageImages(const Range& images)
		{
			for (const auto& image : images)
				AddStorageImage(image);
			return *this;
		}

		void Build(VkDevice device);

	private:
		DescriptorWriter& PushImage(VkDescriptorType type, VkSampler sampler, VkImageView view);

		VkDescriptorSet m_set;
		uint32 m_binding{0};
		std::deque<VkDescriptorImageInfo> m_imageInfos;
		std::deque<VkDescriptorBufferInfo> m_bufferInfos;
		std::vector<VkWriteDescriptorSet> m_writes;
	};

	using Bindings = std::initializer_list<std::pair<uint32, VkDescriptorType>>;

	class Pass
	{
	public:
		Pass() = default;
		Pass(Allocator& allocator, const Shaders& shaders, uint32 shaderId, Bindings bindings);

		VkDescriptorSetLayout SetLayout() const { return m_setLayout; }

		void Bind(VkCommandBuffer cmd, VkDescriptorSet set) const;
		void BindPipeline(VkCommandBuffer cmd) const;
		void BindSet(VkCommandBuffer cmd, VkDescriptorSet set) const;

	private:
		VkDescriptorSetLayout m_setLayout{VK_NULL_HANDLE};
		VkPipelineLayout m_pipelineLayout{VK_NULL_HANDLE};
		VkPipeline m_pipeline{VK_NULL_HANDLE};
	};

	constexpr uint32 GroupCount(uint32 size, uint32 shift)
	{
		return (size + (1u << shift) - 1) >> shift;
	}
}
