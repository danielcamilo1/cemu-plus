#include "JNIUtils.h"
#include "config/CemuConfig.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/FrameGenPacer.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/FrameGenVk.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LosslessDll.h"

extern "C" [[maybe_unused]] JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getLosslessDllPath(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return JNIUtils::ToJString(env, LosslessDll::GetInstalledPath().string());
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_validateLosslessDll(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring path)
{
	return static_cast<jint>(LosslessDll::Validate(JNIUtils::FromJString(env, path)));
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getInstalledLosslessDllStatus([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return static_cast<jint>(LosslessDll::GetInstalledStatus());
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_notifyLosslessDllChanged([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	LosslessDll::NotifyChanged();
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_removeLosslessDll([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return LosslessDll::RemoveInstalled();
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getRuntimeStatus([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return static_cast<jint>(FrameGenVk::GetRuntimeStatus());
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_isEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return GetConfig().frame_gen.enabled.GetValue();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_setEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean enabled)
{
	GetConfig().frame_gen.enabled = enabled;
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getMultiplier([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return static_cast<jint>(FrameGenSettings::Multiplier());
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_setMultiplier([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint multiplier)
{
	GetConfig().frame_gen.multiplier = std::clamp<sint32>(multiplier, FrameGenSettings::MIN_MULTIPLIER, FrameGenSettings::MAX_MULTIPLIER);
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getTargetRate([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return static_cast<jint>(FrameGenSettings::TargetRate());
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_setTargetRate([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint targetRate)
{
	GetConfig().frame_gen.target_rate = std::max<sint32>(0, targetRate);
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_getFlowScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return FrameGenSettings::FlowScalePercentage();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeFrameGen_setFlowScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint flowScale)
{
	GetConfig().frame_gen.flow_scale = flowScale <= 0 ? 0 : std::clamp<sint32>(flowScale, 25, 100);
}
