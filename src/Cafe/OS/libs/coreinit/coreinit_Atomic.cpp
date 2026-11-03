#include "Cafe/OS/common/OSCommon.h"
#include <atomic>
#include <mutex>
#include "coreinit_Atomic.h"

namespace coreinit
{
	/* 32bit atomic operations */

	uint32 OSSwapAtomic(std::atomic<uint32be>* mem, uint32 newValue)
	{
		uint32be _newValue = newValue;
		uint32be previousValue = mem->exchange(_newValue);
		return previousValue;
	}

	bool OSCompareAndSwapAtomic(std::atomic<uint32be>* mem, uint32 compareValue, uint32 swapValue)
	{
		// seen in GTA3 homebrew port
		uint32be _compareValue = compareValue;
		uint32be _swapValue = swapValue;
		return mem->compare_exchange_strong(_compareValue, _swapValue);
	}

	bool OSCompareAndSwapAtomicEx(std::atomic<uint32be>* mem, uint32 compareValue, uint32 swapValue, uint32be* previousValue)
	{
		// seen in GTA3 homebrew port
		uint32be _compareValue = compareValue;
		uint32be _swapValue = swapValue;
		bool r = mem->compare_exchange_strong(_compareValue, _swapValue);
		*previousValue = _compareValue;
		return r;
	}

	uint32 OSAddAtomic(std::atomic<uint32be>* mem, uint32 adder)
	{
        // used by SDL Wii U port
		uint32be knownValue;
		while (true)
		{
			knownValue = mem->load();
			uint32be newValue = knownValue + adder;
			if (mem->compare_exchange_strong(knownValue, newValue))
				break;
		}
		return knownValue;
	}

	/* 64bit atomic operations */

	// the Wii U CPU has no 64bit atomic instructions, so these work on any 4 byte aligned value and games rely on it
	// (Star Fox Zero does). 64bit atomic instructions on ARM64 fault when the value isn't 8 byte aligned,
	// so those values are updated under a lock instead
	static std::mutex s_unalignedAtomic64Mutex;

	static bool IsAligned64(std::atomic<uint64be>* mem)
	{
		return ((uintptr_t)mem & 7) == 0;
	}

	static uint64be UnalignedLoad64(std::atomic<uint64be>* mem)
	{
		uint64be value;
		memcpy(&value, (void*)mem, sizeof(value));
		return value;
	}

	static void UnalignedStore64(std::atomic<uint64be>* mem, uint64be value)
	{
		memcpy((void*)mem, &value, sizeof(value));
	}

	// replaces the value with what op returns for it and returns the previous value
	template<typename TOp>
	static uint64 UnalignedUpdate64(std::atomic<uint64be>* mem, TOp op)
	{
		std::scoped_lock lock(s_unalignedAtomic64Mutex);
		uint64be previousValue = UnalignedLoad64(mem);
		UnalignedStore64(mem, op(previousValue));
		return previousValue;
	}

	static bool UnalignedCompareAndSwap64(std::atomic<uint64be>* mem, uint64 compareValue, uint64 swapValue, uint64be* previousValue)
	{
		std::scoped_lock lock(s_unalignedAtomic64Mutex);
		uint64be currentValue = UnalignedLoad64(mem);
		if (previousValue)
			*previousValue = currentValue;
		if (currentValue != compareValue)
			return false;
		UnalignedStore64(mem, swapValue);
		return true;
	}

	uint64 OSSwapAtomic64(std::atomic<uint64be>* mem, uint64 newValue)
	{
		if (!IsAligned64(mem))
			return UnalignedUpdate64(mem, [&](uint64be) -> uint64be { return newValue; });
		uint64be _newValue = newValue;
		uint64be previousValue = mem->exchange(_newValue);
		return previousValue;
	}

	uint64 OSSetAtomic64(std::atomic<uint64be>* mem, uint64 newValue)
	{
		return OSSwapAtomic64(mem, newValue);
	}

	uint64 OSGetAtomic64(std::atomic<uint64be>* mem)
	{
		if (!IsAligned64(mem))
		{
			std::scoped_lock lock(s_unalignedAtomic64Mutex);
			return UnalignedLoad64(mem);
		}
		return mem->load();
	}

	uint64 OSAddAtomic64(std::atomic<uint64be>* mem, uint64 adder)
	{
		if (!IsAligned64(mem))
			return UnalignedUpdate64(mem, [&](uint64be value) -> uint64be { return value + adder; });
		uint64be knownValue;
		while (true)
		{
			knownValue = mem->load();
			uint64be newValue = knownValue + adder;
			if (mem->compare_exchange_strong(knownValue, newValue))
				break;
		}
		return knownValue;
	}

	uint64 OSAndAtomic64(std::atomic<uint64be>* mem, uint64 val)
	{
		if (!IsAligned64(mem))
			return UnalignedUpdate64(mem, [&](uint64be value) -> uint64be { return value & val; });
		uint64be knownValue;
		while (true)
		{
			knownValue = mem->load();
			uint64be newValue = knownValue & val;
			if (mem->compare_exchange_strong(knownValue, newValue))
				break;
		}
		return knownValue;
	}

	uint64 OSOrAtomic64(std::atomic<uint64be>* mem, uint64 val)
	{
		if (!IsAligned64(mem))
			return UnalignedUpdate64(mem, [&](uint64be value) -> uint64be { return value | val; });
		uint64be knownValue;
		while (true)
		{
			knownValue = mem->load();
			uint64be newValue = knownValue | val;
			if (mem->compare_exchange_strong(knownValue, newValue))
				break;
		}
		return knownValue;
	}

	bool OSCompareAndSwapAtomic64(std::atomic<uint64be>* mem, uint64 compareValue, uint64 swapValue)
	{
		if (!IsAligned64(mem))
			return UnalignedCompareAndSwap64(mem, compareValue, swapValue, nullptr);
		uint64be _compareValue = compareValue;
		uint64be _swapValue = swapValue;
		return mem->compare_exchange_strong(_compareValue, _swapValue);
	}

	bool OSCompareAndSwapAtomicEx64(std::atomic<uint64be>* mem, uint64 compareValue, uint64 swapValue, uint64be* previousValue)
	{
		if (!IsAligned64(mem))
			return UnalignedCompareAndSwap64(mem, compareValue, swapValue, previousValue);
		uint64be _compareValue = compareValue;
		uint64be _swapValue = swapValue;
		bool r = mem->compare_exchange_strong(_compareValue, _swapValue);
		*previousValue = _compareValue;
		return r;
	}

	void InitializeAtomic()
	{
		// 32bit atomic operations
		cafeExportRegister("coreinit", OSSwapAtomic, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomic, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomicEx, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAddAtomic, LogType::Placeholder);
		
		// 64bit atomic operations
		cafeExportRegister("coreinit", OSSetAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSGetAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSSwapAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAddAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAndAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSOrAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomicEx64, LogType::Placeholder);
	}
}