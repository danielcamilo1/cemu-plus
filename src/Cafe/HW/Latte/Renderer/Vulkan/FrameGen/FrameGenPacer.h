// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <chrono>
#include <optional>

namespace FrameGenSettings
{
	constexpr uint32 MIN_MULTIPLIER = 2;
	constexpr uint32 MAX_MULTIPLIER = 4;

	bool IsEnabled();
	uint32 Multiplier();
	uint32 TargetRate(); // 0 = fixed multiplier
	sint32 FlowScalePercentage(); // 0 = auto
	size_t Generations();
	size_t MaxGenerations();
}

struct FrameGenPlan
{
	size_t generations{};
	bool warm{};
};

// decides how many frames to generate for each real frame. With a target rate set the multiplier is probed upwards
// while the target is not met and rolled back whenever a step slows down emulation
class FrameGenPacer
{
public:
	FrameGenPlan Plan(size_t capacity);
	void Reset();

private:
	using Clock = std::chrono::steady_clock;

	void Stabilize(Clock::time_point now);
	void DeferEvaluations(Clock::duration amount);
	void UpdateLimit(Clock::time_point now, float baseRate, float targetRate, size_t ceiling);

	std::optional<Clock::time_point> m_lastFrame;
	std::optional<Clock::time_point> m_stableUntil;
	std::optional<Clock::time_point> m_probeUntil;
	std::optional<Clock::time_point> m_nextProbe;
	std::optional<Clock::time_point> m_deficitSince;
	float m_smoothedInterval{};
	float m_outputCredit{};
	float m_probeBaseRate{};
	float m_unloadedBaseRate{};
	size_t m_issuedGenerations{};
	size_t m_probePreviousLimit{};
	size_t m_limit{};
	uint32 m_probeFailures{};
};
