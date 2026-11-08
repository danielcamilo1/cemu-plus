#include "AndroidInputHelpers.h"
#include "input/api/Device/DeviceController.h"
#include "input/api/Android/ControllerManager.h"
#include "input/ControllerFactory.h"
#include "input/InputManager.h"
#include "JNIUtils.h"
#include "input/emulated/EmulatedController.h"
#include "Cafe/GameProfile/GameProfile.h"
#include "config/ActiveSettings.h"

#include <android/sensor.h>
#include <atomic>
#include <android/log.h> // DIAGNOSTIC (temporary)
#include <chrono> // DIAGNOSTIC (temporary)

namespace NativeInput
{
	constexpr jint DISABLED_TYPE = -1;

	class AndroidControllerCallbacks : public ControllerManager::ControllerCallbacks
	{
	  public:
		AndroidControllerCallbacks(JNIEnv* env, jobject callbacks)
			: m_callbacksObject(callbacks)
		{
			jclass controllerCallbacksClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$ControllerCallbacks");
			m_vibrateControllerMId = env->GetMethodID(controllerCallbacksClass, "vibrateController", "(Ljava/lang/String;JI)V");
			m_cancelControllerVibrationMId = env->GetMethodID(controllerCallbacksClass, "cancelControllerVibration", "(Ljava/lang/String;)V");
			env->DeleteLocalRef(controllerCallbacksClass);
		}

		void vibrate_controller(std::string_view deviceDescriptor, sint64 milliseconds, sint32 amplitude) override
		{
			JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
				jstring deviceDescriptorJstring = JNIUtils::ToJString(env, deviceDescriptor);
				env->CallVoidMethod(*m_callbacksObject, m_vibrateControllerMId, deviceDescriptorJstring, milliseconds, amplitude);
				env->DeleteLocalRef(deviceDescriptorJstring);
			});
		}

		void cancel_controller_vibration(std::string_view deviceDescriptor) override
		{
			JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
				jstring deviceDescriptorJstring = JNIUtils::ToJString(env, deviceDescriptor);
				env->CallVoidMethod(*m_callbacksObject, m_cancelControllerVibrationMId, deviceDescriptorJstring);
				env->DeleteLocalRef(deviceDescriptorJstring);
			});
		}

	  private:
		jmethodID m_vibrateControllerMId;
		jmethodID m_cancelControllerVibrationMId;
		JNIUtils::Scopedjobject m_callbacksObject;
	};

	class DeviceControllerCallbacks : public DeviceController::ControllerCallbacks
	{
	  public:
		DeviceControllerCallbacks(JNIEnv* env, jobject callbacks)
			: m_callbacksObject(callbacks)
		{
			jclass controllerCallbacksClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$DeviceCallbacks");
			m_vibrateMId = env->GetMethodID(controllerCallbacksClass, "vibrate", "(JI)V");
			m_cancelVibrationMId = env->GetMethodID(controllerCallbacksClass, "cancelVibration", "()V");
			env->DeleteLocalRef(controllerCallbacksClass);
		}

		void start_rumble(sint64 milliseconds, float rumble) override
		{
			JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
				env->CallVoidMethod(*m_callbacksObject, m_vibrateMId, milliseconds, static_cast<jint>(rumble * 255.0f));
			});
		}

		void stop_rumble() override
		{
			JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
				env->CallVoidMethod(*m_callbacksObject, m_cancelVibrationMId);
			});
		}

	  private:
		jmethodID m_vibrateMId;
		jmethodID m_cancelVibrationMId;
		JNIUtils::Scopedjobject m_callbacksObject;
	};

	void OnTouchEvent(sint32 x, sint32 y, bool isTV, bool status = true)
	{
		auto& instance = InputManager::instance();
		auto& touchInfo = isTV ? instance.m_main_mouse : instance.m_pad_mouse;
		std::scoped_lock lock(touchInfo.m_mutex);
		touchInfo.position = {x, y};
		touchInfo.left_down = touchInfo.left_down_toggle = status;
	}

	std::pair<EmulatedControllerPtr, ControllerPtr> GetDeviceControllerPair()
	{
		for (size_t i = 0; i < InputManager::kMaxController; ++i)
		{
			auto emulatedController = InputManager::instance().get_controller(i);

			if (!emulatedController)
			{
				continue;
			}

			for (const auto& controller : emulatedController->get_controllers())
			{
				if (controller->api() == InputAPI::Device)
				{
					return {emulatedController, controller};
				}
			}
		}

		if (auto emulatedController = InputManager::instance().get_controller(0))
		{
			auto controller = CreateDefaultDeviceController();
			emulatedController->add_controller(controller);
			return {emulatedController, controller};
		}

		return {nullptr, nullptr};
	}

	std::shared_ptr<ControllerBase> GetControllerForEmulatedController(uint32 index, std::string_view uuid, std::string_view name)
	{
		auto emulatedController = InputManager::instance().get_controller(index);

		if (!emulatedController)
		{
			return nullptr;
		}

		const auto& controllers = emulatedController->get_controllers();
		auto controllerIt = std::find_if(controllers.begin(), controllers.end(), [&](const ControllerPtr& c) { return c->api() == InputAPI::Android && c->uuid() == uuid; });

		if (controllerIt != controllers.end())
		{
			return *controllerIt;
		}

		std::shared_ptr<ControllerBase> controller = ControllerFactory::CreateController(InputAPI::Android, uuid, name);
		emulatedController->add_controller(controller);
		return controller;
	}

	ControllerPtr GetDeviceController(size_t index)
	{
		auto emulatedController = InputManager::instance().get_controller(index);
		if (!emulatedController)
			return {};
		for (const auto& controller : emulatedController->get_controllers())
		{
			if (controller->api() == InputAPI::Device)
				return controller;
		}
		return {};
	}

	// the touch screen, sensors and vibration of the device belong to one controller. A loaded profile may come with
	// them or without, either way they stay with a single controller and with the one that had them if it can
	void KeepSingleDeviceController(size_t index, ControllerPtr previousDeviceController)
	{
		auto& inputManager = InputManager::instance();
		auto emulatedController = inputManager.get_controller(index);
		if (!emulatedController)
			return;

		auto isDevice = [](const ControllerPtr& c) { return c->api() == InputAPI::Device; };
		const auto& controllers = emulatedController->get_controllers();
		bool hasDeviceController = std::ranges::any_of(controllers, isDevice);

		if (!hasDeviceController && previousDeviceController)
		{
			emulatedController->add_controller(previousDeviceController);
			hasDeviceController = true;
		}

		if (!hasDeviceController)
			return;

		for (size_t i = 0; i < InputManager::kMaxController; ++i)
		{
			auto other = i != index ? inputManager.get_controller(i) : nullptr;
			if (!other)
				continue;
			for (const auto& controller : std::vector(other->get_controllers()))
			{
				if (isDevice(controller))
					other->remove_controller(controller);
			}
		}
	}

	// set from the app settings, applied to the motion of the device and of every controller
	std::atomic_bool s_isMotionXInverted{false};
	std::atomic_bool s_isMotionYInverted{false};
	// scales the turning speed the game gets, 1.0 keeps it. The tilt the accelerometer reports isn't scaled, so the
	// orientation the motion handler fuses from both still settles at the real angle
	std::atomic<float> s_motionSensitivityX{1.0f};
	std::atomic<float> s_motionSensitivityY{1.0f};

	// the values are in the frame of the screen, x to the right, y up and z out of it.
	// X mirrors left and right, so turning and rolling both go the other way and gravity still matches.
	// Y flips tilting up and down, the rotation around x and the part of gravity it moves into z
	void ApplyMotionSettings(float& gyroX, float& gyroY, float& gyroZ, float& accelX, [[maybe_unused]] float& accelY, float& accelZ)
	{
		// DIAGNOSTIC (temporary): what the motion settings do to a sample, every 2 seconds
		{
			static std::atomic<int64_t> s_lastLog{0};
			const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
			if (now - s_lastLog > 2000)
			{
				s_lastLog = now;
				__android_log_print(ANDROID_LOG_INFO, "CemuMotion", "apply invX=%d invY=%d sensX=%.2f sensY=%.2f in gyro=(%.3f %.3f %.3f) acc=(%.3f %.3f %.3f)",
					(int)s_isMotionXInverted.load(), (int)s_isMotionYInverted.load(), s_motionSensitivityX.load(), s_motionSensitivityY.load(),
					gyroX, gyroY, gyroZ, accelX, accelY, accelZ);
			}
		}
		if (s_isMotionXInverted)
		{
			gyroY = -gyroY;
			gyroZ = -gyroZ;
			accelX = -accelX;
		}
		if (s_isMotionYInverted)
		{
			gyroX = -gyroX;
			accelZ = -accelZ;
		}
		// X is turning and rolling, Y is tilting up and down, the same axes as the inversion
		const float sensitivityX = s_motionSensitivityX;
		const float sensitivityY = s_motionSensitivityY;
		gyroY *= sensitivityX;
		gyroZ *= sensitivityX;
		gyroX *= sensitivityY;
	}
} // namespace NativeInput

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onControllerKey(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring deviceDescriptor, jint key, jboolean is_pressed)
{
	ControllerManager::instance().process_key_event(JNIUtils::FromJString(env, deviceDescriptor), key, is_pressed);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onControllerAxis(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring deviceDescriptor, jint axis, jfloat value)
{
	ControllerManager::instance().process_axis_event(JNIUtils::FromJString(env, deviceDescriptor), axis, value);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onControllerMotion(
	JNIEnv* env,
	[[maybe_unused]] jclass clazz,
	jstring deviceDescriptor,
	jlong timestamp,
	jfloat gyroX,
	jfloat gyroY,
	jfloat gyroZ,
	jfloat accelX,
	jfloat accelY,
	jfloat accelZ)
{
	NativeInput::ApplyMotionSettings(gyroX, gyroY, gyroZ, accelX, accelY, accelZ);
	ControllerManager::instance().process_motion(
		JNIUtils::FromJString(env, deviceDescriptor),
		std::chrono::steady_clock::time_point{std::chrono::nanoseconds(timestamp)},
		gyroX,
		-gyroY,
		-gyroZ,
		-accelX,
		accelY,
		accelZ);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setControllers(JNIEnv* env, [[maybe_unused]] jclass clazz, jobjectArray controllers)
{
	jsize arrayLength = env->GetArrayLength(controllers);
	std::vector<ControllerManager::ControllerInfo> controllerInfos;
	controllerInfos.reserve(arrayLength);

	jclass controllerInfoClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$ControllerInfo");
	jfieldID idFieldId = env->GetFieldID(controllerInfoClass, "id", "I");
	jfieldID descriptorFieldId = env->GetFieldID(controllerInfoClass, "descriptor", "Ljava/lang/String;");
	jfieldID nameFieldId = env->GetFieldID(controllerInfoClass, "name", "Ljava/lang/String;");
	jfieldID hasRumbleFieldId = env->GetFieldID(controllerInfoClass, "hasRumble", "Z");
	jfieldID hasMotionFieldId = env->GetFieldID(controllerInfoClass, "hasMotion", "Z");

	for (jsize i = 0; i < arrayLength; i++)
	{
		auto controllerObj = env->GetObjectArrayElement(controllers, i);

		ControllerManager::ControllerInfo controllerInfo{
			.id = env->GetIntField(controllerObj, idFieldId),
			.descriptor = JNIUtils::FromJString(env, static_cast<jstring>(env->GetObjectField(controllerObj, descriptorFieldId))),
			.name = JNIUtils::FromJString(env, static_cast<jstring>(env->GetObjectField(controllerObj, nameFieldId))),
			.hasRumble = static_cast<bool>(env->GetBooleanField(controllerObj, hasRumbleFieldId)),
			.hasMotion = static_cast<bool>(env->GetBooleanField(controllerObj, hasMotionFieldId)),
		};

		controllerInfos.emplace_back(std::move(controllerInfo));
	}

	ControllerManager::instance().set_controllers(controllerInfos);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setControllerType([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jint emulated_controller_type)
{
	auto type = static_cast<EmulatedController::Type>(emulated_controller_type);
	auto& androidEmulatedController = EmulatedControllerManager::GetController(index);
	if (EmulatedController::Type::VPAD <= type && type < EmulatedController::Type::MAX)
		androidEmulatedController.SetType(type);
	else
		androidEmulatedController.SetDisabled();
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllerType([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	auto emulatedController = EmulatedControllerManager::GetController(index).GetControllerPtr();
	if (emulatedController)
		return emulatedController->type();

	return NativeInput::DISABLED_TYPE;
}

extern "C" [[maybe_unused]] JNIEXPORT jobject JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllersCount(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	const auto [vpadCount, wpadCount] = InputManager::instance().get_controller_count();
	jclass controllersCountClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$ControllerCount");
	jmethodID ctrMId = env->GetMethodID(controllersCountClass, "<init>", "(II)V");
	return env->NewObject(controllersCountClass, ctrMId, static_cast<jint>(vpadCount), static_cast<jint>(wpadCount));
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setVPADScreenToggle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jboolean enabled)
{
	auto emulatedController = EmulatedControllerManager::GetController(index).GetControllerPtr();
	if (emulatedController == nullptr || emulatedController->type() != EmulatedController::Type::VPAD)
		throw std::runtime_error(fmt::format("Invalid controller type for controller {}, expected VPAD", index));
	dynamic_cast<VPADController*>(emulatedController.get())->set_screen_toggle(enabled);
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getVPADScreenToggle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	auto emulatedController = EmulatedControllerManager::GetController(index).GetControllerPtr();
	if (emulatedController == nullptr || emulatedController->type() != EmulatedController::Type::VPAD)
		throw std::runtime_error(fmt::format("Invalid controller type for controller {}, expected VPAD", index));
	return dynamic_cast<VPADController*>(emulatedController.get())->is_screen_active_toggle();
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_isControllerDisabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	return EmulatedControllerManager::GetController(index).GetControllerPtr() == nullptr;
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setControllerMapping(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring deviceDescriptor, jstring deviceName, jint index, jint mappingId, jint buttonId)
{
	auto controller = ControllerFactory::CreateController(InputAPI::Android, JNIUtils::FromJString(env, deviceDescriptor), JNIUtils::FromJString(env, deviceName));
	EmulatedControllerManager::GetController(index).SetMapping(mappingId, controller, buttonId);
}

extern "C" [[maybe_unused]] JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllerMapping(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jint mappingId)
{
	auto mapping = EmulatedControllerManager::GetController(index).GetMapping(mappingId);
	return JNIUtils::ToJString(env, mapping.value_or(""));
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_clearControllerMapping([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jint mappingId)
{
	EmulatedControllerManager::GetController(index).ClearMapping(mappingId);
}

extern "C" [[maybe_unused]] JNIEXPORT jobject JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllerMappings(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	jclass hashMapClass = env->FindClass("java/util/HashMap");
	jmethodID hashMapConstructor = env->GetMethodID(hashMapClass, "<init>", "()V");
	jmethodID hashMapPut = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
	jclass integerClass = env->FindClass("java/lang/Integer");
	jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
	jobject hashMapObj = env->NewObject(hashMapClass, hashMapConstructor);
	auto mappings = EmulatedControllerManager::GetController(index).GetMappings();

	for (const auto& pair : mappings)
	{
		jint key = static_cast<jint>(pair.first);
		jstring buttonName = JNIUtils::ToJString(env, pair.second);
		jobject mappingId = env->NewObject(integerClass, integerConstructor, key);
		env->CallObjectMethod(hashMapObj, hashMapPut, mappingId, buttonName);
	}

	return hashMapObj;
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onTouchDown([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint x, jint y, jboolean isTV)
{
	NativeInput::OnTouchEvent(x, y, isTV, true);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onTouchUp([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint x, jint y, jboolean isTV)
{
	NativeInput::OnTouchEvent(x, y, isTV, false);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onTouchMove([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint x, jint y, jboolean isTV)
{
	NativeInput::OnTouchEvent(x, y, isTV);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onDeviceMotion([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jlong timestamp, jfloat gyroX, jfloat gyroY, jfloat gyroZ, jfloat accelX, jfloat accelY, jfloat accelZ)
{
	NativeInput::ApplyMotionSettings(gyroX, gyroY, gyroZ, accelX, accelY, accelZ);
	DeviceController::process_motion(
		std::chrono::steady_clock::time_point{std::chrono::nanoseconds(timestamp)},
		gyroX,
		-gyroY,
		-gyroZ,
		-accelX,
		accelY,
		accelZ);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setMotionInverted([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean invertX, jboolean invertY)
{
	NativeInput::s_isMotionXInverted = invertX;
	NativeInput::s_isMotionYInverted = invertY;
	__android_log_print(ANDROID_LOG_INFO, "CemuMotion", "setMotionInverted x=%d y=%d", (int)invertX, (int)invertY); // DIAGNOSTIC (temporary)
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setMotionSensitivity([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat sensitivityX, jfloat sensitivityY)
{
	NativeInput::s_motionSensitivityX = sensitivityX;
	NativeInput::s_motionSensitivityY = sensitivityY;
	__android_log_print(ANDROID_LOG_INFO, "CemuMotion", "setMotionSensitivity x=%.2f y=%.2f", sensitivityX, sensitivityY); // DIAGNOSTIC (temporary)
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setDeviceMotionEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean motionEnabled)
{
	DeviceController::set_motion_enabled(motionEnabled);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onOverlayButton([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint controllerIndex, jint mappingId, jboolean state)
{
	EmulatedControllerManager::GetController(controllerIndex).SetButtonValue(mappingId, state);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_onOverlayAxis([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint controllerIndex, jint mappingId, jfloat value)
{
	EmulatedControllerManager::GetController(controllerIndex).SetAxisValue(mappingId, value);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setControllerCallbacks(JNIEnv* env, [[maybe_unused]] jclass clazz, jobject callbacks)
{
	ControllerManager::instance().set_controller_callbacks(callbacks == nullptr ? nullptr : std::make_shared<NativeInput::AndroidControllerCallbacks>(env, callbacks));
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setDeviceCallbacks(JNIEnv* env, [[maybe_unused]] jclass clazz, jobject callbacks)
{
	DeviceController::set_callbacks(callbacks == nullptr ? nullptr : std::make_shared<NativeInput::DeviceControllerCallbacks>(env, callbacks));
}

extern "C" [[maybe_unused]] JNIEXPORT jobject JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllerSettings(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jstring descriptor, jstring name)
{
	auto controller = NativeInput::GetControllerForEmulatedController(index, JNIUtils::FromJString(env, descriptor), JNIUtils::FromJString(env, name));

	if (controller == nullptr)
	{
		return nullptr;
	}

	auto settings = controller->get_settings();

	jclass axisSettingsClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$AxisSetting");
	jmethodID axisSettingsCtrMID = env->GetMethodID(axisSettingsClass, "<init>", "(FF)V");
	auto toJavaAxisSettings = [&](const ControllerBase::AxisSetting& axisSetting) {
		return env->NewObject(axisSettingsClass, axisSettingsCtrMID, axisSetting.deadzone, axisSetting.range);
	};
	jclass controllerSettingsClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$ControllerSettings");
	jmethodID controllerSettingsCtrMID = env->GetMethodID(controllerSettingsClass, "<init>", "(Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;FZ)V");

	return env->NewObject(
		controllerSettingsClass,
		controllerSettingsCtrMID,
		toJavaAxisSettings(settings.axis),
		toJavaAxisSettings(settings.rotation),
		toJavaAxisSettings(settings.trigger),
		settings.rumble,
		settings.motion);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setControllerSettings(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jstring descriptor, jstring name, jobject controllerSettings)
{
	auto controller = NativeInput::GetControllerForEmulatedController(index, JNIUtils::FromJString(env, descriptor), JNIUtils::FromJString(env, name));

	if (controller == nullptr)
	{
		return;
	}

	jclass axisSettingsClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$AxisSetting");
	jfieldID deadzoneFieldId = env->GetFieldID(axisSettingsClass, "deadzone", "F");
	jfieldID rangeFieldId = env->GetFieldID(axisSettingsClass, "range", "F");
	jclass controllerSettingsClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeInput$ControllerSettings");
	jfieldID motionFieldId = env->GetFieldID(controllerSettingsClass, "motion", "Z");
	jfieldID rumbleFieldId = env->GetFieldID(controllerSettingsClass, "rumble", "F");
	jfieldID axisFieldId = env->GetFieldID(controllerSettingsClass, "axis", "Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;");
	jfieldID rotationFieldId = env->GetFieldID(controllerSettingsClass, "rotation", "Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;");
	jfieldID triggerFieldId = env->GetFieldID(controllerSettingsClass, "trigger", "Linfo/cemu/cemu/nativeinterface/NativeInput$AxisSetting;");
	auto getAxisSettings = [&](jfieldID fieldId) {
		jobject axisSettingJava = env->GetObjectField(controllerSettings, fieldId);
		ControllerBase::AxisSetting axisSetting{};
		axisSetting.deadzone = env->GetFloatField(axisSettingJava, deadzoneFieldId);
		axisSetting.range = env->GetFloatField(axisSettingJava, rangeFieldId);
		return axisSetting;
	};

	ControllerBase::Settings settings{};
	settings.motion = env->GetBooleanField(controllerSettings, motionFieldId);
	settings.rumble = env->GetFloatField(controllerSettings, rumbleFieldId);
	settings.axis = getAxisSettings(axisFieldId);
	settings.rotation = getAxisSettings(rotationFieldId);
	settings.trigger = getAxisSettings(triggerFieldId);

	controller->set_settings(settings);
}

extern "C" [[maybe_unused]] JNIEXPORT jobjectArray JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getMotionEnabledControllerDescriptors(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	using namespace std::views;
	auto& inputManager = InputManager::instance();

	auto controllers = iota(size_t{0}, InputManager::kMaxController) |
					   transform([&](size_t i) { return inputManager.get_controller(i); }) |
					   filter([](const auto& e) { return e != nullptr; }) |
					   transform([](const auto& e) { return e->get_controllers(); }) |
					   join |
					   filter([](const auto& c) { return c->api() == InputAPI::Android && c->use_motion(); }) |
					   transform([](const auto& c) { return c->uuid(); });

	return JNIUtils::CreateStringObjectArray(
		env,
		controllers);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setDeviceRumble([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat rumble)
{
	auto [emulatedController, controller] = NativeInput::GetDeviceControllerPair();

	if (controller)
	{
		controller->set_rumble(rumble);
	}
}

extern "C" [[maybe_unused]] JNIEXPORT jfloat JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getDeviceRumble([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	auto [emulatedController, controller] = NativeInput::GetDeviceControllerPair();

	if (controller)
	{
		return controller->get_settings().rumble;
	}

	return 0.0f;
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getDeviceControllerIndex([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	auto [emulatedController, controller] = NativeInput::GetDeviceControllerPair();

	if (emulatedController)
	{
		return static_cast<jint>(emulatedController->player_index());
	}

	return 0;
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_setDeviceControllerIndex([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	auto [emulatedController, controller] = NativeInput::GetDeviceControllerPair();

	if (controller && emulatedController)
	{
		emulatedController->remove_controller(controller);
	}

	if (!controller)
	{
		controller = CreateDefaultDeviceController();
	}

	if (auto newEmulated = InputManager::instance().get_controller(index))
	{
		newEmulated->add_controller(controller);
	}
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_saveInputs([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	InputManager::instance().save();
}

extern "C" [[maybe_unused]] JNIEXPORT jobjectArray JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getControllerProfiles(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return JNIUtils::CreateStringObjectArray(env, InputManager::get_profiles());
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_isValidControllerProfileName(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring name)
{
	return InputManager::is_valid_profilename(JNIUtils::FromJString(env, name));
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_saveControllerProfile(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jstring name)
{
	auto profileName = JNIUtils::FromJString(env, name);
	if (!InputManager::is_valid_profilename(profileName))
		return false;
	return InputManager::instance().save(index, profileName);
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_loadControllerProfile(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jstring name)
{
	auto profileName = JNIUtils::FromJString(env, name);
	if (!InputManager::is_valid_profilename(profileName))
		return false;

	auto& inputManager = InputManager::instance();
	ControllerPtr previousDeviceController = NativeInput::GetDeviceController(index);

	if (!inputManager.load(index, profileName))
		return false;

	NativeInput::KeepSingleDeviceController(index, previousDeviceController);
	return true;
}

// the controller is set up from the profile and saved to it until the edit ends, so the controls of a game can be
// changed without a game running. A new profile starts as a copy of the global settings of the controller
extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_beginControllerProfileEdit(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jstring name)
{
	auto profileName = JNIUtils::FromJString(env, name);
	if (!InputManager::is_valid_profilename(profileName))
		return false;

	auto& inputManager = InputManager::instance();
	ControllerPtr previousDeviceController = NativeInput::GetDeviceController(index);
	if (inputManager.load(index, profileName))
	{
		// the touch screen, sensors and vibration of the device stay with the controller if the profile has none,
		// the other controllers are left as they are since their global settings are not edited
		if (previousDeviceController && !NativeInput::GetDeviceController(index))
			inputManager.get_controller(index)->add_controller(previousDeviceController);
	}
	else if (inputManager.get_controller(index))
	{
		inputManager.save(index, profileName);
	}
	inputManager.set_edited_profile(index, profileName);
	return true;
}

// back to the global settings of the controller
extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_endControllerProfileEdit([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	auto& inputManager = InputManager::instance();
	inputManager.save(index);
	inputManager.set_edited_profile(index, {});
	if (!inputManager.load(index))
		inputManager.delete_controller(index);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_deleteControllerProfile(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring name)
{
	auto profileName = JNIUtils::FromJString(env, name);
	if (!InputManager::is_valid_profilename(profileName))
		return;
	std::error_code ec;
	fs::remove(ActiveSettings::GetConfigPath("controllerProfiles/{}.xml", profileName), ec);
	fs::remove(ActiveSettings::GetConfigPath("controllerProfiles/{}.txt", profileName), ec);
}

// the profile the running game set for the controller, null when it uses the global settings
extern "C" [[maybe_unused]] JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeInput_getGameControllerProfile(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	if (!InputManager::instance().is_game_profile_set(index))
		return nullptr;
	const auto& profile = g_current_game_profile->GetControllerProfile()[index];
	if (!profile)
		return nullptr;
	return JNIUtils::ToJString(env, profile.value());
}
