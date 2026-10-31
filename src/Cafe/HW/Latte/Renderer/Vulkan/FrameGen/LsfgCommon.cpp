// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LsfgCommon.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LosslessDll.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VKRMemoryManager.h"

namespace LSFG
{
	namespace
	{
		constexpr uint32 DESCRIPTORS_PER_TYPE = 4096;

		struct Constants
		{
			std::array<uint32, 2> inputOffset;
			uint32 firstIter;
			uint32 firstIterS;
			uint32 advancedColorKind;
			uint32 hdrSupport;
			float resolutionInvScale;
			float timestamp;
			float uiThreshold;
			std::array<uint32, 3> padding;
		};
		static_assert(sizeof(Constants) == 48);

		VkImageSubresourceRange ColorRange()
		{
			VkImageSubresourceRange range{};
			range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
			range.baseMipLevel = 0;
			range.levelCount = 1;
			range.baseArrayLayer = 0;
			range.layerCount = 1;
			return range;
		}
	}

	Shaders::Shaders(VkDevice device) : m_device(device)
	{
		LosslessDll::ShaderModules code;
		const LosslessDll::Status status = LosslessDll::LoadShaderModules(code);
		if (status != LosslessDll::Status::Ok)
		{
			cemuLog_log(LogType::Force, "FrameGen: Unable to load shaders from Lossless.dll (status {})", (sint32)status);
			return;
		}

		for (const auto& [id, words] : code)
		{
			VkShaderModuleCreateInfo createInfo{};
			createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
			createInfo.codeSize = words.size() * sizeof(uint32);
			createInfo.pCode = words.data();
			VkShaderModule module = VK_NULL_HANDLE;
			if (vkCreateShaderModule(m_device, &createInfo, nullptr, &module) != VK_SUCCESS)
			{
				cemuLog_log(LogType::Force, "FrameGen: Failed to create shader module {}", id);
				return;
			}
			m_modules.emplace(id, module);
		}
		m_valid = true;
	}

	Shaders::~Shaders()
	{
		for (auto& [id, module] : m_modules)
			vkDestroyShaderModule(m_device, module, nullptr);
	}

	VkShaderModule Shaders::Get(uint32 shaderId) const
	{
		const auto hit = m_modules.find(shaderId);
		return hit == m_modules.end() ? VK_NULL_HANDLE : hit->second;
	}

	Allocator::Allocator(VkDevice device, VKRMemoryManager* memoryManager, float flowScale)
		: m_device(device), m_memoryManager(memoryManager), m_flowScale(flowScale) {}

	Allocator::~Allocator()
	{
		for (VkPipeline pipeline : m_pipelines)
			vkDestroyPipeline(m_device, pipeline, nullptr);
		for (VkPipelineLayout layout : m_pipelineLayouts)
			vkDestroyPipelineLayout(m_device, layout, nullptr);
		for (VkDescriptorSetLayout layout : m_setLayouts)
			vkDestroyDescriptorSetLayout(m_device, layout, nullptr);
		if (m_descriptorPool != VK_NULL_HANDLE)
			vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);
		for (auto& [key, sampler] : m_samplers)
			vkDestroySampler(m_device, sampler, nullptr);
		for (auto& [key, buffer] : m_buffers)
			vkDestroyBuffer(m_device, buffer, nullptr);
		for (VkImageView view : m_views)
			vkDestroyImageView(m_device, view, nullptr);
		for (VkImage image : m_images)
			vkDestroyImage(m_device, image, nullptr);
		for (VkDeviceMemory memory : m_memory)
			vkFreeMemory(m_device, memory, nullptr);
	}

	Image Allocator::CreateImage(VkExtent2D extent, VkFormat format)
	{
		Image result{};
		result.extent = {std::max(1u, extent.width), std::max(1u, extent.height)};
		result.format = format;
		if (m_failed)
			return result;

		VkImageCreateInfo imageInfo{};
		imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		imageInfo.imageType = VK_IMAGE_TYPE_2D;
		imageInfo.format = format;
		imageInfo.extent = {result.extent.width, result.extent.height, 1};
		imageInfo.mipLevels = 1;
		imageInfo.arrayLayers = 1;
		imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
		imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
		imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		if (vkCreateImage(m_device, &imageInfo, nullptr, &result.image) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create image {}x{} format {}", result.extent.width, result.extent.height, (sint32)format);
			m_failed = true;
			return result;
		}
		m_images.push_back(result.image);

		VkMemoryRequirements requirements;
		vkGetImageMemoryRequirements(m_device, result.image, &requirements);
		VkMemoryAllocateInfo allocInfo{};
		allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		allocInfo.allocationSize = requirements.size;
		VkDeviceMemory memory = VK_NULL_HANDLE;
		if (!m_memoryManager->FindMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, allocInfo.memoryTypeIndex) ||
			vkAllocateMemory(m_device, &allocInfo, nullptr, &memory) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to allocate image memory");
			m_failed = true;
			return result;
		}
		m_memory.push_back(memory);
		vkBindImageMemory(m_device, result.image, memory, 0);

		VkImageViewCreateInfo viewInfo{};
		viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		viewInfo.image = result.image;
		viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
		viewInfo.format = format;
		viewInfo.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
		viewInfo.subresourceRange = ColorRange();
		if (vkCreateImageView(m_device, &viewInfo, nullptr, &result.view) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create image view");
			m_failed = true;
			return result;
		}
		m_views.push_back(result.view);
		return result;
	}

	VkDeviceSize Allocator::BufferSize()
	{
		return sizeof(Constants);
	}

	VkSampler Allocator::GetSampler(VkSamplerAddressMode addressMode, VkCompareOp compareOp, bool whiteBorder)
	{
		const uint64 key = static_cast<uint64>(addressMode) | (static_cast<uint64>(compareOp) << 8) | (static_cast<uint64>(whiteBorder) << 16);
		const auto it = m_samplers.find(key);
		if (it != m_samplers.end())
			return it->second;

		VkSamplerCreateInfo samplerInfo{};
		samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
		samplerInfo.magFilter = VK_FILTER_LINEAR;
		samplerInfo.minFilter = VK_FILTER_LINEAR;
		samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
		samplerInfo.addressModeU = addressMode;
		samplerInfo.addressModeV = addressMode;
		samplerInfo.addressModeW = addressMode;
		samplerInfo.mipLodBias = 0.0f;
		samplerInfo.anisotropyEnable = VK_FALSE;
		samplerInfo.maxAnisotropy = 1.0f;
		samplerInfo.compareEnable = VK_FALSE;
		samplerInfo.compareOp = compareOp;
		samplerInfo.minLod = 0.0f;
		samplerInfo.maxLod = VK_LOD_CLAMP_NONE;
		samplerInfo.borderColor = whiteBorder ? VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
		samplerInfo.unnormalizedCoordinates = VK_FALSE;

		VkSampler sampler = VK_NULL_HANDLE;
		if (vkCreateSampler(m_device, &samplerInfo, nullptr, &sampler) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create sampler");
			m_failed = true;
			return VK_NULL_HANDLE;
		}
		m_samplers.emplace(key, sampler);
		return sampler;
	}

	VkBuffer Allocator::GetBuffer(float timestamp, bool firstIter, bool firstIterS)
	{
		uint32 timestampBits{};
		std::memcpy(&timestampBits, &timestamp, sizeof(timestampBits));
		const uint64 key = static_cast<uint64>(timestampBits) | (static_cast<uint64>(firstIter) << 32) | (static_cast<uint64>(firstIterS) << 33);
		const auto it = m_buffers.find(key);
		if (it != m_buffers.end())
			return it->second;

		VkBuffer buffer = VK_NULL_HANDLE;
		VkDeviceMemory memory = VK_NULL_HANDLE;
		if (!m_memoryManager->CreateBuffer(sizeof(Constants), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, buffer, memory))
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create uniform buffer");
			m_failed = true;
			return VK_NULL_HANDLE;
		}
		m_memory.push_back(memory);
		m_buffers.emplace(key, buffer);

		Constants constants{};
		constants.inputOffset = {0, 0};
		constants.firstIter = firstIter ? 1u : 0u;
		constants.firstIterS = firstIterS ? 1u : 0u;
		constants.advancedColorKind = 0;
		constants.hdrSupport = 0;
		constants.resolutionInvScale = 1.0f / m_flowScale;
		constants.timestamp = timestamp;
		constants.uiThreshold = 0.5f;

		void* mapped = nullptr;
		if (vkMapMemory(m_device, memory, 0, sizeof(Constants), 0, &mapped) == VK_SUCCESS)
		{
			std::memcpy(mapped, &constants, sizeof(constants));
			vkUnmapMemory(m_device, memory);
		}
		else
		{
			m_failed = true;
		}
		return buffer;
	}

	VkDescriptorSetLayout Allocator::CreateSetLayout(const std::vector<VkDescriptorType>& types)
	{
		std::vector<VkDescriptorSetLayoutBinding> bindings(types.size());
		for (size_t i = 0; i < types.size(); i++)
		{
			bindings[i] = {};
			bindings[i].binding = static_cast<uint32>(i);
			bindings[i].descriptorType = types[i];
			bindings[i].descriptorCount = 1;
			bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		}

		VkDescriptorSetLayoutCreateInfo layoutInfo{};
		layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
		layoutInfo.bindingCount = static_cast<uint32>(bindings.size());
		layoutInfo.pBindings = bindings.data();

		VkDescriptorSetLayout layout = VK_NULL_HANDLE;
		if (vkCreateDescriptorSetLayout(m_device, &layoutInfo, nullptr, &layout) != VK_SUCCESS)
		{
			m_failed = true;
			return VK_NULL_HANDLE;
		}
		m_setLayouts.push_back(layout);
		return layout;
	}

	VkPipelineLayout Allocator::CreatePipelineLayout(VkDescriptorSetLayout setLayout)
	{
		VkPipelineLayoutCreateInfo layoutInfo{};
		layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
		layoutInfo.setLayoutCount = 1;
		layoutInfo.pSetLayouts = &setLayout;

		VkPipelineLayout layout = VK_NULL_HANDLE;
		if (vkCreatePipelineLayout(m_device, &layoutInfo, nullptr, &layout) != VK_SUCCESS)
		{
			m_failed = true;
			return VK_NULL_HANDLE;
		}
		m_pipelineLayouts.push_back(layout);
		return layout;
	}

	VkPipeline Allocator::CreateComputePipeline(VkPipelineLayout layout, VkShaderModule shader)
	{
		if (shader == VK_NULL_HANDLE || layout == VK_NULL_HANDLE)
		{
			m_failed = true;
			return VK_NULL_HANDLE;
		}

		VkComputePipelineCreateInfo pipelineInfo{};
		pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
		pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
		pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
		pipelineInfo.stage.module = shader;
		pipelineInfo.stage.pName = "main";
		pipelineInfo.layout = layout;

		VkPipeline pipeline = VK_NULL_HANDLE;
		if (vkCreateComputePipelines(m_device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create compute pipeline");
			m_failed = true;
			return VK_NULL_HANDLE;
		}
		m_pipelines.push_back(pipeline);
		return pipeline;
	}

	void Allocator::CreateDescriptorPool(uint32 maxSets)
	{
		const std::array<VkDescriptorPoolSize, 4> poolSizes{{
			{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, DESCRIPTORS_PER_TYPE},
			{VK_DESCRIPTOR_TYPE_SAMPLER, DESCRIPTORS_PER_TYPE},
			{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, DESCRIPTORS_PER_TYPE},
			{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, DESCRIPTORS_PER_TYPE},
		}};

		VkDescriptorPoolCreateInfo poolInfo{};
		poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
		poolInfo.maxSets = maxSets;
		poolInfo.poolSizeCount = static_cast<uint32>(poolSizes.size());
		poolInfo.pPoolSizes = poolSizes.data();
		if (vkCreateDescriptorPool(m_device, &poolInfo, nullptr, &m_descriptorPool) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to create descriptor pool");
			m_failed = true;
		}
	}

	std::vector<VkDescriptorSet> Allocator::AllocateSets(const std::vector<VkDescriptorSetLayout>& layouts)
	{
		std::vector<VkDescriptorSet> sets(layouts.size(), VK_NULL_HANDLE);
		if (m_failed || layouts.empty())
			return sets;

		VkDescriptorSetAllocateInfo allocInfo{};
		allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
		allocInfo.descriptorPool = m_descriptorPool;
		allocInfo.descriptorSetCount = static_cast<uint32>(layouts.size());
		allocInfo.pSetLayouts = layouts.data();
		if (vkAllocateDescriptorSets(m_device, &allocInfo, sets.data()) != VK_SUCCESS)
		{
			cemuLog_log(LogType::Force, "FrameGen: Failed to allocate descriptor sets");
			m_failed = true;
		}
		return sets;
	}

	Barriers& Barriers::Push(Image& image, VkAccessFlags srcAccess, VkAccessFlags dstAccess)
	{
		VkImageMemoryBarrier barrier{};
		barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		barrier.srcAccessMask = srcAccess;
		barrier.dstAccessMask = dstAccess;
		barrier.oldLayout = image.layout;
		barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.image = image.image;
		barrier.subresourceRange = ColorRange();
		m_barriers.push_back(barrier);
		image.layout = VK_IMAGE_LAYOUT_GENERAL;
		return *this;
	}

	Barriers& Barriers::WriteToRead(Image& image)
	{
		return Push(image, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
	}

	Barriers& Barriers::ReadToWrite(Image& image)
	{
		return Push(image, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
	}

	Barriers& Barriers::WriteToRead(Image* image)
	{
		return image == nullptr ? *this : WriteToRead(*image);
	}

	Barriers& Barriers::ReadToWrite(Image* image)
	{
		return image == nullptr ? *this : ReadToWrite(*image);
	}

	void Barriers::Build()
	{
		if (m_barriers.empty())
			return;
		vkCmdPipelineBarrier(m_cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, static_cast<uint32>(m_barriers.size()), m_barriers.data());
		m_barriers.clear();
	}

	DescriptorWriter& DescriptorWriter::PushImage(VkDescriptorType type, VkSampler sampler, VkImageView view)
	{
		VkDescriptorImageInfo& imageInfo = m_imageInfos.emplace_back();
		imageInfo.sampler = sampler;
		imageInfo.imageView = view;
		imageInfo.imageLayout = view == VK_NULL_HANDLE ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;

		VkWriteDescriptorSet write{};
		write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		write.dstSet = m_set;
		write.dstBinding = m_binding++;
		write.dstArrayElement = 0;
		write.descriptorCount = 1;
		write.descriptorType = type;
		write.pImageInfo = &imageInfo;
		m_writes.push_back(write);
		return *this;
	}

	DescriptorWriter& DescriptorWriter::AddSampler(VkSampler sampler)
	{
		return PushImage(VK_DESCRIPTOR_TYPE_SAMPLER, sampler, VK_NULL_HANDLE);
	}

	DescriptorWriter& DescriptorWriter::AddSampledImage(const Image& image)
	{
		return PushImage(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_NULL_HANDLE, image.view);
	}

	DescriptorWriter& DescriptorWriter::AddSampledImage(const Image* image)
	{
		// a null view relies on VK_EXT_robustness2 nullDescriptor
		return PushImage(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_NULL_HANDLE, image == nullptr ? VK_NULL_HANDLE : image->view);
	}

	DescriptorWriter& DescriptorWriter::AddStorageImage(const Image& image)
	{
		return PushImage(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, image.view);
	}

	DescriptorWriter& DescriptorWriter::AddUniformBuffer(VkBuffer buffer, VkDeviceSize size)
	{
		VkDescriptorBufferInfo& bufferInfo = m_bufferInfos.emplace_back();
		bufferInfo.buffer = buffer;
		bufferInfo.offset = 0;
		bufferInfo.range = size;

		VkWriteDescriptorSet write{};
		write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		write.dstSet = m_set;
		write.dstBinding = m_binding++;
		write.dstArrayElement = 0;
		write.descriptorCount = 1;
		write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
		write.pBufferInfo = &bufferInfo;
		m_writes.push_back(write);
		return *this;
	}

	void DescriptorWriter::Build(VkDevice device)
	{
		if (m_writes.empty() || m_set == VK_NULL_HANDLE)
			return;
		vkUpdateDescriptorSets(device, static_cast<uint32>(m_writes.size()), m_writes.data(), 0, nullptr);
		m_writes.clear();
	}

	Pass::Pass(Allocator& allocator, const Shaders& shaders, uint32 shaderId, Bindings bindings)
	{
		std::vector<VkDescriptorType> types;
		for (const auto& [count, type] : bindings)
			types.insert(types.end(), count, type);

		m_setLayout = allocator.CreateSetLayout(types);
		m_pipelineLayout = allocator.CreatePipelineLayout(m_setLayout);
		m_pipeline = allocator.CreateComputePipeline(m_pipelineLayout, shaders.Get(shaderId));
	}

	void Pass::Bind(VkCommandBuffer cmd, VkDescriptorSet set) const
	{
		BindPipeline(cmd);
		BindSet(cmd, set);
	}

	void Pass::BindPipeline(VkCommandBuffer cmd) const
	{
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipeline);
	}

	void Pass::BindSet(VkCommandBuffer cmd, VkDescriptorSet set) const
	{
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipelineLayout, 0, 1, &set, 0, nullptr);
	}
}
