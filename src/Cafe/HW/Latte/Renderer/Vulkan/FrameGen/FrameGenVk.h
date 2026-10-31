// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/FrameGenPacer.h"

#include <memory>

class VulkanRenderer;

namespace LSFG
{
	class Allocator;
	class Chain;
	class Shaders;
}

enum class FrameGenRuntimeStatus : sint32
{
	Inactive = 0, // disabled or no Vulkan renderer running
	Active = 1,
	UnsupportedDevice = 2, // missing Vulkan memory model, float16 or null descriptor support
	DllUnavailable = 3, // Lossless.dll missing or invalid
	InitFailed = 4,
};

// Lossless Scaling frame generation for the TV swapchain.
// Per presented game frame the renderer calls ProcessFrame() with the finished swapchain image, then for each returned
// generation acquires a swapchain image and calls PresentGenerated(), and finally presents the real frame with PresentReal()
class FrameGenVk
{
public:
	FrameGenVk(VulkanRenderer* renderer, bool deviceSupported);
	~FrameGenVk();
	FrameGenVk(const FrameGenVk&) = delete;
	FrameGenVk& operator=(const FrameGenVk&) = delete;

	// true while frame generation is enabled and usable. Presentation is forced to FIFO in this state
	bool IsActive();

	// copies the finished frame out of the swapchain image and runs the shared optical flow passes
	// returns the number of generated frames that have to be presented before the real frame
	size_t ProcessFrame(VkCommandBuffer cmd, VkImage swapchainImage, VkExtent2D extent, uint32 sourceWidth);
	void PresentGenerated(VkCommandBuffer cmd, size_t generation, VkImage swapchainImage, VkExtent2D swapchainExtent);
	void PresentReal(VkCommandBuffer cmd, VkImage swapchainImage, VkExtent2D swapchainExtent);

	void Release();

	static FrameGenRuntimeStatus GetRuntimeStatus();

private:
	bool EnsureShaders();
	void Rebuild(VkExtent2D extent, float flowScale);
	void SetUnavailable(FrameGenRuntimeStatus status, const char* message);

	VulkanRenderer* m_renderer;
	bool m_deviceSupported;
	bool m_unavailable{false};
	uint32 m_dllChangeCounter{0};

	std::unique_ptr<LSFG::Shaders> m_shaders;
	std::unique_ptr<LSFG::Allocator> m_allocator;
	std::unique_ptr<LSFG::Chain> m_chain;
	FrameGenPacer m_pacer;

	VkExtent2D m_builtExtent{};
	float m_builtFlowScale{};
	uint32 m_peakSourceWidth{};
	uint64 m_frameCount{};
	uint64 m_lastCount{};
	size_t m_lastGenerations{};
	uint32 m_warmStreak{};
};
