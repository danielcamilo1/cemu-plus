// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/FrameGenVk.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LosslessDll.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LsfgChain.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h"
#include "Cafe/HW/Latte/Core/LatteOverlay.h"

#include <cmath>

namespace
{
	// the chain needs the two most recent frames before it can interpolate, and the recurrent passes need one more
	// dispatch before their history is meaningful
	constexpr uint64 REQUIRED_FRAMES = 2;
	constexpr uint32 RECURRENCE_FRAMES = 2;

	std::atomic<sint32> s_runtimeStatus{static_cast<sint32>(FrameGenRuntimeStatus::Inactive)};

	void SetRuntimeStatus(FrameGenRuntimeStatus status)
	{
		s_runtimeStatus.store(static_cast<sint32>(status));
	}

	float ConfiguredFlowScale(uint32 sourceWidth, uint32 presentedWidth)
	{
		const sint32 percentage = FrameGenSettings::FlowScalePercentage();
		if (percentage != 0)
			return static_cast<float>(percentage) / 100.0f;
		if (sourceWidth == 0 || presentedWidth == 0)
			return 1.0f;
		// estimating motion at a higher resolution than the game renders at adds no detail
		constexpr float FLOW_SCALE_STEPS = 20.0f;
		const float ratio = static_cast<float>(sourceWidth) / static_cast<float>(presentedWidth);
		const float stepped = std::ceil(ratio * FLOW_SCALE_STEPS) / FLOW_SCALE_STEPS;
		return std::clamp(stepped, 0.25f, 1.0f);
	}

	VkImageMemoryBarrier MakeBarrier(VkImage image, VkAccessFlags srcAccess, VkAccessFlags dstAccess, VkImageLayout oldLayout, VkImageLayout newLayout)
	{
		VkImageMemoryBarrier barrier{};
		barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		barrier.srcAccessMask = srcAccess;
		barrier.dstAccessMask = dstAccess;
		barrier.oldLayout = oldLayout;
		barrier.newLayout = newLayout;
		barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		barrier.image = image;
		barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
		return barrier;
	}

	void BlitImage(VkCommandBuffer cmd, VkImage src, VkExtent2D srcExtent, VkImage dst, VkExtent2D dstExtent)
	{
		VkImageBlit region{};
		region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
		region.srcOffsets[1] = {static_cast<sint32>(srcExtent.width), static_cast<sint32>(srcExtent.height), 1};
		region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
		region.dstOffsets[1] = {static_cast<sint32>(dstExtent.width), static_cast<sint32>(dstExtent.height), 1};
		// a blit instead of a copy so that the RGBA chain images and BGRA swapchains convert correctly
		const bool sameSize = srcExtent.width == dstExtent.width && srcExtent.height == dstExtent.height;
		vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
	}

	// copies a chain image into a freshly acquired swapchain image and leaves it ready for presentation
	void BlitToSwapchain(VkCommandBuffer cmd, LSFG::Image& source, VkImage swapchainImage, VkExtent2D swapchainExtent)
	{
		const std::array before{
			MakeBarrier(source.image, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, source.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
			MakeBarrier(swapchainImage, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
		};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			0, 0, nullptr, 0, nullptr, static_cast<uint32>(before.size()), before.data());

		BlitImage(cmd, source.image, source.extent, swapchainImage, swapchainExtent);

		const std::array after{
			MakeBarrier(source.image, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
			MakeBarrier(swapchainImage, VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR),
		};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
			0, 0, nullptr, 0, nullptr, static_cast<uint32>(after.size()), after.data());
		source.layout = VK_IMAGE_LAYOUT_GENERAL;
	}

	// copies the finished frame out of the swapchain image. The swapchain image keeps its contents and layout
	void CaptureFromSwapchain(VkCommandBuffer cmd, VkImage swapchainImage, VkExtent2D swapchainExtent, LSFG::Image& destination)
	{
		const std::array before{
			MakeBarrier(swapchainImage, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
			MakeBarrier(destination.image, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
		};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			0, 0, nullptr, 0, nullptr, static_cast<uint32>(before.size()), before.data());

		BlitImage(cmd, swapchainImage, swapchainExtent, destination.image, destination.extent);

		const std::array after{
			MakeBarrier(swapchainImage, VK_ACCESS_TRANSFER_READ_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR),
			MakeBarrier(destination.image, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
		};
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
			0, 0, nullptr, 0, nullptr, static_cast<uint32>(after.size()), after.data());
		destination.layout = VK_IMAGE_LAYOUT_GENERAL;
	}
}

FrameGenVk::FrameGenVk(VulkanRenderer* renderer, bool deviceSupported)
	: m_renderer(renderer), m_deviceSupported(deviceSupported), m_dllChangeCounter(LosslessDll::GetChangeCounter())
{
	if (!m_deviceSupported)
		cemuLog_log(LogType::Force, "FrameGen: Device lacks Vulkan memory model, float16 or null descriptor support. Frame generation is unavailable");
}

FrameGenVk::~FrameGenVk()
{
	m_chain.reset();
	m_allocator.reset();
	m_shaders.reset();
	SetRuntimeStatus(FrameGenRuntimeStatus::Inactive);
}

FrameGenRuntimeStatus FrameGenVk::GetRuntimeStatus()
{
	return static_cast<FrameGenRuntimeStatus>(s_runtimeStatus.load());
}

bool FrameGenVk::IsActive()
{
	if (!FrameGenSettings::IsEnabled())
		return false;
	if (!m_deviceSupported)
	{
		SetRuntimeStatus(FrameGenRuntimeStatus::UnsupportedDevice);
		return false;
	}
	// a newly installed Lossless.dll is picked up by the next ProcessFrame()
	if (LosslessDll::GetChangeCounter() != m_dllChangeCounter)
		return true;
	return !m_unavailable;
}

void FrameGenVk::SetUnavailable(FrameGenRuntimeStatus status, const char* message)
{
	m_unavailable = true;
	SetRuntimeStatus(status);
	cemuLog_log(LogType::Force, "FrameGen: {}", message);
	LatteOverlay_pushNotification(message, 6000);
}

bool FrameGenVk::EnsureShaders()
{
	if (m_shaders)
		return true;
	if (LosslessDll::GetInstalledStatus() != LosslessDll::Status::Ok)
	{
		SetUnavailable(FrameGenRuntimeStatus::DllUnavailable, "Frame generation disabled: Lossless.dll is missing or invalid");
		return false;
	}
	auto shaders = std::make_unique<LSFG::Shaders>(m_renderer->GetLogicalDevice());
	if (!shaders->IsValid())
	{
		SetUnavailable(FrameGenRuntimeStatus::InitFailed, "Frame generation disabled: Failed to load the Lossless.dll shaders");
		return false;
	}
	m_shaders = std::move(shaders);
	return true;
}

void FrameGenVk::Rebuild(VkExtent2D extent, float flowScale)
{
	Release();

	m_allocator = std::make_unique<LSFG::Allocator>(m_renderer->GetLogicalDevice(), m_renderer->GetMemoryManager(), flowScale);
	m_chain = std::make_unique<LSFG::Chain>(*m_allocator, *m_shaders, extent, flowScale);
	if (m_allocator->HasFailed())
	{
		m_chain.reset();
		m_allocator.reset();
		SetUnavailable(FrameGenRuntimeStatus::InitFailed, "Frame generation disabled: Failed to create Vulkan resources");
		return;
	}

	cemuLog_log(LogType::Force, "FrameGen: Created chain for {}x{} with flow scale {:.2f}", extent.width, extent.height, flowScale);
	m_builtExtent = extent;
	m_builtFlowScale = flowScale;
	m_frameCount = 0;
	m_warmStreak = 0;
}

void FrameGenVk::Release()
{
	if (m_chain || m_allocator)
	{
		// previously submitted command buffers may still reference the chain
		m_renderer->WaitDeviceIdle();
		m_chain.reset();
		m_allocator.reset();
	}
	m_pacer.Reset();
	m_warmStreak = 0;
	m_peakSourceWidth = 0;
	if (!m_unavailable)
		SetRuntimeStatus(FrameGenRuntimeStatus::Inactive);
}

size_t FrameGenVk::ProcessFrame(VkCommandBuffer cmd, VkImage swapchainImage, VkExtent2D extent, uint32 sourceWidth)
{
	// retry after the user installs a different Lossless.dll
	const uint32 dllChangeCounter = LosslessDll::GetChangeCounter();
	if (dllChangeCounter != m_dllChangeCounter)
	{
		m_dllChangeCounter = dllChangeCounter;
		m_unavailable = false;
		Release();
		m_shaders.reset();
	}

	if (!IsActive())
	{
		if (m_chain)
			Release();
		return 0;
	}
	if (!EnsureShaders())
		return 0;

	m_peakSourceWidth = std::max(m_peakSourceWidth, sourceWidth);
	const float flowScale = ConfiguredFlowScale(m_peakSourceWidth, extent.width);
	if (!m_chain || m_builtExtent.width != extent.width || m_builtExtent.height != extent.height || m_builtFlowScale != flowScale)
	{
		Rebuild(extent, flowScale);
		if (!m_chain)
			return 0;
	}
	SetRuntimeStatus(FrameGenRuntimeStatus::Active);

	const FrameGenPlan plan = m_pacer.Plan(LSFG::MAX_GENERATIONS);
	const uint64 count = m_frameCount++;
	m_lastCount = count;
	m_lastGenerations = plan.generations;

	const bool warm = plan.warm && count + 1 >= REQUIRED_FRAMES;
	m_warmStreak = warm ? m_warmStreak + 1 : 0;
	const bool generated = warm && m_warmStreak >= RECURRENCE_FRAMES && plan.generations > 0;

	CaptureFromSwapchain(cmd, swapchainImage, extent, m_chain->Input(count));
	if (warm)
		m_chain->DispatchShared(cmd, count);

	return generated ? m_lastGenerations : 0;
}

void FrameGenVk::PresentGenerated(VkCommandBuffer cmd, size_t generation, VkImage swapchainImage, VkExtent2D swapchainExtent)
{
	m_chain->DispatchGeneration(cmd, m_lastCount, m_lastGenerations, generation);
	BlitToSwapchain(cmd, m_chain->Output(generation), swapchainImage, swapchainExtent);
}

void FrameGenVk::PresentReal(VkCommandBuffer cmd, VkImage swapchainImage, VkExtent2D swapchainExtent)
{
	BlitToSwapchain(cmd, m_chain->Input(m_lastCount), swapchainImage, swapchainExtent);
}
